import SwiftUI

@main
struct SMSBridgeApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var client = WebSocketClient.shared
    @StateObject private var storage = StorageManager.shared

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .onChange(of: scenePhase) { newPhase in
                    if newPhase == .active {
                        // Automatically re-establish connection and sync when app enters foreground
                        client.reconnectUsingSavedCredentials()
                    }
                }
                .onAppear {
                    client.reconnectUsingSavedCredentials()
                }
        }
    }
}
