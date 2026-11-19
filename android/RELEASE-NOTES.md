Android 1.8.19 preview

- Match the Android header to the desktop reference: bundled Big Shoulders Display title font, title-case gradient branding and compact right-aligned controls.
- Add colored initial badges and separate three-dot menus to every site chip.
- Add the gold outlined Quick Access pill with a live saved-site count and dropdown indicator.
- Keep the colored DPI dot while using the reference's muted DPI label.
- Responsive toolbar uses a second line on narrow screens; Android TV retains larger controls.

Networking and page lifecycle behavior are unchanged in this header update.

Previous compatibility improvements retained:

- Extra TV uses a desktop browser identity from its first request, so its desktop address and navigation select the same device profile.
- Keep Extra TV mobile-host links on its configured desktop host without losing paths, queries or fragments.
- Apply this compatibility profile only to Extra TV, keyed by site ID so address-list updates continue to work.
- Includes native Android navigation tests using a mobile-redirect fixture. The site's live tablet behavior still needs confirmation.

Previous stability improvements retained:

- Keep existing WebViews and pane containers attached when opening, closing, focusing or rearranging panes.
- Selecting an open site focuses it; the close button explicitly closes it.
- Preserve document/player state and address input across layout changes.
- Keep pane ordering stable and retain the pinned main pane when opening another site.
- Queue proxy connections instead of resetting page resources when capacity is reached.
- Wait for proxy configuration before starting/restoring pages, preventing mixed first-load routing.
- Use encrypted DNS for HTTPS pages and resources from their first connection; protect unregistered CDN hosts too.

Includes Android 13 regression checks for persistent document state, pane controls and DPI status, plus proxy/DNS unit tests. Website availability and third-party player behavior still depend on the site and network.

Windows and macOS application code is unchanged.
