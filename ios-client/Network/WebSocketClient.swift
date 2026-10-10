import Foundation
import Combine

enum ConnectionState: Equatable {
    case disconnected
    case connecting
    case connectedUnauthenticated
    case authenticated(gatewayName: String)
    case error(String)
}

enum DiagnosticTransitionType: String {
    case manualAction = "MANUAL_ACTION"
    case networkEvent = "NETWORK_EVENT"
    case socketError = "SOCKET_ERROR"
    case timeout = "TIMEOUT"
    case handshake = "HANDSHAKE"
    case heartbeat = "HEARTBEAT"
    case cancellation = "CANCELLATION"
}

final class WebSocketClient: NSObject, ObservableObject {
    static let shared = WebSocketClient()

    @Published var connectionState: ConnectionState = .disconnected
    @Published var activeSimCards: [SimCardInfo] = []
    @Published var gatewayDeviceName: String = "Unknown Gateway"
    @Published var currentEndpoint: String = ""

    private var webSocketTask: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private let pinningDelegate = TLSPinningDelegate()
    private let storage = StorageManager.shared
    private let keychain = KeychainHelper.shared

    // Authoritative session tracking to prevent race conditions from superseded sockets
    private var activeSessionId = UUID()
    private var isPairingSession = false
    private var pendingPairingToken: String?
    private var shouldReconnect = true
    private var reconnectAttempt = 0
    private var pendingReconnectWorkItem: DispatchWorkItem?

    // Heartbeat & keep-alive state
    private var heartbeatTimer: Timer?
    private var consecutiveHeartbeatFailures = 0
    private let maxHeartbeatFailures = 3
    private let heartbeatInterval: TimeInterval = 15.0

    private override init() {
        super.init()
    }

    // MARK: - State Management & Diagnostics

    private func transitionState(
        to newState: ConnectionState,
        reason: String,
        type: DiagnosticTransitionType
    ) {
        let oldState = self.connectionState
        guard oldState != newState else { return }

        self.connectionState = newState

        let timestamp = ISO8601DateFormatter().string(from: Date())
        let sessionPrefix = activeSessionId.uuidString.prefix(8)
        print("[DIAGNOSTICS] [\(timestamp)] [Session: \(sessionPrefix)] State: \(stateName(oldState)) -> \(stateName(newState)) | Type: \(type.rawValue) | Reason: \(reason)")
    }

    private func stateName(_ state: ConnectionState) -> String {
        switch state {
        case .disconnected: return "DISCONNECTED"
        case .connecting: return "CONNECTING"
        case .connectedUnauthenticated: return "CONNECTED_UNAUTH"
        case .authenticated(let name): return "AUTHENTICATED(\(name))"
        case .error(let msg): return "ERROR(\(msg))"
        }
    }

    // MARK: - Pairing & Connection Flow

    func pair(with envelope: QrPairingEnvelope) {
        guard !envelope.endpoints.isEmpty else {
            transitionState(to: .error("No valid endpoints in QR code"), reason: "Empty endpoint list", type: .manualAction)
            return
        }

        self.isPairingSession = true
        self.pendingPairingToken = envelope.pairingToken
        self.gatewayDeviceName = envelope.deviceName

        // Save pinned certificate fingerprint to Keychain
        keychain.save(key: "pinnedCertSha256", value: envelope.certSha256)
        pinningDelegate.setPinnedFingerprint(envelope.certSha256)

        // Try primary endpoint (e.g. hotspot 192.168.43.1 first)
        let endpoint = envelope.endpoints.first!
        keychain.save(key: "lastEndpoint", value: endpoint)
        connect(to: endpoint)
    }

    func reconnectUsingSavedCredentials() {
        // Prevent overlapping connection attempts when already connected or connecting
        if case .authenticated = connectionState {
            print("[WS] Already authenticated and connected; ignoring redundant reconnect request.")
            return
        }
        if case .connecting = connectionState || case .connectedUnauthenticated = connectionState {
            print("[WS] Connection already in progress; ignoring duplicate attempt.")
            return
        }

        guard let endpoint = keychain.read(key: "lastEndpoint"),
              let certSha = keychain.read(key: "pinnedCertSha256"),
              let _ = keychain.read(key: "clientAuthToken") else {
            transitionState(to: .disconnected, reason: "No saved credentials found", type: .manualAction)
            return
        }

        self.isPairingSession = false
        self.pendingPairingToken = nil
        self.shouldReconnect = true
        pinningDelegate.setPinnedFingerprint(certSha)
        connect(to: endpoint)
    }

    func connect(to endpointString: String) {
        // Do not recreate socket if already authenticated to this endpoint
        if case .authenticated = connectionState, currentEndpoint == endpointString {
            print("[WS] Already authenticated to \(endpointString); skipping duplicate connection.")
            return
        }
        if case .connecting = connectionState, currentEndpoint == endpointString {
            print("[WS] Connection already in progress to \(endpointString); skipping duplicate connection.")
            return
        }

        // Cancel any pending reconnect timers
        cancelPendingReconnect()

        // Generate new authoritative session ID to invalidate callbacks from previous sockets
        let newSessionId = UUID()
        self.activeSessionId = newSessionId
        self.currentEndpoint = endpointString

        // Teardown previous socket cleanly without posting asynchronous .disconnected state
        teardownCurrentSocket()

        transitionState(to: .connecting, reason: "Initiating connection to \(endpointString)", type: .networkEvent)

        guard let url = URL(string: endpointString) else {
            transitionState(to: .error("Invalid URL format: \(endpointString)"), reason: "URL parsing error", type: .manualAction)
            return
        }

        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 300

        let session = URLSession(
            configuration: configuration,
            delegate: pinningDelegate,
            delegateQueue: OperationQueue()
        )
        self.urlSession = session

        let task = session.webSocketTask(with: url)
        self.webSocketTask = task
        task.resume()

        listenForMessages(for: newSessionId)

        // Wait brief moment for TLS handshake, then trigger auth or pair
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self = self, self.activeSessionId == newSessionId else { return }
            if self.isPairingSession {
                self.sendPairRequest()
            } else {
                self.sendAuthenticate()
            }
        }
    }

    func disconnect(userInitiated: Bool = true) {
        cancelPendingReconnect()
        if userInitiated {
            shouldReconnect = false
        }
        teardownCurrentSocket()
        transitionState(
            to: .disconnected,
            reason: userInitiated ? "User initiated disconnect" : "Socket cleanup",
            type: userInitiated ? .manualAction : .networkEvent
        )
    }

    private func teardownCurrentSocket() {
        stopHeartbeat()
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
    }

    func revokePairing() {
        cancelPendingReconnect()
        disconnect(userInitiated: true)
        keychain.clearAll()
        storage.clearAllLocalData()
        DispatchQueue.main.async {
            self.activeSimCards = []
            self.gatewayDeviceName = "Unknown Gateway"
            self.transitionState(to: .disconnected, reason: "Pairing revoked by user", type: .manualAction)
        }
    }

    // MARK: - Heartbeat & Keep-Alive

    private func startHeartbeat() {
        stopHeartbeat()
        consecutiveHeartbeatFailures = 0
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.heartbeatTimer = Timer.scheduledTimer(withTimeInterval: self.heartbeatInterval, repeats: true) { [weak self] _ in
                self?.sendHeartbeatPing()
            }
        }
    }

    private func stopHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        consecutiveHeartbeatFailures = 0
    }

    private func sendHeartbeatPing() {
        guard case .authenticated = connectionState, let task = webSocketTask else { return }
        let sessionId = self.activeSessionId
        let pingStart = Date()
        let timestamp = ISO8601DateFormatter().string(from: pingStart)
        let sessionPrefix = sessionId.uuidString.prefix(8)
        print("[DIAGNOSTICS] [\(timestamp)] [Session: \(sessionPrefix)] Heartbeat ping sent")

        task.sendPing { [weak self] error in
            guard let self = self, self.activeSessionId == sessionId else { return }
            let durationMs = Int(Date().timeIntervalSince(pingStart) * 1000)
            let responseTimestamp = ISO8601DateFormatter().string(from: Date())

            DispatchQueue.main.async {
                guard self.activeSessionId == sessionId else { return }
                if let error = error {
                    self.consecutiveHeartbeatFailures += 1
                    print("[DIAGNOSTICS] [\(responseTimestamp)] [Session: \(sessionPrefix)] Heartbeat failed (\(durationMs)ms, attempt \(self.consecutiveHeartbeatFailures)/\(self.maxHeartbeatFailures)): \(error.localizedDescription)")

                    // Only disconnect after max consecutive failures (tolerance against transient network jitter)
                    if self.consecutiveHeartbeatFailures >= self.maxHeartbeatFailures {
                        self.transitionState(
                            to: .error("Heartbeat timed out after \(self.maxHeartbeatFailures) consecutive attempts"),
                            reason: "Heartbeat failure threshold reached",
                            type: .timeout
                        )
                        self.handleDisconnection()
                    }
                } else {
                    if self.consecutiveHeartbeatFailures > 0 {
                        print("[DIAGNOSTICS] [\(responseTimestamp)] [Session: \(sessionPrefix)] Heartbeat recovered after \(self.consecutiveHeartbeatFailures) transient missed ping(s)")
                    }
                    self.consecutiveHeartbeatFailures = 0
                    print("[DIAGNOSTICS] [\(responseTimestamp)] [Session: \(sessionPrefix)] Heartbeat pong received (\(durationMs)ms round-trip)")
                }
            }
        }
    }

    // MARK: - Outgoing Protocol Messages

    private func sendPairRequest() {
        guard let token = pendingPairingToken else { return }
        let clientId = getOrCreateClientId()
        let deviceName = "\(ProcessInfo.processInfo.hostName)"

        let payload = PairRequestPayload(
            pairingToken: token,
            clientName: "iPhone (\(deviceName))",
            clientId: clientId
        )
        let message = BridgeMessage(type: MessageTypes.pairRequest, payload: payload)
        send(message)
    }

    private func sendAuthenticate() {
        guard let authToken = keychain.read(key: "clientAuthToken") else {
            DispatchQueue.main.async {
                self.transitionState(to: .error("No saved authentication token"), reason: "Missing token in Keychain", type: .manualAction)
            }
            return
        }
        let clientId = getOrCreateClientId()
        let payload = AuthenticatePayload(clientAuthToken: authToken, clientId: clientId)
        let message = BridgeMessage(type: MessageTypes.authenticate, payload: payload)
        send(message)
    }

    func sendSms(
        recipient: String,
        body: String,
        subscriptionId: Int
    ) -> String {
        let requestId = "req_iphone_\(UUID().uuidString.prefix(12))"

        // Record in local store immediately as QUEUED
        storage.addOutgoingMessage(
            requestId: requestId,
            recipient: recipient,
            body: body,
            subscriptionId: subscriptionId
        )

        let payload = SmsSendRequestPayload(
            requestId: requestId,
            recipient: recipient,
            body: body,
            subscriptionId: subscriptionId
        )
        let message = BridgeMessage(type: MessageTypes.smsSendRequest, payload: payload)
        send(message)

        return requestId
    }

    func requestSync() {
        let lastSync = storage.lastSyncTimestamp
        let payload = SyncRequestPayload(sinceTimestamp: lastSync, lastKnownMessageId: nil, limit: 100)
        let message = BridgeMessage(type: MessageTypes.syncRequest, payload: payload)
        send(message)
    }

    private func send<T: Codable>(_ message: BridgeMessage<T>) {
        do {
            let data = try JSONEncoder().encode(message)
            guard let jsonString = String(data: data, encoding: .utf8) else { return }
            webSocketTask?.send(.string(jsonString)) { error in
                if let error = error {
                    print("[WS] Send error: \(error.localizedDescription)")
                }
            }
        } catch {
            print("[WS] JSON Encoding error: \(error)")
        }
    }

    // MARK: - Incoming Message Listener

    private func listenForMessages(for sessionId: UUID) {
        webSocketTask?.receive { [weak self] result in
            guard let self = self else { return }

            // Guard against callbacks from previous superseded sockets
            guard self.activeSessionId == sessionId else {
                return
            }

            switch result {
            case .failure(let error):
                let nsError = error as NSError
                // If the socket was intentionally cancelled (e.g. by new connection or user disconnect), ignore error
                if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
                    print("[WS] Superseded socket cancelled (\(sessionId.uuidString.prefix(8)))")
                    return
                }

                print("[WS] WebSocket receive error: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    guard self.activeSessionId == sessionId else { return }
                    self.transitionState(
                        to: .error("Connection lost: \(error.localizedDescription)"),
                        reason: "Socket receive error: \(error.localizedDescription)",
                        type: .socketError
                    )
                    self.handleDisconnection()
                }

            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleIncomingJson(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleIncomingJson(text)
                    }
                @unknown default:
                    break
                }
                // Continue listening on the same session
                self.listenForMessages(for: sessionId)
            }
        }
    }

    private func handleIncomingJson(_ json: String) {
        guard let data = json.data(using: .utf8) else { return }
        do {
            let root = try JSONDecoder().decode(GenericBridgeMessage.self, from: data)
            DispatchQueue.main.async {
                self.routeMessage(type: root.type, data: data)
            }
        } catch {
            print("[WS] Failed to parse message header: \(error)")
        }
    }

    private func routeMessage(type: String, data: Data) {
        let decoder = JSONDecoder()
        do {
            switch type {
            case MessageTypes.pairResponse:
                let msg = try decoder.decode(BridgeMessage<PairResponsePayload>.self, from: data)
                handlePairResponse(msg.payload)

            case MessageTypes.authResponse:
                let msg = try decoder.decode(BridgeMessage<AuthResponsePayload>.self, from: data)
                handleAuthResponse(msg.payload)

            case MessageTypes.connectionStatus:
                let msg = try decoder.decode(BridgeMessage<ConnectionStatusPayload>.self, from: data)
                if let sims = msg.payload.simCards {
                    self.activeSimCards = sims
                }

            case MessageTypes.smsIncoming:
                let msg = try decoder.decode(BridgeMessage<SmsIncomingPayload>.self, from: data)
                storage.addOrUpdateIncomingMessage(msg.payload)

            case MessageTypes.smsSendResult:
                let msg = try decoder.decode(BridgeMessage<SmsSendResultPayload>.self, from: data)
                storage.updateSendResult(msg.payload)

            case MessageTypes.smsDeliveryStatus:
                let msg = try decoder.decode(BridgeMessage<SmsDeliveryStatusPayload>.self, from: data)
                storage.updateDeliveryStatus(msg.payload)

            case MessageTypes.syncResponse:
                let msg = try decoder.decode(BridgeMessage<SyncResponsePayload>.self, from: data)
                handleSyncResponse(msg.payload)

            case MessageTypes.error:
                let msg = try decoder.decode(BridgeMessage<ErrorPayload>.self, from: data)
                print("[WS] Received gateway error: \(msg.payload.code) - \(msg.payload.message)")

            default:
                print("[WS] Unknown message type received: \(type)")
            }
        } catch {
            print("[WS] Error decoding payload for \(type): \(error)")
        }
    }

    private func handlePairResponse(_ payload: PairResponsePayload) {
        if payload.success, let token = payload.clientAuthToken {
            keychain.save(key: "clientAuthToken", value: token)
            self.gatewayDeviceName = payload.gatewayDeviceName
            if let sims = payload.simCards {
                self.activeSimCards = sims
            }
            cancelPendingReconnect()
            self.reconnectAttempt = 0
            transitionState(
                to: .authenticated(gatewayName: payload.gatewayDeviceName),
                reason: "Pairing handshake successful",
                type: .handshake
            )
            startHeartbeat()
            self.requestSync()
        } else {
            transitionState(
                to: .error(payload.errorMessage ?? "Pairing rejected by Android Gateway"),
                reason: "Gateway rejected pairing",
                type: .handshake
            )
        }
    }

    private func handleAuthResponse(_ payload: AuthResponsePayload) {
        if payload.success {
            self.gatewayDeviceName = payload.gatewayDeviceName
            if let sims = payload.simCards {
                self.activeSimCards = sims
            }
            cancelPendingReconnect()
            self.reconnectAttempt = 0
            transitionState(
                to: .authenticated(gatewayName: payload.gatewayDeviceName),
                reason: "Authentication handshake successful",
                type: .handshake
            )
            startHeartbeat()
            self.requestSync()
        } else {
            transitionState(
                to: .error(payload.errorMessage ?? "Authentication failed"),
                reason: "Gateway rejected authentication credentials",
                type: .handshake
            )
        }
    }

    private func handleSyncResponse(_ payload: SyncResponsePayload) {
        for msg in payload.messages {
            storage.addOrUpdateIncomingMessage(msg)
        }
        if let pending = payload.pendingOutboxStatus {
            for status in pending {
                storage.updateSendResult(status)
            }
        }
        storage.lastSyncTimestamp = payload.serverTimestamp
    }

    private func handleDisconnection() {
        stopHeartbeat()
        guard shouldReconnect else { return }

        // Cancel any existing pending reconnect work item
        cancelPendingReconnect()

        reconnectAttempt += 1
        let delaySec = min(Double(reconnectAttempt * 2), 15.0)
        let sessionPrefix = activeSessionId.uuidString.prefix(8)
        print("[WS] Scheduling reconnection in \(delaySec)s (attempt #\(reconnectAttempt)) [Session: \(sessionPrefix)]...")

        let workItem = DispatchWorkItem { [weak self] in
            guard let self = self, self.shouldReconnect else { return }
            self.reconnectUsingSavedCredentials()
        }
        self.pendingReconnectWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delaySec, execute: workItem)
    }

    private func cancelPendingReconnect() {
        pendingReconnectWorkItem?.cancel()
        pendingReconnectWorkItem = nil
    }

    private func getOrCreateClientId() -> String {
        if let existing = keychain.read(key: "clientId") {
            return existing
        }
        let newId = UUID().uuidString
        keychain.save(key: "clientId", value: newId)
        return newId
    }

    private struct GenericBridgeMessage: Codable {
        let version: Int
        let id: String
        let type: String
    }
}

