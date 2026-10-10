import Foundation

struct AppConversation: Identifiable, Codable, Hashable {
    var id: String { phoneNumber }
    let phoneNumber: String
    var displayName: String?
    var lastMessageText: String
    var lastTimestamp: Date
    var unreadCount: Int

    var title: String {
        return displayName ?? phoneNumber
    }
}
