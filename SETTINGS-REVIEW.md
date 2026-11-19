# Settings comparison and recommendations

Implemented changes in this batch are language selection and the requested pane, DPI, menu and link-update changes. Other settings remain unchanged.

| Existing desktop setting | Android status / limitation |
| --- | --- |
| Language | Turkish and English, first-launch selection and later changes; existing data preserved |
| Restore sites/layout | Supported; Auto is the fresh-install default |
| Passcode | Supported; locally hashed; Android process lifecycle differs from desktop quit |
| Site import/export | Supported through Android document picker; TVs need an installed document provider |
| Default volume | Desktop default-volume setting; Android currently offers per-pane volume only |
| Focused-pane audio | Supported; embedded cross-origin players can restrict script-based volume changes |
| Link update interval | Desktop configurable; Android checks on startup and manually |
| Link health | Supported manually; desktop also offers startup/off preferences |
| Open in Main by default | Desktop preference; Android supports Main per pane |
| Ad blocking | Desktop has full filter lists; Android has built-in host rules |
| Auto-skip | Both supported with player-dependent behavior |
| Custom blocklist editor | Desktop only |
| Clear cache on exit | Desktop setting; Android needs a lifecycle-specific approach because apps can be killed without a quit event |
| Logs / reset controls | Desktop exposes these; Android does not yet expose the equivalents |
| App updates | Windows automatic; unsigned macOS manual; Android requires a persistent signing key and installer confirmation or store distribution |
| DPI presets | Windows uses WinDivert/GoodbyeDPI; macOS uses SpoofDPI; Android uses its app-local DNS/fragmentation proxy, so raw Windows flags cannot be copied |
| Chat and moderation | Desktop only, intentionally excluded from Android and TV |

## Needs approval before implementation

- Bring default volume, link update interval, startup health checks and Open in Main by default to Android.
- Add Android blocklist editing, log export, reset controls and explicit cache clearing.
- Add an Android light/system theme. The current project defines a dark theme; the requested custom dialogs use those existing colors. Windows and macOS appearance remains unchanged apart from translated labels and language selection.
- Add Android update installation after choosing a permanent signing key and distribution route.

The Android TLS proxy remains experimental and cannot guarantee bypass of every network restriction. Language support does not change platform signing/updater requirements.

Website content, user messages, site names and URLs are preserved in their original form. Operating-system-owned document/permission dialogs and browser-owned security warning pages use the system's language rather than the app's interface catalog.
