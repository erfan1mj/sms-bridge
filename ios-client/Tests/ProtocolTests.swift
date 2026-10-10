import XCTest
@testable import SMSBridge

final class ProtocolTests: XCTestCase {
    func testPairRequestSerialization() throws {
        let payload = PairRequestPayload(
            pairingToken: "sample_token_123",
            clientName: "Erfan's iPhone",
            clientId: "uuid-1234"
        )
        let message = BridgeMessage(type: MessageTypes.pairRequest, payload: payload)

        let data = try JSONEncoder().encode(message)
        let jsonString = String(data: data, encoding: .utf8)!

        XCTAssertTrue(jsonString.contains("sample_token_123"))
        XCTAssertTrue(jsonString.contains("PAIR_REQUEST"))

        let decoded = try JSONDecoder().decode(BridgeMessage<PairRequestPayload>.self, from: data)
        XCTAssertEqual(decoded.type, "PAIR_REQUEST")
        XCTAssertEqual(decoded.payload.pairingToken, "sample_token_123")
    }

    func testUnicodePersianTextRoundTrip() throws {
        let persianBody = "سلام! این یک پیام آزمایشی به زبان فارسی است."
        let payload = SmsIncomingPayload(
            messageId: "msg_fa_1",
            sender: "+989120000000",
            body: persianBody,
            timestamp: 1728608400000,
            subscriptionId: 1,
            isMultipart: false,
            partCount: 1
        )
        let message = BridgeMessage(type: MessageTypes.smsIncoming, payload: payload)

        let data = try JSONEncoder().encode(message)
        let decoded = try JSONDecoder().decode(BridgeMessage<SmsIncomingPayload>.self, from: data)

        XCTAssertEqual(decoded.payload.body, persianBody)
        XCTAssertEqual(decoded.payload.sender, "+989120000000")
    }

    func testSendStatusLifecycleValues() {
        XCTAssertEqual(SendStatus.queued.rawValue, "QUEUED")
        XCTAssertEqual(SendStatus.submitted.rawValue, "SUBMITTED")
        XCTAssertEqual(SendStatus.sent.rawValue, "SENT")
        XCTAssertEqual(SendStatus.delivered.rawValue, "DELIVERED")
        XCTAssertEqual(SendStatus.failed.rawValue, "FAILED")
    }
}
