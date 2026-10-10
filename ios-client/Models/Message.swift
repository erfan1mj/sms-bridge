import Foundation

struct AppMessage: Identifiable, Codable, Hashable {
    let id: String // messageId (for incoming) or requestId (for outgoing)
    let conversationNumber: String // Normalized recipient/sender phone number
    let sender: String
    let recipient: String
    let body: String
    let timestamp: Date
    let isOutgoing: Bool
    var sendStatus: SendStatus
    var deliveryStatus: String? // "DELIVERED" or nil
    let subscriptionId: Int
    let isMultipart: Bool
    var errorMessage: String?

    var isDelivered: Bool {
        return deliveryStatus == "DELIVERED"
    }

    var isSent: Bool {
        return sendStatus == .sent || isDelivered
    }
}
