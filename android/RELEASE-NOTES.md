Android 1.8.38 preview

- Apply the selected Desktop classic design to Android settings, app dialogs and link menus: gold headings, blue switches, divided rows, consistent title bars and compact actions.
- Replace passcode text inputs with an app-themed four-digit keypad at startup and in settings. Creating a code requires confirmation; changing it requires the current code and confirmation of the new code. Retain the failed-attempt cooldown.
- Add separate Remove / Enter code settings actions. Removing a code requires confirmation and the current code.
- Add Move left / Move right actions for saved links, preserving order across restarts and site-list refreshes without reloading panes.
- Add labeled Name and URL fields to the temporary yellow-tab save popup. Validate edits and retain the current WebView/player when saving.
- Remove Inattv's observed sponsored page-skin image through its ad container, preserving shared-CDN images and player frames.
- Windows and macOS are unchanged.

Previous Android improvements retained:

- Remove TRGOALS' observed first-party mobile and desktop page-skin promotions, including the full-width image above the player.

- Support Patron's press-event skip control after its countdown and remove its sponsored player poster while retaining playback controls.
- Report same-origin fallback skip actions directly to the owning page.

- Remove the promotional main-player poster left behind after Netspor/Mahsun pre-roll skipping, while retaining the play control.
- Apply same-origin player rules on older WebViews without document-start support, and inject fallback rules as pages become visible.
- Capture live-site checks after renderer frames reach the compositor and include DOM reports beside screenshots.

- Fix live-site advertising missed by network-only rules: remove observed first-party banner slots, external promotional banner images, Arda ad zones, Patron promotional bars and Mahsun's bonus-wheel control.
- Block the traffmovie.com ad network observed on Arda, including subdomains.
- Recognize the actual Netspor/Mahsun pre-roll structure and use its existing skip control to initialize the main player. Do not merely hide the pre-roll and leave a blank player.
- Support play-triangle suffixes on skip labels and avoid seeking hidden ad videos after the match has started.
- Assign stable site profiles before navigation so rotating domains and embedded frames retain the correct rules.
- Add hidden-promotional-element counts alongside network-block counters in diagnostics.
- Add public live-site Android checks with before/after screenshots for Arda, Netspor, Mahsun and Patron. Unreachable/challenged live sites are explicitly reported as skipped rather than verified.
- Cover TRGOALS banner strips and Zeus banner/skin frames; retain legitimate player frames. Include live checks for those layouts too.
- Remove Patron's observed sponsored page-background image as well as its banner elements; disabling filtering restores the original background without reloading.

Previous improvements retained:

- Load a bundled Peter Lowe advertising/tracking-host snapshot (3,568 domains) alongside existing network rules, with domain-boundary matching.
- Apply the same blocking preference to page/frame requests, ad navigations, new-window links and service-worker fetches. Blocked responses are not cached.
- Start player scripts at document creation, recognize uppercase English/Turkish labels, accessible icon buttons and common skip controls, and wait for countdown buttons to become ready.
- Synchronize settings with existing, delayed and nested player frames without refreshing matches.
- Keep match mute/volume unchanged when skipping a finite ad video.
- Add Settings → Network → Ad blocking diagnostics with request counts, service-worker blocks, skip-button activity and WebView frame-injection support.
- Add native integration tests using real local HTTP and service workers, plus embedded-player fixtures. Website-specific and server-inserted ads can still require site-specific handling.

Previous improvements retained:

- Add a DPI enable/disable switch inside Football Arena Settings → Network, synchronized with routing mode and the toolbar power control.
- Switching back on restores the previous enabled routing mode (Automatic or Always bypass).

- Open user-initiated new-window links as yellow temporary tabs with a + save action. Saving preserves the existing page and player.
- Expand advertising host/path rules to match desktop coverage. Leave player and media CDN URLs alone.
- Install auto-skip in embedded frames on supported WebViews, including cross-origin players. Keep ordinary Close buttons untouched.
- Apply ad-blocking and auto-skip switches without reloading matches. Remove overbroad cosmetic rules that could hide player containers.
- Restyle Settings as a bounded dark modal with a vertical category sidebar, gold heading and divided content rows.
- Change the Android footer to h o w l . d e v.
- For main pages flagged as deceptive, offer a themed Back to safety / Open once choice. Continuing requires an explicit tap for that warning; Safe Browsing remains enabled, with no permanent whitelist. Other threats retain Android's interstitial. Preserve security warnings when WebView reports ERROR_UNSAFE_RESOURCE.
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
