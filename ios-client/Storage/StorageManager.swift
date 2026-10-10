import Foundation
import Combine

final class StorageManager: ObservableObject {
    static let shared = StorageManager()

    @Published var conversations: [AppConversation] = []
    @Published var messagesByConversation: [String: [AppMessage]] = [:]
    @Published var lastSyncTimestamp: Int64 = 0

    private let fileManager = FileManager.default
    private let conversationsFileName = "conversations.json"
    private let messagesFileName = "messages.json"
    private let syncMetaFileName = "sync_meta.json"

    private init() {
        loadData()
    }

    private var storageDirectory: URL {
        fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    // MARK: - Message Ingestion & Updates

    func addOrUpdateIncomingMessage(_ payload: SmsIncomingPayload) {
        let normalizedNumber = normalizePhoneNumber(payload.sender)
        let message = AppMessage(
            id: payload.messageId,
            conversationNumber: normalizedNumber,
            sender: payload.sender,
            recipient: "Me",
            body: payload.body,
            timestamp: Date(timeIntervalSince1970: Double(payload.timestamp) / 1000.0),
            isOutgoing: false,
            sendStatus: .delivered,
            deliveryStatus: nil,
            subscriptionId: payload.subscriptionId,
            isMultipart: payload.isMultipart,
            errorMessage: nil
        )

        insertMessage(message)
        updateConversation(for: normalizedNumber, lastMessage: payload.body, timestamp: message.timestamp, incrementUnread: true)
        saveData()
    }

    func addOutgoingMessage(
        requestId: String,
        recipient: String,
        body: String,
        subscriptionId: Int
    ) {
        let normalizedNumber = normalizePhoneNumber(recipient)
        let message = AppMessage(
            id: requestId,
            conversationNumber: normalizedNumber,
            sender: "Me",
            recipient: recipient,
            body: body,
            timestamp: Date(),
            isOutgoing: true,
            sendStatus: .queued,
            deliveryStatus: nil,
            subscriptionId: subscriptionId,
            isMultipart: false,
            errorMessage: nil
        )

        insertMessage(message)
        updateConversation(for: normalizedNumber, lastMessage: body, timestamp: message.timestamp, incrementUnread: false)
        saveData()
    }

    func updateSendResult(_ result: SmsSendResultPayload) {
        let requestId = result.requestId
        for (convNumber, list) in messagesByConversation {
            if let index = list.firstIndex(where: { $0.id == requestId }) {
                var updated = list[index]
                updated.sendStatus = result.status
                updated.errorMessage = result.errorMessage
                messagesByConversation[convNumber]?[index] = updated
                break
            }
        }
        saveData()
    }

    func updateDeliveryStatus(_ delivery: SmsDeliveryStatusPayload) {
        let requestId = delivery.requestId
        for (convNumber, list) in messagesByConversation {
            if let index = list.firstIndex(where: { $0.id == requestId }) {
                var updated = list[index]
                if delivery.status == "DELIVERED" {
                    updated.deliveryStatus = "DELIVERED"
                    updated.sendStatus = .delivered
                }
                messagesByConversation[convNumber]?[index] = updated
                break
            }
        }
        saveData()
    }

    func markConversationAsRead(_ conversationNumber: String) {
        if let index = conversations.firstIndex(where: { $0.phoneNumber == conversationNumber }) {
            conversations[index].unreadCount = 0
            saveData()
        }
    }

    func clearAllLocalData() {
        conversations.removeAll()
        messagesByConversation.removeAll()
        lastSyncTimestamp = 0

        try? fileManager.removeItem(at: storageDirectory.appendingPathComponent(conversationsFileName))
        try? fileManager.removeItem(at: storageDirectory.appendingPathComponent(messagesFileName))
        try? fileManager.removeItem(at: storageDirectory.appendingPathComponent(syncMetaFileName))
    }

    // MARK: - Private Helpers

    private func insertMessage(_ message: AppMessage) {
        var list = messagesByConversation[message.conversationNumber] ?? []
        if let existingIdx = list.firstIndex(where: { $0.id == message.id }) {
            list[existingIdx] = message
        } else {
            list.append(message)
            list.sort { $0.timestamp < $1.timestamp }
        }
        messagesByConversation[message.conversationNumber] = list
    }

    private func updateConversation(for number: String, lastMessage: String, timestamp: Date, incrementUnread: Bool) {
        if let index = conversations.firstIndex(where: { $0.phoneNumber == number }) {
            var conv = conversations[index]
            conv.lastMessageText = lastMessage
            conv.lastTimestamp = timestamp
            if incrementUnread {
                conv.unreadCount += 1
            }
            conversations[index] = conv
        } else {
            let newConv = AppConversation(
                phoneNumber: number,
                displayName: nil,
                lastMessageText: lastMessage,
                lastTimestamp: timestamp,
                unreadCount: incrementUnread ? 1 : 0
            )
            conversations.append(newConv)
        }
        conversations.sort { $0.lastTimestamp > $1.lastTimestamp }
    }

    func normalizePhoneNumber(_ number: String) -> String {
        let filtered = number.trimmingCharacters(in: .whitespacesAndNewlines)
        return filtered.replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: "(", with: "")
            .replacingOccurrences(of: ")", with: "")
    }

    // MARK: - File Persistence

    private func saveData() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self else { return }
            do {
                let convData = try JSONEncoder().encode(self.conversations)
                try convData.write(to: self.storageDirectory.appendingPathComponent(self.conversationsFileName))

                let msgData = try JSONEncoder().encode(self.messagesByConversation)
                try msgData.write(to: self.storageDirectory.appendingPathComponent(self.messagesFileName))

                let metaData = try JSONEncoder().encode(self.lastSyncTimestamp)
                try metaData.write(to: self.storageDirectory.appendingPathComponent(self.syncMetaFileName))
            } catch {
                print("StorageManager error saving data: \(error)")
            }
        }
    }

    private func loadData() {
        do {
            let convUrl = storageDirectory.appendingPathComponent(conversationsFileName)
            if fileManager.fileExists(atPath: convUrl.path) {
                let data = try Data(contentsOf: convUrl)
                self.conversations = try JSONDecoder().decode([AppConversation].self, from: data)
            }

            let msgUrl = storageDirectory.appendingPathComponent(messagesFileName)
            if fileManager.fileExists(atPath: msgUrl.path) {
                let data = try Data(contentsOf: msgUrl)
                self.messagesByConversation = try JSONDecoder().decode([String: [AppMessage]].self, from: data)
            }

            let metaUrl = storageDirectory.appendingPathComponent(syncMetaFileName)
            if fileManager.fileExists(atPath: metaUrl.path) {
                let data = try Data(contentsOf: metaUrl)
                self.lastSyncTimestamp = try JSONDecoder().decode(Int64.self, from: data)
            }
        } catch {
            print("StorageManager error loading data: \(error)")
        }
    }
}
