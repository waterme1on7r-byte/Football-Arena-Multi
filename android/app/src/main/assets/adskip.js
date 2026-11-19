// Runs immediately in every new document, including cross-origin player frames.
(() => {
  if (window.__faAd) return;
  window.__faAd = true;
  const stats = window.__faContentStats = { skipActions: 0 };
  const TYPE = "football-arena-content-settings";
  const READY = "football-arena-content-ready";
  const ACTION = "football-arena-skip-action";
  // English upper-case I must stay i, while Turkish İ can contain a combining dot.
  const normalize = s => String(s || "").toLowerCase().replace(/\u0307/g, "").replace(/\s+/g, " ").trim();
  const STRICT = /^(?:reklam[ıi]?\s*(?:ge[cç]|atla|kapat)|skip\s+(?:video\s+)?ads?|skip advertisement|ad\s*skip)\s*[›>»→]*$/;
  const LOOSE = /^(?:ge[cç]|atla|skip|kapat|close|[×✕✖x])\s*[›>»→]*$/;
  const CTX = /(^|[\s_-])(ad|ads|advert|advertisement|reklam|vast|preroll|pre-roll|adsbygoogle)([\s_-]|$)/;
  const KNOWN = '.jw-skip.jw-skippable,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.vjs-skip-button,.vast-skip-button,[data-skip-ad]';
  const clicked = new WeakMap();
  const tokens = e => String(e.className && e.className.baseVal !== undefined ? e.className.baseVal : e.className || "")
    .concat(" ", e.id || "").replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  const inAdCtx = e => { for (let i = 0; e && i < 6; i++, e = e.parentElement) if (CTX.test(tokens(e))) return true; return false; };
  const visible = e => {
    const r = e.getBoundingClientRect(); const cs = getComputedStyle(e);
    return r.width > 0 && r.height > 0 && cs.visibility !== "hidden" && cs.display !== "none" && cs.opacity !== "0" && cs.pointerEvents !== "none";
  };
  const frames = () => Array.from(document.querySelectorAll("iframe"));
  const settings = () => ({ type: TYPE, skipOff: !!window.__faAdOff, blockAds: !!window.__faBlockAds });
  const send = (target, data) => { try { target.postMessage(data, "*"); } catch (_) {} };
  const action = () => { stats.skipActions++; if (parent !== window) send(parent, { type: ACTION }); };
  let style;
  function sync() {
    if (!document.documentElement) return;
    if (!style) {
      style = document.createElement("style");
      // Restrict cosmetics to dedicated banner slots, never the player/pre-roll container.
      style.textContent = 'ins.adsbygoogle,iframe[id^="google_ads_iframe"],.ad-banner,.advertisement-banner,.popunder{display:none!important}';
    }
    if (!style.isConnected) (document.head || document.documentElement).appendChild(style);
    style.disabled = !window.__faBlockAds;
  }
  window.__faApplyContentSettings = data => {
    window.__faAdOff = !!data.skipOff; window.__faBlockAds = !!data.blockAds;
    sync(); frames().forEach(f => send(f.contentWindow, settings())); scan();
  };
  addEventListener("message", event => {
    if (!event.data) return;
    if (event.source === parent && event.data.type === TYPE) window.__faApplyContentSettings(event.data);
    else if (frames().some(f => f.contentWindow === event.source)) {
      if (event.data.type === READY) send(event.source, settings());
      else if (event.data.type === ACTION) action();
    }
  });
  const ready = () => { sync(); scan(); if (parent !== window) send(parent, { type: READY }); };
  document.addEventListener("DOMContentLoaded", ready, { once: true });
  document.addEventListener("load", event => {
    if (event.target.tagName === "IFRAME") send(event.target.contentWindow, settings());
  }, true);
  function scan() {
    if (!document.documentElement || document.hidden || window.__faAdOff) return;
    const now = Date.now(); const seen = new Set();
    for (const e of document.querySelectorAll('button,a,[role="button"],div,span,' + KNOWN)) {
      if (e.childElementCount > 3 && !e.matches(KNOWN)) continue;
      const labels = [e.getAttribute("aria-label"), e.getAttribute("title"), e.textContent].map(normalize).filter(Boolean);
      const known = e.matches(KNOWN);
      if (!known && !labels.some(t => t.length < 60 && (STRICT.test(t) || (LOOSE.test(t) && inAdCtx(e))))) continue;
      const target = e.closest('button,a,[role="button"]') || e;
      if (seen.has(target)) continue; seen.add(target);
      if (!known && target === e && !e.matches('button,a,[role="button"]') && !e.onclick && !e.hasAttribute("onclick")) continue;
      if (target.disabled || target.getAttribute("aria-disabled") === "true" || !visible(target)) continue;
      // Wait for countdown controls to become available rather than repeatedly pressing them.
      if (labels.some(t => /\b[1-9][0-9]*\s*(?:s|sn|sec|seconds?|saniye)?\b/.test(t))) continue;
      if (now - (clicked.get(target) || 0) < 3000) continue;
      clicked.set(target, now);
      try { action(); target.click(); } catch (_) {}
    }
    for (const video of document.querySelectorAll("video")) {
      const duration = video.duration;
      if (!Number.isFinite(duration) || duration <= 0 || duration > 180 || video.currentTime >= duration - .1) continue;
      if (CTX.test(normalize(video.currentSrc || video.src).replace(/[\/.?=&]/g, " ")) || inAdCtx(video)) {
        // Do not change mute/volume: many players reuse this element for the match.
        try { video.currentTime = duration; } catch (_) {}
      }
    }
  }
  let timer;
  new MutationObserver(() => {
    clearTimeout(timer); timer = setTimeout(() => { sync(); scan(); }, 150);
  }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ["class", "style", "disabled", "aria-disabled", "aria-label", "title"] });
  setInterval(() => { sync(); scan(); }, 750);
  ready();
})();
