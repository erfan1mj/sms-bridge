import SwiftUI

struct ConversationsListView: View {
    @ObservedObject var storage = StorageManager.shared
    @ObservedObject var client = WebSocketClient.shared

    @State private var searchText = ""
    @State private var showingNewMessageSheet = false

    var body: some View {
        NavigationStack {
            Group {
                if filteredConversations.isEmpty {
                    emptyStateView
                } else {
                    List {
                        ForEach(filteredConversations) { conv in
                            NavigationLink(destination: ConversationDetailView(conversationNumber: conv.phoneNumber)) {
                                ConversationRowView(conversation: conv)
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Messages")
            .searchable(text: $searchText, prompt: "Search numbers or messages")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: { showingNewMessageSheet = true }) {
                        Image(systemName: "square.and.pencil")
                    }
                }
            }
            .sheet(isPresented: $showingNewMessageSheet) {
                NewConversationSheet()
            }
            .refreshable {
                client.requestSync()
            }
        }
    }

    private var filteredConversations: [AppConversation] {
        if searchText.isEmpty {
            return storage.conversations
        } else {
            return storage.conversations.filter {
                $0.phoneNumber.contains(searchText) ||
                $0.lastMessageText.localizedCaseInsensitiveContains(searchText)
            }
        }
    }

    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 54))
                .foregroundColor(.secondary)

            Text("No Conversations Yet")
                .font(.title2)
                .bold()

            Text("Synchronized SMS messages from your Android phone will appear here.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            Button(action: { showingNewMessageSheet = true }) {
                Label("Compose New SMS", systemImage: "plus")
                    .bold()
            }
            .buttonStyle(.borderedProminent)
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}
