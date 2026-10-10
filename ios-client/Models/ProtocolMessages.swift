import Foundation

// MARK: - Core Framing
struct BridgeMessage<T: Codable>: Codable {
    let version: Int
    let id: String
    let type: String
    let payload: T

    init(type: String, payload: T, id: String = UUID().uuidString, version: Int = 1) {
        self.version = version
        self.id = id
        self.type = type
        self.payload = payload
    }
}

enum MessageTypes {
    static let pairRequest = "PAIR_REQUEST"
    static let pairResponse = "PAIR_RESPONSE"
    static let authenticate = "AUTHENTICATE"
    static let authResponse = "AUTH_RESPONSE"
    static let connectionStatus = "CONNECTION_STATUS"
    static let smsIncoming = "SMS_INCOMING"
    static let smsSendRequest = "SMS_SEND_REQUEST"
    static let smsSendResult = "SMS_SEND_RESULT"
    static let smsDeliveryStatus = "SMS_DELIVERY_STATUS"
    static let syncRequest = "SYNC_REQUEST"
    static let syncResponse = "SYNC_RESPONSE"
    static let error = "ERROR"
}

// MARK: - Payloads

struct SimCardInfo: Codable, Identifiable, Hashable {
    var id: Int { subscriptionId }
    let subscriptionId: Int
    let displayName: String
    let carrierName: String
    let slotIndex: Int
    let isDefault: BooleanLiteralType
}

struct PairRequestPayload: Codable {
    let pairingToken: String
    let clientName: String
    let clientId: String
}

struct PairResponsePayload: Codable {
    let success: Bool
    let clientAuthToken: String?
    let gatewayDeviceName: String
    let gatewayAndroidVersion: String
    let simCards: [SimCardInfo]?
    let errorMessage: String?
}

struct AuthenticatePayload: Codable {
    let clientAuthToken: String
    let clientId: String
}

struct AuthResponsePayload: Codable {
    let success: Bool
    let gatewayDeviceName: String
    let simCards: [SimCardInfo]?
    let serverTimestamp: Int64
    let errorMessage: String?
}

struct ConnectionStatusPayload: Codable {
    let status: String
    let simCards: [SimCardInfo]?
    let smsPermissionGranted: Bool
    let isDefaultSmsApp: Bool
    let batteryLevel: Int?
}

struct SmsIncomingPayload: Codable {
    let messageId: String
    let sender: String
    let body: String
    let timestamp: Int64
    let subscriptionId: Int
    let isMultipart: Bool
    let partCount: Int
}

struct SmsSendRequestPayload: Codable {
    let requestId: String
    let recipient: String
    let body: String
    let subscriptionId: Int
}

enum SendStatus: String, Codable {
    case queued = "QUEUED"
    case submitted = "SUBMITTED"
    case sent = "SENT"
    case delivered = "DELIVERED"
    case failed = "FAILED"
    case unknown = "UNKNOWN"
}

struct SmsSendResultPayload: Codable {
    let requestId: String
    let status: SendStatus
    let partsCount: Int?
    let timestamp: Int64?
    let errorCode: String?
    let errorMessage: String?
}

struct SmsDeliveryStatusPayload: Codable {
    let requestId: String
    let status: String // "DELIVERED", "DELIVERY_FAILED"
    let timestamp: Int64
    let rawPduStatus: Int?
}

struct SyncRequestPayload: Codable {
    let sinceTimestamp: Int64
    let lastKnownMessageId: String?
    let limit: Int
}

struct SyncResponsePayload: Codable {
    let messages: [SmsIncomingPayload]
    let pendingOutboxStatus: [SmsSendResultPayload]?
    let serverTimestamp: Int64
}

struct ErrorPayload: Codable {
    let code: String
    let message: String
    let relatedRequestId: String?
}

struct QrPairingEnvelope: Codable {
    let v: Int
    let endpoints: [String]
    let certSha256: String
    let pairingToken: String
    let deviceName: String
}
