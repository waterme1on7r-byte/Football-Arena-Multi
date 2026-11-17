// Checks the link list downloaded from GitHub (sites.json) before the app uses it.
const ID = /^[a-z0-9_-]{1,40}$/i;
const web = u => { try { const x = new URL(u); return (x.protocol === "http:" || x.protocol === "https:") ? x.href : null; } catch { return null; } };

function cleanChat(c) {
  if (!c || typeof c !== "object") return null;
  const out = {};
  if (typeof c.enabled === "boolean") out.enabled = c.enabled;
  if (typeof c.room === "string" && /^[A-Za-z0-9_-]{3,60}$/.test(c.room)) out.room = c.room;
  if (Array.isArray(c.servers)) {
    const s = c.servers.filter(x => typeof x === "string" && /^wss:\/\/[^\s]+$/i.test(x)).slice(0, 5);
    if (s.length) out.servers = s;
  }
  return out;
}

function validateRemote(d) {
  if (!d || typeof d !== "object" || !Array.isArray(d.sites)) return null;
  const sites = [];
  for (const s of d.sites.slice(0, 200)) {
    if (!s || typeof s !== "object") continue;
    const id = String(s.id || ""), name = String(s.name || "").replace(/[\u0000-\u001f]/g, "").trim().slice(0, 40), url = web(String(s.url || ""));
    if (!ID.test(id) || !name || !url) continue;
    sites.push({ id, name, url, quick: s.quick === true, hasQuick: typeof s.quick === "boolean", force: s.force === true });
  }
  const remove = Array.isArray(d.remove) ? d.remove.map(String).filter(x => ID.test(x)).slice(0, 200) : [];
  // kill switch: app versions older than this are blocked until they update (ignored if it is not a plain version number)
  const minVersion = typeof d.minVersion === "string" && /^\d+(\.\d+){0,2}$/.test(d.minVersion.trim()) ? d.minVersion.trim() : "";
  return { version: Number(d.version) || 0, sites, remove, chat: cleanChat(d.chat), minVersion };
}

module.exports = { validateRemote, cleanChat };
