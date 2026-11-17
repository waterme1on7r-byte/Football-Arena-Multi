"use strict";
// Chat admin identity. There is NO password in this file.
// One entry per admin. Each holds (1) a public key, used by every client to check that this admin's messages and bans are
// genuine, and (2) the matching private key locked with that admin's password (PBKDF2 + AES-GCM). Only the password opens it.
// Add an admin or change a password:  node tools/admin-key.js NAME   (it rewrites the block between the markers below).
(function (root) {
  /*DATA-START*/
  const DATA = [{"name":"WTMLJR","pub":{"kty":"EC","crv":"P-256","x":"e-HWEfqLShgQFseJ9CV4X0EpT1htsh1DEBFL9AhASx0","y":"aoJzbvwyQPhh8K6rxGrVsiSelLvhrNBchWCRpjgjPUs"},"salt":"TIz9tnCzFlshm1Jk9n5AKw==","iv":"84tfbmK11tPezFKd","it":600000,"ct":"PlP12ARtMN4tYvKRQRtYZq1ZN/OeBCTn6YElG/JdmoOwyUZ+HQZz4ImGi7+jGOTNPqT/qLPtuIkCDhfPWg5HemmJJZw9+IdoCu0nAF98l7TdUALPkD4RDJGHyBnsTOJ72dvV71BFRlvEKMH11IP1AgzoxEbfTKXmUMxY9b85OgnZow2Nz+2OBiyJbFr8f2t9m7wbs7BXNYN70g=="},{"name":"LEONBISHOP","pub":{"kty":"EC","crv":"P-256","x":"HA5j8osV61JkjNrTJZKwn3sCSnfBct4e9UNi9boJssw","y":"T1g6n1w4kCyecvi3II44efIRDZjxOpC3kPwJuRvO3ZI"},"salt":"I+HWrYxydCaWx7Sq6SrTgQ==","iv":"UhPFuRehD6nmtm1R","it":600000,"ct":"8+vYdbINHkeRVRdfVr+IIQraBHaxobP+tc8aR1kcyVfp+QXf/RKgSL9YmDKKDTJ8KSbeur39AQHqKUQlj0X1xU0w7SOHkKieHUC723lUPTmwp5Bn2hdCpe6G8tBeCFfyRgnT8iZWSR4fzVdP2dHnObV8EIc3CyxoT/MxCASzgQ6DuBDDILkYnsywmdTcs2Jwr94N7jjoCt4tyg=="}];
  /*DATA-END*/

  const enc = s => new TextEncoder().encode(s);
  const b64d = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
  const b64e = b => btoa(String.fromCharCode(...new Uint8Array(b)));
  const EC = { name: "ECDSA", namedCurve: "P-256" };
  const entry = name => (DATA || []).find(d => d.name === name);
  const pubs = new Map();
  let priv = null, who = "";

  async function pub(name) {
    const e = entry(name); if (!e) throw new Error("unknown admin");
    if (!pubs.has(name)) pubs.set(name, await crypto.subtle.importKey("jwk", e.pub, EC, false, ["verify"]));
    return pubs.get(name);
  }
  // true when the password is right for that admin name; the private key then stays in memory only (never saved anywhere)
  async function unlock(name, password) {
    const e = entry(name); if (!e) return false;
    try {
      const base = await crypto.subtle.importKey("raw", enc(String(password)), "PBKDF2", false, ["deriveKey"]);
      const key = await crypto.subtle.deriveKey({ name: "PBKDF2", salt: b64d(e.salt), iterations: e.it, hash: "SHA-256" }, base, { name: "AES-GCM", length: 256 }, false, ["decrypt"]);
      const pkcs8 = await crypto.subtle.decrypt({ name: "AES-GCM", iv: b64d(e.iv) }, key, b64d(e.ct));
      priv = await crypto.subtle.importKey("pkcs8", pkcs8, EC, false, ["sign"]); who = name;
      return true;
    } catch { priv = null; who = ""; return false; }
  }
  const unlocked = () => !!priv;
  const lock = () => { priv = null; who = ""; };
  const current = () => who;
  async function sign(text) { if (!priv) throw new Error("locked"); return b64e(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, priv, enc(String(text)))); }
  // checks the signature against the named admin's public key only
  async function verify(text, sig, name) {
    try {
      if (typeof sig !== "string" || sig.length > 120) return false;
      return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, await pub(name), b64d(sig), enc(String(text)));
    } catch { return false; }
  }
  const api = { unlock, unlocked, lock, current, sign, verify, names: () => (DATA || []).map(d => d.name), configured: () => !!(DATA && DATA.length) };
  if (typeof module !== "undefined" && module.exports) module.exports = api; else root.FA_ADMIN = api;
})(typeof window !== "undefined" ? window : globalThis);
