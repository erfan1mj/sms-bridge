import SwiftUI

struct ConversationDetailView: View {
    let conversationNumber: String
    @ObservedObject var storage = StorageManager.shared
    @ObservedObject var client = WebSocketClient.shared

    @State private var selectedSubId: Int = -1
    @State private var scrollProxy: ScrollViewProxy?

    var body: some View {
        VStack(spacing: 0) {
            // Offline Warning Banner if disconnected
            if case .disconnected = client.connectionState {
                HStack {
                    Image(systemName: "exclamationmark.circle.fill")
                        .foregroundColor(.orange)
                    Text("Android Gateway is offline. Messages cannot be sent.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .background(Color.orange.opacity(0.12))
            }

            // Message Scroll Area
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 4) {
                        ForEach(messages) { msg in
                            MessageBubbleView(message: msg)
                                .id(msg.id)
                        }
                    }
                    .padding(.vertical, 8)
                }
                .onAppear {
                    self.scrollProxy = proxy
                    scrollToBottom(proxy)
                    storage.markConversationAsRead(conversationNumber)
                }
                .onChange(of: messages.count) { _ in
                    scrollToBottom(proxy)
                }
            }

            Divider()

            // Message Composer
            MessageComposerView(
                recipient: conversationNumber,
                selectedSubId: $selectedSubId,
                onSend: { text, subId in
                    _ = client.sendSms(
                        recipient: conversationNumber,
                        body: text,
                        subscriptionId: subId
                    )
                }
            )
        }
        .navigationTitle(conversationNumber)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if selectedSubId == -1, let firstSim = client.activeSimCards.first {
                selectedSubId = firstSim.subscriptionId
            }
        }
    }

    private var messages: [AppMessage] {
        storage.messagesByConversation[conversationNumber] ?? []
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        if let last = messages.last {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(last.id, anchor: .bottom)
            }
        }
    }
}
