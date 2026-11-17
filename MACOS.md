# Football Arena for macOS

The macOS package uses the same Electron renderer, settings store, sites list,
chat room, and app data model as the Windows app. Chat continues to use the
existing shared room and server configuration. The platform-specific DPI
helper is SpoofDPI on macOS and GoodbyeDPI on Windows.

## Build

Build on macOS with Node.js 20 or newer:

```sh
npm install
npm run dist:mac
```

This creates Intel x64 and Apple Silicon arm64 DMG and ZIP packages under
`dist/`. Both official SpoofDPI v1.5.5 binaries must first be placed at the
paths documented in `spoofdpi/README.txt`. The build does not download or
execute third-party binaries automatically.

The existing Windows package command and GoodbyeDPI resource are unchanged.

## Minimum macOS version

This source currently uses Electron 35 and `WebContentsView`. Electron 33 and
newer require macOS 11 Big Sur or newer, so this build cannot run on High Sierra
10.13.6. Electron documents that Electron 27+ requires Catalina or newer, while
Electron 20–26 support High Sierra; targeting 10.13 would therefore require a
separate legacy runtime port and replacing `WebContentsView` with the older
browser-view API, then building and testing that legacy app independently.
High Sierra cannot run on Apple Silicon Macs, so a legacy target would only
apply to Intel. The current supported macOS package target is 11.0+ for both
Intel and Apple Silicon.

## Updates without Apple Developer signing

The macOS app checks the existing GitHub Releases feed. When a newer version
is found, Settings opens the release page so the user can download and install
the DMG manually. Windows retains its current background download and
quit-to-install behavior. Publishing macOS releases requires the generated
`latest-mac.yml` metadata and both architecture packages to be attached to the
same GitHub release. The app bundle is unsigned; macOS may show its normal
Gatekeeper warning on first launch.

## SpoofDPI behavior

SpoofDPI is a local HTTP proxy. Football Arena starts it on loopback and points
its embedded site and chat sessions at that proxy. The app does not alter the
system-wide macOS proxy settings. The settings page labels the macOS helper as
SpoofDPI and omits the GoodbyeDPI-specific strategy options.
