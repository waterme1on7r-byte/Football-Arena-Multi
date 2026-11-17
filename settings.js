// App settings, kept in the user data folder (%APPDATA%\Football Arena\settings.json), never in the install folder,
// so they survive app updates. Every setting has a default, and whatever is read from disk is checked and merged on top
// of the defaults: an older file that lacks new settings (or has broken values) can never cause an error.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

// Same lists as the GoodbyeDPI options offered in Settings > Network.
const DPI_PRESETS = {
  1: "-5 --dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253",
  2: "-9 --dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253",
  3: "-6 --dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253",
};

const DEFAULTS = Object.freeze({
  clearOnExit: true,                       // delete cache + temporary data when the app closes
  restore: true,                           // remember layout and open sites on restart
  audio: { defaultVolume: 100, focusOnly: false },
  links: { health: "manual", every: 15, openInMain: false },   // health: startup | manual | off ; every: minutes, 0 = manual
  chat: { enabled: true, nick: "", notify: true, sound: false },
  net: { adblock: true, autoskip: true, dpiEnabled: true, dpiPreset: 1 },
  pass: null,                              // { salt, hash } of the 4-digit parental passcode, or null
});

const isObj = v => v && typeof v === "object" && !Array.isArray(v);
const bool = (v, d) => (typeof v === "boolean" ? v : d);
const oneOf = (v, list, d) => (list.includes(v) ? v : d);
const int = (v, lo, hi, d) => { const n = Math.round(Number(v)); return Number.isFinite(n) ? Math.max(lo, Math.min(hi, n)) : d; };

// Returns a complete, valid settings object from anything (undefined, an old file, garbage).
function normalize(raw) {
  const r = isObj(raw) ? raw : {}, a = isObj(r.audio) ? r.audio : {}, l = isObj(r.links) ? r.links : {}, c = isObj(r.chat) ? r.chat : {}, n = isObj(r.net) ? r.net : {};
  const D = DEFAULTS;
  const pass = isObj(r.pass) && /^[0-9a-f]{32}$/.test(String(r.pass.salt)) && /^[0-9a-f]{64}$/.test(String(r.pass.hash)) ? { salt: r.pass.salt, hash: r.pass.hash } : null;
  return {
    clearOnExit: bool(r.clearOnExit, D.clearOnExit),
    restore: bool(r.restore, D.restore),
    audio: { defaultVolume: int(a.defaultVolume, 0, 100, D.audio.defaultVolume), focusOnly: bool(a.focusOnly, D.audio.focusOnly) },
    links: {
      health: oneOf(l.health, ["startup", "manual", "off"], D.links.health),
      every: oneOf(Number(l.every), [0, 15, 60], D.links.every),
      openInMain: bool(l.openInMain, D.links.openInMain),
    },
    chat: {
      enabled: bool(c.enabled, D.chat.enabled),
      nick: typeof c.nick === "string" ? c.nick.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 20) : D.chat.nick,
      notify: bool(c.notify, D.chat.notify),
      sound: bool(c.sound, D.chat.sound),
    },
    net: {
      adblock: bool(n.adblock, D.net.adblock),
      autoskip: bool(n.autoskip, D.net.autoskip),
      dpiEnabled: bool(n.dpiEnabled, D.net.dpiEnabled),
      dpiPreset: oneOf(Number(n.dpiPreset), [1, 2, 3], D.net.dpiPreset),
    },
    pass,
  };
}

// Applies a partial change from the window (only known keys; the passcode can never be changed this way).
function merge(cur, patch) {
  if (!isObj(patch)) return cur;
  const next = JSON.parse(JSON.stringify(cur));
  for (const k of ["clearOnExit", "restore"]) if (k in patch) next[k] = patch[k];
  for (const g of ["audio", "links", "chat", "net"]) if (isObj(patch[g])) for (const k of Object.keys(next[g])) if (k in patch[g]) next[g][k] = patch[g][k];
  next.pass = cur.pass;
  return normalize(next);
}

function load(file) {
  try { return normalize(JSON.parse(fs.readFileSync(file, "utf8"))); } catch { return normalize(null); }
}
function save(file, s) {
  try {
    const tmp = file + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify(s, null, 2), "utf8");
    fs.renameSync(tmp, file);
    return true;
  } catch { return false; }
}

// ---- parental passcode: exactly 4 digits, stored only as a salted scrypt hash ----
const validCode = c => typeof c === "string" && /^[0-9]{4}$/.test(c);
function hashCode(code, saltHex) {
  return crypto.scryptSync(code, Buffer.from(saltHex, "hex"), 32).toString("hex");
}
function makePass(code) {
  const salt = crypto.randomBytes(16).toString("hex");
  return { salt, hash: hashCode(code, salt) };
}
function checkPass(pass, code) {
  if (!pass || !validCode(code)) return false;
  try { return crypto.timingSafeEqual(Buffer.from(hashCode(code, pass.salt), "hex"), Buffer.from(pass.hash, "hex")); } catch { return false; }
}

// What the window is allowed to see: never the hash.
function publicView(s) {
  const { pass, ...rest } = s;
  return { ...rest, hasPasscode: !!pass };
}

// "1.8.0" > "1.7.1": numeric dotted compare. Returns -1, 0, 1, or NaN when either side is not a version.
function cmpVersion(a, b) {
  const p = v => { const m = /^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?/.exec(String(v || "").trim()); return m ? [+m[1], +(m[2] || 0), +(m[3] || 0)] : null; };
  const x = p(a), y = p(b); if (!x || !y) return NaN;
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1;
  return 0;
}

module.exports = { DEFAULTS, DPI_PRESETS, normalize, merge, load, save, validCode, makePass, checkPass, publicView, cmpVersion };
