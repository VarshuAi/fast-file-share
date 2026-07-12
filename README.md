<!-- VARSHUOS-THEME-START -->
<div align="center">

<img src="https://raw.githubusercontent.com/VarshuAi/VarshuAi/main/assets/boot.svg" width="100%"/>

![Theme](https://img.shields.io/badge/Monday-RedStrike_OS-FF0000?style=flat-square&labelColor=0D1117)
![Distro](https://img.shields.io/badge/VarshuOS-Inferno-FF6B6B?style=flat-square&labelColor=0D1117)

</div>

<!-- VARSHUOS-THEME-END -->
# Fast File Share ⚡

Fast File Share is a high-speed, local, offline file sharing tool that enables seamless bidirectional file transfers between your mobile devices and PCs. It operates entirely within your local network (Wi-Fi or Hotspot) and does not require an active internet connection.

## Key Features
- 🚀 **Max Speed Transfers**: Piped binary stream upload loop for maximum performance.
- 📶 **100% Offline**: Works completely without the internet via local hotspot or local Wi-Fi.
- 📋 **Clipboard Sync**: Direct paste support for quick image and text sharing.
- 📈 **Real-time Metrics**: Live upload speed tracking and ETA indicators.
- 📱 **Android App with Embedded Server**: Run the foreground sharing server directly from your Android phone.

---

## Getting Started

### 1. Running the Android Application
1. Install the `fast-file-share.apk` on your Android device.
2. Ensure your phone is connected to the same Wi-Fi network as your PC, or turn on your phone's **Wi-Fi Hotspot** and connect your PC to it.
3. Open the app and tap **Start Server**.
4. The screen will show your **Browser Share Link** (e.g. `http://192.168.43.1:8080/client/index.html`).
5. Open this link in any browser on your PC to start sending and receiving files.
6. Click **Open Share Dashboard** inside the app to manage files directly on the phone.

### 2. Running the Standalone PC Server (Node.js)
If you want to host the server on your PC instead of your phone:
1. Make sure you have [Node.js](https://nodejs.org/) installed.
2. Open terminal in the `server` directory.
3. Run:
   ```bash
   npm install
   npm start
   ```
4. Access the sharing dashboard from any connected mobile browser using the IP addresses printed in the console.

---

## License
MIT
