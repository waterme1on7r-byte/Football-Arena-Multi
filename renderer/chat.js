"use strict";
(() => {
const $ = s => document.querySelector(s);
const el = (t, c, x) => { const e = document.createElement(t); if (c) e.className = c; if (x != null) e.textContent = x; return e; };
const store = {
  get(k, d) { try { const v = JSON.parse(localStorage.getItem(k)); return v ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};
const rid = () => {
  const b = crypto.getRandomValues(new Uint8Array(10));
  return [...b].map(x => x.toString(36).padStart(2, "0")).join("").slice(0, 16);
};
const ID = /^[\w-]{6,40}$/;
const noCtl = s => String(s).replace(/[\u0000-\u001f\u007f]/g, " ");
const hue = s => [...String(s)].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7) % 360;
const FA = window.FA_ADMIN || { configured: () => false, unlock: async () => false, unlocked: () => false, lock() {}, sign: async () => "", verify: async () => false };
const hostApi = Object.assign({ config: async () => ({}), minimize() {}, unread() {}, onVisible() {}, onSettings() {}, onNick() {}, onUnban() {}, nick() {}, admin() {} }, window.host || {});

/* ---------- tiny MQTT 3.1.1 client over WebSocket (publish / subscribe, QoS 0) ---------- */
const utf8 = s => new TextEncoder().encode(s);
const cat = (...parts) => { const n = parts.reduce((a, p) => a + p.length, 0), o = new Uint8Array(n); let i = 0; for (const p of parts) { o.set(p, i); i += p.length; } return o; };
const str = s => { const b = utf8(s); return cat(new Uint8Array([b.length >> 8, b.length & 255]), b); };
const varlen = n => { const o = []; do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 128; o.push(d); } while (n > 0); return new Uint8Array(o); };
const packet = (first, body) => cat(new Uint8Array([first]), varlen(body.length), body);

class Mqtt {
  constructor(urls, h) { this.urls = urls; this.h = h; this.i = 0; this.wait = 1000; this.buf = new Uint8Array(0); this.topics = []; this.id = 1; this.connect(); }
  connect() {
    clearTimeout(this.rt);
    const url = this.urls[this.i % this.urls.length];
    this.h.state("connecting");
    let ws; try { ws = new WebSocket(url, "mqtt"); } catch { return this.retry(); }
    this.ws = ws; ws.binaryType = "arraybuffer"; this.buf = new Uint8Array(0);
    ws.onopen = () => ws.send(packet(0x10, cat(str("MQTT"), new Uint8Array([4, 2, 0, 30]), str("fa-" + rid()))));
    ws.onmessage = e => this.feed(new Uint8Array(e.data));
    ws.onclose = ws.onerror = () => { if (this.ws === ws) { this.ws = null; clearInterval(this.ping); this.up = false; this.h.state("down"); this.retry(); } };
  }
  retry() { this.i++; this.rt = setTimeout(() => this.connect(), this.wait); this.wait = Math.min(this.wait * 1.6, 15000); }
  sub(topic) { this.topics.push(topic); if (this.up) this.send(packet(0x82, cat(new Uint8Array([0, this.id++ & 255]), str(topic), new Uint8Array([0])))); }
  pub(topic, obj, retain) { if (!this.up) return false; this.send(packet(retain ? 0x31 : 0x30, cat(str(topic), utf8(JSON.stringify(obj))))); return true; }
  send(b) { try { this.ws && this.ws.readyState === 1 && this.ws.send(b); } catch {} }
  feed(chunk) {
    this.buf = cat(this.buf, chunk);
    for (;;) {
      const b = this.buf; if (b.length < 2) return;
      let len = 0, mul = 1, k = 1, d;
      do { if (k >= b.length) return; d = b[k++]; len += (d & 127) * mul; mul *= 128; } while (d & 128 && k < 5);
      if (b.length < k + len) return;
      const body = b.slice(k, k + len), type = b[0] >> 4, flags = b[0] & 15;
      this.buf = b.slice(k + len);
      if (type === 2) {            // CONNACK
        if (body[1] === 0) {
          this.up = true; this.wait = 1000; this.h.state("up");
          this.topics.forEach(t => this.send(packet(0x82, cat(new Uint8Array([0, this.id++ & 255]), str(t), new Uint8Array([0])))));
          clearInterval(this.ping); this.ping = setInterval(() => this.send(new Uint8Array([0xC0, 0])), 20000);
          this.h.open();
        } else { try { this.ws.close(); } catch {} }
      } else if (type === 3) {     // PUBLISH
        const tl = (body[0] << 8) | body[1], topic = new TextDecoder().decode(body.slice(2, 2 + tl));
        const qos = (flags >> 1) & 3, off = 2 + tl + (qos ? 2 : 0);
        this.h.message(topic, body.slice(off));
      }
    }
  }
}

/* ---------- chat ---------- */
let cid = store.get("cid", ""); if (!ID.test(cid)) { cid = rid(); store.set("cid", cid); }
let nick = store.get("nick", "");
let muted = new Set(store.get("muted", []));
let isOpen = true, unread = 0, replyTo = null, lastSent = 0, mq = null, room = "", T = {};
// chat protocol version: every message carries it ("v"). A client that meets a message from a newer version it cannot show
// (unknown kind of message) shows "Update to see this" instead of dropping it silently. Messages from older clients have no "v" = 1.
const CHAT_V = 2;
let notify = true, soundOn = false, lastBeep = 0, actx = null;   // set in Settings > Chat
function beep() {
  const now = Date.now(); if (now - lastBeep < 1500) return; lastBeep = now;
  try {
    actx = actx || new (window.AudioContext || window.webkitAudioContext)(); if (actx.state === "suspended") actx.resume();
    const o = actx.createOscillator(), g = actx.createGain(), t = actx.currentTime;
    o.type = "sine"; o.frequency.setValueAtTime(880, t); o.frequency.setValueAtTime(1175, t + 0.09);
    g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.12, t + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t + 0.28);
    o.connect(g); g.connect(actx.destination); o.start(t); o.stop(t + 0.3);
  } catch {}
}
const msgs = [], seen = new Set(), peers = new Map(), flood = new Map();

/* ---------- protected admin name + moderation ----------
   The name WTMLJR can only be used by whoever knows the admin password (see adminkey.js). Everybody else's client
   throws away any message that uses that name unless it carries the admin's signature, so it cannot be faked.
   Bans and deleted messages are one signed list that the admin publishes; every client checks the signature. */
const ADMIN_NAMES = ["WTMLJR", "LEONBISHOP"];   // names only the matching password can use (keys live in adminkey.js)
const LOOK = { "а": "a", "е": "e", "о": "o", "р": "p", "с": "c", "т": "t", "м": "m", "ј": "j", "г": "r", "ԝ": "w", "ӏ": "l", "і": "l", "ı": "l", "ο": "o", "τ": "t", "μ": "m", "ρ": "p", "ι": "l", "һ": "h", "ѕ": "s", "ո": "n", "в": "b" };
const skel = s => String(s).normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^\p{L}\p{N}]/gu, "").replace(/./gu, c => LOOK[c] || c).replace(/[i1]/g, "l").replace(/0/g, "o");
const ADMINS = ADMIN_NAMES.map(n => ({ n, k: skel(n) }));
const reserved = n => { const k = skel(n), a = ADMINS.find(x => k.includes(x.k)); return a ? a.n : ""; };   // which protected name this is (or "")
const sigText = m => [m.i, m.u, m.n, m.t, m.ts].join("|");
let isAdmin = false, mod = { seq: 0, bans: [], dels: [], raw: null };
const banned = new Set(), deleted = new Set();

const list = $("#list");
const online = () => { const now = Date.now(); let n = 1; for (const [u, t] of peers) if (u !== cid && now - t < 75000) n++; return n; };
function drawOnline() { $("#online").textContent = mq && mq.up ? "· " + online() + " online" : ""; }
function setState(s) {
  const st = $("#status"); $(".live").classList.toggle("on", s === "up");
  st.hidden = s === "up"; st.textContent = s === "connecting" ? "Connecting…" : "Connection lost. Reconnecting…";
  drawOnline();
}
const timeText = ts => new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

function render() {
  const stick = list.scrollHeight - list.scrollTop - list.clientHeight < 70;
  list.textContent = "";
  const shown = msgs.filter(m => !muted.has(m.u) && !banned.has(m.u) && !deleted.has(m.i)).slice(-150);
  if (!shown.length) list.append(el("div", "empty", "No messages yet. Say hi to everyone watching 👋"));
  for (const m of shown) {
    const row = el("div", "msg" + (m.u === cid ? " me" : "") + (m.adm ? " isadm" : ""));
    const av = el("div", "av", (m.n.match(/[\p{L}\p{N}]/u) || ["?"])[0].toLocaleUpperCase("tr")); av.style.setProperty("--h", hue(m.u));
    const body = el("div", "body"), head = el("div", "head");
    head.append(el("span", "nm", m.n)); if (m.adm) head.append(el("span", "adm", "ADMIN")); head.append(el("span", "tm", timeText(m.ts)));
    body.append(head);
    if (m.r) { const q = el("div", "quote"); q.append(el("b", null, m.r.n), document.createTextNode(m.r.t)); body.append(q); }
    body.append(el("div", "txt" + (m.unsupported ? " unsup" : ""), m.t));
    const acts = el("div", "acts");
    const rb = el("button", null, "↩"); rb.title = "Reply"; rb.onclick = () => setReply(m);
    acts.append(rb);
    if (m.u !== cid) { const mb = el("button", null, "🚫"); mb.title = "Mute " + m.n; mb.onclick = () => mute(m.u); acts.append(mb); }
    if (isAdmin) {
      const db = el("button", null, "🗑"); db.title = "Delete message"; db.onclick = () => delMessage(m); acts.append(db);
      if (m.u !== cid && !m.adm) {
        const bb = el("button", null, "⛔"); bb.title = "Ban " + m.n;
        bb.onclick = async () => { if (await confirmBox("Ban " + m.n + "?", "Their messages disappear for everyone, and they cannot write here any more.", "Ban")) banUser(m); };
        acts.append(bb);
      }
    }
    row.append(av, body, acts); list.append(row);
  }
  if (stick) list.scrollTop = list.scrollHeight;
  const mc = muted.size; $("#muteBar").hidden = !mc; $("#muteTxt").textContent = mc + " muted";
}
function mute(u) { muted.add(u); store.set("muted", [...muted]); render(); }
$("#unmute").onclick = () => { muted.clear(); store.set("muted", []); render(); };
function setReply(m) { replyTo = { n: m.n, t: m.t.slice(0, 100) }; $("#reply").hidden = false; $("#reply b").textContent = m.n; $("#reply span").textContent = m.t.slice(0, 100); $("#inp").focus(); }
function clearReply() { replyTo = null; $("#reply").hidden = true; }
$("#replyX").onclick = clearReply;

function cleanMsg(o) {
  if (!o || typeof o !== "object") return null;
  const v = Number.isInteger(o.v) && o.v >= 1 && o.v < 1000 ? o.v : 1;
  let t = typeof o.t === "string" ? noCtl(o.t).trim().slice(0, 300) : "";
  const n = typeof o.n === "string" ? noCtl(o.n).trim().slice(0, 20) : "";
  if (!n || typeof o.i !== "string" || !ID.test(o.i) || typeof o.u !== "string" || !ID.test(o.u) || !Number.isFinite(o.ts)) return null;
  // a message of a kind this version does not know (or without readable text) from a newer app: say so instead of hiding it
  const unsupported = v > CHAT_V && ((typeof o.k === "string" && o.k !== "") || !t);
  if (unsupported) t = "Update to see this";
  if (!t) return null;
  let r = null;
  if (!unsupported && o.r && typeof o.r === "object" && typeof o.r.n === "string" && typeof o.r.t === "string") r = { n: noCtl(o.r.n).trim().slice(0, 20), t: noCtl(o.r.t).trim().slice(0, 100) };
  const g = !unsupported && typeof o.g === "string" && /^[A-Za-z0-9+/=]{80,100}$/.test(o.g) ? o.g : "";
  return { i: o.i, u: o.u, n, t, ts: o.ts, r, g, v: Math.min(v, CHAT_V), ...(unsupported ? { unsupported: true } : {}) };
}

// the protected name is only accepted together with the admin's signature
async function vetAdmin(m) {
  const who = reserved(m.n);
  if (!who) { m.g = ""; return true; }
  if (m.n !== who || !m.g || !(await FA.verify(sigText(m), m.g, who))) return false;
  m.adm = true; m.sts = m.ts; return true;
}
async function acceptMod(o) {   // o = { s: json text, g: signature }
  const a = o && typeof o.a === "string" ? o.a : "WTMLJR";   // which admin signed the list (older lists have no name: the first admin)
  if (!o || typeof o.s !== "string" || o.s.length > 40000 || !ADMIN_NAMES.includes(a) || !(await FA.verify(o.s, o.g, a))) return false;
  let d; try { d = JSON.parse(o.s); } catch { return false; }
  if (!d || !Number.isFinite(d.seq) || d.seq <= mod.seq) return false;
  const bans = (Array.isArray(d.bans) ? d.bans : []).slice(0, 300).filter(b => b && typeof b.u === "string" && ID.test(b.u))
    .map(b => ({ u: b.u, n: typeof b.n === "string" ? noCtl(b.n).slice(0, 20) : "", ts: Number(b.ts) || 0 }));
  const dels = (Array.isArray(d.dels) ? d.dels : []).slice(0, 400).filter(x => typeof x === "string" && ID.test(x));
  mod = { seq: d.seq, bans, dels, raw: { s: o.s, g: o.g, a } };
  banned.clear(); deleted.clear(); bans.forEach(b => banned.add(b.u)); dels.forEach(x => deleted.add(x));
  for (const u of banned) peers.delete(u);
  store.set("mod", mod.raw);
  render(); drawBanState(); drawAdm(); drawOnline();
  return true;
}
async function publishMod(bans, dels) {   // admin only: sign the new list, apply it here and publish it (kept by the broker)
  if (!isAdmin) return;
  const seq = Math.max(mod.seq + 1, Date.now());
  const s = JSON.stringify({ seq, bans, dels: dels.slice(-400) });
  const o = { s, g: await FA.sign(s), a: FA.current() };
  if (await acceptMod(o) && mq) mq.pub(T.mod, o, true);
}
const delMessage = m => publishMod(mod.bans, [...new Set([...mod.dels, m.i])]);
const banUser = m => publishMod([...mod.bans.filter(b => b.u !== m.u), { u: m.u, n: m.n, ts: Date.now() }], mod.dels);
const unbanUser = u => publishMod(mod.bans.filter(b => b.u !== u), mod.dels);
function drawBanState() {
  const b = banned.has(cid) && !isAdmin;
  for (const id of ["#inp", "#go", "#emo"]) $(id).disabled = b;
  $("#inp").placeholder = b ? "You have been banned from this chat." : "Write a message…";
}
function reportAdmin() { try { hostApi.admin({ admin: isAdmin, bans: mod.bans.map(b => ({ u: b.u, n: b.n })) }); } catch {} }
function drawAdm() {
  reportAdmin();
  const box = $("#admList"); if (!box) return; box.textContent = "";
  if (!mod.bans.length) { box.append(el("div", "empty", "No one is banned.")); return; }
  for (const b of mod.bans) {
    const row = el("div", "brow"), nm = el("span", "bn", b.n || "unknown"), un = el("button", null, "Unban");
    un.onclick = () => unbanUser(b.u); row.append(nm, un); box.append(row);
  }
}
let cfmDone = null;
function confirmBox(title, text, ok) {
  $("#cfmT").textContent = title; $("#cfmP").textContent = text; $("#cfmYes").textContent = ok; $("#cfm").hidden = false; $("#cfmNo").focus();
  return new Promise(res => { cfmDone = res; });
}
function closeCfm(v) { $("#cfm").hidden = true; const f = cfmDone; cfmDone = null; if (f) f(v); }
$("#cfmYes").onclick = () => closeCfm(true); $("#cfmNo").onclick = () => closeCfm(false);
function add(m, live) {
  if (seen.has(m.i)) return false;
  seen.add(m.i); if (seen.size > 800) seen.delete(seen.values().next().value);
  const now = Date.now();
  m.ts = live ? (Math.abs(m.ts - now) > 600000 ? now : m.ts) : Math.min(m.ts, now + 60000);
  msgs.push(m); msgs.sort((a, b) => a.ts - b.ts); if (msgs.length > 300) msgs.shift();
  return true;
}
function floodOk(u) {
  const now = Date.now(), a = (flood.get(u) || []).filter(t => now - t < 6000);
  a.push(now); flood.set(u, a); return a.length <= 8;
}

async function onMessage(topic, bytes) {
  let o; try { o = JSON.parse(new TextDecoder().decode(bytes)); } catch { return; }
  if (topic === T.m) {
    const m = cleanMsg(o); if (!m || m.u === cid || banned.has(m.u) || deleted.has(m.i) || !floodOk(m.u)) return;
    if (!(await vetAdmin(m))) return;
    peers.set(m.u, Date.now());
    if (add(m, true)) {
      render();
      if (notify && !muted.has(m.u)) {
        if (!isOpen) { unread++; hostApi.unread(unread); }
        if (soundOn) beep();
      }
    }
  } else if (topic === T.p) {
    if (o && typeof o.u === "string" && ID.test(o.u) && !banned.has(o.u)) { peers.set(o.u, Date.now()); drawOnline(); }
  } else if (topic === T.s) {      // someone just joined and asks for recent messages
    if (!o || typeof o.u !== "string" || !ID.test(o.u) || o.u === cid || banned.has(o.u)) return;
    peers.set(o.u, Date.now()); drawOnline();
    if (Math.random() < Math.min(1, 8 / Math.max(1, online() - 1))) setTimeout(presence, 100 + Math.random() * 600);   // say hello so the newcomer sees who is here
    if ((msgs.length || mod.raw) && Math.random() < Math.min(1, 3 / Math.max(1, online() - 1))) {
      setTimeout(() => mq.pub(room + "/to/" + o.u, { mod: mod.raw || undefined, h: msgs.slice(-30).map(m => ({ i: m.i, u: m.u, n: m.n, t: m.t, ts: m.sts || m.ts, r: m.r, g: m.g || undefined, v: m.v || undefined })) }), 150 + Math.random() * 700);
    }
  } else if (topic === T.to) {
    if (!o) return;
    if (o.mod) await acceptMod(o.mod);
    if (!Array.isArray(o.h)) return;
    let any = false;
    for (const x of o.h.slice(0, 40)) { const m = cleanMsg(x); if (m && m.u !== cid && !banned.has(m.u) && !deleted.has(m.i) && await vetAdmin(m) && add(m, false)) any = true; }
    if (any) render();
  } else if (topic === T.mod) {
    await acceptMod(o);
  }
}

function presence() { if (nick && mq) mq.pub(T.p, { u: cid, n: nick, ts: Date.now() }); }
async function sendMessage() {
  const inp = $("#inp"), t = noCtl(inp.value).trim().slice(0, 300);
  if (!t || !nick || (banned.has(cid) && !isAdmin)) return;
  if (reserved(nick) && !isAdmin) { askNick(true); return; }
  const now = Date.now();
  if (now - lastSent < 1200) return;
  if (!mq || !mq.up) { $("#status").hidden = false; $("#status").textContent = "Not connected yet. Try again in a moment."; return; }
  const m = { i: rid(), u: cid, n: nick, t, ts: now, v: CHAT_V, ...(replyTo ? { r: replyTo } : {}) };
  if (isAdmin) { m.g = await FA.sign(sigText(m)); m.adm = true; m.sts = now; }
  const { adm, sts, ...wire } = m;
  mq.pub(T.m, wire); lastSent = now;
  add(m, true); inp.value = ""; clearReply(); render(); list.scrollTop = list.scrollHeight;
}
$("#send").addEventListener("submit", e => { e.preventDefault(); sendMessage(); });
$("#min").onclick = () => hostApi.minimize();
document.addEventListener("keydown", e => { if (e.key === "Escape") { if (!$("#cfm").hidden) closeCfm(false); else if (!$("#adm").hidden) $("#adm").hidden = true; else if (!$("#emoji").hidden) $("#emoji").hidden = true; else hostApi.minimize(); } });

/* emoji */
const EMOJI = "😀 😂 🤣 😍 😎 🤩 😢 😡 👍 👎 👏 🙏 💪 🔥 ⚽ 🥅 🏆 🎉 ❤️ 💛 💙 🤝 😱 🤔 🙌 😅 😬 😴 🤯 🥳 😭 👀".split(" ");
for (const ch of EMOJI) { const b = el("button", null, ch); b.type = "button"; b.onclick = () => { const i = $("#inp"); i.value = (i.value + ch).slice(0, 300); i.focus(); }; $("#emoji").append(b); }
$("#emo").onclick = e => { e.stopPropagation(); $("#emoji").hidden = !$("#emoji").hidden; };
document.addEventListener("click", e => { if (!e.target.closest("#emoji")) $("#emoji").hidden = true; });

/* nickname (WTMLJR asks for the admin password) */
const err = t => { $("#nickErr").textContent = t || ""; };
function askNick(needPw) {
  $("#nick").hidden = false; $("#nickIn").value = nick; err(needPw ? "This name is protected. Enter the password." : "");
  $("#pwIn").value = ""; $("#pwIn").hidden = !needPw;
  (needPw ? $("#pwIn") : $("#nickIn")).focus(); if (!needPw) $("#nickIn").select();
}
let nickBusy = false;
async function saveNick() {
  if (nickBusy) return;
  const n = noCtl($("#nickIn").value).trim().replace(/\s+/g, " ");
  if (n.length < 2) { err("Use at least 2 characters."); return; }
  const who = reserved(n);
  if (who) {
    if ($("#pwIn").hidden) { $("#pwIn").hidden = false; $("#pwIn").focus(); err("This name is protected. Enter the password."); return; }
    if (!$("#pwIn").value) { err("Enter the password."); return; }
    nickBusy = true; $("#nickOk").disabled = true; err("Checking…");
    const ok = await FA.unlock(who, $("#pwIn").value);
    nickBusy = false; $("#nickOk").disabled = false; $("#pwIn").value = "";
    if (!ok) { err("Wrong password."); $("#pwIn").focus(); return; }
    nick = who; isAdmin = true;
  } else { nick = n.slice(0, 20); isAdmin = false; FA.lock(); }
  store.set("nick", nick); $("#nick").hidden = true; $("#pwIn").hidden = true; err("");
  $("#admBtn").hidden = !isAdmin; drawBanState(); render(); presence(); $("#inp").focus();
  hostApi.nick(nick); reportAdmin();
  if (isAdmin) setTimeout(() => { if (isAdmin && mq && mq.up && (mod.bans.length || mod.dels.length)) publishMod(mod.bans, mod.dels); }, 3000);   // keep the broker's copy fresh
}
$("#nickOk").onclick = saveNick;
$("#nickIn").addEventListener("keydown", e => { if (e.key === "Enter") saveNick(); });
$("#pwIn").addEventListener("keydown", e => { if (e.key === "Enter") saveNick(); });
$("#nickBtn").onclick = () => askNick(false);
$("#admBtn").onclick = () => { $("#adm").hidden = !$("#adm").hidden; drawAdm(); };
$("#admClose").onclick = () => { $("#adm").hidden = true; };

// nickname changed from Settings: a protected name still needs its password
function adoptNick(n) {
  n = noCtl(n).trim().replace(/\s+/g, " ").slice(0, 20);
  if (n.length < 2 || n === nick) return;
  nick = n; store.set("nick", nick); isAdmin = false; FA.lock(); $("#admBtn").hidden = true;
  if (reserved(nick)) askNick(true); else { $("#nick").hidden = true; $("#pwIn").hidden = true; err(""); }
  drawBanState(); render(); presence(); reportAdmin();
}
hostApi.onVisible(v => { isOpen = v; if (v) { unread = 0; hostApi.unread(0); list.scrollTop = list.scrollHeight; if (nick) $("#inp").focus(); else $("#nickIn").focus(); } });

/* start */
(async () => {
  let cfg = {}; try { cfg = (await hostApi.config()) || {}; } catch {}
  room = cfg.room || "football-arena-wtmljr-v1";
  notify = cfg.notify !== false; soundOn = cfg.sound === true;
  if (typeof cfg.nick === "string" && cfg.nick.trim()) { nick = cfg.nick.trim().slice(0, 20); store.set("nick", nick); }   // nickname chosen in Settings
  hostApi.onSettings(c => { if (c) { notify = c.notify !== false; soundOn = c.sound === true; if (!notify) { unread = 0; hostApi.unread(0); } } });
  hostApi.onNick(n => adoptNick(n));
  hostApi.onUnban(u => { if (isAdmin && ID.test(u)) unbanUser(u); });
  const servers = cfg.servers && cfg.servers.length ? cfg.servers : ["wss://broker.hivemq.com:8884/mqtt"];
  T = { m: room + "/m", p: room + "/p", s: room + "/s", mod: room + "/mod", to: room + "/to/" + cid };
  await acceptMod(store.get("mod", null));
  render(); drawBanState();
  if (!nick) askNick(false); else if (reserved(nick)) askNick(true); else $("#inp").focus();
  if (nick) hostApi.nick(nick);   // let Settings show the nickname
  mq = new Mqtt(servers, {
    state: setState,
    open() {
      presence();
      setTimeout(() => mq.pub(T.s, { u: cid }), 900);          // ask whoever is here for the latest messages
    },
    message: onMessage,
  });
  [T.m, T.p, T.s, T.mod, T.to].forEach(t => mq.sub(t));
  setInterval(() => { presence(); drawOnline(); }, 25000);
  setInterval(drawOnline, 10000);
})();
})();
