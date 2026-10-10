import SwiftUI

struct ConnectionView: View {
    @ObservedObject var client = WebSocketClient.shared
    @State private var showingScanner = false
    @State private var showingManualSheet = false
    @State private var scanError: String?

    var body: some View {
        NavigationStack {
            List {
                // 1. Connection Status Banner
                Section {
                    HStack(spacing: 16) {
                        statusIcon
                            .font(.system(size: 36))

                        VStack(alignment: .leading, spacing: 4) {
                            Text(statusTitle)
                                .font(.headline)
                            Text(statusSubtitle)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 8)
                }

                // 2. Gateway Device Information
                if case .authenticated = client.connectionState {
                    Section(header: Text("Paired Android Gateway")) {
                        LabeledContent("Device", value: client.gatewayDeviceName)
                        LabeledContent("Endpoint", value: client.currentEndpoint)

                        if !client.activeSimCards.isEmpty {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Available SIM Cards:")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                ForEach(client.activeSimCards) { sim in
                                    HStack {
                                        Image(systemName: "simcard")
                                            .foregroundColor(.blue)
                                        Text("\(sim.displayName) (\(sim.carrierName))")
                                            .font(.subheadline)
                                        Spacer()
                                        if sim.isDefault {
                                            Text("Default")
                                                .font(.caption2)
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .background(Color.blue.opacity(0.15))
                                                .cornerRadius(4)
                                        }
                                    }
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }

                // 3. Pairing Actions
                Section(header: Text("Connection Actions")) {
                    Button(action: { showingScanner = true }) {
                        Label("Scan Android QR Code", systemImage: "qrcode.viewfinder")
                    }

                    Button(action: { showingManualSheet = true }) {
                        Label("Manual Connection Fallback", systemImage: "slider.horizontal.3")
                    }

                    if case .authenticated = client.connectionState {
                        Button(role: .destructive, action: { client.disconnect(userInitiated: true) }) {
                            Label("Disconnect", systemImage: "bolt.slash")
                        }
                    } else if case .disconnected = client.connectionState {
                        Button(action: { client.reconnectUsingSavedCredentials() }) {
                            Label("Reconnect Now", systemImage: "arrow.clockwise")
                        }
                    }
                }

                // 4. Instructions
                Section(header: Text("Pairing Instructions")) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("1. Connect both devices to the same Wi-Fi network or Android Hotspot.")
                        Text("2. Open SMS Bridge Gateway on Android and tap 'Pair New iPhone'.")
                        Text("3. Tap 'Scan Android QR Code' above and point the camera at your Android screen.")
                        Text("4. Once paired, the iPhone can send and receive cellular SMS through Android's SIM card.")
                    }
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("Connection")
            .sheet(isPresented: $showingScanner) {
                QRScannerView(
                    onCodeScanned: { code in
                        showingScanner = false
                        handleScannedQr(code)
                    },
                    onDismiss: { showingScanner = false }
                )
            }
            .sheet(isPresented: $showingManualSheet) {
                ManualConnectionSheet()
            }
            .alert("Pairing Error", isPresented: Binding<Bool>(
                get: { scanError != nil },
                set: { if !$0 { scanError = nil } }
            )) {
                Button("OK") { scanError = nil }
            } message: {
                Text(scanError ?? "")
            }
        }
    }

    private var statusIcon: some View {
        switch client.connectionState {
        case .authenticated:
            return Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
        case .connecting:
            return Image(systemName: "arrow.triangle.2.circlepath.circle.fill").foregroundColor(.orange)
        case .connectedUnauthenticated:
            return Image(systemName: "lock.shield.fill").foregroundColor(.orange)
        case .disconnected:
            return Image(systemName: "antenna.radiowaves.left.and.right.slash").foregroundColor(.gray)
        case .error:
            return Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.red)
        }
    }

    private var statusTitle: String {
        switch client.connectionState {
        case .authenticated(let name):
            return "Connected to \(name)"
        case .connecting:
            return "Connecting..."
        case .connectedUnauthenticated:
            return "Authenticating..."
        case .disconnected:
            return "Disconnected"
        case .error:
            return "Connection Error"
        }
    }

    private var statusSubtitle: String {
        switch client.connectionState {
        case .authenticated:
            return "SMS Bridge is active and ready"
        case .connecting:
            return "Establishing encrypted TLS socket"
        case .connectedUnauthenticated:
            return "Verifying credentials with Android"
        case .disconnected:
            return "Not connected to Android Gateway"
        case .error(let msg):
            return msg
        }
    }

    private func handleScannedQr(_ code: String) {
        guard let data = code.data(using: .utf8) else {
            scanError = "Invalid QR code format."
            return
        }

        do {
            let envelope = try JSONDecoder().decode(QrPairingEnvelope.self, from: data)
            client.pair(with: envelope)
        } catch {
            scanError = "QR code is not a valid SMS Bridge pairing token: \(error.localizedDescription)"
        }
    }
}
