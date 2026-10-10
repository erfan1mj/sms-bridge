import SwiftUI

struct ManualConnectionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var client = WebSocketClient.shared

    @State private var host: String = "192.168.43.1"
    @State private var port: String = "8765"
    @State private var pairingToken: String = ""
    @State private var certSha256: String = ""
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Gateway Host & Network")) {
                    TextField("IP Address (e.g. 192.168.43.1)", text: $host)
                        .keyboardType(.numbersAndPunctuation)
                        .autocapitalization(.none)

                    TextField("Port (Default 8765)", text: $port)
                        .keyboardType(.numberPad)
                }

                Section(
                    header: Text("Security Credentials"),
                    footer: Text("The pairing token and TLS certificate fingerprint are displayed on your Android Gateway screen.")
                ) {
                    TextField("Pairing Token", text: $pairingToken)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)

                    TextField("TLS Certificate SHA-256", text: $certSha256)
                        .autocapitalization(.allCharacters)
                        .disableAutocorrection(true)
                }

                if let errorMessage = errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundColor(.red)
                            .font(.footnote)
                    }
                }

                Section {
                    Button(action: handleConnect) {
                        HStack {
                            Spacer()
                            Text("Connect & Pair")
                                .bold()
                            Spacer()
                        }
                    }
                    .disabled(host.isEmpty || pairingToken.isEmpty || certSha256.isEmpty)
                }
            }
            .navigationTitle("Manual Connection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func handleConnect() {
        let cleanHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPort = port.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanToken = pairingToken.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanCert = certSha256.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !cleanHost.isEmpty, !cleanToken.isEmpty, !cleanCert.isEmpty else {
            errorMessage = "Please fill in all required fields."
            return
        }

        let endpoint = "wss://\(cleanHost):\(cleanPort)/ws"
        let envelope = QrPairingEnvelope(
            v: 1,
            endpoints: [endpoint],
            certSha256: cleanCert,
            pairingToken: cleanToken,
            deviceName: "Android Manual Gateway"
        )

        client.pair(with: envelope)
        dismiss()
    }
}
