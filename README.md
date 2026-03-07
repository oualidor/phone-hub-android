# Phone HUB Server

## Connect your Android phone to Linux GNOME seamlessly
Designed to work in tandem with the **Phone HUB GNOME Extension**, this app turns your phone into a secure hub for your desktop, allowing for seamless integration without cloud dependencies.

## 🚀 Features

*   **QR Code Pairing:** Effortless, secure pairing by scanning a QR code on your desktop. No manual IP entry required.
*   **SFTP File Mounting:** Mount your phone's entire filesystem (`/sdcard`) directly to your PC using SSHFS. Browse your phone's files in Nautilus like a local drive.
*   **Call Management:** Real-time call notifications with contact name resolution. Answer or decline calls directly from your GNOME shell.
*   **Notification Sync:** Mirror all system notifications from your phone to your desktop via local WebSockets.
*   **Remote Control & Webcam:** Mirror your screen or use your phone as a high-quality webcam (requires ADB for these specific native features).

## 🛠️ How it works (Transparency)

Phone HUB is built for users who value privacy and local control. It uses a **Local-First Architecture**:

1.  **Transport:** All communication happens over your local Wi-Fi. 
    - **REST API (Ktor):** Handles pairing requests and simple status checks.
    - **WebSockets:** Provides real-time, low-latency events for notifications and calls.
    - **SFTP (Apache SSHD):** Runs a secure SFTP server on port `2222` for file access.
2.  **Security:** 
    - **Token-Based Auth:** During pairing, the phone and PC exchange unique random tokens. Every subsequent request must include this token.
    - **Manual Authorization:** You must explicitly tap "Allow" on your phone for every new pairing request.
    - **Internal Home:** The SFTP server is isolated and uses a virtualized file system rooted in your device's storage.

## ⚙️ Installation & Setup

1.  **Install the App:** Clone this repository and build it in Android Studio, or install the provided APK.
2.  **Install the Extension:** Install the [Phone HUB GNOME Extension](https://github.com/oualidor/gnome-phone-hub) on your PC.
3.  **Grant Permissions:** Open the app and grant:
    - **File Access:** Required for SFTP mounting.
    - **SMS/Calls/Contacts:** For communication syncing.
    - **Notification Access:** For system-wide mirroring.
    - **Appear on Top:** Allows the pairing dialog to show even if the app is in the background.
4.  **Pairing:** Select **"Pair New Device"** in GNOME, scan the QR code with the app, and tap **"Allow"** on your phone.

## 🔒 Privacy & Security

Phone HUB operates strictly over your **local network**. No data is ever sent to external servers. Your pairing tokens are stored locally on your device and in your GNOME settings.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
