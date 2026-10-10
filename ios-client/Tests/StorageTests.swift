import XCTest
@testable import SMSBridge

final class StorageTests: XCTestCase {
    func testPhoneNumberNormalization() {
        let storage = StorageManager.shared
        XCTAssertEqual(storage.normalizePhoneNumber("+1 (555) 123-4567"), "+15551234567")
        XCTAssertEqual(storage.normalizePhoneNumber("  0912 111 2233  "), "09121112233")
    }

    func testIncomingMessageInsertion() {
        let storage = StorageManager.shared
        let payload = SmsIncomingPayload(
            messageId: "test_msg_id_999",
            sender: "+10002000300",
            body: "Test Body",
            timestamp: 1728608400000,
            subscriptionId: 1,
            isMultipart: false,
            partCount: 1
        )
        storage.addOrUpdateIncomingMessage(payload)

        let convNumber = "+10002000300"
        let messages = storage.messagesByConversation[convNumber] ?? []
        XCTAssertTrue(messages.contains { $0.id == "test_msg_id_999" })
    }
}
