# Android SMS Permissions, System Roles, and Background Restrictions Analysis

## 1. Permission Model Analysis (Target SDK 34/35 — Android 14/15)

In modern Android operating system versions, SMS capabilities are strictly governed by security sandboxing and granular permissions.

### 1.1 `android.permission.RECEIVE_SMS`
- **Category:** Runtime / Dangerous permission.
- **Function:** Allows the application to register a `BroadcastReceiver` that intercepts the broadcast `android.provider.Telephony.SMS_RECEIVED`.
- **Behavior:**
  - When an incoming SMS arrives at the cellular modem, the telephony framework constructs PDUs and broadcasts them.
  - The Android Gateway's receiver extracts these PDUs using `Telephony.Sms.Intents.getMessagesFromIntent(intent)`.
  - **Crucial System Rule:** Any app with `RECEIVE_SMS` can receive this broadcast; **the app does NOT need to be the Default SMS App** to receive incoming SMS.
  - The app processes incoming multipart messages, concatenates the parts in sequence, and persists them into its local Room database.

### 1.2 `android.permission.SEND_SMS`
- **Category:** Runtime / Dangerous permission.
- **Function:** Allows the app to invoke `SmsManager.sendTextMessage()` and `SmsManager.sendMultipartTextMessage()`.
- **Behavior:**
  - Directly instructs the RIL (Radio Interface Layer) to transmit SMS over the cellular baseband.
  - Uses `PendingIntent` for `sentIntent` and `deliveryIntent` to listen to exact radio transmission results (`Activity.RESULT_OK`, `RESULT_ERROR_GENERIC_FAILURE`, `RESULT_ERROR_NO_SERVICE`, `RESULT_ERROR_RADIO_OFF`) and carrier delivery receipts.
  - **Crucial System Rule:** Any app with `SEND_SMS` can transmit SMS; **the app does NOT need to be the Default SMS App** to send SMS.

### 1.3 `android.permission.READ_PHONE_STATE`
- **Category:** Runtime / Dangerous permission.
- **Function:** Enables querying `SubscriptionManager.getActiveSubscriptionInfoList()` to identify installed physical and eSIM profiles on Dual-SIM devices, allowing the user to route SMS through a specific SIM card.

### 1.4 `android.permission.READ_SMS`
- **Evaluation:** Not strictly required for the core MVP bridge, because newly arrived messages are captured via `RECEIVE_SMS` and outgoing messages are captured via our bridge's local database.
- Historical SMS import: In non-default SMS apps, reading `content://sms/` can be restricted on certain OEM skins and Google Play policies. Therefore, for MVP v1, we do not require `READ_SMS` for real-time bridging, keeping permission exposure minimal.

---

## 2. Default SMS Application Role (`RoleManager.ROLE_SMS`)

### 2.1 What happens if the app is NOT the default SMS app?
- **Incoming SMS:** The app **still receives** incoming SMS via `SMS_RECEIVED`.
- **Outgoing SMS:** The app **still transmits** outgoing SMS via `SmsManager`.
- **System SMS Database (`content://sms/`):**
  - Android restricts write access to the system SMS content provider to the default SMS app only (`Telephony.Sms.Intents.SMS_DELIVER_ACTION`).
  - Therefore, messages sent through our gateway will be delivered to the recipient, but might not appear in the thread history of Google Messages or Samsung Messages on the Android phone itself.
  - The default messaging app will continue handling notifications and system storage normally.

### 2.2 What happens if the app BECOMES the default SMS app?
- **Consequences explained to user:**
  1. The Android Gateway takes responsibility for all SMS/MMS handling across the entire phone.
  2. The previous default SMS app (e.g., Google Messages) stops receiving `SMS_DELIVER_ACTION` and will no longer show incoming notifications or handle MMS.
  3. The Android Gateway would need full UI and database synchronization to act as the primary messaging interface for the phone.
  4. The user may miss features like RCS (Rich Communication Services) provided by Google Messages.
- **Implementation Strategy:**
  - **Do NOT silently request or require the default SMS app role.**
  - The Gateway functions completely as an SMS bridge using solely `RECEIVE_SMS` and `SEND_SMS`.
  - The UI provides an informative card explaining the trade-offs, leaving the system default intact while functioning reliably.

---

## 3. Background Execution Restrictions & Foreground Service

On Android 8.0+ (API 26) through Android 14+ (API 34):
- **Broadcast Receiver Exception:** `SMS_RECEIVED` is an explicit system broadcast exception to background execution limits. When a cellular SMS arrives, Android wakes up the app's receiver even if the application is in the background or killed.
- **Local WebSocket Server:** However, maintaining an active local WebSocket server over Wi-Fi/Hotspot requires an open socket connection. If the app is placed in background, Android's Doze mode and App Standby will close background network sockets.
- **Solution:** A dedicated Foreground Service with a clear ongoing notification:
  - Service Type: `specialUse` / `connectedDevice` (Android 14+ compliance).
  - Notification details: "SMS Bridge is active — listening for iPhone connection on local network".
  - Includes a quick "Stop Bridge" action directly in the notification.
  - Allows full battery efficiency when disabled, and reliable real-time bridging when enabled.
