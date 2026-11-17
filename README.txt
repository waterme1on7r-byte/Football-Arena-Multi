Football Arena - desktop app (Electron)

Build the Windows installer on GitHub (no setup on your PC):
 1. Create a new GitHub repository and upload everything in this folder (keep the .github folder).
 2. Open the Actions tab -> "Build Windows installer" -> Run workflow.
 3. When it finishes, open the run and download the "football-arena-installer" artifact.
    Inside is "Football Arena Setup" (the version number is in the file name).

Or build on your own Windows PC (Node.js 20+ needed):
    npm install
    npm run dist        (run the terminal as Administrator)
The installer appears in the "dist" folder.

Test without installing:  npm install  then  npm start
Press F12 inside any page or the app window to open developer tools.

Ad blocking (v1.1)
 - Built-in rules and the big public filter lists (downloaded on first run, then cached).
 - "Reklamı geç" / "Skip ad" buttons are pressed automatically as soon as they appear.
 - Your own list: %APPDATA%\Football Arena\blocklist.txt  (one domain or address fragment per line, restart the app).

Quick access (v1.2)
 - The button at the right end of the bar opens your less-used sites. Each site's menu has
   "Add to quick access" / "Remove from quick access", Edit and Delete.

GoodbyeDPI (v1.2)
 - Put the "x86" and "x86_64" folders of GoodbyeDPI into the "goodbyedpi" folder before building
   (see goodbyedpi/PUT_GOODBYEDPI_FOLDERS_HERE.txt). Options are in goodbyedpi/args.txt.
 - It starts hidden when the app starts and stops when the app closes. No service is installed.
 - Windows shows one permission (UAC) prompt at start. If you decline, the app still works without it.

v1.3
 - Menus, the quick access panel and dialogs no longer turn the open sites black: the sites stay visible as a frozen picture.
 - A small light next to the layout buttons shows GoodbyeDPI: green = on, red = off, yellow = starting.
   Hover for details. Click a red light to try again.
 - GoodbyeDPI writes a short log to %APPDATA%\Football Arena\dpi.log (what started, what failed).

v1.4
 - GoodbyeDPI is now started directly by the app (no hidden PowerShell). The app asks Windows for administrator
   rights once when it opens; accept the prompt. GoodbyeDPI runs hidden and stops when the app closes.
 - %APPDATA%\Football Arena\dpi.log shows what happened, including GoodbyeDPI's own messages.

v1.5 (lighter on memory)
 - Less memory: smaller ad-block lists, a cap on runaway page scripts, background services off,
   hidden pages slow down, and "Lite mode" on PCs with up to 8 GB RAM or 4 cores (also switches animations off).
 - A small RAM counter in the top bar shows what the app uses right now.
 - Force a mode: create %APPDATA%\Football Arena\performance.txt containing the word lite or normal, then restart.

v1.6
 - Link updates: the app downloads sites.json from GitHub (repo root, must be public) at start and every 15 minutes,
   or when you press "Links" at the bottom left. Edit sites.json on GitHub to repair links for everybody.
   Test another address: put it on the first line of %APPDATA%\Football Arena\sites-url.txt.
 - Chat: the chat button is at the bottom right. The chat window only starts the first time it is opened.
 - Signature WTMLJR at the bottom center. The RAM counter is gone.

v1.7
 - Sound per pane: speaker button (mute) and a volume slider in each pane's top bar. Remembered per site. The slider is hidden on very
   narrow panes, the mute button stays. (Volume is applied to the page's video/audio; a player that fights it is re-set every 1.5 s.)
 - Main pane: the button next to the speaker makes that pane big (left) with all the others stacked on the right. Press it again, or pick
   a layout button (Auto / 1 / 2 / 3 / 4), to go back.
 - Link health: press "Health" at the bottom left. Every site gets a dot: green = works, yellow = slow or shows a protection check,
   blue = redirects to another domain (hover for the address), red = not working.
 - All pop-ups are in the app now (delete / import errors use the themed box). Only the Windows file chooser of Export / Import remains.
 - DPI: hover (or click) the DPI button for a small menu: Turn off / Turn on and Restart.
 - Faster link updates: the last list is shown at once at start; GitHub is only asked "did it change?" (tiny reply), uses the app's own network
   stack (works with GoodbyeDPI's DNS) and falls back to a mirror if GitHub does not answer.
 - Chat: the nicknames WTMLJR and LEONBISHOP are protected; each asks for its own admin password. Everybody else can use any other name freely.
   The admin can delete messages (bin icon) and ban people (stop icon), and review bans with the shield button in the chat header.
   There is no password in the code: renderer/adminkey.js holds a public key and a private key locked with the password.
   Each admin has their own locked key. To change a password or add an admin:  node tools/admin-key.js NAME   (then commit
   renderer/adminkey.js, and for a new name add it to ADMIN_NAMES in renderer/chat.js). Use a long passphrase.
 - A line above the footer, like the one under the logo.

v1.8
 - Settings: the button next to "Add site" opens a Settings pane with App, Sound & Video, Links, Chat and Network. Export / Import,
   the version, update checks, the log folder and "Reset to defaults" moved there. All settings are saved in
   %APPDATA%\Football Arena\settings.json (not in the install folder), so they survive updates. A missing or damaged settings file just
   falls back to the defaults, and every new setting has a default.
 - Pane buttons: only "Main pane" is yellow. Speaker and volume slider are grey like Refresh / Close.
 - The DPI hover menu is one row of two icon buttons (power + restart). The power button is grey (off), green (on), amber (starting) or red (failed).
 - Cache and temporary data (HTTP cache, code cache, service workers, shader cache) are deleted when the app closes. Logins, sites and settings stay.
   Switch it off in Settings > App.
 - Parental controls: Settings > App > Passcode. Exactly 4 digits, asked every time the app starts; nothing (no site, no chat) opens before it.
   It is stored only as a salted hash. It is a family lock, not strong security (4 digits), and 5 wrong tries add a waiting time.
   Forgot it? Close the app and delete the "pass" entry in %APPDATA%\Football Arena\settings.json (or delete the file).
 - Sound & Video: default volume for sites whose volume you have not set, "only the focused pane plays sound" (the pane you last clicked; it gets a light
   border), and "remember layout and open sites on restart" (off = start with nothing open).
 - Links: link-check interval (15 min / 1 hour / manual; it always looks once at start), health check at startup / manual / off (hides the button),
   and "open links in the Main Pane" (a site you open becomes the big pane). "Check for new links now" moved here from the footer.
 - Chat: on/off (off closes the chat and hides its button), nickname, mute notifications, sound for new messages, and a ban list that only shows
   when you are logged in as a chat admin. Chat messages now carry a version number; a message from a newer app that this version cannot show
   reads "Update to see this" instead of vanishing.
 - Network: ad blocker on/off, auto-skip on/off, an in-app editor for blocklist.txt (changes apply at once, no restart), GoodbyeDPI on/off
   (off = not started and the DPI indicator disappears; on = it comes back), the same On/Off + Restart controls, and three DPI options. The chosen
   option is used at every start and is also written into goodbyedpi/args.txt.
   Option 1 / 2 / 3 are exactly the argument lists you gave. Note that they do not contain "--set-ttl 5" which the old default had.

App updates (v1.8)
 - The app now updates itself (electron-updater + GitHub Releases): it checks at start and every 4 hours, downloads in the background and shows
   "Version X is ready" with Restart now / Later. Later = installed when you close the app. The update installs over the existing installation.
 - The FIRST time, everybody has to install 1.8.0 by hand (older versions cannot update themselves).
 - To publish an update:  1) raise "version" in package.json (for example 1.8.1)  2) commit and push  3) create the tag and push it:
       git tag v1.8.1
       git push origin v1.8.1
   GitHub Actions then builds the installer and publishes the Release (installer + latest.yml). The tag must match the version in package.json.
   Pushes to main and manual runs only build the installer as before; nothing is published.
 - The repository must be public (same as sites.json), otherwise the app cannot read the Releases.
 - Code signing: the installer is not signed, so Windows SmartScreen may warn on each install or update. Updates still work. A signing certificate
   costs money and is a separate decision; electron-builder can use one later without changes to the app.
 - Kill switch: sites.json can contain  "minVersion": "1.8.0".  Apps older than that show an "Update needed" screen and open nothing until they update
   (the update check still works). Leave it at "1.0.0" (or remove it) to block nobody. A value that is not a plain version number is ignored.

