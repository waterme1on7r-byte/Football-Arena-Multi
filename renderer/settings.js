"use strict";
// Settings pane: App, Sound & Video, Links, Chat, Network. Everything is saved by the app itself (user data folder);
// this file only draws the controls and sends changes. It needs app.js (window.FAI) and the preload bridge (window.arena).
(() => {
if (!window.FAI || !window.arena) return;
const $ = s => document.querySelector(s);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const S = () => FAI.S();
const set = patch => arena.setCfg(patch).catch(() => {});

// same three choices as in settings.js (main process)
const DPI_OPTIONS = {
  1: "-5 --dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253",
  2: "-9 --dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253",
  3: "-6 --dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253",
};
const TABS = [["app", "App"], ["sound", "Sound & Video"], ["links", "Links"], ["chat", "Chat"], ["net", "Network"]];
let tab = "app", chatAdmin = { admin: false, bans: [] }, dpiState = null;

/* ---------- small builders ---------- */
const btn = (text, fn, cls) => { const b = el("button", cls || "sbtn", text); b.type = "button"; b.onclick = fn; return b; };
function sw(checked, onChange, label) {
  const l = el("label", "sw"), i = el("input"); i.type = "checkbox"; i.checked = !!checked; i.setAttribute("aria-label", label || "");
  i.onchange = () => onChange(i.checked); l.append(i, el("i")); l.input = i; return l;
}
function row(title, desc, ...ctl) {
  const r = el("div", "srow"), l = el("div", "lbl"); l.append(el("b", null, title)); if (desc) l.append(el("small", null, desc));
  const c = el("div", "ctl"); c.append(...ctl.filter(Boolean)); r.append(l, c); return r;
}
const sec = t => el("div", "sec", t);
function select(options, value, onChange, label) {
  const s = el("select"); s.setAttribute("aria-label", label || "");
  for (const [v, t] of options) { const o = el("option", null, t); o.value = String(v); s.append(o); }
  s.value = String(value); s.onchange = () => onChange(s.value); return s;
}
const waitText = ms => "Too many tries. Wait " + Math.ceil(ms / 1000) + " s.";
const pinInput = ph => {
  const i = el("input", "pin"); i.type = "password"; i.inputMode = "numeric"; i.maxLength = 4; i.placeholder = ph; i.autocomplete = "off"; i.setAttribute("aria-label", ph);
  i.oninput = () => { i.value = i.value.replace(/\D/g, "").slice(0, 4); }; return i;
};

/* ---------- App ---------- */
function updText(u) {
  switch (u && u.state) {
    case "checking": return "Checking…";
    case "downloading": return "Downloading version " + (u.version || "") + (u.percent ? " (" + u.percent + "%)" : "") + "…";
    case "available": return "Version " + (u.version || "") + " is available. Open the release page to download the macOS installer.";
    case "ready": return "Version " + (u.version || "") + " is ready.";
    case "uptodate": return "You have the latest version.";
    case "error": return "Could not check for updates." + (u.message ? " " + u.message : "");
    case "unsupported": return u.reason === "dev" ? "Updates work in the installed app." : "The updater is not available.";
    default: return "";
  }
}
function paintUpd() {
  const t = $("#appUpdSt"), b = $("#appUpdBtn"); if (!t || !b) return;
  const u = FAI.upd(); t.textContent = updText(u);
  const ready = u.state === "ready", available = u.state === "available"; b.textContent = ready ? "Restart now" : available ? "Open release page" : "Check for updates";
  b.disabled = u.state === "checking" || u.state === "downloading" || u.state === "unsupported";
  b.classList.toggle("primary", ready);
}
function passForm(mode, host, done) {
  host.textContent = "";
  const f = el("div", "mini"), cur = mode !== "set" ? pinInput("Current") : null, nw = mode !== "remove" ? pinInput("New") : null, again = nw ? pinInput("Repeat") : null, msg = el("span", "lerr");
  const save = btn(mode === "remove" ? "Remove" : "Save", async () => {
    const c = cur ? cur.value : "", n = nw ? nw.value : "";
    if (cur && c.length !== 4) { msg.textContent = "Enter the current 4 digits."; return; }
    if (nw && n.length !== 4) { msg.textContent = "The new passcode must be exactly 4 digits."; return; }
    if (again && again.value !== n) { msg.textContent = "The two new passcodes do not match."; return; }
    save.disabled = true; let r = { ok: false }; try { r = await arena.lockSet(c, mode === "remove" ? "" : n); } catch {}
    save.disabled = false;
    if (r && r.ok) { done(); return; }
    msg.textContent = r && r.wait ? waitText(r.wait) : r && r.error === "format" ? "The passcode must be exactly 4 digits." : "Wrong current passcode.";
  }, "sbtn primary");
  f.append(...[cur, nw, again].filter(Boolean), save, btn("Cancel", done), msg); host.append(f); (cur || nw).focus();
}
function tabApp(p) {
  p.append(row("Language", null, select([["tr", "Türkçe"], ["en", "English"]], S().language || "tr", value => FA_I18N.setLanguage(value), "Language")));
  p.append(sec("About"));
  p.append(row("Version", null, el("span", "stat", S().version || "")));
  const st = el("span", "stat"); st.id = "appUpdSt";
  const ub = btn("Check for updates", () => { const u = FAI.upd(); if (u.state === "ready") arena.updInstall(); else if (u.state === "available") arena.updRelease(); else arena.updCheck().then(paintUpd).catch(() => {}); }); ub.id = "appUpdBtn";
  p.append(row("App updates", S().platform === "darwin" ? "Check for a newer version, then install it manually from the downloaded macOS package." : "New versions download in the background and install when you close the app.", st, ub));
  p.append(row("Log folder", "Opens the folder with dpi.log, update.log and your settings.", btn("Open log folder", () => arena.openLogs())));
  p.append(row("Clear cache on exit", "Deletes the cache and temporary data when the app closes. Logins, sites and settings stay.", sw(S().clearOnExit, v => set({ clearOnExit: v }), "Clear cache on exit")));
  p.append(sec("Sites list"));
  p.append(row("Export / import", "Save your sites to a file or add sites from one.", btn("Export sites", () => FAI.exportSites()), btn("Import sites", () => FAI.importSites())));
  p.append(sec("Parental controls"));
  const host = el("div"); const redraw = () => renderTab();
  const has = S().hasPasscode;
  p.append(row("Passcode", has ? "A 4-digit passcode is asked every time the app starts." : "Ask for a 4-digit passcode every time the app starts.",
    has ? btn("Change…", () => passForm("change", host, redraw)) : null, has ? btn("Remove…", () => passForm("remove", host, redraw)) : btn("Set passcode…", () => passForm("set", host, redraw))));
  p.append(host);
  p.append(sec("Reset"));
  const rhost = el("div");
  const doReset = async code => {
    const ok = await FAI.ask({ title: "Reset to defaults", text: "Your sites, their order, the layout and all settings go back to the defaults" + (S().hasPasscode ? ". The parental passcode stays." : "."), ok: "Reset", danger: true });
    if (!ok) return;
    let r = { ok: false }; try { r = await arena.resetAll(code); } catch {}
    if (r && r.ok) FAI.resetLocal();
    else { rhost.textContent = ""; rhost.append(el("div", "lerr", r && r.wait ? waitText(r.wait) : "Wrong passcode.")); }
  };
  p.append(row("Reset to defaults", "Sites, layout, sound and settings.", btn("Reset…", () => {
    if (!S().hasPasscode) { doReset(""); return; }
    rhost.textContent = ""; const f = el("div", "mini"), pin = pinInput("Passcode"), go = btn("Continue", () => { if (pin.value.length === 4) doReset(pin.value); }, "sbtn primary");
    pin.onkeydown = e => { if (e.key === "Enter") go.click(); };
    f.append(pin, go, btn("Cancel", () => { rhost.textContent = ""; })); rhost.append(f); pin.focus();
  })));
  p.append(rhost);
  paintUpd();
}

/* ---------- Sound and video ---------- */
function tabSound(p) {
  p.append(sec("Sound"));
  const val = el("span", "stat", S().audio.defaultVolume + "%"), rng = el("input", "rng"); rng.type = "range"; rng.min = 0; rng.max = 100; rng.step = 5; rng.value = S().audio.defaultVolume; rng.setAttribute("aria-label", "Default volume");
  const paint = () => { rng.style.setProperty("--p", rng.value + "%"); val.textContent = rng.value + "%"; }; paint();
  rng.oninput = paint; rng.onchange = () => set({ audio: { defaultVolume: +rng.value } });
  p.append(row("Default volume", "For newly opened panes of sites whose volume you have not set yet.", rng, val));
  p.append(row("Only the focused pane plays sound", "The pane you last clicked plays; the others are silent.", sw(S().audio.focusOnly, v => set({ audio: { focusOnly: v } }), "Only the focused pane plays sound")));
  p.append(sec("Layout"));
  p.append(row("Remember my layout and open sites on restart", "Off: the app starts with nothing open.", sw(S().restore, v => set({ restore: v }), "Remember my layout and open sites on restart")));
}

/* ---------- Links ---------- */
function tabLinks(p) {
  p.append(sec("Link updates"));
  const st = el("span", "stat"); st.id = "lkStatus"; const cb = btn("Check for new links now", () => FAI.syncRemote(true)); cb.id = "lkCheck";
  p.append(row("Check for new links", "Manual still looks once when the app starts.", select([[15, "Every 15 minutes"], [60, "Every 1 hour"], [0, "Manual"]], S().links.every, v => set({ links: { every: +v } }), "How often to check for new links")));
  p.append(row("Update the list now", "Gets the latest working addresses.", st, cb));
  p.append(sec("Health check"));
  p.append(row("Health check", "Shows a dot on every site: works, slow, redirects or not working.", select([["startup", "Run at startup"], ["manual", "Only when I press Health"], ["off", "Off (hide the button)"]], S().links.health, v => { set({ links: { health: v } }); const run = $("#hlRun"); if (run) run.hidden = v === "off"; }, "Health check")));
  const run = btn("Run health check now", () => FAI.checkHealth()); run.id = "hlRun"; run.hidden = S().links.health === "off";
  p.append(row("Run it now", null, run));
  p.append(sec("Opening sites"));
  p.append(row("Open links in the Main Pane by default", "A site you open becomes the big pane, the others stack on the right.", sw(S().links.openInMain, v => set({ links: { openInMain: v } }), "Open links in the Main Pane by default")));
}

/* ---------- Chat ---------- */
function paintBans() {
  const box = $("#banBox"); if (!box) return; box.textContent = "";
  const sect = $("#banSec"); if (sect) sect.hidden = !chatAdmin.admin;
  if (!chatAdmin.admin) { box.hidden = true; return; } box.hidden = false;
  if (!chatAdmin.bans.length) { box.append(el("div", "hint", "No one is banned.")); return; }
  for (const b of chatAdmin.bans) {
    const r = el("div", "banrow"); r.append(el("span", null, b.n || "unknown"), btn("Unban", () => arena.chatUnban(b.u))); box.append(r);
  }
}
function tabChat(p) {
  const c = S().chat;
  p.append(sec("Chat"));
  p.append(row("Chat", "Off closes the chat and hides its button.", sw(c.enabled, v => set({ chat: { enabled: v } }), "Chat on or off")));
  const nick = el("input", "nick"); nick.type = "text"; nick.id = "nickIn2"; nick.maxLength = 20; nick.value = c.nick; nick.placeholder = "Set when you join"; nick.autocomplete = "off"; nick.setAttribute("aria-label", "Nickname");
  const msg = el("span", "lerr");
  const save = () => { const n = nick.value.replace(/\s+/g, " ").trim(); if (n.length < 2) { msg.textContent = "Use at least 2 characters."; return; } msg.textContent = ""; set({ chat: { nick: n } }); msg.style.color = "var(--mute)"; msg.textContent = "Saved."; };
  nick.onkeydown = e => { if (e.key === "Enter") save(); };
  p.append(row("Nickname", "Protected names ask for their password inside the chat.", nick, btn("Save", save)));
  if (msg) p.append(msg);
  p.append(sec("Notifications"));
  const snd = sw(c.sound, v => set({ chat: { sound: v } }), "Sound for new messages");
  const mute = sw(!c.notify, v => { set({ chat: { notify: !v } }); snd.input.disabled = v; }, "Mute notifications"); snd.input.disabled = !c.notify;
  p.append(row("Mute notifications", "No unread badge and no sound for new messages.", mute));
  p.append(row("Sound for new messages", "A short beep when someone writes.", snd));
  const bs = sec("Ban list"); bs.id = "banSec"; const box = el("div"); box.id = "banBox"; p.append(bs, box);   // only shown to administrators
  paintBans(); arena.chatAdmin().then(d => { if (d) { chatAdmin = d; paintBans(); } }).catch(() => {});
}

/* ---------- Network ---------- */
const DPI_TXT = { on: "On", starting: "Starting…", off: "Off", error: "Failed" };
function dpiKind(s) { return !s ? "off" : s.state === "off" && s.detail && s.detail !== "manual" ? "error" : s.state; }
function paintDpi() {
  const pill = $("#dpiPill"); if (!pill) return;
  const on = S().net.dpiEnabled, k = dpiKind(dpiState);
  pill.hidden = !on; pill.dataset.s = k; pill.textContent = DPI_TXT[k] || "…";
  const t = $("#dpiOnOff"), r = $("#dpiRst"), busy = k === "starting";
  if (t) { t.hidden = !on; t.textContent = k === "on" ? "Turn off" : "Turn on"; t.disabled = busy; }
  if (r) { r.hidden = !on; r.disabled = busy || k !== "on"; }
}
async function openBlocklist() {
  let text = ""; try { text = await arena.blocklistRead(); } catch {}
  $("#blText").value = text; await FAI.showDialog($("#blDlg")); $("#blText").focus();
}
function tabNet(p) {
  const n = S().net;
  p.append(sec("Ad blocker"));
  p.append(row("Ad blocker", "Blocks ads and pop-ups. Open pages pick it up when reloaded.", sw(n.adblock, v => set({ net: { adblock: v } }), "Ad blocker")));
  p.append(row("Auto-skip", "Presses “Skip ad” buttons and fast-forwards short ads.", sw(n.autoskip, v => set({ net: { autoskip: v } }), "Auto-skip")));
  p.append(row("Blocklist", "Your own blocked domains and address parts, edited here.", btn("Edit blocklist…", openBlocklist)));
  const mac = S().platform === "darwin", dpiName = mac ? "SpoofDPI" : "GoodbyeDPI";
  p.append(sec(dpiName));
  p.append(row(dpiName, mac ? "Uses an app-only local proxy. It does not change macOS network settings." : "On: starts with the app. Off: stays off and its indicator is hidden.", sw(n.dpiEnabled, v => { set({ net: { dpiEnabled: v } }); setTimeout(paintDpi, 50); }, dpiName)));
  const pill = el("span", "pill"); pill.id = "dpiPill";
  const t = btn("Turn off", () => { if (dpiKind(dpiState) === "on") arena.dpiStop(); else arena.dpiRetry(); }); t.id = "dpiOnOff";
  const r = btn("Restart", () => arena.dpiRetry()); r.id = "dpiRst";
  p.append(row("Status", "The same controls as the DPI button in the top bar.", pill, t, r));
  if (!mac) {
    p.append(sec("DPI options"));
    p.append(el("p", "hint", "The selected option is used every time the app starts. Changing it restarts GoodbyeDPI if it is running."));
    const list = el("div", "dpiopt");
    for (const k of [1, 2, 3]) {
      const l = el("label"), i = el("input"); i.type = "radio"; i.name = "dpiopt"; i.value = k; i.checked = n.dpiPreset === k;
      i.onchange = () => { if (i.checked) set({ net: { dpiPreset: k } }); };
      const d = el("div"); d.append(el("b", null, "Option " + k), el("code", null, DPI_OPTIONS[k])); l.append(i, d); list.append(l);
    }
    p.append(list);
  }
  paintDpi();
}

/* ---------- tabs + dialog ---------- */
const BUILD = { app: tabApp, sound: tabSound, links: tabLinks, chat: tabChat, net: tabNet };
function renderTab() {
  const p = $("#setPanel"), keep = p.scrollTop; p.textContent = ""; BUILD[tab](p); p.scrollTop = keep;
  $("#setTabs").querySelectorAll("button").forEach(b => { const on = b.dataset.t === tab; b.setAttribute("aria-selected", on); b.tabIndex = on ? 0 : -1; });
}
const tabs = $("#setTabs");
for (const [k, name] of TABS) { const b = el("button", null, name); b.type = "button"; b.dataset.t = k; b.setAttribute("role", "tab"); b.onclick = () => { tab = k; renderTab(); }; tabs.append(b); }
tabs.addEventListener("keydown", e => {
  if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
  const i = TABS.findIndex(t => t[0] === tab), j = (i + (e.key === "ArrowDown" ? 1 : TABS.length - 1)) % TABS.length;
  tab = TABS[j][0]; renderTab(); tabs.querySelector('[aria-selected="true"]').focus(); e.preventDefault();
});
$("#settingsBtn").onclick = () => { renderTab(); FAI.showDialog($("#setDlg")); };
$("#setClose").onclick = () => $("#setDlg").close();

/* blocklist editor */
$("#blCancel").onclick = () => $("#blDlg").close();
$("#blSave").onclick = async () => {
  let ok = false; try { ok = await arena.blocklistWrite($("#blText").value); } catch {}
  if (ok) $("#blDlg").close(); else FAI.ask({ title: "Could not save", text: "The blocklist could not be written.", info: true });
};

/* live updates while the dialog is open (controls that are being used are never rebuilt) */
arena.onUpd(() => paintUpd());
arena.onDpi(s => { dpiState = s; paintDpi(); });
arena.dpiStatus().then(s => { dpiState = s; paintDpi(); }).catch(() => {});
arena.onChatAdmin(d => { if (d) { chatAdmin = d; paintBans(); } });
arena.onCfg(d => {
  if (!$("#setDlg").open) return;
  const n = $("#nickIn2"); if (n && document.activeElement !== n && n.value !== d.chat.nick) n.value = d.chat.nick;
  paintDpi();
});
})();
