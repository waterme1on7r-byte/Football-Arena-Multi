# Football Arena for Android and Android TV

This is a separate native Android shell. One APK runs on phones, tablets, and Android TV; it detects TV devices and uses larger controls that can be reached with a D-pad. Chat is intentionally absent on both Android form factors. The app reads the same public `sites.json` from `football-arena-multi` as the desktop app.

## Preview build

The GitHub Actions workflow builds a debug APK on pushes and tags. Download `football-arena-android-debug` from the workflow run's Artifacts section and install it on an Android device. Android may ask you to allow installs from the browser or file manager used to open it.

## Current scope

Implemented in this first Android shell: live site-list refresh, saved custom sites, add/edit/delete sites, WebView navigation, back/forward/reload, page sharing, last-page restore, 1/2/4-pane layouts, full-screen video, pane mute and focused-pane audio, basic ad-host blocking, and the existing auto-skip script. The UI adapts its control sizes for Android TV remotes. No chat UI or chat connection is included.

The Android preview does not yet match desktop feature-for-feature. The desktop's per-pane volume slider, main-pane layout, parental passcode, link-health dashboard, full filter-list ad blocker, DPI bypass controls, log export, settings import/export, and in-app updating still need Android implementations. Android updates should ultimately be distributed through Google Play or signed APKs using a persistent release key; the CI debug APK is for preview/testing and is not the final update channel.

## Build locally

Requires JDK 17, Android SDK platform 35, and Gradle 8.11.1:

```sh
cd android
gradle assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`.

