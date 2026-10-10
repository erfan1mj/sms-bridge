import Foundation
import Combine

enum ConnectionState: Equatable {
    case disconnected
    case connecting
    case connectedUnauthenticated
    case authenticated(gatewayName: String)
    case error(String)
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

    private var isPairingSession = false
    private var pendingPairingToken: String?
    private var shouldReconnect = true
    private var reconnectAttempt = 0

    private override init() {
        super.init()
    }

    // MARK: - Pairing & Connection Flow

    func pair(with envelope: QrPairingEnvelope) {
        guard !envelope.endpoints.isEmpty else {
            self.connectionState = .error("No valid endpoints in QR code")
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
        guard let endpoint = keychain.read(key: "lastEndpoint"),
              let certSha = keychain.read(key: "pinnedCertSha256"),
              let _ = keychain.read(key: "clientAuthToken") else {
            self.connectionState = .disconnected
            return
        }

        self.isPairingSession = false
        self.pendingPairingToken = nil
        pinningDelegate.setPinnedFingerprint(certSha)
        connect(to: endpoint)
    }

    func connect(to endpointString: String) {
        disconnect(userInitiated: false)
        self.currentEndpoint = endpointString
        self.connectionState = .connecting

        guard let url = URL(string: endpointString) else {
            self.connectionState = .error("Invalid URL format: \(endpointString)")
            return
        }

        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 300

        self.urlSession = URLSession(
            configuration: configuration,
            delegate: pinningDelegate,
            delegateQueue: OperationQueue()
        )

        let task = self.urlSession!.webSocketTask(with: url)
        self.webSocketTask = task
        task.resume()

        listenForMessages()

        // Wait brief moment for TLS handshake, then trigger auth or pair
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self = self else { return }
            if self.isPairingSession {
                self.sendPairRequest()
            } else {
                self.sendAuthenticate()
            }
        }
    }

    func disconnect(userInitiated: Bool = true) {
        if userInitiated {
            shouldReconnect = false
        }
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
        DispatchQueue.main.async {
            self.connectionState = .disconnected
        }
    }

    func revokePairing() {
        disconnect(userInitiated: true)
        keychain.clearAll()
        storage.clearAllLocalData()
        DispatchQueue.main.async {
            self.activeSimCards = []
            self.gatewayDeviceName = "Unknown Gateway"
            self.connectionState = .disconnected
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
                self.connectionState = .error("No saved authentication token")
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

    private func listenForMessages() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .failure(let error):
                print("[WS] WebSocket receive error: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.connectionState = .error("Connection lost: \(error.localizedDescription)")
                }
                self.handleDisconnection()
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
                // Continue listening
                self.listenForMessages()
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
            self.connectionState = .authenticated(gatewayName: payload.gatewayDeviceName)
            self.reconnectAttempt = 0
            self.requestSync()
        } else {
            self.connectionState = .error(payload.errorMessage ?? "Pairing rejected by Android Gateway")
        }
    }

    private func handleAuthResponse(_ payload: AuthResponsePayload) {
        if payload.success {
            self.gatewayDeviceName = payload.gatewayDeviceName
            if let sims = payload.simCards {
                self.activeSimCards = sims
            }
            self.connectionState = .authenticated(gatewayName: payload.gatewayDeviceName)
            self.reconnectAttempt = 0
            self.requestSync()
        } else {
            self.connectionState = .error(payload.errorMessage ?? "Authentication failed")
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
        guard shouldReconnect else { return }
        reconnectAttempt += 1
        let delaySec = min(Double(reconnectAttempt * 2), 15.0)
        print("[WS] Will attempt reconnection in \(delaySec)s (attempt #\(reconnectAttempt))...")

        DispatchQueue.main.asyncAfter(deadline: .now() + delaySec) { [weak self] in
            guard let self = self, self.shouldReconnect else { return }
            self.reconnectUsingSavedCredentials()
        }
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
