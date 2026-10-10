# Android-to-iPhone SMS Bridge Protocol (v1)

## 1. Overview & Transport
The SMS Bridge protocol provides secure, bidirectional, local communication between an Android Gateway device and an iPhone Client over a local Wi-Fi network or Android Hotspot.

- **Transport:** WebSocket over TLS (`wss://[android-ip]:[port]/ws`)
- **Default Port:** `8765`
- **Serialization:** UTF-8 JSON
- **Protocol Version:** `1`

## 2. Security & Trust Architecture

### 2.1 Out-of-Band Trust & TLS Verification (Pinning)
To ensure end-to-end transport encryption over local networks without reliance on public DNS or third-party Certificate Authorities:
1. The Android Gateway generates an X.509 TLS certificate upon first initialization and stores its keypair securely.
2. The Android Gateway computes the SHA-256 fingerprint of this certificate (`certSha256`).
3. When placing the Android Gateway in pairing mode, it generates a cryptographically random, short-lived (5-minute TTL) `pairingToken`.
4. The Android Gateway renders a pairing QR code containing:
   ```json
   {
     "v": 1,
     "endpoints": ["wss://192.168.43.1:8765/ws", "wss://192.168.1.120:8765/ws"],
     "certSha256": "8f4a2c91b5e34d...",
     "pairingToken": "c8f1e63a...",
     "deviceName": "Pixel 8 (Android)"
   }
   ```
5. When the iPhone scans the QR code, it connects via `wss://`. During the TLS handshake, the iOS client validates the server's TLS certificate against the scanned `certSha256` pin.
6. **TLS certificate verification is never disabled.** Handshakes with certificates that do not match the pinned SHA-256 hash are immediately aborted.

### 2.2 Pairing & Authentication
1. Once TLS is established, the iPhone sends a `PAIR_REQUEST` carrying the `pairingToken` and client metadata.
2. The Android Gateway verifies the token. Upon success, it issues a high-entropy `clientAuthToken` and stores the paired client in its local database.
3. The iPhone stores `clientAuthToken` and the pinned certificate fingerprint in the iOS Keychain.
4. On future reconnects, the iPhone presents `AUTHENTICATE` with `clientAuthToken`. Unauthenticated sessions are terminated after 10 seconds.
5. Either device can revoke pairing at any time.

---

## 3. Message Framing
Every message transmitted over the WebSocket is a single JSON object conforming to this structure:

```json
{
  "version": 1,
  "id": "c76f4b6a-939e-4e89-9a74-d022b7d90391",
  "type": "MESSAGE_TYPE",
  "payload": {}
}
```

- `version`: Integer, protocol version (`1`).
- `id`: UUIDv4 string, unique to this message instance.
- `type`: String enum identifying the message type.
- `payload`: JSON object containing the payload specific to `type`.

---

## 4. Message Definitions

### 4.1 `PAIR_REQUEST`
Sent by client immediately after TLS handshake when pairing a new device.
```json
{
  "version": 1,
  "id": "6a9c14c5-3492-4916-bd89-8d18b335da43",
  "type": "PAIR_REQUEST",
  "payload": {
    "pairingToken": "c8f1e63a7d4e2b01...",
    "clientName": "Erfan's iPhone 15 Pro",
    "clientId": "a98df23e-..."
  }
}
```

### 4.2 `PAIR_RESPONSE`
Sent by Android Gateway in response to `PAIR_REQUEST`.
```json
{
  "version": 1,
  "id": "31b4ce6d-713e-4fa0-8f92-56fa08f1e40a",
  "type": "PAIR_RESPONSE",
  "payload": {
    "success": true,
    "clientAuthToken": "9bfa780d6e24...",
    "gatewayDeviceName": "Google Pixel 8",
    "gatewayAndroidVersion": "14",
    "simCards": [
      {
        "subscriptionId": 1,
        "displayName": "Carrier A",
        "carrierName": "MCI",
        "slotIndex": 0,
        "isDefault": true
      }
    ],
    "errorMessage": null
  }
}
```

### 4.3 `AUTHENTICATE`
Sent by iPhone upon reconnecting with an existing pairing.
```json
{
  "version": 1,
  "id": "e81d8560-6188-464a-a236-4c4f90119842",
  "type": "AUTHENTICATE",
  "payload": {
    "clientAuthToken": "9bfa780d6e24...",
    "clientId": "a98df23e-..."
  }
}
```

### 4.4 `AUTH_RESPONSE`
Sent by Android Gateway acknowledging `AUTHENTICATE`.
```json
{
  "version": 1,
  "id": "fa2b39c0-6d45-48b2-b349-16a8bfb51201",
  "type": "AUTH_RESPONSE",
  "payload": {
    "success": true,
    "gatewayDeviceName": "Google Pixel 8",
    "simCards": [
      {
        "subscriptionId": 1,
        "displayName": "Carrier A",
        "carrierName": "MCI",
        "slotIndex": 0,
        "isDefault": true
      }
    ],
    "serverTimestamp": 1728608400000,
    "errorMessage": null
  }
}
```

### 4.5 `CONNECTION_STATUS`
Broadcast periodically or on telephony state changes (SIM change, cellular service status).
```json
{
  "version": 1,
  "id": "8b7e21a4-2390-4c7b-9da1-08197eefb3c1",
  "type": "CONNECTION_STATUS",
  "payload": {
    "status": "AUTHENTICATED",
    "simCards": [
      {
        "subscriptionId": 1,
        "displayName": "Carrier A",
        "carrierName": "MCI",
        "slotIndex": 0,
        "isDefault": true
      }
    ],
    "smsPermissionGranted": true,
    "isDefaultSmsApp": false,
    "batteryLevel": 88
  }
}
```

### 4.6 `SMS_INCOMING`
Pushed by Android Gateway to the iPhone when a new SMS is received.
```json
{
  "version": 1,
  "id": "4d90f23a-e0b4-4e20-9462-811ebc21051b",
  "type": "SMS_INCOMING",
  "payload": {
    "messageId": "msg_98df8018274a",
    "sender": "+1234567890",
    "body": "سلام! کد تأیید شما 48291 است.",
    "timestamp": 1728608401234,
    "subscriptionId": 1,
    "isMultipart": false,
    "partCount": 1
  }
}
```

### 4.7 `SMS_SEND_REQUEST`
Sent by iPhone to request an SMS dispatch via Android SIM.
- `requestId`: Idempotency key generated by the iPhone. Android checks if this `requestId` has already been processed to prevent duplicate sends on network retries.
```json
{
  "version": 1,
  "id": "1894d03e-8f23-41bb-9872-9c10eb3410fa",
  "type": "SMS_SEND_REQUEST",
  "payload": {
    "requestId": "req_iphone_98b472e1",
    "recipient": "+1234567890",
    "body": "Hello from iPhone via Android SIM!",
    "subscriptionId": 1
  }
}
```

### 4.8 `SMS_SEND_RESULT`
Sent by Android Gateway indicating the progress/result of an `SMS_SEND_REQUEST`.
Status lifecycle:
- `QUEUED`: Received and validated.
- `SUBMITTED`: Passed to Android `SmsManager`.
- `SENT`: Confirmed dispatched by Android radio via `SMS_SENT_ACTION`.
- `FAILED`: Radio error, permission error, or carrier rejection.
```json
{
  "version": 1,
  "id": "b182e094-1182-41eb-8924-f7b23190ab7c",
  "type": "SMS_SEND_RESULT",
  "payload": {
    "requestId": "req_iphone_98b472e1",
    "status": "SENT",
    "partsCount": 1,
    "timestamp": 1728608405000,
    "errorCode": null,
    "errorMessage": null
  }
}
```

### 4.9 `SMS_DELIVERY_STATUS`
Sent by Android Gateway if/when a genuine carrier delivery report arrives via `SMS_DELIVERED_ACTION`.
```json
{
  "version": 1,
  "id": "9283e104-583b-41da-a719-7489abf18e22",
  "type": "SMS_DELIVERY_STATUS",
  "payload": {
    "requestId": "req_iphone_98b472e1",
    "status": "DELIVERED",
    "timestamp": 1728608412000,
    "rawPduStatus": 0
  }
}
```

### 4.10 `SYNC_REQUEST`
Sent by iPhone upon reconnecting to retrieve messages missed while offline.
```json
{
  "version": 1,
  "id": "47a82910-410a-49ce-b441-fa001b9423ea",
  "type": "SYNC_REQUEST",
  "payload": {
    "sinceTimestamp": 1728600000000,
    "lastKnownMessageId": "msg_98df8018274a",
    "limit": 100
  }
}
```

### 4.11 `SYNC_RESPONSE`
Sent by Android Gateway in response to `SYNC_REQUEST`.
```json
{
  "version": 1,
  "id": "b47921ca-49d0-47b2-8491-0e838e819b91",
  "type": "SYNC_RESPONSE",
  "payload": {
    "messages": [
      {
        "messageId": "msg_98df8018274b",
        "sender": "+1987654321",
        "body": "Meeting at 3 PM",
        "timestamp": 1728608500000,
        "subscriptionId": 1,
        "isMultipart": false
      }
    ],
    "pendingOutboxStatus": [
      {
        "requestId": "req_iphone_98b472e1",
        "status": "DELIVERED",
        "timestamp": 1728608412000
      }
    ],
    "serverTimestamp": 1728608600000
  }
}
```

### 4.12 `ERROR`
Sent by either side on unrecoverable protocol errors.
```json
{
  "version": 1,
  "id": "e091b483-3294-411a-a1b0-ff882410a293",
  "type": "ERROR",
  "payload": {
    "code": "UNAUTHORIZED",
    "message": "Invalid or expired authentication token",
    "relatedRequestId": null
  }
}
```

Standard Error Codes:
- `UNAUTHORIZED`: Pairing token invalid, expired, or missing auth token.
- `INVALID_PAYLOAD`: Malformed JSON or missing required fields.
- `PERMISSION_DENIED`: SMS permissions not granted on Android.
- `NO_SIM`: No active cellular subscription available.
- `CARRIER_ERROR`: Radio failure or carrier SMSC error.
- `DUPLICATE_REQUEST`: Idempotency collision with in-flight send request.
- `RATE_LIMITED`: Too many requests in short interval.

---

## 5. Idempotency & Duplicate Prevention
- Every outgoing SMS generated by iPhone assigns a unique `requestId` (e.g. `req_iphone_<UUID>`).
- The Android Gateway stores every `requestId` in its local database (`sent_requests` table).
- If the network drops after the iPhone transmits `SMS_SEND_REQUEST` but before receiving `SMS_SEND_RESULT`, the iPhone does **NOT** blindly send a new request with a new ID. Instead, on reconnecting, it sends `SYNC_REQUEST` or re-queries the status with the same `requestId`.
- If the Android Gateway receives an `SMS_SEND_REQUEST` with an already-known `requestId`, it returns the cached `SMS_SEND_RESULT` rather than invoking `SmsManager` a second time.
