const { app, BrowserWindow, WebContentsView, ipcMain, session, dialog, Menu, webFrameMain, net, shell } = require("electron");
const path = require("path");
const fs = require("fs");
const os = require("os");
const adblock = require("./adblock");
const dpi = require("./dpi");
const { validateRemote } = require("./remote");
const cfgmod = require("./settings");
const updater = require("./updater");
let AD_SKIP = "";
try { AD_SKIP = fs.readFileSync(path.join(__dirname, "adskip.js"), "utf8"); } catch {}

if (!app.requestSingleInstanceLock()) app.quit();

// ---- settings (user data folder, so they survive updates; every value has a default) ----
const SETTINGS_FILE = () => path.join(app.getPath("userData"), "settings.json");
let S = cfgmod.load(SETTINGS_FILE());
const saveS = () => cfgmod.save(SETTINGS_FILE(), S);
let locked = !!S.pass;        // parental passcode: nothing opens until it is entered
let blockedMin = "";          // kill switch: set when sites.json demands a newer app version than this one
const minBlocked = min => (min && cfgmod.cmpVersion(app.getVersion(), min) < 0 ? min : "");   // NaN (not a version) never blocks
const pub = () => ({ ...cfgmod.publicView(S), locked, version: app.getVersion(), platform: process.platform, blocked: blockedMin ? { min: blockedMin } : null });

// Performance: "lite" is chosen automatically on PCs with up to 8 GB RAM or 4 processor cores.
// Force it with a file performance.txt (just the word lite or normal) in %APPDATA%\Football Arena.
function perfMode() {
  let pref = "auto";
  try { pref = fs.readFileSync(path.join(app.getPath("userData"), "performance.txt"), "utf8").trim().toLowerCase().split(/\s+/)[0] || "auto"; } catch {}
  if (pref === "lite" || pref === "normal") return pref;
  return (os.totalmem() <= 8.5 * 2 ** 30 || os.cpus().length <= 4) ? "lite" : "normal";
}
const PERF = perfMode();
app.commandLine.appendSwitch("js-flags", "--max-old-space-size=512");           // a runaway ad script cannot eat gigabytes
app.commandLine.appendSwitch("disable-features", "MediaRouter,Translate,OptimizationHints");
app.commandLine.appendSwitch("disable-background-networking");
if (PERF === "lite") app.commandLine.appendSwitch("enable-low-end-device-mode");

const PARTITION = "persist:arena";
const views = new Map();   // id -> WebContentsView (one real browser view per open site)
const meta = new Map();    // id -> { failed, popups: [] }
let win = null, lastList = [], overlay = false, fsId = null, focusedId = null;

// Volume per pane: Electron can only mute a page, so the volume is set on the <video>/<audio> elements inside it
// (and inside every frame, since players usually live in an iframe). Players that reset their own volume are re-set every 1.5 s.
const volJs = v => `(()=>{window.__faVol=${v};if(!window.__faVolInit){window.__faVolInit=1;const ap=()=>{for(const e of document.querySelectorAll("video,audio")){try{if(Math.abs(e.volume-window.__faVol)>.01)e.volume=window.__faVol}catch{}}};window.__faVolApply=ap;setInterval(ap,1500);for(const ev of ["play","loadedmetadata"])document.addEventListener(ev,ap,true)}window.__faVolApply()})()`;
function pushVolume(wc, v) {
  try { for (const f of wc.mainFrame.framesInSubtree) f.executeJavaScript(volJs(v)).catch(() => {}); } catch {}
}
const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const send = (ch, data) => { if (win && !win.isDestroyed()) win.webContents.send(ch, data); };
const isWeb = u => /^https?:\/\//i.test(String(u));

function createView(id, url) {
  const view = new WebContentsView({
    webPreferences: {
      partition: PARTITION,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      autoplayPolicy: "no-user-gesture-required",
      backgroundThrottling: false,
      spellcheck: false,
      webgl: PERF !== "lite",
    },
  });
  const wc = view.webContents;
  const m = { failed: null, popups: [], vol: 1 };
  meta.set(id, m);

  // A link that wants a new tab / window opens as a yellow tab inside the app instead.
  wc.setWindowOpenHandler(({ url: target }) => {
    const now = Date.now();
    m.popups = m.popups.filter(t => now - t < 3000);
    if (m.popups.length >= 3) return { action: "deny" };   // guard against ad pop-up spam
    m.popups.push(now);
    if (isWeb(target) && !adblock.isAdUrl(target)) send("tab:open", { from: id, url: target });
    return { action: "deny" };
  });
  wc.on("will-navigate", (e, u) => { if ((!isWeb(u) && !u.startsWith("data:")) || adblock.isAdUrl(u)) e.preventDefault(); });
  wc.on("will-redirect", (e, u) => { if (adblock.isAdUrl(u)) e.preventDefault(); });
  // skip pre-roll ads in the page and in every frame inside it
  wc.on("did-frame-finish-load", (_e, _isMain, pid, rid) => {
    try { const f = webFrameMain.fromId(pid, rid); if (f && AD_SKIP && S.net.autoskip) f.executeJavaScript(AD_SKIP).catch(() => {}); if (f && m.vol !== 1) f.executeJavaScript(volJs(m.vol)).catch(() => {}); } catch {}
  });

  const report = u => send("tab:url", { id, url: u.startsWith("data:") ? (m.failed || u) : u });
  wc.on("did-navigate", (_e, u) => { if (!u.startsWith("data:")) m.failed = null; report(u); });
  wc.on("did-navigate-in-page", (_e, u, isMain) => { if (isMain) report(u); });

  wc.on("did-fail-load", (_e, code, desc, failedUrl, isMain) => {
    if (!isMain || code === -3 || failedUrl.startsWith("data:")) return;
    m.failed = failedUrl;
    const html = `<body style="margin:0;height:100vh;display:grid;place-items:center;background:#0b0f14;color:#e9edf2;font:15px system-ui,sans-serif"><div style="text-align:center;max-width:46ch;padding:20px"><h2 style="margin:0 0 10px">Couldn’t load this page</h2><p style="color:#8696a8;word-break:break-all">${esc(failedUrl)}<br>${esc(desc)}</p><p style="color:#8696a8">Press ↻ to try again.</p></div></body>`;
    wc.loadURL("data:text/html;charset=utf-8," + encodeURIComponent(html));
  });

  // A site’s own fullscreen button: fill the whole window, then go back.
  wc.on("enter-html-full-screen", () => {
    fsId = id;
    win.setFullScreen(true);
    const [w, h] = win.getContentSize();
    view.setBounds({ x: 0, y: 0, width: w, height: h });
    win.contentView.addChildView(view);
    view.setVisible(true);
  });
  wc.on("leave-html-full-screen", () => {
    fsId = null;
    win.setFullScreen(false);
    setTimeout(() => { applyList(); send("relayout"); if (chatView && chatOpen) win.contentView.addChildView(chatView); }, 150);
  });

  wc.on("before-input-event", (e, input) => {
    if (input.type === "keyDown" && input.key === "F12") wc.toggleDevTools();
  });
  // the pane you click in is the "focused" pane (used by "only the focused pane plays sound")
  wc.on("focus", () => { if (focusedId !== id) { focusedId = id; applyList(); send("pane:focus", id); } });

  win.contentView.addChildView(view);
  if (chatView && chatOpen) win.contentView.addChildView(chatView);   // chat stays on top
  views.set(id, view);
  if (isWeb(url)) wc.loadURL(url).catch(() => {});
  return view;
}

function destroyView(id) {
  const v = views.get(id);
  if (!v) return;
  views.delete(id); meta.delete(id); if (focusedId === id) focusedId = null;
  try { win.contentView.removeChildView(v); } catch {}
  try { v.webContents.close(); } catch {}
  if (fsId === id) { fsId = null; win.setFullScreen(false); }
}

function effectiveFocus() {   // the pane that may play sound when "only the focused pane" is on
  if (focusedId && lastList.some(i => i.id === focusedId && i.visible)) return focusedId;
  const first = lastList.find(i => i.visible); return first ? first.id : null;
}
function applyList() {
  const only = S.audio.focusOnly, foc = only ? effectiveFocus() : null;
  for (const it of lastList) {
    const v = views.get(it.id);
    if (!v || fsId === it.id) continue;
    v.setBounds({ x: it.x, y: it.y, width: it.width, height: it.height });
    v.setVisible(it.visible && !overlay);
    v.webContents.setAudioMuted(!it.visible || it.muted || (only && it.id !== foc));
    const m = meta.get(it.id);
    if (m && m.vol !== it.volume) { m.vol = it.volume; pushVolume(v.webContents, it.volume); }
    // a page hidden on purpose (another one is maximized) is allowed to slow down
    if (typeof v.webContents.setBackgroundThrottling === "function") v.webContents.setBackgroundThrottling(!it.visible);
  }
}

ipcMain.on("views:update", (_e, list) => {
  if (!Array.isArray(list) || locked || blockedMin) return;   // locked by the passcode, or blocked by the kill switch
  lastList = list.map(it => ({
    id: String(it.id), url: String(it.url || ""),
    x: Math.round(+it.x || 0), y: Math.round(+it.y || 0),
    width: Math.max(0, Math.round(+it.width || 0)), height: Math.max(0, Math.round(+it.height || 0)),
    visible: !!it.visible, muted: !!it.muted,
    volume: Number.isFinite(+it.volume) ? Math.max(0, Math.min(1, +it.volume)) : 1,
  }));
  const ids = new Set(lastList.map(i => i.id));
  for (const id of [...views.keys()]) if (!ids.has(id)) destroyView(id);
  for (const it of lastList) if (!views.has(it.id)) createView(it.id, it.url);
  applyList();
});
ipcMain.on("view:navigate", (_e, id, url) => {
  const v = views.get(String(id));
  if (v && isWeb(url)) { const m = meta.get(String(id)); if (m) m.failed = null; v.webContents.loadURL(String(url)).catch(() => {}); }
});
ipcMain.on("view:reload", (_e, id) => {
  const v = views.get(String(id)), m = meta.get(String(id));
  if (!v) return;
  if (m && m.failed) { const u = m.failed; m.failed = null; v.webContents.loadURL(u).catch(() => {}); }
  else v.webContents.reload();
});
ipcMain.on("overlay", (_e, on) => { overlay = !!on; applyList(); });
ipcMain.on("view:focus", (_e, id) => { id = String(id); if (views.has(id) && focusedId !== id) { focusedId = id; applyList(); send("pane:focus", id); } });
// Picture of every visible page, used while a menu or dialog covers the native views.
ipcMain.handle("overlay:freeze", async () => {
  const out = [];
  await Promise.all(lastList.filter(it => it.visible && views.has(it.id)).map(async it => {
    try {
      const img = await views.get(it.id).webContents.capturePage();
      if (!img.isEmpty()) out.push({ id: it.id, data: "data:image/jpeg;base64," + img.toJPEG(75).toString("base64") });
    } catch {}
  }));
  return out;
});
// ---- link health check: is the address reachable, how fast, did it move? (one site per call) ----
ipcMain.handle("health:check", async (_e, url) => {
  url = String(url || "");
  if (!isWeb(url)) return { state: "down", ms: 0, note: "Not a web address" };
  const ctrl = new AbortController(), t0 = Date.now(), timer = setTimeout(() => ctrl.abort(), 8000);
  try {
    const r = await net.fetch(url, { method: "GET", redirect: "follow", signal: ctrl.signal, headers: { Accept: "text/html,*/*;q=0.8" } });
    const ms = Date.now() - t0, code = r.status, hostOf = u => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch { return ""; } };
    try { if (r.body) r.body.cancel(); } catch {}
    const moved = r.url && hostOf(r.url) && hostOf(r.url) !== hostOf(url) ? r.url : "";
    let state = "ok", note = "Reachable (" + ms + " ms)";
    if (code === 404 || code === 410) { state = "dead"; note = "Page not found (" + code + ")"; }
    else if (code >= 500 && code !== 503) { state = "down"; note = "Server error (" + code + ")"; }
    else if (code === 401 || code === 403 || code === 429 || code === 503) { state = "guard"; note = "Reachable, but the site shows a protection check (" + code + ")"; }
    else if (moved) { state = "moved"; note = "Redirects to " + moved; }
    else if (ms > 3500) { state = "slow"; note = "Slow to answer (" + ms + " ms)"; }
    return { state, ms, code, note, moved };
  } catch (e) {
    const timeout = ctrl.signal.aborted;
    return { state: "down", ms: Date.now() - t0, note: timeout ? "No answer within 8 seconds" : "Could not connect" + (e && e.message ? " (" + String(e.message).replace(/^net::/, "") + ")" : "") };
  } finally { clearTimeout(timer); }
});
const dpiCtx = () => ({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath, appDir: __dirname, logFile: path.join(app.getPath("userData"), "dpi.log"), args: cfgmod.DPI_PRESETS[S.net.dpiPreset] });
const dpiStatus = async () => (S.net.dpiEnabled ? dpi.status() : { state: "disabled" });   // "disabled" hides the DPI indicator
async function applyDpiProxy(enabled) {
  if (process.platform !== "darwin") return;
  const cfg = enabled ? { proxyRules: "http://127.0.0.1:8080", proxyBypassRules: "<-loopback>" } : { mode: "direct" };
  await Promise.all([session.fromPartition(PARTITION), session.defaultSession].map(async s => {
    try { await s.setProxy(cfg); await s.forceReloadProxyConfig(); }
    catch (e) { console.error("DPI proxy:", e && e.message); }
  }));
}
function mirrorDpiArgs() {   // keep goodbyedpi/args.txt in step with the option chosen in Settings (the app itself uses the setting)
  try {
    const base = app.isPackaged ? path.join(process.resourcesPath, "goodbyedpi") : path.join(__dirname, "goodbyedpi");
    const f = path.join(base, "args.txt"), line = cfgmod.DPI_PRESETS[S.net.dpiPreset];
    const lines = fs.readFileSync(f, "utf8").split(/\r?\n/), i = lines.findIndex(l => l.trim() && !l.trim().startsWith("#"));
    if (i >= 0) lines[i] = line; else lines.push(line);
    fs.writeFileSync(f, lines.join("\n"), "utf8");
  } catch {}
}
ipcMain.handle("dpi:status", () => dpiStatus());
ipcMain.handle("app:perf", () => PERF);

// ---- link list from GitHub (sites.json): fixes broken links for everyone ----
const DEFAULT_SITES_URL = "https://raw.githubusercontent.com/waterme1on7r-byte/football-arena-multi/main/sites.json";
const DEFAULT_ROOM = "football-arena-wtmljr-v1";
const DEFAULT_SERVERS = ["wss://broker.hivemq.com:8884/mqtt", "wss://broker.emqx.io:8084/mqtt", "wss://test.mosquitto.org:8081/"];
const MIRROR_URL = "https://cdn.jsdelivr.net/gh/waterme1on7r-byte/football-arena-multi@main/sites.json";   // only used when GitHub itself does not answer
const remoteFile = () => path.join(app.getPath("userData"), "remote.json");
let remoteCache = {};   // { url, etag, data } : the last good sites.json, so the app can show it instantly and ask GitHub "changed?" (a tiny 304 reply)
try { remoteCache = JSON.parse(fs.readFileSync(remoteFile(), "utf8")) || {}; } catch {}
let remoteChat = null;
try { const c = validateRemote(remoteCache.data); remoteChat = (c && c.chat) || remoteCache.chat || null; blockedMin = minBlocked(c && c.minVersion); } catch {}
function sitesUrl() {   // to test another address, put it on the first line of sites-url.txt in the app data folder
  try {
    const l = fs.readFileSync(path.join(app.getPath("userData"), "sites-url.txt"), "utf8").split(/\r?\n/).map(s => s.trim()).find(s => s && !s.startsWith("#"));
    if (l) return l;
  } catch {}
  return DEFAULT_SITES_URL;
}
async function getJson(url, etag, ms, fresh) {
  const ctrl = new AbortController(), timer = setTimeout(() => ctrl.abort(), ms);
  try {
    const r = await net.fetch(url, { signal: ctrl.signal, headers: etag ? { "If-None-Match": etag } : {}, ...(fresh ? { cache: "no-cache" } : {}) });
    if (r.status === 304) return { notModified: true };
    if (!r.ok) return null;
    return { data: await r.json(), etag: r.headers.get("etag") || "" };
  } catch { return null; } finally { clearTimeout(timer); }
}
ipcMain.handle("sites:cached", () => { try { return validateRemote(remoteCache.data); } catch { return null; } });
ipcMain.handle("sites:remote", async (_e, manual) => {
  const url = sitesUrl(), mine = remoteCache.url === url;
  let r = await getJson(url, mine ? remoteCache.etag : "", 5000, !!manual);
  if (!r && url === DEFAULT_SITES_URL) {
    r = await getJson(MIRROR_URL, "", 6000, false);
    // the mirror can lag behind GitHub: never let it replace a newer list we already have
    if (r && r.data && mine && remoteCache.data && String(r.data.updated || "") < String(remoteCache.data.updated || "")) r = null;
  }
  if (!r) return null;
  let clean = null;
  if (r.notModified) clean = validateRemote(remoteCache.data);
  else {
    clean = validateRemote(r.data);
    if (clean) { remoteCache = { url, etag: r.etag, data: r.data }; try { fs.writeFileSync(remoteFile(), JSON.stringify(remoteCache)); } catch {} }
  }
  if (!clean) return null;
  remoteChat = clean.chat;
  applyMinVersion(clean.minVersion);
  return clean;
});

// ---- chat: a small window above the sites, created the first time it is opened ----
let chatView = null, chatOpen = false;
const FOOT = 32;
function chatBounds() {
  const [w, h] = win.getContentSize();
  const W = Math.min(360, w - 24), H = Math.min(500, h - FOOT - 24);
  return { x: w - W - 14, y: h - FOOT - H - 8, width: W, height: H };
}
const chatAllowed = () => S.chat.enabled && !(remoteChat && remoteChat.enabled === false) && !locked && !blockedMin;
function destroyChat() {   // chat off: close its window completely (also leaves the chat network)
  chatOpen = false;
  if (chatView) { try { win.contentView.removeChildView(chatView); } catch {} try { chatView.webContents.close(); } catch {} chatView = null; }
  chatAdmin = { admin: false, bans: [] }; send("chat:admin", chatAdmin);
}
let chatAdmin = { admin: false, bans: [] };
const chatCfg = () => ({ nick: S.chat.nick, notify: S.chat.notify, sound: S.chat.sound, v: app.getVersion(), language: S.language });
function setChat(open) {
  if (open && !chatAllowed()) return;
  chatOpen = open;
  if (open) {
    if (!chatView) {
      chatView = new WebContentsView({ webPreferences: { preload: path.join(__dirname, "chatpreload.js"), contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false, backgroundThrottling: false, autoplayPolicy: "no-user-gesture-required" } });
      chatView.setBackgroundColor("#0b0f14");
      const wc = chatView.webContents;
      wc.setWindowOpenHandler(() => ({ action: "deny" }));
      wc.on("will-navigate", e => e.preventDefault());
      wc.on("before-input-event", (e, input) => { if (input.type === "keyDown" && input.key === "F12") wc.toggleDevTools(); });
      wc.loadFile(path.join(__dirname, "renderer", "chat.html"));
    }
    chatView.setBounds(chatBounds());
    win.contentView.addChildView(chatView);
    chatView.setVisible(true);
    chatView.webContents.focus();
    chatView.webContents.send("chat:visible", true);
  } else if (chatView) {
    chatView.setVisible(false);
    chatView.webContents.send("chat:visible", false);
  }
  send("chat:state", { open });
}
ipcMain.on("chat:toggle", () => { if (!chatAllowed()) return; setChat(!chatOpen); });
ipcMain.on("chat:minimize", e => { if (chatView && e.sender === chatView.webContents) setChat(false); });
ipcMain.on("chat:unread", (e, n) => { if (chatView && e.sender === chatView.webContents) send("chat:unread", { n: Math.max(0, Math.min(99, Number(n) || 0)) }); });
const fromChat = e => chatView && e.sender === chatView.webContents;
ipcMain.on("chat:nick", (e, n) => { if (!fromChat(e)) return; S.chat.nick = cfgmod.normalize({ chat: { nick: String(n) } }).chat.nick; saveS(); send("settings:changed", pub()); });   // nickname changed inside the chat
ipcMain.on("chat:admin", (e, d) => {   // only the chat window knows whether the admin password was entered; it tells us so the ban list can be shown
  if (!fromChat(e) || !d || typeof d !== "object") return;
  const bans = Array.isArray(d.bans) ? d.bans.slice(0, 300).map(b => ({ u: String(b.u || "").slice(0, 40), n: String(b.n || "").slice(0, 20) })) : [];
  chatAdmin = { admin: d.admin === true, bans }; send("chat:admin", chatAdmin);
});
ipcMain.handle("chat:adminState", () => chatAdmin);
ipcMain.on("chat:unban", (e, u) => { if (e.sender === (win && win.webContents) && chatView && chatAdmin.admin) chatView.webContents.send("chat:unban", String(u).slice(0, 40)); });
ipcMain.handle("chat:config", () => ({
  ...chatCfg(),
  enabled: S.chat.enabled && !(remoteChat && remoteChat.enabled === false),
  room: (remoteChat && remoteChat.room) || DEFAULT_ROOM,
  servers: (remoteChat && remoteChat.servers) || DEFAULT_SERVERS,
}));
ipcMain.on("dpi:retry", () => { if (!S.net.dpiEnabled) return; dpi.start(dpiCtx()).then(async ok => { if (process.platform === "darwin") await applyDpiProxy(!!ok); send("dpi:status", await dpiStatus()); }).catch(() => {}); });
ipcMain.on("dpi:stop", async () => { if (!S.net.dpiEnabled) return; dpi.disable(); await applyDpiProxy(false); send("dpi:status", await dpiStatus()); });

ipcMain.handle("sites:export", async (_e, text) => {
  const r = await dialog.showSaveDialog(win, { defaultPath: "football-arena-sites.json", filters: [{ name: "JSON", extensions: ["json"] }] });
  if (r.canceled || !r.filePath) return false;
  fs.writeFileSync(r.filePath, String(text), "utf8");
  return true;
});
ipcMain.handle("sites:import", async () => {
  const r = await dialog.showOpenDialog(win, { properties: ["openFile"], filters: [{ name: "JSON", extensions: ["json"] }] });
  if (r.canceled || !r.filePaths[0]) return null;
  return fs.readFileSync(r.filePaths[0], "utf8");
});

// Ad blocking: built-in rules first (always on unless switched off in Settings), then the big filter lists when they can be loaded.
let blocker = null, blockerOn = false, adSes = null;
const blFile = () => path.join(app.getPath("userData"), "blocklist.txt");
function wireAdblock() {   // (re)applies the Settings switch; safe to call any number of times
  adblock.setEnabled(S.net.adblock);
  if (!adSes) return;
  const mine = d => d.resourceType !== "mainFrame" && adblock.isAdUrl(d.url);
  if (S.net.adblock) {
    if (blocker && !blockerOn) { try { blocker.enableBlockingInSession(adSes); blockerOn = true; } catch (e) { console.error("enable blocking:", e && e.message); } }
    if (blocker && blockerOn && typeof blocker.onBeforeRequest === "function") adSes.webRequest.onBeforeRequest({ urls: ["<all_urls>"] }, (d, cb) => mine(d) ? cb({ cancel: true }) : blocker.onBeforeRequest(d, cb));
    else if (!blocker) adSes.webRequest.onBeforeRequest({ urls: ["<all_urls>"] }, (d, cb) => cb({ cancel: mine(d) }));
  } else {
    if (blocker && blockerOn) { try { blocker.disableBlockingInSession(adSes); } catch {} blockerOn = false; }
    try { adSes.webRequest.onBeforeRequest(null); } catch {}
  }
}
async function enableAdBlock(ses) {
  adSes = ses;
  adblock.loadCustom(blFile());
  wireAdblock();
  try {
    const { ElectronBlocker } = require("@ghostery/adblocker-electron");
    fs.promises.unlink(path.join(app.getPath("userData"), "adblock-engine.bin")).catch(() => {}); // old, bigger engine
    // ads-only lists use noticeably less memory than ads + tracking
    const make = ElectronBlocker.fromPrebuiltAdsOnly || ElectronBlocker.fromPrebuiltAdsAndTracking;
    blocker = await make.call(ElectronBlocker, fetch, {
      path: path.join(app.getPath("userData"), "adblock-ads-engine.bin"),
      read: fs.promises.readFile, write: fs.promises.writeFile,
    });
    wireAdblock();
  } catch (e) { console.error("Filter lists unavailable, using built-in rules only:", e && e.message); }
}
function pushAutoSkip() {   // Settings > Network > Auto-skip: takes effect in the open pages at once
  const js = S.net.autoskip ? "window.__faAdOff=false;" + AD_SKIP : "window.__faAdOff=true;";
  for (const v of views.values()) { try { for (const f of v.webContents.mainFrame.framesInSubtree) f.executeJavaScript(js).catch(() => {}); } catch {} }
}
// edit blocklist.txt inside the app
ipcMain.handle("blocklist:read", () => { try { adblock.loadCustom(blFile()); return fs.readFileSync(blFile(), "utf8"); } catch { return adblock.TEMPLATE; } });
ipcMain.handle("blocklist:write", (e, text) => {
  if (e.sender !== (win && win.webContents) || locked) return false;
  try { fs.writeFileSync(blFile(), String(text).slice(0, 200000), "utf8"); adblock.loadCustom(blFile()); return true; } catch { return false; }
});

// ---- kill switch: sites.json "minVersion" ----
function applyMinVersion(min) {
  const was = blockedMin; blockedMin = minBlocked(min);
  if (blockedMin && !was) { for (const id of [...views.keys()]) destroyView(id); lastList = []; destroyChat(); send("chat:state", { open: false }); }
  if (blockedMin !== was) send("app:blocked", blockedMin ? { min: blockedMin } : null);
}

// ---- settings: the window asks, main validates, saves and applies ----
const fromWin = e => win && !win.isDestroyed() && e.sender === win.webContents;
ipcMain.on("settings:get", e => { e.returnValue = pub(); });
function applySettings(old) {
  if (old.audio.focusOnly !== S.audio.focusOnly) { applyList(); send("pane:focus", S.audio.focusOnly ? effectiveFocus() : null); }
  if (old.net.adblock !== S.net.adblock) wireAdblock();
  if (old.net.autoskip !== S.net.autoskip) pushAutoSkip();
  if (old.net.dpiPreset !== S.net.dpiPreset) mirrorDpiArgs();
  if (old.net.dpiEnabled !== S.net.dpiEnabled) {
    if (S.net.dpiEnabled) dpi.start(dpiCtx()).then(ok => { if (process.platform === "darwin") return applyDpiProxy(!!ok); }).catch(() => {}); else { dpi.disable(); applyDpiProxy(false).catch(() => {}); }
    dpiStatus().then(st => send("dpi:status", st)).catch(() => {});
  } else if (old.net.dpiPreset !== S.net.dpiPreset && S.net.dpiEnabled) {
    dpi.status().then(st => { if (st.state === "on" || st.state === "starting") dpi.start(dpiCtx()).catch(() => {}); }).catch(() => {});
  }
  if (old.chat.enabled !== S.chat.enabled) {
    if (!S.chat.enabled) { destroyChat(); send("chat:state", { open: false }); }
  } else if (chatView && S.chat.enabled) {
    chatView.webContents.send("chat:settings", chatCfg());
    if (old.chat.nick !== S.chat.nick) chatView.webContents.send("chat:nick", S.chat.nick);
  }
}
ipcMain.handle("settings:set", (e, patch) => {
  if (!fromWin(e)) return pub();
  // First-launch language selection also works before the parental code is entered.
  if (locked && (!patch || !["tr", "en"].includes(patch.language) || Object.keys(patch).some(k => !["language", "languageChosen"].includes(k)))) return pub();
  const old = S; S = cfgmod.merge(S, patch); saveS();
  try { applySettings(old); } catch (err) { console.error("applySettings:", err && err.message); }
  const v = pub(); send("settings:changed", v); return v;
});

// ---- parental passcode (exactly 4 digits) ----
let fails = 0, lockUntil = 0;
const waitLeft = () => Math.max(0, lockUntil - Date.now());
function failed() { fails++; if (fails >= 5) lockUntil = Date.now() + Math.min(300000, 15000 * 2 ** (fails - 5)); return { ok: false, wait: waitLeft() }; }
ipcMain.handle("lock:try", (e, code) => {
  if (!fromWin(e)) return { ok: false };
  if (!locked) return { ok: true };
  if (waitLeft() > 0) return { ok: false, wait: waitLeft() };
  if (!cfgmod.checkPass(S.pass, String(code))) return failed();
  fails = 0; locked = false; send("lock:state", false); return { ok: true };
});
// set / change / remove the passcode: changing or removing needs the current one. next = "" removes it.
ipcMain.handle("lock:set", (e, d) => {
  if (!fromWin(e) || locked || !d || typeof d !== "object") return { ok: false };
  if (S.pass) {
    if (waitLeft() > 0) return { ok: false, wait: waitLeft() };
    if (!cfgmod.checkPass(S.pass, String(d.current))) return failed();
    fails = 0;
  }
  const next = String(d.next || "");
  if (next && !cfgmod.validCode(next)) return { ok: false, error: "format" };
  S = { ...S, pass: next ? cfgmod.makePass(next) : null }; saveS();
  send("settings:changed", pub()); return { ok: true, hasPasscode: !!S.pass };
});

// ---- app: log folder, reset to defaults ----
ipcMain.handle("app:openLogs", async e => { if (!fromWin(e) || locked) return ""; return shell.openPath(app.getPath("userData")); });
ipcMain.handle("app:reset", (e, code) => {   // the window clears its own saved sites/layout afterwards; the passcode is never reset here
  if (!fromWin(e) || locked) return { ok: false };
  if (S.pass) { if (waitLeft() > 0) return { ok: false, wait: waitLeft() }; if (!cfgmod.checkPass(S.pass, String(code))) return failed(); fails = 0; }
  const old = S; S = cfgmod.normalize(null); S.pass = old.pass; saveS();
  try { applySettings(old); } catch {}
  send("settings:changed", pub()); return { ok: true };
});

// ---- app updates (electron-updater, GitHub Releases) ----
ipcMain.handle("updater:status", () => updater.status());
ipcMain.handle("updater:check", e => { if (!fromWin(e)) return updater.status(); return updater.check(); });
ipcMain.handle("updater:release", e => { if (!fromWin(e)) return false; return updater.openReleasePage(); });
ipcMain.handle("updater:install", e => {
  if (!fromWin(e) || updater.status().state !== "ready") return false;
  dpi.stop();   // GoodbyeDPI must not hold its files while the installer replaces them
  return updater.installNow();
});

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);

  // Look like normal Chrome to the sites (no "Electron" in the user agent) and keep logins between runs.
  const ses = session.fromPartition(PARTITION);
  const ua = app.userAgentFallback.replace(/ Electron\/[\d.]+/, "").replace(new RegExp(" " + app.getName().replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "/\\S+"), "");
  ses.setUserAgent(ua);
  ses.setPermissionRequestHandler((_wc, permission, cb) => cb(permission === "fullscreen"));
  enableAdBlock(ses).catch(() => {});

  win = new BrowserWindow({
    width: 1440, height: 900, minWidth: 900, minHeight: 600, show: false,
    backgroundColor: "#0b0f14", title: "Football Arena", autoHideMenuBar: true,
    icon: path.join(__dirname, "build", "icon.ico"),
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false },
  });
  win.maximize();
  win.once("ready-to-show", () => win.show());
  updater.init(st => send("updater:state", st), m => { try { fs.appendFileSync(path.join(app.getPath("userData"), "update.log"), new Date().toISOString() + " " + m + "\n"); } catch {} });
  updater.start();
  win.loadFile(path.join(__dirname, "renderer", "index.html"));
  // Starts the platform DPI helper with the app and stops it on quit.
  mirrorDpiArgs();
  if (S.net.dpiEnabled) dpi.start(dpiCtx()).then(ok => { if (process.platform === "darwin") return applyDpiProxy(!!ok); }).catch(() => {});
  let lastDpi = "";
  setInterval(async () => {
    try { const s = await dpiStatus(); const k = JSON.stringify(s); if (k !== lastDpi) { lastDpi = k; send("dpi:status", s); } } catch {}
  }, 2000);
  win.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  win.webContents.on("will-navigate", e => e.preventDefault());
  win.webContents.on("before-input-event", (e, input) => {
    if (input.type === "keyDown" && input.key === "F12") win.webContents.toggleDevTools();
  });
  win.on("resize", () => {
    if (fsId && views.get(fsId)) { const [w, h] = win.getContentSize(); views.get(fsId).setBounds({ x: 0, y: 0, width: w, height: h }); }
    if (chatView && chatOpen) chatView.setBounds(chatBounds());
  });
  win.on("closed", () => { views.clear(); meta.clear(); win = null; });
});

app.on("second-instance", () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
// Cache and temporary data are deleted when the app closes (logins, sites and settings are kept). Short and time-limited:
// the window is hidden while it runs, and if anything is slow the app just quits.
let cleaned = false;
async function clearTemp() {
  const wipe = ses => Promise.allSettled([
    ses.clearCache(), ses.clearCodeCaches({}),
    ses.clearStorageData({ storages: ["cachestorage", "serviceworkers", "shadercache"] }),
  ]);
  await Promise.race([Promise.allSettled([wipe(session.fromPartition(PARTITION)), wipe(session.defaultSession)]), new Promise(r => setTimeout(r, 4000))]);
}
app.on("before-quit", e => {
  if (cleaned || !S.clearOnExit || updater.installing()) return;   // (restart-to-update leaves at once)
  cleaned = true; e.preventDefault();
  try { if (win && !win.isDestroyed()) win.hide(); } catch {}
  clearTemp().catch(() => {}).finally(() => app.quit());
});
app.on("will-quit", () => { dpi.stop(); if (process.platform === "darwin") applyDpiProxy(false).catch(() => {}); });
process.on("exit", () => dpi.stop());
app.on("window-all-closed", () => app.quit());
