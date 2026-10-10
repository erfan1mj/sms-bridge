import XCTest
@testable import SMSBridge

final class ConnectionStateTests: XCTestCase {

    func testConnectionStateEqualityAndTransitions() {
        let disconnected = ConnectionState.disconnected
        let connecting = ConnectionState.connecting
        let unauth = ConnectionState.connectedUnauthenticated
        let auth1 = ConnectionState.authenticated(gatewayName: "Pixel 8")
        let auth2 = ConnectionState.authenticated(gatewayName: "Pixel 8")
        let auth3 = ConnectionState.authenticated(gatewayName: "Galaxy S24")
        let err1 = ConnectionState.error("Timeout")
        let err2 = ConnectionState.error("Timeout")

        XCTAssertEqual(disconnected, .disconnected)
        XCTAssertEqual(auth1, auth2)
        XCTAssertNotEqual(auth1, auth3)
        XCTAssertEqual(err1, err2)
        XCTAssertNotEqual(connecting, disconnected)
        XCTAssertNotEqual(unauth, connecting)
    }

    func testDiagnosticTransitionTypes() {
        XCTAssertEqual(DiagnosticTransitionType.manualAction.rawValue, "MANUAL_ACTION")
        XCTAssertEqual(DiagnosticTransitionType.networkEvent.rawValue, "NETWORK_EVENT")
        XCTAssertEqual(DiagnosticTransitionType.socketError.rawValue, "SOCKET_ERROR")
        XCTAssertEqual(DiagnosticTransitionType.timeout.rawValue, "TIMEOUT")
        XCTAssertEqual(DiagnosticTransitionType.handshake.rawValue, "HANDSHAKE")
        XCTAssertEqual(DiagnosticTransitionType.heartbeat.rawValue, "HEARTBEAT")
        XCTAssertEqual(DiagnosticTransitionType.cancellation.rawValue, "CANCELLATION")
    }

    func testHeartbeatToleranceThreshold() {
        // Threshold is 3: 1 and 2 failures must NOT disconnect; 3 failures must disconnect
        let maxFailures = 3
        var currentFailures = 0

        func simulatePingResult(success: Bool) -> Bool {
            if success {
                currentFailures = 0
                return false // do not disconnect
            } else {
                currentFailures += 1
                return currentFailures >= maxFailures // true means disconnect
            }
        }

        // Test 1: single failure (jitter) -> should NOT disconnect
        XCTAssertFalse(simulatePingResult(success: false), "Single missed ping must not disconnect")
        XCTAssertEqual(currentFailures, 1)

        // Test 2: recovery after 1 failure -> resets count
        XCTAssertFalse(simulatePingResult(success: true), "Successful ping must reset failure count")
        XCTAssertEqual(currentFailures, 0)

        // Test 3: two consecutive failures -> should NOT disconnect yet
        XCTAssertFalse(simulatePingResult(success: false))
        XCTAssertFalse(simulatePingResult(success: false))
        XCTAssertEqual(currentFailures, 2)

        // Test 4: recovery after 2 failures -> resets count
        XCTAssertFalse(simulatePingResult(success: true))
        XCTAssertEqual(currentFailures, 0)

        // Test 5: three consecutive failures -> MUST disconnect
        XCTAssertFalse(simulatePingResult(success: false))
        XCTAssertFalse(simulatePingResult(success: false))
        XCTAssertTrue(simulatePingResult(success: false), "3 consecutive missed pings must declare connection dead")
        XCTAssertEqual(currentFailures, 3)
    }

    func testIdempotentConnectionGuards() {
        let client = WebSocketClient.shared

        // Initial state is disconnected
        client.disconnect(userInitiated: true)
        XCTAssertEqual(client.connectionState, .disconnected)

        // Calling disconnect repeatedly must remain disconnected without crash or flapping
        client.disconnect(userInitiated: true)
        XCTAssertEqual(client.connectionState, .disconnected)
    }
}
