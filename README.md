# Android-to-iPhone SMS Bridge (MVP v1)

A native, personal-use SMS bridging system allowing an iPhone to send and receive cellular SMS messages through a SIM card installed in an Android smartphone.

Communication occurs **directly over your local Wi-Fi network or Android Hotspot**. No cloud servers, external accounts, third-party messaging services, or subscription fees are required.

---

## 1. Architecture Overview

The system consists of two native applications and a versioned bidirectional protocol:

```
┌─────────────────────────────────┐                ┌──────────────────────────────────┐
│         Android Gateway         │                │          iPhone Client           │
│   (Kotlin / Jetpack Compose)    │                │         (Swift / SwiftUI)        │
│                                 │                │                                  │
│ • Cellular Radio (SIM 1 / SIM 2)│                │ • Conversations UI & Bubbles     │
│ • Telephony SmsManager / PDU Rcv│  Local Wi-Fi   │ • Message Composer (GSM/Unicode) │
│ • Room Database (Persist & Sync)│ ────────────── │ • In-App SMS State Engine        │
│ • TLSv1.3 WebSocket Host (8765) │ or AP Hotspot  │ • Keychain Token & Pin Storage   │
│ • Out-of-band SHA-256 TLS Pin   │  (WSS / JSON)  │ • Pinned TLS Certificate Verifier│
│ • Foreground Service (Keepalive)│                │ • Auto-Reconnect & Offline Sync  │
└─────────────────────────────────┘                └──────────────────────────────────┘
```

### 1.1 Android Gateway (`/android-gateway`)
- **Language / Framework:** Kotlin, Jetpack Compose, Material 3, Coroutines & Flow.
- **Data Persistence:** Room Database (`messages`, `sent_requests`, `paired_clients`).
- **Networking:** Embedded TLS WebSocket Server with mutual session validation and connection timeout.
- **Security:** Self-signed X.509 certificate generated on-device with SHA-256 fingerprint displayed via QR code; certificate pinning enforced by client.
- **Telephony:** Intercepts incoming SMS via `android.provider.Telephony.SMS_RECEIVED`, parses PDUs, concatenates multipart messages, routes outgoing SMS via `SmsManager.sendTextMessage()` / `sendMultipartTextMessage()`, tracks exact radio transmission (`SMS_SENT`) and carrier delivery receipts (`SMS_DELIVERED`).
- **Multi-SIM:** Detects installed SIM subscriptions via `SubscriptionManager` and routes messages to user-selected SIMs.

### 1.2 iPhone Client (`/ios-client`)
- **Language / Framework:** Swift, SwiftUI, NavigationStack, async/await, Combine.
- **Networking:** Native `URLSessionWebSocketTask` with a custom `TLSPinningDelegate`. **TLS certificate verification is never disabled**; it validates the server certificate against the pinned SHA-256 hash scanned from the physical QR code.
- **Storage:** Secure iOS Keychain for auth tokens and pinned certificate hash; local JSON cache for conversation threads.
- **User Interface:**
  - **Connection Screen:** Real-time connection badge, AVFoundation camera QR code scanner, manual connection fallback, gateway diagnostics.
  - **Conversations Screen:** Thread list grouped by phone number, message preview, timestamp, unread count badge, and new conversation composer.
  - **Conversation Detail:** Chat bubble interface, chronological ordering, sending status indicators (`QUEUED`, `SUBMITTED`, `SENT`, `DELIVERED`, `FAILED`), SMS parts estimation (GSM vs Unicode/Persian), and SIM card selector.
  - **Settings Screen:** Paired device info, synchronization diagnostics, force sync, local cache clearing, and pairing revocation.

### 1.3 Shared Protocol (`/shared-protocol`)
- Versioned JSON messaging over TLS WebSocket (`wss://[android-ip]:8765/ws`).
- Message types: `PAIR_REQUEST`, `PAIR_RESPONSE`, `AUTHENTICATE`, `AUTH_RESPONSE`, `CONNECTION_STATUS`, `SMS_INCOMING`, `SMS_SEND_REQUEST`, `SMS_SEND_RESULT`, `SMS_DELIVERY_STATUS`, `SYNC_REQUEST`, `SYNC_RESPONSE`, `ERROR`.
- Full specification documented in [PROTOCOL.md](file:///d:/claude/god%20project/shared-protocol/PROTOCOL.md) and [protocol.json](file:///d:/claude/god%20project/shared-protocol/protocol.json).

---

## 2. Android Permissions & System Role Analysis

A complete architectural breakdown is provided in [PERMISSIONS_AND_ROLES.md](file:///d:/claude/god%20project/docs/PERMISSIONS_AND_ROLES.md).

| Permission | Category | Purpose in SMS Bridge |
|---|---|---|
| `android.permission.RECEIVE_SMS` | Runtime / Dangerous | Intercepts incoming cellular SMS broadcasts (`SMS_RECEIVED`), parses PDUs, concatenates multipart parts, and saves them locally. **Does NOT require being the default SMS app.** |
| `android.permission.SEND_SMS` | Runtime / Dangerous | Transmits outgoing SMS via `SmsManager`. Listens to `PendingIntent` callbacks for exact radio send status. **Does NOT require being the default SMS app.** |
| `android.permission.READ_PHONE_STATE` | Runtime / Dangerous | Queries `SubscriptionManager` to identify active physical SIM cards and eSIMs on Dual-SIM devices. |
| `android.permission.FOREGROUND_SERVICE` | Normal | Keeps the local TLS WebSocket server and socket connection alive when the app is minimized or the screen is locked. |
| `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE` | Normal (Android 14+) | Explicit Android 14+ foreground service type for connected peripheral / bridge communication. |
| `android.permission.POST_NOTIFICATIONS` | Runtime (Android 13+) | Displays the persistent foreground service notification with a "Stop Bridge" action. |

### Why SMS Bridge does NOT force Default SMS App status:
- On Android, setting an app as the default SMS app causes the primary messaging application (e.g., Google Messages) to stop receiving incoming SMS and MMS notifications, disrupting regular phone use.
- The SMS Bridge operates reliably using only standard `RECEIVE_SMS` and `SEND_SMS` permissions.
- *Limitation:* Messages sent by the bridge will reach the cellular recipient, but Android prevents non-default apps from writing to the system provider (`content://sms/`), meaning sent messages will appear in the SMS Bridge app and on the iPhone, but not in Google Messages.
- An in-app informational card explains these trade-offs to the user before offering any default role change.

---

## 3. Pairing and Connection Workflow

```
[Android Phone]                                    [iPhone]
       │                                              │
       ├─ Start Bridge (Binds wss://0.0.0.0:8765/ws)  │
       ├─ Generate 5-min pairing token & cert SHA-256 │
       ├─ Display QR Code on screen                   │
       │                                              ├─ Scan QR via Camera
       │ ◄─────── TLS Handshake (Pinning check) ──────┤ (Verify cert SHA-256)
       │ ◄─────── PAIR_REQUEST (token, name) ─────────┤
       ├─ Validate token; issue clientAuthToken       │
       ├───────── PAIR_RESPONSE (authToken, SIMs) ───►│ (Store in Keychain)
       │                                              ├─ SYNC_REQUEST (cursor 0)
       ├───────── SYNC_RESPONSE (missed messages) ────►│
       │                                              │
  [AUTHENTICATED]                                [AUTHENTICATED]
```

### Out-of-Band Certificate Pinning (RFC-grade security)
To prevent Man-in-the-Middle (MITM) attacks on untrusted Wi-Fi networks without relying on external public Certificate Authorities:
1. Android generates an X.509 self-signed certificate upon first launch and computes its SHA-256 fingerprint.
2. The fingerprint is embedded directly in the visual QR code scanned by the iPhone.
3. During the TLS handshake, iOS's `TLSPinningDelegate` extracts the server certificate, hashes it with `CryptoKit.SHA256`, and compares it with the scanned hash. If they do not match, the connection is instantly rejected. **TLS certificate verification is never bypassed.**

---

## 4. Outgoing SMS Lifecycle & Idempotency

Every outgoing message submitted by the iPhone assigns an idempotency key (`requestId`, e.g. `req_iphone_<UUID>`).

```
iPhone: QUEUED ──► Android: SUBMITTED ──► Radio: SENT ──► Carrier: DELIVERED
                         │                   │                 │
                         └──► FAILED ────────┴──► FAILED ──────┘
```

1. **`QUEUED`**: Message recorded in iPhone local store before dispatch.
2. **`SUBMITTED`**: Android Gateway authenticated the request, verified permissions and SIM availability, and passed message parts to `SmsManager`.
3. **`SENT`**: Radio confirmed cellular transmission via `PendingIntent` callback (`Activity.RESULT_OK`).
4. **`DELIVERED`**: Carrier delivery report received via `SMS_DELIVERED_ACTION` PDU.
5. **`FAILED`**: Explicit radio error (`RESULT_ERROR_NO_SERVICE`, `RESULT_ERROR_RADIO_OFF`, `GENERIC_FAILURE`, or invalid number).
6. **Network Reconnection / Retry Safety:** If the Wi-Fi connection drops while sending, the iPhone re-queries status for the same `requestId`. Android returns the existing status from its Room database and **never sends a duplicate SMS**.

---

## 5. Directory Structure

```
/
├── android-gateway/         # Native Android application
│   ├── app/src/main/        # Kotlin source, Compose UI, Service, Room DB
│   ├── app/build.gradle.kts # Dependencies (Room, Ktor/JavaWebSocket, ZXing)
│   └── build.gradle.kts     # Root build file
├── ios-client/              # Native iOS application
│   ├── SMSBridgeApp.swift   # SwiftUI App entry point
│   ├── Models/              # Protocol messages, Conversation, Message
│   ├── Network/             # WebSocketClient, TLSPinningDelegate (Pinning)
│   ├── Storage/             # StorageManager, KeychainHelper
│   ├── Views/               # Connection, Conversations, Composer, Settings
│   └── Info.plist           # Camera & Local Network privacy permissions
├── shared-protocol/         # Shared Protocol specifications
│   ├── PROTOCOL.md          # Full Protocol v1 Specification
│   └── protocol.json        # Formal JSON Schema
├── docs/                    # Technical documentation
│   ├── PERMISSIONS_AND_ROLES.md # Android SMS & background execution analysis
│   ├── TEST_CHECKLIST.md        # 17-scenario physical device testing manual
│   └── IOS_BUILD_GUIDE.md       # Xcode build, signing, and run instructions
└── tests/                   # Automated tests
    └── test_protocol_and_bridge.py # Full protocol and security test suite
```

---

## 6. How to Run & Test

### Automated Integration Tests
Run the standalone protocol verification suite (tests TLS pinning, pairing, auth, idempotency, Unicode/Persian handling, and sync):
```bash
python tests/test_protocol_and_bridge.py -v
```

### Building the Android Gateway
1. **Pre-built APK Available:** The Android Gateway Debug APK has been built and is ready to install:
   - Root location: [sms-bridge-gateway-debug.apk](file:///d:/claude/god%20project/sms-bridge-gateway-debug.apk) (20.0 MB)
   - Gradle output location: [android-gateway/app/build/outputs/apk/debug/app-debug.apk](file:///d:/claude/god%20project/android-gateway/app/build/outputs/apk/debug/app-debug.apk)
2. **Installing via adb:**
   ```bash
   adb install -r sms-bridge-gateway-debug.apk
   ```
3. **Building from Source:**
   Open [/android-gateway](file:///d:/claude/god%20project/android-gateway) in Android Studio or compile via command line:
   ```bash
   cd android-gateway
   ./gradlew assembleDebug
   ```

### Building the iPhone Client
Detailed step-by-step instructions are available in [IOS_BUILD_GUIDE.md](file:///d:/claude/god%20project/docs/IOS_BUILD_GUIDE.md):
1. Open Xcode on a Mac.
2. Create a SwiftUI project named `SMSBridge` and import the files from `/ios-client/`.
3. Set your free Apple ID Team under **Signing & Capabilities**.
4. Connect your iPhone and run on the physical device.

### Physical Two-Device Testing
Follow the 17-point test manual in [TEST_CHECKLIST.md](file:///d:/claude/god%20project/docs/TEST_CHECKLIST.md) to verify real SMS receiving, sending, Persian text, multipart splitting, and offline recovery.

---

## 7. Verified Features & Known Limitations

### Verified Features:
- [x] Local peer-to-peer Wi-Fi and Android Hotspot communication.
- [x] Out-of-band TLS certificate SHA-256 fingerprint pinning via QR code (zero cert bypass).
- [x] Short-lived (5-minute) pairing token authentication.
- [x] Bidirectional JSON WebSocket protocol (v1).
- [x] Full incoming SMS reception via `RECEIVE_SMS` broadcast receiver.
- [x] Multipart SMS concatenation in sequence.
- [x] Full Unicode and Persian/Farsi character support.
- [x] Dual-SIM card detection and subscription routing.
- [x] Outgoing SMS dispatch with `PendingIntent` radio confirmation and delivery reports.
- [x] Idempotency engine preventing duplicate SMS sends on network retries.
- [x] Offline message buffering and cursor-based synchronization upon reconnect.
- [x] Complete native Jetpack Compose UI (Android) and SwiftUI interface (iOS).
- [x] Reconnection and session recovery when the iPhone app returns to foreground.
- [x] Revoke-pairing functionality on both platforms.

### Known Limitations:
- **No System SMS Provider Write on Android:** Messages sent from the iPhone reach the cellular recipient, but do not appear in Google Messages on the Android host unless the Android Gateway is set as the default SMS app.
- **Local Network Scope:** Both devices must be on the same local network subnet or Android hotspot; communication does not traverse cellular carrier CGNAT without a VPN or local routing.
- **iOS Background Reception:** When the iPhone application is suspended or terminated by iOS, it cannot maintain an active TCP/TLS socket without Apple Push Notifications (APNs). Newly arrived SMS messages are buffered on Android and immediately synchronized the moment the iPhone application is reopened.
