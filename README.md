# Phone HUB Server

## Connect your android phone to linux gnome easyly
Designed to work in tandem with the **Phone HUB GNOME Extension**, this app turns your phone into a background server providing a secure local API for your desktop.

## 🚀 Features

*   **Call Management:** See who's calling with contact name resolution and answer or decline calls directly from your GNOME shell.
*   **Notification Mirroring:** Forward all your phone notifications to your desktop (requires Notification Access).
*   **Secure Pairing:** Simple and secure pairing process ensures only authorized devices can access your data.
*   **Mirror your phone and control it from PC (abd reauired)
*   **Use your phone as webcam (abd reauired)

## 🛠️ Installation & Setup

1.  **Install the App:** Clone this repository and build the project in Android Studio, or install the APK on your Android device.
2.  **Install the Desktop Extension:** Download and install the [Phone HUB GNOME Extension](https://github.com/oualidkhial/phone-hub-extension) (link to be updated).
3.  **Grant Permissions:** Open the app and grant the necessary permissions:
    *   **SMS:** To read and sync messages.
    *   **Phone/Call Logs:** To monitor incoming calls.
    *   **Contacts:** To display names instead of just numbers.
    *   **Notification Access:** (Special permission) To mirror system notifications.
4.  **Pairing:**
    *   Ensure both your phone and PC are on the same Wi-Fi network.
    *   In the GNOME extension, select **"Pair New Device"**.
    *   An authorization dialog will appear on your phone. Tap **"Allow"** to establish the secure connection.



## 🔒 Privacy & Security

Phone HUB Server operates strictly over your **local network**. No data is ever sent to external servers or the cloud. The pairing system ensures that only the PC you explicitly authorize can communicate with the phone's API.

## 🤝 Contributing

Contributions are welcome! If you have ideas for new features or find any bugs, please open an issue or submit a pull request.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
