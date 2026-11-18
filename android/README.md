# Football Arena for Android and Android TV

This is a separate native Android shell. One APK runs on phones, tablets, and Android TV; it detects TV devices and uses larger controls that can be reached with a D-pad. Chat is intentionally absent on both Android form factors. The app reads the same public `sites.json` from `football-arena-multi` as the desktop app.

## Preview build

The GitHub Actions workflow builds a debug APK on pushes and tags. Download `football-arena-android-debug` from the workflow run's Artifacts section and install it on an Android device. Android may ask you to allow installs from the browser or file manager used to open it.

## Current scope

The Android preview includes live site-list refresh, saved custom sites, add/edit/delete sites, quick access, site import/export, link-health checks, a salted passcode lock, WebView navigation, page sharing, last-page restore, Auto/1/2/3/4-pane layouts, a Main pane, full-screen video, pane mute/volume and focused-pane audio, basic ad-host blocking, and the existing auto-skip script. The desktop-style toolbar and pane controls use larger focusable buttons on TV. Chat is excluded.

The native Android UI is adapted from the desktop design; it is not pixel-identical. Full filter-list ad blocking, log export, full settings import/export, and in-app APK updating are still outstanding. Android updates need a persistent signing key; CI debug APKs are for testing, not the final update channel.

## DPI bypass

Upstream SpoofDPI does not officially support Android. This preview instead has a separate experimental Kotlin implementation of TLS handshake fragmentation. It runs a CONNECT proxy bound to loopback and routes HTTPS requests from this app's WebViews through it using AndroidX ProxyController. It does not require root or a VPN, modify certificates, decrypt TLS, or route other apps.

As of 1.8.8, the proxy resolves tunneled origins using DNS over HTTPS (Google Public DNS, with Cloudflare's IP-based HTTPS endpoint as fallback). It does not fall back to the system resolver if those requests fail, because system answers can point to a blocking page instead of the real server. Normal TLS certificate validation is retained for the resolver connections. Diagnostics include the encrypted DNS result. Answers are cached for their TTL, capped at five minutes. This does not change device-wide DNS. It does not overcome every kind of IP or network block, and needs device/network testing.

Open **DPI** for On/Off, Restart, and three fragmentation sizes. It defaults to Off. **On** means the listener and WebView routing were configured, not that bypass succeeded on your network. Older WebView versions without proxy override support show Unsupported. A page reload is performed after switching routing. Test playback on the actual phone/TV/network before treating bypass as working.

## Build locally

Requires JDK 17, Android SDK platform 35, and Gradle 8.11.1:

```sh
cd android
gradle assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`.

