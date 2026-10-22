# Vani Bobo

Vani Bobo is a personal, consent-based remote utility for connecting your own Android phone to a web dashboard. The Android app stays visible in the launcher and shows an ongoing notification while connected.

## Implemented features

- Authenticated dashboard/device WebSocket connections.
- Live online/offline state and phone status (battery, charging state, Android version and device model).
- Remote ring for up to 20 seconds, stop ringing, request status, and open Android Settings.
- Responsive dark glass dashboard with activity feed, device selection, connection states and useful error messages.
- HTTPS/WSS-only connection from the Android app.
- No hidden launcher, deceptive system-update screen, forced overlay, automatic boot persistence, WhatsApp scraping, microphone recording, camera capture, or bulk file collection.

## Run the web server

Requires Node.js 18 or newer.

```sh
npm install
DASHBOARD_TOKEN='use-a-long-random-secret-for-dashboard' DEVICE_TOKEN='use-a-different-long-random-secret-for-phone' npm start
```

Use two different random secrets, at least 32 characters each. Keep them private. Configure these as environment variables in your hosting provider. Do not put secrets in this repository.

Deploy the server behind HTTPS. The dashboard must be opened through HTTPS so it can use a secure WebSocket (`wss://`). On Render, use the public HTTPS service URL. The server listens on `process.env.PORT`.

## Pair the Android app

1. Build and install the APK from the GitHub Actions artifact **Vani-Bobo-debug-APK**.
2. Open Vani Bobo on your phone. The app has a visible launcher icon and setup screen.
3. Enter the HTTPS URL of your deployed server (no trailing slash), a device ID such as `my-phone`, and the exact `DEVICE_TOKEN` configured on the server.
4. Tap **Save & connect**. Allow notifications so the ongoing connection notification can be shown.
5. Open the same HTTPS website and enter the `DASHBOARD_TOKEN`.
6. Use the dashboard to request status, ring/stop ringing, or open Settings.

If your host restarts, the phone may need to reconnect; the service uses bounded retry delays while it remains running. Android may stop long-running services due to battery optimisation or OS restrictions. This is a normal Android limitation; check your phone's battery/background settings if you need a persistent connection.

## Build

The GitHub Actions workflow uses JDK 17 and Gradle 8.7 and uploads a debug APK. A debug APK is for testing, not Play Store release. For a public release, create a proper release signing key and use GitHub Actions secrets rather than the debug signing configuration.

## Security notes

- Use strong, separate dashboard and device tokens.
- Use HTTPS/WSS only; never share the tokens or expose them in screenshots.
- This dashboard only implements status, ring, stop-ring, and opening Android Settings. Android may restrict opening another screen from the background; if that happens, use the visible phone notification/app to continue.
- The server authenticates dashboard API requests and WebSocket connections. It is intended for a private personal deployment, not an unauthenticated public service.
