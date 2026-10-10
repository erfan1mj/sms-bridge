import SwiftUI

struct MainTabView: View {
    @ObservedObject var storage = StorageManager.shared
    @ObservedObject var client = WebSocketClient.shared

    var body: some View {
        TabView {
            ConversationsListView()
                .tabItem {
                    Label("Messages", systemImage: "bubble.left.and.bubble.right.fill")
                }
                .badge(totalUnreadCount)

            ConnectionView()
                .tabItem {
                    Label("Connection", systemImage: "antenna.radiowaves.left.and.right")
                }

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
        }
    }

    private var totalUnreadCount: Int {
        storage.conversations.reduce(0) { $0 + $1.unreadCount }
    }
}
