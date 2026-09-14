# Background Playback Implementation Plan

**Goal:** Keep music and queue advancement alive when AndroidNav's screen closes and improve controls.
**Architecture:** Bound, started foreground PlaybackService owns the existing native queue and MediaSession. MainActivity owns WebView and image selection only.
**Tech Stack:** Java 17, Android SDK 35, HTML/CSS/JS, Gradle 8.11.1.
**Spec:** ../specs/2026-09-14-playback-design.md

## Constraints
Preserve package ID, signing key, Navidrome API contract, wallpaper and oldest-first order. Verify installation in an emulator.

## Tasks
- [ ] Add instrumentation regression: create short WAVs, invoke AndroidPlayer.playQueue, finish activity, wait for track two, reopen and assert state. Run against baseline in CI.
- [ ] Extract PlaybackService and rebind PlayerBridge; foreground declaration, wake handling, focus handling, notification intents and immutable state snapshots. Run regression after change.
- [ ] Style controls and add persistent mini-player, accessible sliders and inline errors. Check narrow-screen layout and player controls with a browser.
- [ ] Build signed APK in CI, install and run instrumentation on API 34. Review diff, publish branch/PR and deliver APK with verification results.
