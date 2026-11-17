"use strict";
(() => {
const $ = s => document.querySelector(s);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const store = {
  get(k, d) { try { const v = JSON.parse(localStorage.getItem(k)); return v ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
// Settings live in the app (user data folder). This is the copy the window works with; it is replaced whenever they change.
const DEF = { clearOnExit: true, restore: true, audio: { defaultVolume: 100, focusOnly: false }, links: { health: "manual", every: 15, openInMain: false },
  chat: { enabled: true, nick: "", notify: true, sound: false }, net: { adblock: true, autoskip: true, dpiEnabled: true, dpiPreset: 1 }, hasPasscode: false, locked: false, version: "", blocked: null };
let S = Object.assign({}, DEF, arena.cfg || {});

// Sites added automatically the first time the app runs.
const DEFAULT_SITES = [
  {id:"ardaspor",name:"ARDASPOR",url:"https://ardaspor40.top/"},
  {id:"inatbox",name:"INATBOX",url:"https://inattv1328.xyz/"},
  {id:"trgoals",name:"TRGOALS",url:"https://taraftarium1087.xyz/"},
  {id:"patronspor",name:"PATRONSPOR",url:"https://patronizle60.cfd/"},
  {id:"mahsunsports",name:"MahsunSports",url:"https://mahsunsports60.xyz/"},
  {id:"netspor",name:"NETSPOR",url:"https://netspor.co/"},
  {id:"zeustv",name:"Zeus TV",url:"https://zeustv276.live/"},
  {id:"fctv33",name:"FCTV33",url:"https://www.fctv33.com/tr"},
  {id:"extratv",name:"Extra TV",url:"https://www.extrabet2112.com/tr/int/sport/TV"},
  {id:"liveball",name:"Liveball",url:"https://liveball.sx/"},
  {id:"ardaspor-giris",name:"Arda Spor Giriş",url:"https://ardaspor.live/",quick:true},
  {id:"inatbox-giris",name:"Inatbox Giriş",url:"https://inattvgiris.co/",quick:true},
  {id:"patronspor-giris",name:"PatronSpor Giriş",url:"https://patronspor.is/",quick:true},
  {id:"trgoal-giris",name:"TrGoal Giriş",url:"https://taraftarium24.io/",quick:true},
  {id:"mahsunsports-giris",name:"MahsunSports Giriş",url:"https://mahsunsports.com/",quick:true}
];

const hostOf = u => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch { return u; } };
let sites = store.get("fa.sites", []), open = store.get("fa.open", []), layout = store.get("fa.layout", 0);
if (store.get("fa.seed", 0) < 2) { // one-time update: new defaults in, VipBox out, quick-access flags set; your own sites and order are kept
  const norm = u => { try { return new URL(/^https?:\/\//i.test(u) ? u : "https://" + u).href; } catch { return u; } };
  sites = sites.filter(s => !/vipbox/i.test(s.name) && !/vipbox/i.test(s.url));
  DEFAULT_SITES.forEach(d => {
    let s = sites.find(x => norm(x.url) === norm(d.url));
    if (!s) { s = { id: uid(), rid: d.id, name: d.name, url: norm(d.url) }; sites.push(s); }
    if (!s.rid) s.rid = d.id;
    if (d.quick) s.quick = true;
  });
  open = []; store.set("fa.seed", 2); store.set("fa.sites", sites); store.set("fa.open", open);
}
open = open.filter(id => sites.some(s => s.id === id));
if (S.restore === false) { open = []; layout = 0; }   // "Remember my layout and open sites" is off
let tmp = [], maxId = null, mainId = null, editing = null, promoting = null, menuEl = null, panelEl = null, dragged = false;
const urls = new Map(), panes = new Map();
const deleted = store.get("fa.deleted", []);   // built-in sites you removed (the link list will not bring them back)
const dlg = $("#dlg"), msgDlg = $("#msgDlg");
const audio = store.get("fa.audio", {});   // per site: { m: muted, v: volume 0-100 }
const aud = id => audio[id] || (audio[id] = { m: false, v: Number.isFinite(+S.audio.defaultVolume) ? +S.audio.defaultVolume : 100 });
const health = new Map();                  // site id -> result of the last link check
let dpiNeed = false;

const find = id => sites.find(s => s.id === id) || tmp.find(s => s.id === id);
const save = () => { store.set("fa.sites", sites); store.set("fa.open", open.filter(id => sites.some(s => s.id === id))); store.set("fa.layout", layout); };
function clean(u) {
  u = String(u || "").trim(); if (!u) return null;
  if (!/^https?:\/\//i.test(u)) u = "https://" + u;
  try { return new URL(u).href; } catch { return null; }
}

/* ---------- top bar ---------- */
const ICON = {
  edit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"/></svg>',
  bolt: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2L3 14h9l-1 8 10-12h-9z"/></svg>',
  minus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8 12h8"/></svg>',
  trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/></svg>',
};
const hue = n => [...String(n)].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7) % 360;
function avatar(name) {
  const a = el("span", "av", (String(name).match(/[\p{L}\p{N}]/u) || ["?"])[0].toLocaleUpperCase("tr"));
  a.style.setProperty("--h", hue(name)); return a;
}
function optBtn(s) {
  const o = el("button", "opt", "⋮"); o.title = "Options"; o.setAttribute("aria-label", "Options for " + s.name); o.setAttribute("aria-haspopup", "menu");
  o.onclick = e => { e.stopPropagation(); showMenu(o, s); }; return o;
}
const hdot = s => {
  const h = health.get(s.id); if (!h) return null;
  const d = el("span", "hd"); d.dataset.s = h.state; d.title = h.note || ""; return d;
};
function drawNav() {
  const nav = $("#sites"); nav.textContent = "";
  sites.filter(s => !s.quick).forEach(s => {
    const c = el("div", "chip"); c.dataset.id = s.id;
    if (open.includes(s.id)) c.setAttribute("aria-current", "true");
    const b = el("button", "nm"); b.append(avatar(s.name), el("span", null, s.name)); const hd = hdot(s); if (hd) b.append(hd); b.title = s.url + (health.get(s.id) ? "\n" + health.get(s.id).note : "");
    b.onclick = () => { if (!dragged) toggle(s.id); };
    c.append(b, optBtn(s)); nav.append(c);
  });
  tmp.forEach(t => { // yellow temporary tabs opened from links inside sites
    const c = el("div", "chip tmp"); c.dataset.id = t.id; c.setAttribute("aria-current", "true");
    const b = el("button", null, t.name); b.title = "Click to close · " + t.url; b.onclick = () => toggle(t.id);
    const o = el("button", "opt", "+"); o.title = "Add to saved sites"; o.setAttribute("aria-label", "Add " + t.name + " to saved sites");
    o.onclick = e => { e.stopPropagation(); openDlg(null, t); };
    c.append(b, o); nav.append(c);
  });
  const q = sites.filter(s => s.quick);
  $("#qCount").textContent = q.length;
  $("#quickBtn").classList.toggle("has-open", q.some(s => open.includes(s.id)));
  if (panelEl) drawQuickList();
}
function drawLayout() { document.querySelectorAll("#layout button").forEach(b => b.setAttribute("aria-pressed", +b.dataset.l === layout)); }

const SVG = (inner, w) => '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="' + (w || 2) + '" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + inner + '</svg>';
const SPK = '<path d="M11 5L6 9H3v6h3l5 4z"/>';
const ICON_VOL = { on: SVG(SPK + '<path d="M15.5 8.5a5 5 0 010 7"/><path d="M18.5 5.5a9 9 0 010 13"/>'), low: SVG(SPK + '<path d="M15.5 8.5a5 5 0 010 7"/>'), off: SVG(SPK + '<path d="M16 9l5 6M21 9l-5 6"/>') };
const ICON_MAIN = SVG('<rect x="3" y="4" width="12" height="16" rx="1.5"/><rect x="17.5" y="4" width="3.5" height="7" rx="1"/><rect x="17.5" y="13" width="3.5" height="7" rx="1"/>');

/* ---------- panes (the real page is a native view placed over each .slot) ---------- */
function makePane(s) {
  const p = el("section", "pane"); p.dataset.id = s.id;
  const bar = el("div", "bar"), t = el("b", null, s.name);
  const a = el("input"); a.type = "text"; a.value = s.url; a.spellcheck = false; a.autocomplete = "off";
  a.setAttribute("aria-label", "Address for " + s.name);
  a.onfocus = () => a.select();
  a.onkeydown = e => {
    if (e.key === "Enter") { const u = clean(a.value); if (u) { a.value = u; arena.navigate(s.id, u); } a.blur(); }
    else if (e.key === "Escape") { a.value = urls.get(s.id) || s.url; a.blur(); }
  };
  const mk = (txt, label, fn, html) => { const b = el("button", null, html ? null : txt); if (html) b.innerHTML = html; b.title = label; b.setAttribute("aria-label", label); b.onclick = fn; return b; };
  // sound: speaker button = mute, slider = volume (kept per site)
  const au = aud(s.id);
  const spk = mk("", "Mute / unmute", () => { au.m = !au.m; if (!au.m && au.v === 0) au.v = 50; paintAudio(); changed(); }, ICON_VOL.on); spk.classList.add("spk");
  const vol = el("input", "vol"); vol.type = "range"; vol.min = 0; vol.max = 100; vol.step = 1; vol.setAttribute("aria-label", "Volume for " + s.name);
  vol.oninput = () => { au.v = +vol.value; au.m = au.v === 0; paintAudio(); changed(); };
  const paintAudio = () => {
    vol.value = au.m ? 0 : au.v; vol.style.setProperty("--p", (au.m ? 0 : au.v) + "%");
    spk.innerHTML = ICON_VOL[au.m || au.v === 0 ? "off" : au.v < 50 ? "low" : "on"]; spk.setAttribute("aria-pressed", au.m ? "true" : "false");
    spk.title = au.m ? "Unmute" : "Mute"; vol.title = "Volume " + (au.m ? 0 : au.v) + "%";
  };
  const changed = () => { store.set("fa.audio", audio); pushViews(); };
  paintAudio();
  const mb = mk("", "Make this the main pane", () => { mainId = mainId === s.id ? null : s.id; render(); }, ICON_MAIN); mb.classList.add("mainBtn");
  bar.append(t, a, spk, vol, mb,
    mk("↻", "Reload", () => arena.reload(s.id)),
    mk("⛶", "Maximize", () => { maxId = maxId === s.id ? null : s.id; render(); }),
    mk("✕", "Close", () => toggle(s.id)));
  bar.addEventListener("pointerdown", () => arena.focusPane(s.id), true);
  p.append(bar, el("div", "slot"));
  return p;
}
function render() {
  const m = $("#stage");
  for (const [id, p] of panes) if (!open.includes(id)) { p.remove(); panes.delete(id); urls.delete(id); }
  if (maxId && !open.includes(maxId)) maxId = null;
  m.querySelector(".empty")?.remove();
  open.forEach(id => {
    let p = panes.get(id);
    if (!p) { const s = find(id); if (!s) return; p = makePane(s); panes.set(id, p); }
    m.append(p);
  });
  if (mainId && !open.includes(mainId)) mainId = null;
  const others = open.filter(id => id !== mainId), mainOn = !!mainId && !maxId && others.length > 0;
  panes.forEach((p, id) => {
    p.hidden = !!maxId && id !== maxId;
    p.style.gridColumn = p.style.gridRow = "";
    const mbtn = p.querySelector(".mainBtn"); if (mbtn) { mbtn.setAttribute("aria-pressed", id === mainId ? "true" : "false"); mbtn.title = id === mainId ? "Back to the normal layout" : "Make this the main pane"; }
  });
  if (!open.length) {
    const e = el("div", "empty");
    e.innerHTML = sites.length ? "<h2>Pick a site</h2>Tap a site above to play it here. Open several to watch side by side."
      : "<h2>Add your first site</h2>Press Add site, enter a name and URL, then tap it to play.";
    m.append(e); m.style.gridTemplateColumns = "1fr";
  } else {
    if (mainOn) {   // one big pane on the left, the others stacked in a narrow column on the right
      m.style.gridTemplateColumns = "minmax(0,3fr) minmax(0,1fr)";
      m.style.gridTemplateRows = "repeat(" + others.length + ",minmax(0,1fr))";
      const mp = panes.get(mainId); if (mp) { mp.style.gridColumn = "1"; mp.style.gridRow = "1 / span " + others.length; }
      others.forEach(id => { const op = panes.get(id); if (op) op.style.gridColumn = "2"; });
    } else {
      const cols = maxId ? 1 : (layout || Math.ceil(Math.sqrt(open.length)));
      m.style.gridTemplateColumns = "repeat(" + cols + ",minmax(0,1fr))";
      m.style.gridTemplateRows = "";
      m.style.gridAutoRows = "minmax(0,1fr)";
    }
  }
  paintFocus();
  requestAnimationFrame(pushViews);
}
function pushViews() {
  const list = [];
  open.forEach(id => {
    const p = panes.get(id), s = find(id); if (!p || !s) return;
    const r = p.querySelector(".slot").getBoundingClientRect();
    const au = aud(id);
    list.push({ id, url: s.url, x: r.left, y: r.top, width: r.width, height: r.height, visible: !p.hidden && r.width > 0, muted: au.m, volume: au.m ? 0 : au.v / 100 });
  });
  arena.update(list);
}
function sync() { save(); drawNav(); render(); drawLayout(); }
function toggle(id) {
  const opening = !open.includes(id);
  open = opening ? [...open, id] : open.filter(i => i !== id);
  if (opening && S.links.openInMain) mainId = id;   // Settings > Links: new sites open as the main pane
  if (!open.includes(id)) { tmp = tmp.filter(t => t.id !== id); if (maxId === id) maxId = null; } // closing a yellow tab removes it
  sync();
}

/* ---------- site menu + quick access panel ---------- */
// The real pages are native views drawn above this page, so while a menu, panel or dialog is open they are swapped for a
// frozen picture of themselves (the sites stay visible instead of going black) and put back afterwards.
let frozen = false, pendingOpen = 0, menuTok = 0, panelTok = 0;
const needOverlay = () => !!menuEl || !!panelEl || dlg.open || msgDlg.open || !!document.querySelector("dialog[open]") || dpiNeed || pendingOpen > 0;
async function freeze() {
  if (frozen) return; frozen = true;
  let shots = []; try { shots = await arena.freeze(); } catch {}
  if (!frozen) return;
  for (const s of shots) { const p = panes.get(s.id); if (p) p.querySelector(".slot").style.backgroundImage = "url(" + s.data + ")"; }
  await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
  if (frozen) arena.overlay(true);
}
function refreshOverlay() {
  if (needOverlay()) return;
  frozen = false; arena.overlay(false);
  setTimeout(() => { if (!frozen) panes.forEach(p => { p.querySelector(".slot").style.backgroundImage = ""; }); }, 90);
}
function closeMenu(keep) { menuTok++; if (menuEl) { menuEl.remove(); menuEl = null; } if (!keep) refreshOverlay(); }
async function showMenu(btn, s) {
  const again = menuEl && menuEl.dataset.id === s.id; closeMenu(true); if (again) { refreshOverlay(); return; }
  const tok = ++menuTok; pendingOpen++;
  try { await freeze(); } finally { pendingOpen--; }
  if (tok !== menuTok) { refreshOverlay(); return; }
  const m = el("div", "menu"); m.setAttribute("role", "menu"); m.dataset.id = s.id;
  const item = (icon, text, fn, cls) => {
    const b = el("button", cls || null); b.setAttribute("role", "menuitem"); b.innerHTML = ICON[icon]; b.append(el("span", null, text));
    b.onclick = () => { closeMenu(true); fn(); refreshOverlay(); }; m.append(b);
  };
  item("edit", "Edit", () => openDlg(s));
  item(s.quick ? "minus" : "bolt", s.quick ? "Remove from quick access" : "Add to quick access", () => toggleQuick(s));
  m.append(el("div", "sep"));
  item("trash", "Delete", () => removeSite(s), "danger");
  document.body.append(m); menuEl = m;
  const r = btn.getBoundingClientRect();
  m.style.top = Math.max(8, Math.min(r.bottom + 6, innerHeight - m.offsetHeight - 8)) + "px";
  m.style.left = Math.max(8, Math.min(r.left, innerWidth - m.offsetWidth - 8)) + "px";
  m.querySelector("button").focus();
}
function toggleQuick(s) { if (s.quick) delete s.quick; else s.quick = true; sync(); }

function placePanel() {
  const r = $("#quickBtn").getBoundingClientRect();
  panelEl.style.top = r.bottom + 10 + "px"; panelEl.style.right = Math.max(12, innerWidth - r.right) + "px";
}
function drawQuickList() {
  const list = panelEl.querySelector(".qlist"); list.textContent = "";
  const q = sites.filter(s => s.quick);
  if (!q.length) { list.append(el("div", "qempty", "Nothing here yet. Open a site’s ⋮ menu and choose Add to quick access.")); return; }
  q.forEach(s => {
    const row = el("div", "qrow"); if (open.includes(s.id)) row.classList.add("on");
    const go = el("button", "go"), tx = el("span", "txt");
    tx.append(el("b", null, s.name), el("small", null, hostOf(s.url)));
    go.append(avatar(s.name), tx); { const hd = hdot(s); if (hd) go.append(hd); } if (open.includes(s.id)) go.append(el("span", "dot"));
    go.onclick = () => { closePanel(); toggle(s.id); };
    row.append(go, optBtn(s)); list.append(row);
  });
}
async function openPanel() {
  if (panelEl) { closePanel(); return; }
  closeMenu(true);
  const tok = ++panelTok; pendingOpen++;
  try { await freeze(); } finally { pendingOpen--; }
  if (tok !== panelTok) { refreshOverlay(); return; }
  const p = el("div", "qpanel"); p.setAttribute("role", "dialog"); p.setAttribute("aria-label", "Quick access");
  p.innerHTML = '<div class="qhead"><h3>Quick access</h3><p>Sites you use less often</p></div><div class="qlist"></div>';
  document.body.append(p); panelEl = p;
  $("#quickBtn").setAttribute("aria-expanded", "true");
  drawQuickList(); placePanel();
}
function closePanel() {
  panelTok++;
  if (!panelEl) return;
  panelEl.remove(); panelEl = null; $("#quickBtn").setAttribute("aria-expanded", "false"); refreshOverlay();
}
$("#quickBtn").onclick = e => { e.stopPropagation(); openPanel(); };
document.addEventListener("click", e => { closeMenu(); if (panelEl && !e.target.closest(".qpanel,.menu,#quickBtn")) closePanel(); });
document.addEventListener("keydown", e => { if (e.key === "Escape") { if (menuEl) closeMenu(); else closePanel(); } });
async function removeSite(s) {
  if (!(await ask({ title: "Delete site", text: "Delete " + s.name + " from your list?", ok: "Delete", danger: true }))) return;
  if (s.rid && !deleted.includes(s.rid)) { deleted.push(s.rid); store.set("fa.deleted", deleted); }
  sites = sites.filter(t => t.id !== s.id); open = open.filter(i => i !== s.id); sync();
}

/* ---------- add / edit / add-from-yellow-tab dialog ---------- */
async function openDlg(s, fromTmp) {
  pendingOpen++;
  editing = s || null; promoting = fromTmp || null; $("#form").reset();
  $("#dlgTitle").textContent = s ? "Edit site" : fromTmp ? "Add to saved sites" : "Add site";
  $("#del").hidden = !s;
  if (s) { $("#name").value = s.name; $("#url").value = s.url; }
  if (fromTmp) $("#url").value = urls.get(fromTmp.id) || fromTmp.url; // taken from the tab’s address bar; the name is left for you
  try { await freeze(); } finally { pendingOpen--; }
  dlg.showModal(); $("#name").focus();
}
dlg.addEventListener("close", refreshOverlay);

/* ---------- in-app message / confirm box (replaces the Windows pop-ups) ---------- */
let msgResolve = null;
async function ask({ title, text, ok = "OK", cancel = "Cancel", danger = false, info = false }) {
  pendingOpen++;
  $("#msgTitle").textContent = title || ""; $("#msgText").textContent = text || "";
  const yes = $("#msgYes"), no = $("#msgNo");
  yes.textContent = ok; yes.className = danger ? "primary danger" : "primary"; no.textContent = cancel; no.hidden = !!info;
  const answer = new Promise(r => { msgResolve = r; });
  try { await freeze(); } finally { pendingOpen--; }
  msgDlg.showModal(); (danger ? no : yes).focus();
  return answer;
}
function closeMsg(v) { const r = msgResolve; msgResolve = null; if (msgDlg.open) msgDlg.close(); if (r) r(v); }
$("#msgYes").onclick = () => closeMsg(true); $("#msgNo").onclick = () => closeMsg(false);
msgDlg.addEventListener("cancel", e => { e.preventDefault(); closeMsg(false); });   // Esc
msgDlg.addEventListener("close", () => { const r = msgResolve; msgResolve = null; if (r) r(false); refreshOverlay(); });
$("#add").onclick = () => openDlg();
$("#del").onclick = () => { const s = editing; dlg.close(); if (s) removeSite(s); };
$("#cancel").onclick = () => dlg.close();
$("#form").onsubmit = () => {
  const url = clean($("#url").value); if (!url) return;
  const name = $("#name").value.trim();
  if (editing) {
    const old = editing.url; editing.name = name; editing.url = url; if (url !== old) health.delete(editing.id);
    if (editing.rid) editing.edited = true;   // your own version is kept when the link list updates
    const p = panes.get(editing.id);
    if (p) { p.querySelector("b").textContent = name; if (url !== old) arena.navigate(editing.id, url); }
  } else if (promoting) {
    const t = promoting; tmp = tmp.filter(i => i.id !== t.id);
    sites.push({ id: t.id, name, url });
    const p = panes.get(t.id);
    if (p) { p.querySelector("b").textContent = name; if (url !== (urls.get(t.id) || t.url)) arena.navigate(t.id, url); }
    if (!open.includes(t.id)) open.push(t.id);
  } else {
    const s = { id: uid(), name, url }; sites.push(s); open.push(s.id);
  }
  sync();
};

/* ---------- toolbar ---------- */
$("#layout").onclick = e => { const b = e.target.closest("button"); if (b) { layout = +b.dataset.l; mainId = null; sync(); } };
$("#closeAll").onclick = () => { open = []; tmp = []; maxId = null; mainId = null; sync(); };
const exportSites = () => arena.exportSites(JSON.stringify(sites.map(({ name, url, quick }) => quick ? { name, url, quick } : { name, url }), null, 2));
const importSites = async () => {
  const text = await arena.importSites(); if (!text) return;
  try {
    const list = JSON.parse(text); if (!Array.isArray(list)) throw 0;
    list.forEach(x => { const url = x && x.url && clean(x.url); if (url && x.name && !sites.some(s => s.url === url)) sites.push({ id: uid(), name: String(x.name).slice(0, 40), url, ...(x.quick ? { quick: true } : {}) }); });
    sync();
  } catch { await ask({ title: "Import failed", text: "That file isn’t a valid sites list.", info: true }); }
};

/* ---------- drag a site button to change the order ---------- */
(() => {
  const nav = $("#sites"); let d = null;
  nav.addEventListener("pointerdown", e => {
    const c = e.target.closest(".chip");
    if (!c || e.button || c.classList.contains("tmp") || e.target.closest(".opt")) return;
    d = { c, x: e.clientX, y: e.clientY, on: false };
  });
  addEventListener("pointermove", e => {
    if (!d) return;
    if (!d.on) { if (Math.hypot(e.clientX - d.x, e.clientY - d.y) < 6) return; d.on = true; dragged = true; closeMenu(); d.c.classList.add("drag"); document.body.style.userSelect = "none"; }
    let before = null;
    for (const o of nav.children) {
      if (o === d.c || o.classList.contains("tmp")) continue;
      const r = o.getBoundingClientRect();
      if (e.clientY < r.top || (e.clientY <= r.bottom && e.clientX < r.left + r.width / 2)) { before = o; break; }
    }
    if (!before) before = nav.querySelector(".chip.tmp"); // yellow tabs stay last
    if (before !== d.c.nextSibling) nav.insertBefore(d.c, before);
  });
  const end = () => {
    if (!d) return; const was = d.on; d.c.classList.remove("drag"); document.body.style.userSelect = ""; d = null;
    if (was) {
      const order = [...nav.querySelectorAll(".chip:not(.tmp)")].map(c => c.dataset.id);
      sites = [...order.map(id => sites.find(s => s.id === id)).filter(Boolean), ...sites.filter(s => !order.includes(s.id))];
      save(); drawNav(); setTimeout(() => { dragged = false; }, 60);
    }
  };
  addEventListener("pointerup", end); addEventListener("pointercancel", end);
})();

/* ---------- messages from the app ---------- */
arena.onUrl(({ id, url }) => {
  urls.set(id, url);
  const a = panes.get(id)?.querySelector("input");
  if (a && document.activeElement !== a) a.value = url;
});
arena.onOpen(({ from, url }) => { // a link wanted a new tab → yellow tab at the end of the bar, pane next to the one you clicked in
  const u = clean(url); if (!u) return;
  let host; try { host = new URL(u).hostname.replace(/^www\./, ""); } catch { return; }
  let s = tmp.find(t => t.url === u);
  if (!s) { s = { id: "t" + uid(), name: host, url: u }; tmp.push(s); }
  if (!open.includes(s.id)) { const i = open.indexOf(from); open.splice(i < 0 ? open.length : i + 1, 0, s.id); if (S.links.openInMain) mainId = s.id; }
  sync();
});
arena.perf().then(m => document.body.classList.toggle("lite", m === "lite")).catch(() => {});
const DPI_TIP = {
  on: "GoodbyeDPI is on.", starting: "GoodbyeDPI is starting…", off: "GoodbyeDPI is off.", "off:manual": "GoodbyeDPI is turned off.",
  "off:missing": "GoodbyeDPI is off: its files were not found in the app folder.",
  "off:not-admin": "GoodbyeDPI is off: the app has no administrator rights. Reopen it and accept the Windows prompt.",
  "off:exited": "GoodbyeDPI stopped right after starting. Windows Security or an antivirus may block its driver. See dpi.log.",
};
const dpiWrap = $("#dpiWrap"), dpiMenu = $("#dpiMenu");
let dpiLast = null;
function showDpi(s) {
  dpiLast = s;
  const b = $("#dpi"); if (!s || s.state === "na" || s.state === "disabled") { dpiWrap.hidden = true; return; }   // disabled in Settings: no indicator
  dpiWrap.hidden = false; b.dataset.state = s.state;
  const tip = DPI_TIP[s.state === "off" && s.detail ? "off:" + s.detail : s.state] || DPI_TIP[s.state];
  b.title = tip; b.setAttribute("aria-label", tip);
  const busy = s.state === "starting", tg = $("#dpiToggle"), label = s.state === "on" ? "Turn off" : "Turn on";
  tg.dataset.state = s.state === "off" && s.detail && s.detail !== "manual" ? "error" : s.state;
  tg.title = label; tg.setAttribute("aria-label", label); tg.disabled = busy;
  $("#dpiRestart").disabled = busy || s.state !== "on";
}
// hover (or click / keyboard) opens a small menu right under the DPI button
let dpiTimer = 0;
async function openDpi() {
  clearTimeout(dpiTimer); if (!dpiMenu.hidden) return;
  dpiMenu.hidden = false; $("#dpi").setAttribute("aria-expanded", "true");
  // the menu is short, but if it ever reaches down over the native site views it would be hidden: then use the frozen-picture trick
  if (dpiMenu.getBoundingClientRect().bottom > $("#stage").getBoundingClientRect().top) { dpiNeed = true; await freeze(); if (dpiMenu.hidden) { dpiNeed = false; refreshOverlay(); } }
}
function closeDpi(now) {
  clearTimeout(dpiTimer);
  const go = () => { dpiMenu.hidden = true; $("#dpi").setAttribute("aria-expanded", "false"); if (dpiNeed) { dpiNeed = false; refreshOverlay(); } };
  if (now) go(); else dpiTimer = setTimeout(go, 220);
}
dpiWrap.addEventListener("mouseenter", openDpi);
dpiWrap.addEventListener("mouseleave", () => closeDpi(false));
dpiWrap.addEventListener("focusout", e => { if (!dpiWrap.contains(e.relatedTarget)) closeDpi(true); });
$("#dpi").onclick = () => { if (dpiMenu.hidden) openDpi(); else closeDpi(true); };
$("#dpiToggle").onclick = () => { if ($("#dpi").dataset.state === "on") arena.dpiStop(); else arena.dpiRetry(); closeDpi(true); };
$("#dpiRestart").onclick = () => { arena.dpiRetry(); closeDpi(true); };
document.addEventListener("keydown", e => { if (e.key === "Escape" && !dpiMenu.hidden) closeDpi(true); });
arena.onDpi(showDpi); arena.dpiStatus().then(showDpi).catch(() => {});
arena.onRelayout(() => requestAnimationFrame(pushViews));

/* ---------- link list from GitHub ---------- */
const normUrl = u => { try { return new URL(/^https?:\/\//i.test(u) ? u : "https://" + u).href; } catch { return u; } };
function applyRemote(r) {
  if (!r || !Array.isArray(r.sites)) return 0;
  let n = 0;
  for (const d of r.sites) {
    const url = normUrl(d.url);
    let s = sites.find(x => x.rid === d.id)
      || sites.find(x => !x.rid && x.name.toLowerCase() === d.name.toLowerCase())
      || sites.find(x => !x.rid && normUrl(x.url) === url);
    if (!s) {
      if (deleted.includes(d.id) && !d.force) continue;
      sites.push({ id: uid(), rid: d.id, name: d.name, url, ...(d.quick ? { quick: true } : {}) }); n++; continue;
    }
    if (!s.rid) s.rid = d.id;
    if (s.edited && !d.force) continue;                 // keep the user's own edit unless the list forces it
    if (s.url !== url || s.name !== d.name) {
      const moved = s.url !== url; s.url = url; s.name = d.name; n++;
      const p = panes.get(s.id);
      if (p) { p.querySelector("b").textContent = s.name; if (moved) arena.navigate(s.id, url); }
    }
    if (d.force) { delete s.edited; if (d.hasQuick) { if (d.quick) s.quick = true; else delete s.quick; } }
  }
  for (const rid of r.remove || []) {
    const s = sites.find(x => x.rid === rid);
    if (s) { sites = sites.filter(x => x !== s); open = open.filter(i => i !== s.id); n++; }
  }
  if (n) sync();
  return n;
}
let updTimer = 0;
function setUpd(text, busy) {
  const t = $("#lkStatus"), b = $("#lkCheck");
  if (t) t.textContent = text || ""; if (b) b.disabled = !!busy;
  clearTimeout(updTimer); if (text && !busy) updTimer = setTimeout(() => { const e = $("#lkStatus"); if (e) e.textContent = ""; }, 8000);
}
let remoteChatOff = false;
const paintChatBtn = () => { $("#chatBtn").hidden = remoteChatOff || S.chat.enabled === false; };
function applyChatCfg(c) { remoteChatOff = !!c && c.enabled === false; paintChatBtn(); }
let checking = false;
async function syncRemote(manual) {
  if (checking) return; checking = true;
  if (manual) setUpd("Checking…", true);
  let r = null; try { r = await arena.remoteSites(!!manual); } catch {}
  checking = false;
  if (!r) { setUpd(manual ? "Couldn’t reach the update server" : ""); return; }
  const n = applyRemote(r);
  setUpd(n ? "Updated " + n + " link" + (n > 1 ? "s" : "") : manual ? "Links are up to date" : "");
  arena.chatConfig().then(applyChatCfg).catch(() => {});
}
arena.chatConfig().then(applyChatCfg).catch(() => {});
// show the last downloaded list at once, then ask GitHub whether anything changed
arena.remoteCached().then(r => { if (r) { applyRemote(r); applyChatCfg(r.chat); } }).catch(() => {}).finally(() => syncRemote(false));
let linkTimer = 0;
function scheduleLinks() {   // Settings > Links: every 15 minutes / every hour / manual (it still looks once at start)
  clearInterval(linkTimer); const m = +S.links.every;
  if (m > 0) linkTimer = setInterval(() => syncRemote(false), m * 60 * 1000);
}
scheduleLinks();

/* ---------- link health check ---------- */
let hltTimer = 0, hltBusy = false;
function setHlt(text, busy) {
  $("#hltTxt").textContent = text || "Health"; $("#hlt").classList.toggle("busy", !!busy);
  clearTimeout(hltTimer); if (text && !busy) hltTimer = setTimeout(() => { $("#hltTxt").textContent = "Health"; }, 9000);
}
async function checkHealth() {
  if (hltBusy) return; hltBusy = true;
  const list = [...sites], queue = [...list]; let done = 0;
  health.clear(); drawNav(); setHlt("Checking 0/" + list.length, true);
  const worker = async () => {
    while (queue.length) {
      const s = queue.shift(); let r;
      try { r = await arena.health(s.url); } catch { r = { state: "down", note: "Check failed" }; }
      if (sites.includes(s)) health.set(s.id, r);
      setHlt("Checking " + (++done) + "/" + list.length, true); drawNav();
    }
  };
  await Promise.all([worker(), worker(), worker(), worker()]);
  hltBusy = false;
  const bad = list.filter(s => ["down", "dead"].includes((health.get(s.id) || {}).state)).length;
  setHlt(bad ? bad + " link" + (bad > 1 ? "s" : "") + " not working" : "All links reachable");
}
$("#hlt").onclick = checkHealth;

/* ---------- chat button ---------- */
$("#chatBtn").onclick = () => arena.chatToggle();
arena.onChatState(({ open }) => {
  $("#chatBtn").setAttribute("aria-pressed", open ? "true" : "false");
  if (open) { $("#chatBadge").hidden = true; }
});
arena.onChatUnread(({ n }) => { const b = $("#chatBadge"); b.hidden = !n; b.textContent = n > 9 ? "9+" : n; });
addEventListener("resize", () => { if (panelEl) placePanel(); requestAnimationFrame(pushViews); });

/* ---------- settings changes, focus highlight ---------- */
let focusedPane = null;
function paintFocus() {   // with "only the focused pane plays sound" the pane that has the sound gets a light border
  const vis = open.filter(id => panes.get(id) && !panes.get(id).hidden);
  const eff = focusedPane && vis.includes(focusedPane) ? focusedPane : vis[0];
  panes.forEach((p, id) => p.classList.toggle("focused", !!S.audio.focusOnly && id === eff && vis.length > 1));
}
function applyCfg() {
  $("#hlt").hidden = S.links.health === "off";
  paintChatBtn(); scheduleLinks(); paintFocus();
}
arena.onFocus(id => { focusedPane = id; paintFocus(); });
arena.onCfg(d => { S = Object.assign({}, DEF, d); applyCfg(); });
applyCfg();
if (S.links.health === "startup") setTimeout(() => { if (!locked) checkHealth(); }, 5000);

/* ---------- parental passcode: nothing opens until the 4 digits are entered ---------- */
let locked = !!S.locked;
const lockEl = $("#lock"), lockIn = $("#lockIn"), lockErr = $("#lockErr");
function paintLock() { lockEl.hidden = !locked; if (locked) { lockIn.value = ""; lockIn.focus(); } }
lockIn.addEventListener("input", async () => {
  lockIn.value = lockIn.value.replace(/\D/g, "").slice(0, 4);
  if (lockIn.value.length < 4) return;
  const code = lockIn.value; lockIn.disabled = true;
  let r = { ok: false }; try { r = await arena.lockTry(code); } catch {}
  lockIn.disabled = false; lockIn.value = "";
  if (r && r.ok) { lockErr.textContent = ""; return; }
  lockErr.textContent = r && r.wait ? "Too many tries. Wait " + Math.ceil(r.wait / 1000) + " s." : "Wrong passcode.";
  lockEl.querySelector(".lcard").classList.remove("shake"); void lockEl.offsetWidth; lockEl.querySelector(".lcard").classList.add("shake"); lockIn.focus();
});
arena.onLock(v => { locked = v; paintLock(); if (!v) { requestAnimationFrame(pushViews); maybePromptUpdate(); } });
paintLock();

/* ---------- kill switch: sites.json can demand a newer version ---------- */
const blockEl = $("#block");
function paintBlock() {
  const b = S.blocked; blockEl.hidden = !b;
  if (b) $("#blockTxt").textContent = "This version (" + S.version + ") is no longer supported. Version " + b.min + " or newer is needed. Update to continue.";
}
arena.onBlocked(d => { S.blocked = d; paintBlock(); if (!d) requestAnimationFrame(pushViews); });
$("#blockBtn").onclick = async () => { $("#blockSt").textContent = "Checking…"; try { await arena.updCheck(); } catch {} setTimeout(() => { const st = lastUpd.state; $("#blockSt").textContent = st === "uptodate" ? "No update is published yet." : st === "error" ? "Could not check for updates." : st === "unsupported" ? "Updates work in the installed app only." : ""; }, 1500); };
paintBlock();

/* ---------- app update ready: Restart now / Later ---------- */
let lastUpd = { state: "idle" }, promptedFor = "";
async function maybePromptUpdate() {
  if (lastUpd.state !== "ready" || locked) return;
  const v = lastUpd.version || "new"; if (promptedFor === v) return;
  if (msgDlg.open) { setTimeout(maybePromptUpdate, 5000); return; }
  promptedFor = v;
  if (await ask({ title: "Update ready", text: "Version " + v + " is ready.", ok: "Restart now", cancel: "Later" })) arena.updInstall();   // "Later": installed when the app is closed
}
arena.onUpd(st => { lastUpd = st || lastUpd; $("#blockSt").textContent = ""; maybePromptUpdate(); });
arena.updStatus().then(st => { lastUpd = st || lastUpd; maybePromptUpdate(); }).catch(() => {});

/* ---------- bridge for settings.js ---------- */
async function showDialog(d) { pendingOpen++; try { await freeze(); } finally { pendingOpen--; } if (!d.open) d.showModal(); }
for (const id of ["#setDlg", "#blDlg"]) $(id).addEventListener("close", refreshOverlay);
window.FAI = {
  ask, showDialog, exportSites, importSites, checkHealth: () => checkHealth(), syncRemote: m => syncRemote(!!m),
  S: () => S, upd: () => lastUpd,
  resetLocal() { try { localStorage.clear(); } catch {} location.reload(); },   // sites, order, layout, sound: back to the built-in defaults
};
new ResizeObserver(() => requestAnimationFrame(pushViews)).observe($("#stage"));
sync();
})();
