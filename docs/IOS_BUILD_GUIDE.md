# iOS Client Build & Deployment Guide

Since compiling native iOS applications requires macOS with Apple's Xcode toolchain, follow these step-by-step instructions on your Mac to compile and deploy the iPhone client to your physical device.

---

## 1. Requirements
- **Hardware:** Mac (Apple Silicon or Intel) running macOS Sonoma (14+) or Sequoia (15+).
- **Software:** Xcode 15.0 or later (installed via Mac App Store).
- **Apple ID:** Free personal Apple ID or paid Apple Developer Account.
- **Target Device:** iPhone running iOS 16.0 or later.
- **Connection Cable:** Lightning or USB-C cable to connect iPhone to Mac.

---

## 2. Project Setup in Xcode

1. **Launch Xcode** and select **"Create New Project"**.
2. Under the **iOS** tab, select **App** and click **Next**.
3. Configure the Project Options:
   - **Product Name:** `SMSBridge`
   - **Organization Identifier:** `com.smsbridge` (or your preferred prefix)
   - **Bundle Identifier:** `com.smsbridge.iosclient`
   - **Interface:** `SwiftUI`
   - **Language:** `Swift`
   - **Storage:** None
   - Click **Next** and save the project.

---

## 3. Importing Source Files

Replace the boilerplate generated files with the source files from `/ios-client/`:

1. Drag and drop the following folders and files from `/ios-client/` into your Xcode Project Navigator:
   - `SMSBridgeApp.swift` (replace default App entry point)
   - `Models/` (`ProtocolMessages.swift`, `Message.swift`, `Conversation.swift`)
   - `Network/` (`WebSocketClient.swift`, `TLSPinningDelegate.swift`)
   - `Storage/` (`StorageManager.swift`, `KeychainHelper.swift`)
   - `Views/` (`MainTabView.swift`, `Connection/`, `Conversations/`, `Settings/`)
2. In the file import modal:
   - Check **"Copy items if needed"**.
   - Select **"Create groups"**.
   - Ensure the `SMSBridge` target is checked.

---

## 4. Privacy & Entitlements Configuration (`Info.plist`)

In Xcode, select your Project Target -> **Info** tab (or open `Info.plist`), and verify that the following keys are present:

1. **Camera Permission (for QR Scanner):**
   - **Key:** `Privacy - Camera Usage Description` (`NSCameraUsageDescription`)
   - **Value:** `Camera access is required to scan the pairing QR code from your Android Gateway.`
2. **Local Network Permission:**
   - **Key:** `Privacy - Local Network Usage Description` (`NSLocalNetworkUsageDescription`)
   - **Value:** `Local network access is required to connect to the Android SMS Gateway over Wi-Fi or Hotspot.`
3. **Bonjour Services (Optional Discovery):**
   - **Key:** `Bonjour services` (`NSBonjourServices`) -> Array item 0: `_smsbridge._tcp`

---

## 5. Device Signing & Provisioning

1. Connect your physical iPhone to your Mac via cable.
2. If prompted on your iPhone, tap **"Trust This Computer"** and enter your passcode.
3. In Xcode, navigate to:
   **Project Settings** -> **SMSBridge Target** -> **Signing & Capabilities**.
4. Check **"Automatically manage signing"**.
5. In the **Team** dropdown, select your Personal Apple ID team (e.g. `Your Name (Personal Team)`).
6. Xcode will automatically generate a free Provisioning Profile and Development Certificate.

---

## 6. Build & Install on iPhone

1. In the top Xcode toolbar, click the device selector and choose your connected **iPhone**.
2. Press **Cmd + R** (or click the **Play** button) to build and run.
3. **First-time Untrusted Developer prompt:**
   - If iOS displays "Untrusted Developer" upon opening the app:
   - On your iPhone, open **Settings** -> **General** -> **VPN & Device Management**.
   - Tap your Developer Apple ID under *Developer App*.
   - Tap **Trust "[Your Apple ID]"**.
4. **Developer Mode (iOS 16+):**
   - On iPhone, open **Settings** -> **Privacy & Security** -> scroll down to **Developer Mode**.
   - Toggle **Developer Mode ON** and restart your iPhone when prompted.
5. Re-launch the **SMS Bridge** app from your iPhone home screen.

You are now ready to scan the Android Gateway QR code and begin bridging SMS!
