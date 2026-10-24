# AndroidNav 2.5.0 regression checks

The GitHub Actions Build APK workflow builds and verifies the signed APK, runs a JavaScript syntax check, then installs and launches the APK on an Android 11 emulator.

Manual checks on a phone connected to Navidrome:
- Scroll deep into songs and playlists: background stays visible and no sticky tabs remain.
- The playlist/songs switcher is accessible from each page; swipe navigation also works.
- Artist and album show only once when identical.
- Next, previous, repeat-all, repeat-one, and song position updates correctly.
- Allow a track to finish: the newly playing song immediately appears in the expanded list row and the compact player.
- The compact player uses translucent charcoal/white controls rather than HTML/native-blue sliders.
- Status-bar media controls are drawn by Android, and their theme is OS-controlled.
