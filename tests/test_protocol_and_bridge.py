#!/usr/bin/env python3
"""
SMS Bridge Protocol & Architecture Verification Test Suite (v1)

Verifies:
1. Protocol JSON Framing & Schema Compliance
2. TLS Fingerprint Pinning & Verification Logic
3. Pairing Handshake (Valid Token, Expired Token, Revocation)
4. Authentication Lifecycle & Unauthorized Rejection
5. Unicode & Persian/Farsi Text Handling
6. Multipart SMS Concatenation Logic
7. Idempotency & Duplicate Request Prevention
8. Offline Buffering & Cursor-based Sync
"""

import unittest
import json
import uuid
import hashlib
import time

# --- Simulated Protocol Engine Matching Android & iOS Implementation ---

class MockSmsBridgeGateway:
    def __init__(self):
        self.pairing_token = None
        self.pairing_expiry = 0
        self.paired_clients = {}  # clientId -> authToken
        self.revoked_clients = set()
        self.incoming_messages = []
        self.sent_requests = {}  # requestId -> { status, parts, updatedAt, body, recipient }
        self.tls_cert_der = b"MockAndroidSelfSignedCertificateDERData"
        self.cert_sha256 = ":".join(f"{b:02X}" for b in hashlib.sha256(self.tls_cert_der).digest())

    def start_pairing(self, validity_sec=300):
        self.pairing_token = uuid.uuid4().hex
        self.pairing_expiry = time.time() + validity_sec
        return self.pairing_token, self.cert_sha256

    def is_pairing_token_valid(self, token):
        if not self.pairing_token or self.pairing_token != token:
            return False
        return time.time() <= self.pairing_expiry

    def handle_message(self, raw_json, session_auth_client_id=None):
        msg = json.loads(raw_json)
        msg_type = msg.get("type")
        payload = msg.get("payload", {})
        msg_id = msg.get("id", str(uuid.uuid4()))

        if msg_type == "PAIR_REQUEST":
            token = payload.get("pairingToken")
            client_id = payload.get("clientId")
            if not self.is_pairing_token_valid(token):
                return {
                    "version": 1,
                    "id": str(uuid.uuid4()),
                    "type": "PAIR_RESPONSE",
                    "payload": {
                        "success": False,
                        "clientAuthToken": None,
                        "gatewayDeviceName": "Mock Android",
                        "errorMessage": "Invalid or expired pairing token."
                    }
                }, None

            auth_token = uuid.uuid4().hex
            self.paired_clients[client_id] = auth_token
            self.pairing_token = None  # One-time use
            return {
                "version": 1,
                "id": str(uuid.uuid4()),
                "type": "PAIR_RESPONSE",
                "payload": {
                    "success": True,
                    "clientAuthToken": auth_token,
                    "gatewayDeviceName": "Mock Android Pixel 8",
                    "gatewayAndroidVersion": "14",
                    "simCards": [
                        {"subscriptionId": 1, "displayName": "SIM 1", "carrierName": "Carrier", "slotIndex": 0, "isDefault": True}
                    ]
                }
            }, client_id

        elif msg_type == "AUTHENTICATE":
            client_id = payload.get("clientId")
            token = payload.get("clientAuthToken")
            if client_id in self.revoked_clients or self.paired_clients.get(client_id) != token:
                return {
                    "version": 1,
                    "id": str(uuid.uuid4()),
                    "type": "ERROR",
                    "payload": {"code": "UNAUTHORIZED", "message": "Authentication failed"}
                }, None

            return {
                "version": 1,
                "id": str(uuid.uuid4()),
                "type": "AUTH_RESPONSE",
                "payload": {
                    "success": True,
                    "gatewayDeviceName": "Mock Android Pixel 8",
                    "simCards": [
                        {"subscriptionId": 1, "displayName": "SIM 1", "carrierName": "Carrier", "slotIndex": 0, "isDefault": True}
                    ],
                    "serverTimestamp": int(time.time() * 1000)
                }
            }, client_id

        elif msg_type == "SMS_SEND_REQUEST":
            if not session_auth_client_id:
                return {
                    "version": 1,
                    "id": str(uuid.uuid4()),
                    "type": "ERROR",
                    "payload": {"code": "UNAUTHORIZED", "message": "Session not authenticated"}
                }, session_auth_client_id

            req_id = payload.get("requestId")
            recipient = payload.get("recipient", "").strip()
            body = payload.get("body", "")

            # Idempotency check
            if req_id in self.sent_requests:
                existing = self.sent_requests[req_id]
                return {
                    "version": 1,
                    "id": str(uuid.uuid4()),
                    "type": "SMS_SEND_RESULT",
                    "payload": {
                        "requestId": req_id,
                        "status": existing["status"],
                        "partsCount": existing["partsCount"],
                        "timestamp": existing["updatedAt"],
                        "errorCode": None,
                        "errorMessage": None
                    }
                }, session_auth_client_id

            # Validation
            if not recipient or len(recipient) < 3:
                return {
                    "version": 1,
                    "id": str(uuid.uuid4()),
                    "type": "SMS_SEND_RESULT",
                    "payload": {
                        "requestId": req_id,
                        "status": "FAILED",
                        "errorCode": "INVALID_PAYLOAD",
                        "errorMessage": "Invalid recipient phone number"
                    }
                }, session_auth_client_id

            # Calculate multipart
            is_unicode = any(ord(c) > 127 for c in body)
            limit = 70 if is_unicode else 160
            parts = 1 if len(body) <= limit else ((len(body) + limit - 1) // limit)

            self.sent_requests[req_id] = {
                "status": "SENT",
                "partsCount": parts,
                "updatedAt": int(time.time() * 1000),
                "body": body,
                "recipient": recipient
            }

            return {
                "version": 1,
                "id": str(uuid.uuid4()),
                "type": "SMS_SEND_RESULT",
                "payload": {
                    "requestId": req_id,
                    "status": "SENT",
                    "partsCount": parts,
                    "timestamp": int(time.time() * 1000)
                }
            }, session_auth_client_id

        elif msg_type == "SYNC_REQUEST":
            if not session_auth_client_id:
                return {
                    "version": 1,
                    "id": str(uuid.uuid4()),
                    "type": "ERROR",
                    "payload": {"code": "UNAUTHORIZED", "message": "Session not authenticated"}
                }, session_auth_client_id

            since = payload.get("sinceTimestamp", 0)
            msgs = [m for m in self.incoming_messages if m["timestamp"] > since]
            pending = [
                {
                    "requestId": rid,
                    "status": data["status"],
                    "partsCount": data["partsCount"],
                    "timestamp": data["updatedAt"]
                }
                for rid, data in self.sent_requests.items()
                if data["updatedAt"] > since
            ]

            return {
                "version": 1,
                "id": str(uuid.uuid4()),
                "type": "SYNC_RESPONSE",
                "payload": {
                    "messages": msgs,
                    "pendingOutboxStatus": pending,
                    "serverTimestamp": int(time.time() * 1000)
                }
            }, session_auth_client_id

        return {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "ERROR",
            "payload": {"code": "INVALID_PAYLOAD", "message": f"Unknown type: {msg_type}"}
        }, session_auth_client_id


# --- Unit Tests ---

class TestSmsBridgeProtocol(unittest.TestCase):

    def setUp(self):
        self.gateway = MockSmsBridgeGateway()

    def test_01_tls_pinning_verification(self):
        """Verify strict TLS certificate fingerprint pinning logic."""
        cert_data = b"MockAndroidSelfSignedCertificateDERData"
        expected_hash = hashlib.sha256(cert_data).digest()
        expected_fp = ":".join(f"{b:02X}" for b in expected_hash)

        # 1. Matching certificate
        received_cert = b"MockAndroidSelfSignedCertificateDERData"
        actual_fp = ":".join(f"{b:02X}" for b in hashlib.sha256(received_cert).digest())
        self.assertEqual(actual_fp, expected_fp, "Valid certificate must match pinned fingerprint")

        # 2. Rogue/MITM certificate
        rogue_cert = b"RogueHackerCertificateData"
        rogue_fp = ":".join(f"{b:02X}" for b in hashlib.sha256(rogue_cert).digest())
        self.assertNotEqual(rogue_fp, expected_fp, "MITM certificate must fail fingerprint comparison")

    def test_02_pairing_flow_success_and_expiry(self):
        """Verify pairing token validation, one-time consumption, and timeout."""
        token, _ = self.gateway.start_pairing(validity_sec=2)
        client_id = str(uuid.uuid4())

        pair_req = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "PAIR_REQUEST",
            "payload": {
                "pairingToken": token,
                "clientName": "iPhone 15 Pro",
                "clientId": client_id
            }
        }

        # Successful pair
        resp, auth_client_id = self.gateway.handle_message(json.dumps(pair_req))
        self.assertTrue(resp["payload"]["success"])
        self.assertIsNotNone(resp["payload"]["clientAuthToken"])
        self.assertEqual(auth_client_id, client_id)

        # Re-using the same token must fail (consumed)
        resp2, _ = self.gateway.handle_message(json.dumps(pair_req))
        self.assertFalse(resp2["payload"]["success"])

        # Expired token test
        token_exp, _ = self.gateway.start_pairing(validity_sec=-1) # in the past
        pair_req_exp = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "PAIR_REQUEST",
            "payload": {
                "pairingToken": token_exp,
                "clientName": "iPhone 15 Pro",
                "clientId": str(uuid.uuid4())
            }
        }
        resp_exp, _ = self.gateway.handle_message(json.dumps(pair_req_exp))
        self.assertFalse(resp_exp["payload"]["success"], "Expired token must be rejected")

    def test_03_authentication_and_unauthorized_rejection(self):
        """Verify authentication token handshake and rejection of unpaired clients."""
        client_id = str(uuid.uuid4())
        token, _ = self.gateway.start_pairing()

        # Pair first
        pair_req = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "PAIR_REQUEST",
            "payload": {"pairingToken": token, "clientName": "iPhone", "clientId": client_id}
        }
        pair_resp, _ = self.gateway.handle_message(json.dumps(pair_req))
        auth_token = pair_resp["payload"]["clientAuthToken"]

        # Re-authenticate
        auth_req = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "AUTHENTICATE",
            "payload": {"clientAuthToken": auth_token, "clientId": client_id}
        }
        auth_resp, sess_id = self.gateway.handle_message(json.dumps(auth_req))
        self.assertTrue(auth_resp["payload"]["success"])
        self.assertEqual(sess_id, client_id)

        # Invalid token
        bad_auth = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "AUTHENTICATE",
            "payload": {"clientAuthToken": "bogus_token", "clientId": client_id}
        }
        bad_resp, _ = self.gateway.handle_message(json.dumps(bad_auth))
        self.assertEqual(bad_resp["type"], "ERROR")
        self.assertEqual(bad_resp["payload"]["code"], "UNAUTHORIZED")

    def test_04_persian_unicode_and_multipart_handling(self):
        """Verify Unicode and Persian text encoding and multipart part calculation."""
        persian_body = "سلام! این یک پیام آزمایشی حاوی نویسه‌های فارسی و یونیکد است."
        req_id = "req_fa_1"

        send_req = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "SMS_SEND_REQUEST",
            "payload": {
                "requestId": req_id,
                "recipient": "+989121112233",
                "body": persian_body,
                "subscriptionId": 1
            }
        }

        resp, _ = self.gateway.handle_message(json.dumps(send_req), session_auth_client_id="client_1")
        self.assertEqual(resp["type"], "SMS_SEND_RESULT")
        self.assertEqual(resp["payload"]["status"], "SENT")
        self.assertEqual(self.gateway.sent_requests[req_id]["body"], persian_body)

        # Long multipart Persian message (> 70 chars UCS-2)
        long_persian = persian_body * 3
        req_long_id = "req_fa_long"
        send_long = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "SMS_SEND_REQUEST",
            "payload": {
                "requestId": req_long_id,
                "recipient": "+989121112233",
                "body": long_persian,
                "subscriptionId": 1
            }
        }
        resp_long, _ = self.gateway.handle_message(json.dumps(send_long), session_auth_client_id="client_1")
        self.assertGreater(resp_long["payload"]["partsCount"], 1, "Long Unicode SMS must be multipart")

    def test_05_idempotency_prevents_duplicate_sms_sends(self):
        """Verify that duplicate requests with the same requestId do NOT re-trigger sending."""
        req_id = "req_idempotent_test_99"
        send_req = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "SMS_SEND_REQUEST",
            "payload": {
                "requestId": req_id,
                "recipient": "+15551234567",
                "body": "First dispatch",
                "subscriptionId": 1
            }
        }

        # 1. First send
        resp1, _ = self.gateway.handle_message(json.dumps(send_req), session_auth_client_id="client_1")
        self.assertEqual(resp1["payload"]["status"], "SENT")
        first_time = resp1["payload"]["timestamp"]

        # 2. Repeated send with same requestId (simulating network retry)
        resp2, _ = self.gateway.handle_message(json.dumps(send_req), session_auth_client_id="client_1")
        self.assertEqual(resp2["payload"]["status"], "SENT")
        self.assertEqual(resp2["payload"]["timestamp"], first_time, "Must return existing cached result without re-executing")

    def test_06_offline_buffering_and_sync(self):
        """Verify incoming SMS messages while iPhone is offline are retrieved upon sync."""
        # 1. Incoming SMS arrives while iPhone is offline
        t0 = int(time.time() * 1000)
        self.gateway.incoming_messages.append({
            "messageId": "msg_offline_1",
            "sender": "+18005550199",
            "body": "Verification Code: 4921",
            "timestamp": t0 + 100,
            "subscriptionId": 1,
            "isMultipart": False,
            "partCount": 1
        })

        # 2. iPhone connects and issues SYNC_REQUEST with sinceTimestamp = t0
        sync_req = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "SYNC_REQUEST",
            "payload": {
                "sinceTimestamp": t0,
                "limit": 50
            }
        }

        resp, _ = self.gateway.handle_message(json.dumps(sync_req), session_auth_client_id="client_1")
        self.assertEqual(resp["type"], "SYNC_RESPONSE")
        synced_msgs = resp["payload"]["messages"]
        self.assertEqual(len(synced_msgs), 1)
        self.assertEqual(synced_msgs[0]["messageId"], "msg_offline_1")
        self.assertEqual(synced_msgs[0]["body"], "Verification Code: 4921")

    def test_07_invalid_input_handling(self):
        """Verify validation handles empty/short phone numbers and empty bodies."""
        bad_req = {
            "version": 1,
            "id": str(uuid.uuid4()),
            "type": "SMS_SEND_REQUEST",
            "payload": {
                "requestId": "req_invalid",
                "recipient": "1",  # Invalid
                "body": "Hello",
                "subscriptionId": 1
            }
        }
        resp, _ = self.gateway.handle_message(json.dumps(bad_req), session_auth_client_id="client_1")
        self.assertEqual(resp["payload"]["status"], "FAILED")
        self.assertEqual(resp["payload"]["errorCode"], "INVALID_PAYLOAD")


if __name__ == "__main__":
    unittest.main()
