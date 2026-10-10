import SwiftUI

struct SettingsView: View {
    @ObservedObject var client = WebSocketClient.shared
    @ObservedObject var storage = StorageManager.shared
    private let keychain = KeychainHelper.shared

    @State private var showingRevokeConfirmation = false
    @State private var showingClearCacheConfirmation = false

    var body: some View {
        NavigationStack {
            List {
                // 1. Paired Device Information
                Section(header: Text("Paired Android Device")) {
                    LabeledContent("Gateway Name", value: client.gatewayDeviceName)
                    LabeledContent("Last Endpoint", value: keychain.read(key: "lastEndpoint") ?? "Not configured")
                    LabeledContent("Client ID", value: String(keychain.read(key: "clientId")?.prefix(8) ?? "None") + "...")
                }

                // 2. Security & TLS Diagnostics
                Section(header: Text("Security & Certificate Pinning")) {
                    LabeledContent("TLS Transport", value: "TLSv1.3 Encrypted")
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Pinned Certificate SHA-256:")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text(keychain.read(key: "pinnedCertSha256") ?? "No certificate pinned")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundColor(.primary)
                    }
                    .padding(.vertical, 2)
                    LabeledContent("Status", value: "Verified via Out-of-band QR")
                }

                // 3. Synchronization Diagnostics
                Section(header: Text("Message Synchronization")) {
                    LabeledContent("Total Conversations", value: "\(storage.conversations.count)")
                    LabeledContent("Total Messages Cached", value: "\(totalCachedMessages)")
                    LabeledContent(
                        "Last Synchronized",
                        value: storage.lastSyncTimestamp > 0
                            ? Date(timeIntervalSince1970: Double(storage.lastSyncTimestamp) / 1000.0).formatted(date: .abbreviated, time: .standard)
                            : "Never"
                    )

                    Button(action: { client.requestSync() }) {
                        Label("Force Synchronization Now", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled(client.connectionState == .disconnected)
                }

                // 4. Data Management & Revocation
                Section(header: Text("Data Management")) {
                    Button(role: .destructive, action: { showingClearCacheConfirmation = true }) {
                        Label("Clear Local Message Cache", systemImage: "trash")
                    }

                    Button(role: .destructive, action: { showingRevokeConfirmation = true }) {
                        Label("Revoke Pairing & Disconnect", systemImage: "xmark.shield.fill")
                    }
                }

                // 5. App & Architecture Details
                Section(header: Text("About SMS Bridge")) {
                    LabeledContent("Version", value: "1.0.0 (MVP v1)")
                    LabeledContent("Protocol", value: "JSON over TLS WebSocket (v1)")
                    Text("This personal application bridges SMS between your Android SIM and this iPhone over your local Wi-Fi or hotspot. No cloud servers or third-party accounts are involved.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Settings")
            .confirmationDialog(
                "Clear Local Cache?",
                isPresented: $showingClearCacheConfirmation,
                titleVisibility: .visible
            ) {
                Button("Clear Messages", role: .destructive) {
                    storage.clearAllLocalData()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This will remove all cached messages on this iPhone. Messages on your Android device will not be affected.")
            }
            .confirmationDialog(
                "Revoke Pairing?",
                isPresented: $showingRevokeConfirmation,
                titleVisibility: .visible
            ) {
                Button("Revoke Pairing", role: .destructive) {
                    client.revokePairing()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This will delete authentication tokens and certificate pins. You will need to scan a new QR code to reconnect.")
            }
        }
    }

    private var totalCachedMessages: Int {
        storage.messagesByConversation.values.reduce(0) { $0 + $1.count }
    }
}
