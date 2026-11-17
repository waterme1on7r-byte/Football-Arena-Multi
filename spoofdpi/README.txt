Place the official SpoofDPI v1.5.5 macOS binaries here before packaging:

  spoofdpi/x64/spoofdpi       Intel (darwin/amd64)
  spoofdpi/arm64/spoofdpi     Apple Silicon (darwin/arm64)

Download only from the upstream release page:
https://github.com/xvzc/spoofdpi/releases/tag/v1.5.5

The app starts SpoofDPI on 127.0.0.1:8080 with system proxy changes disabled.
Football Arena applies that proxy to its own browser sessions and restores a
direct connection when DPI is turned off or the app exits. It does not change
macOS network service settings. Keep the upstream license/notices with the
binary distribution.
