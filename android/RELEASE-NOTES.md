Android 1.8.17 preview

- Keep existing WebViews and pane containers attached when opening, closing, focusing or rearranging panes.
- Selecting an open site focuses it; the close button explicitly closes it.
- Preserve document/player state and address input across layout changes.
- Keep pane ordering stable and retain the pinned main pane when opening another site.
- Queue proxy connections instead of resetting page resources when capacity is reached.
- Wait for proxy configuration before starting/restoring pages, preventing mixed first-load routing.
- Use encrypted DNS for HTTPS pages and resources from their first connection; protect unregistered CDN hosts too.

Includes Android 13 regression checks for persistent document state, pane controls and DPI status, plus proxy/DNS unit tests. Website availability and third-party player behavior still depend on the site and network.

Windows and macOS application code is unchanged.
