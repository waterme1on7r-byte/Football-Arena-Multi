Android 1.8.22 preview

- Open user-initiated new-window links as yellow temporary tabs with a + save action. Saving preserves the existing page and player.
- Expand advertising host/path rules to match desktop coverage. Leave player and media CDN URLs alone.
- Install auto-skip in embedded frames on supported WebViews, including cross-origin players. Keep ordinary Close buttons untouched.
- Apply ad-blocking and auto-skip switches without reloading matches. Remove overbroad cosmetic rules that could hide player containers.
- Restyle Settings as a bounded dark modal with a vertical category sidebar, gold heading and divided content rows.
- Change the Android footer to h o w l . d e v.
- Preserve Safe Browsing interstitials when WebView reports ERROR_UNSAFE_RESOURCE. Security protection remains enabled; Inatbox's deceptive-content classification is not a DPI error.
- Include embedded-frame HTTP, TLS and JavaScript errors and frame-host routing in diagnostics, instead of showing only Extra TV's parent connection. Subresource certificate failures no longer replace the whole parent page with an error overlay.

Includes proxy/DNS unit tests and Android regression checks for persistent panes, temporary-tab saving, settings layout and embedded-frame auto-skip. Extra TV's live player needs tablet verification; successful parent traffic does not establish player success.

Previous header improvements retained:

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
