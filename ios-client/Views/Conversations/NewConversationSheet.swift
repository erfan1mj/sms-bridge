import SwiftUI

struct NewConversationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var client = WebSocketClient.shared

    @State private var recipientNumber: String = ""
    @State private var messageBody: String = ""
    @State private var selectedSubId: Int = -1
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Recipient")) {
                    TextField("Phone number (e.g. +1234567890)", text: $recipientNumber)
                        .keyboardType(.phonePad)
                }

                if client.activeSimCards.count > 1 {
                    Section(header: Text("Outgoing SIM Card")) {
                        Picker("Select SIM", selection: $selectedSubId) {
                            ForEach(client.activeSimCards) { sim in
                                Text("\(sim.displayName) (\(sim.carrierName))").tag(sim.subscriptionId)
                            }
                        }
                    }
                }

                Section(header: Text("Message")) {
                    TextEditor(text: $messageBody)
                        .frame(minHeight: 100)
                }

                if let errorMessage = errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundColor(.red)
                            .font(.footnote)
                    }
                }
            }
            .navigationTitle("New SMS")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send") { handleSend() }
                        .disabled(recipientNumber.isEmpty || messageBody.isEmpty)
                }
            }
            .onAppear {
                if selectedSubId == -1, let firstSim = client.activeSimCards.first {
                    selectedSubId = firstSim.subscriptionId
                }
            }
        }
    }

    private func handleSend() {
        let cleanNumber = recipientNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanBody = messageBody.trimmingCharacters(in: .whitespacesAndNewlines)

        guard cleanNumber.count >= 3 else {
            errorMessage = "Please enter a valid phone number."
            return
        }
        guard !cleanBody.isEmpty else {
            errorMessage = "Message body cannot be empty."
            return
        }

        _ = client.sendSms(
            recipient: cleanNumber,
            body: cleanBody,
            subscriptionId: selectedSubId
        )
        dismiss()
    }
}
