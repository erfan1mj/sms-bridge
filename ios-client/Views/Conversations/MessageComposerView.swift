import SwiftUI

struct MessageComposerView: View {
    let recipient: String
    @Binding var selectedSubId: Int
    let onSend: (String, Int) -> Void

    @ObservedObject var client = WebSocketClient.shared
    @State private var text: String = ""
    @FocusState private var isFocused: Bool

    var body: some View {
        VStack(spacing: 6) {
            // SIM Selection & SMS Multipart Indicator Bar
            HStack {
                if client.activeSimCards.count > 1 {
                    Menu {
                        ForEach(client.activeSimCards) { sim in
                            Button(action: { selectedSubId = sim.subscriptionId }) {
                                HStack {
                                    Text("\(sim.displayName) (\(sim.carrierName))")
                                    if selectedSubId == sim.subscriptionId {
                                        Image(systemName: "checkmark")
                                    }
                                }
                            }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "simcard.2")
                            Text(currentSimLabel)
                                .font(.caption2)
                            Image(systemName: "chevron.up.chevron.down")
                                .font(.system(size: 8))
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color(.systemGray5))
                        .cornerRadius(6)
                    }
                } else if let sim = client.activeSimCards.first {
                    HStack(spacing: 4) {
                        Image(systemName: "simcard")
                        Text("\(sim.carrierName)")
                            .font(.caption2)
                    }
                    .foregroundColor(.secondary)
                }

                Spacer()

                // SMS character count & multipart estimate
                if !text.isEmpty {
                    Text(smsCounterText)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.horizontal, 12)

            // Text Field & Send Button
            HStack(alignment: .bottom, spacing: 8) {
                TextField("SMS message via Android SIM...", text: $text, axis: .vertical)
                    .lineLimit(1...5)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Color(.systemGray6))
                    .cornerRadius(20)
                    .focused($isFocused)

                Button(action: handleSend) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 32))
                        .foregroundColor(canSend ? .blue : .gray)
                }
                .disabled(!canSend)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 6)
        }
        .background(Color(.systemBackground))
    }

    private var canSend: Bool {
        guard case .authenticated = client.connectionState else { return false }
        return !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var currentSimLabel: String {
        if let sim = client.activeSimCards.first(where: { $0.subscriptionId == selectedSubId }) {
            return sim.displayName
        }
        return "SIM"
    }

    private var smsCounterText: String {
        let isUnicode = text.unicodeScalars.contains { $0.value > 127 }
        let length = text.count
        if isUnicode {
            // UCS-2: 70 chars single, 67 multipart
            let parts = length <= 70 ? 1 : Int(ceil(Double(length) / 67.0))
            return "\(length) chars (\(parts) SMS • Unicode)"
        } else {
            // GSM 7-bit: 160 chars single, 153 multipart
            let parts = length <= 160 ? 1 : Int(ceil(Double(length) / 153.0))
            return "\(length) chars (\(parts) SMS • GSM)"
        }
    }

    private func handleSend() {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        onSend(trimmed, selectedSubId)
        text = ""
    }
}
