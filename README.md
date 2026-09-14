# AndroidNav

A Navidrome music client for Android with a wallpaper-based HTML interface and native audio playback.

## Version 2.5.0

- Styled connection fields, tabs, buttons, inline errors, and a persistent mini-player.
- A foreground playback service owns the player, queue, media session and notification controls. Music and automatic track advancement continue after the activity closes or the screen turns off.
- CPU wake protection during playback and bounded preparation, Wi-Fi lock while streaming, preparation timeout, audio focus handling, headphone disconnection pause, and tap-to-retry stream errors.
- Reopening the app reconnects to the running service without replacing its queue. Previous/next and repeat modes work through notification controls.

Android force-stop terminates playback. A reboot or process termination does not automatically restore the queue. Samsung battery restrictions may also affect background apps.

## Build and checks

Java 17, Gradle 8.11.1, Android SDK 35. Decode the existing persistent debug signing key before building:

```sh
base64 --decode signing/androidnav-debug.keystore.b64 > signing/androidnav-debug.keystore
gradle :app:assembleDebug
gradle :app:connectedDebugAndroidTest
```

GitHub Actions builds and verifies the APK signature, installs it on an API 34 emulator, and tests:

1. Queue advancement after the activity is destroyed, followed by reopening and pausing.
2. HTTP streaming with the activity destroyed and screen off, pause during preparation, repeat-all wraparound, previous, and stop.

The regression test failed on the old player with an empty queue after reopening. Tests generate their own audio; they do not need personal Navidrome credentials.
