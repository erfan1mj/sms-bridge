# Two-Device Physical Testing Checklist & Verification Plan

> **CARRIER CHARGE NOTICE:**  
> Test steps involving actual cellular transmission over the Android SIM card are explicitly tagged with **[CARRIER CHARGE]**. Standard cellular SMS rates apply depending on your mobile carrier plan.

---

## Pre-Requisites
- **Device A:** Physical Android smartphone (Android 8.0+ / API 26+, target API 34/35) with at least one active, provisioned SIM card (capable of cellular SMS).
- **Device B:** Physical iPhone (iOS 16.0+) connected to the same local network or Android Hotspot.
- **Network:** Either:
  1. Android Personal Hotspot enabled, iPhone connected to it.
  2. Both devices connected to a private Wi-Fi network that allows peer-to-peer device communication (client isolation disabled).

---

## Physical Test Cases Matrix

| ID | Test Scenario | Steps | Expected Result | Carrier Charge? |
|---|---|---|---|---|
| **TC-01** | **Pairing over Android Hotspot** | 1. Turn on Android Hotspot.<br>2. Connect iPhone to Android Hotspot Wi-Fi.<br>3. Open Android Gateway, toggle Service ON, tap "Pair New iPhone".<br>4. Open iPhone app, tap "Scan Android QR Code", scan screen. | iPhone establishes TLS socket, pins certificate SHA-256 fingerprint, performs `PAIR_REQUEST`, displays green "Connected" status and SIM information. | **NO** |
| **TC-02** | **Pairing over Shared Wi-Fi** | 1. Connect both devices to home/office Wi-Fi.<br>2. Generate pairing QR on Android.<br>3. Scan on iPhone. | Connection negotiates over local Wi-Fi IP subnet (e.g., `192.168.1.x`), successfully authenticating. | **NO** |
| **TC-03** | **Live Incoming SMS (Online)** | 1. Ensure both devices are paired and connected.<br>2. Have a third phone send an SMS to the Android SIM. | Android intercepts `SMS_RECEIVED`, stores in Room DB, dispatches `SMS_INCOMING` over WebSocket. iPhone displays message in conversation thread in real time. | **NO** (to tester) |
| **TC-04** | **Incoming SMS (iPhone Offline)** | 1. Kill or disconnect the iPhone app.<br>2. Send an SMS to the Android SIM from a third phone.<br>3. Verify Android notification / Gateway log indicates message saved locally. | Message persisted in Android Room DB with `syncedToClient = false`. Android Foreground Service keeps running. | **NO** (to tester) |
| **TC-05** | **Sync After Reconnection** | 1. Re-open iPhone app and reconnect to Android Gateway.<br>2. iPhone issues `SYNC_REQUEST` with `sinceTimestamp`. | Android returns missed message in `SYNC_RESPONSE`. Message appears in correct thread on iPhone with original sender timestamp. | **NO** |
| **TC-06** | **Outgoing SMS Dispatch** | 1. On iPhone, compose message to a test recipient.<br>2. Tap Send. | 1. iPhone registers outbox status as `QUEUED`.<br>2. Android validates, passes to `SmsManager` (`SUBMITTED`).<br>3. Radio confirms send (`SENT` with checkmark).<br>4. Carrier delivery receipt arrives (`DELIVERED` with double-check). | **[CARRIER CHARGE]** (1 SMS) |
| **TC-07** | **Unicode & Persian/Farsi SMS** | 1. Compose Persian message: "سلام! این یک پیام آزمایشی به زبان فارسی است."<br>2. Send to test number. | iPhone composer shows `(Unicode)` counter. Message dispatches cleanly through Android SIM and arrives on recipient phone with intact Persian characters. | **[CARRIER CHARGE]** (1 SMS) |
| **TC-08** | **Multipart Long SMS** | 1. Compose a message longer than 160 GSM chars (or > 70 Unicode chars).<br>2. Verify parts counter on iPhone.<br>3. Send message. | Android `SmsManager.divideMessage` splits into multiple parts. `PendingIntent` tracks all parts. Status changes to `SENT` only after all parts confirm. Recipient receives complete single message. | **[CARRIER CHARGE]** (Multi-part SMS) |
| **TC-09** | **Invalid Recipient Phone Number** | 1. Enter invalid recipient (e.g., "12" or "abc").<br>2. Attempt to send. | iPhone / Android Gateway input validator rejects request. Status marks as `FAILED` (`INVALID_PAYLOAD`) without attempting cellular radio transmission. | **NO** |
| **TC-10** | **SMS Permission Denied on Android** | 1. Revoke `SEND_SMS` permission in Android Settings -> Apps.<br>2. Attempt to send SMS from iPhone. | Android Gateway rejects request with `PERMISSION_DENIED`. iPhone UI marks message as `FAILED` with explicit permission warning. | **NO** |
| **TC-11** | **No Cellular Signal (Airplane Mode)** | 1. Put Android into Airplane Mode, then re-enable only Wi-Fi.<br>2. Send SMS from iPhone. | `SmsManager` radio returns `RESULT_ERROR_RADIO_OFF` or `RESULT_ERROR_NO_SERVICE`. Android reports failure, iPhone displays failed status. | **NO** |
| **TC-12** | **Dual-SIM Slot Selection** | 1. On Dual-SIM Android, verify both SIMs listed.<br>2. In iPhone composer, tap SIM selector and switch from SIM 1 to SIM 2.<br>3. Send message. | Android routes SMS via `SmsManager.createForSubscriptionId(sim2SubId)`. Dispatch executes through second SIM. | **[CARRIER CHARGE]** (1 SMS) |
| **TC-13** | **Duplicate Request Prevention (Idempotency)** | 1. Simulate duplicate send by rapidly tapping or network replay.<br>2. Check Android database. | Android Gateway identifies existing `requestId`, returns cached result, and strictly avoids duplicate dispatch to carrier. | **[CARRIER CHARGE]** (1 SMS only) |
| **TC-14** | **Android App Process Restart** | 1. Force stop Android app or restart Android phone.<br>2. Relaunch Android Gateway.<br>3. Reconnect iPhone. | TLS certificate and paired clients persist cleanly from KeyStore and Room DB. iPhone authenticates automatically without re-scanning QR. | **NO** |
| **TC-15** | **iPhone App Process Restart** | 1. Force kill iPhone app from multitasking switcher.<br>2. Re-open iPhone app. | App reads credentials from Keychain, reconnects automatically, retrieves any missed messages. | **NO** |
| **TC-16** | **Network Disruption During Sync** | 1. Toggle Wi-Fi OFF during large sync or send operation.<br>2. Toggle Wi-Fi back ON. | iPhone auto-reconnect backoff triggers, safely recovers session, and queries pending request states without duplicate sends. | **NO** |
| **TC-17** | **Attempt Connection from Unpaired Client** | 1. Point a generic WebSocket tool (e.g. `wscat`) at Android Gateway `wss://` port without valid token. | Gateway disconnects connection after authentication timeout (15s) with `4001: Authentication timeout`. SMS endpoints are completely inaccessible. | **NO** |
