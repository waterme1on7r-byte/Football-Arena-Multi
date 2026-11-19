// Runs immediately in every new document, including cross-origin player frames.
(() => {
  if (window.__faAd) return;
  window.__faAd = true;
  const stats = window.__faContentStats = { skipActions: 0, hiddenPromotions: 0 };
  const TYPE = "football-arena-content-settings";
  const READY = "football-arena-content-ready";
  const ACTION = "football-arena-skip-action";
  // English upper-case I must stay i, while Turkish İ can contain a combining dot.
  const normalize = s => String(s || "").toLowerCase().replace(/\u0307/g, "").replace(/\s+/g, " ").trim();
  const STRICT = /^(?:reklam[ıi]?\s*(?:ge[cç]|atla|kapat)|skip\s+(?:video\s+)?ads?|skip advertisement|ad\s*skip)\s*[›>»→▶►]*$/;
  const LOOSE = /^(?:ge[cç]|atla|skip|kapat|close|[×✕✖x])\s*[›>»→]*$/;
  const CTX = /(^|[\s_-])(ad|ads|advert|advertisement|reklam|vast|preroll|pre-roll|adsbygoogle)([\s_-]|$)/;
  const KNOWN = '#preroll-skip-btn,.jw-skip.jw-skippable,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.vjs-skip-button,.vast-skip-button,[data-skip-ad]';
  const clicked = new WeakMap();
  const tokens = e => String(e.className && e.className.baseVal !== undefined ? e.className.baseVal : e.className || "")
    .concat(" ", e.id || "").replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  const inAdCtx = e => { for (let i = 0; e && i < 6; i++, e = e.parentElement) if (CTX.test(tokens(e))) return true; return false; };
  const visible = (e, interactive = true) => {
    const r = e.getBoundingClientRect(); const cs = getComputedStyle(e);
    return r.width > 0 && r.height > 0 && cs.visibility !== "hidden" && cs.display !== "none" && cs.opacity !== "0" && (!interactive || cs.pointerEvents !== "none");
  };
  const frames = () => Array.from(document.querySelectorAll("iframe"));
  const settings = () => ({ type: TYPE, skipOff: !!window.__faAdOff, blockAds: !!window.__faBlockAds });
  const send = (target, data) => { try { target.postMessage(data, "*"); } catch (_) {} };
  const action = () => { stats.skipActions++; if (parent !== window) send(parent, { type: ACTION }); };
  const marked = new Set();
  const adSeen = new WeakSet();
  const siteId = () => window.__faSiteId || "";
  const sportsIds = new Set(['ardaspor','inatbox','trgoals','patronspor','mahsunsports','netspor','zeustv','fctv33','extratv','liveball']);
  const sportsLayout = () => sportsIds.has(siteId().replace(/-giris$/, '')) || /(^|\.)(?:ardaspor|inattv|taraftarium|patronizle|mahsunsports|netspor|zeustv|fctv33|extrabet|liveball)\d*\./i.test(location.hostname);
  const ardaLayout = () => siteId() === "ardaspor" || /(^|\.)ardaspor\d*\./i.test(location.hostname);
  const netsporLayout = () => siteId() === "netspor" || /(^|\.)netspor\d*\./i.test(location.hostname) || !!document.querySelector('iframe#videoPlayer[src*="event.html"]');
  const advertisingFrame = element => {
    if (!element.matches('iframe[src]')) return false;
    try {
      const url = new URL(element.src, location.href);
      return url.hostname === 'traffmovie.com' || url.hostname.endsWith('.traffmovie.com') ||
        (element.id === 'page-skin-iframe' && /\/ads\/background-render\.php$/.test(url.pathname));
    } catch (_) { return false; }
  };
  function hidePromotion(element) {
    // Never collapse a container that owns the actual player.
    if (!element || element.matches('video') || (element.matches('iframe') && !advertisingFrame(element)) || element.querySelector('video') ||
        Array.from(element.querySelectorAll('iframe')).some(frame => !advertisingFrame(frame))) return;
    if (!element.hasAttribute("data-fa-ad-hidden")) {
      element.setAttribute("data-fa-ad-hidden", "true"); marked.add(element);
      if (!adSeen.has(element)) { adSeen.add(element); stats.hiddenPromotions++; }
    }
  }
  function filterPromotions() {
    if (!window.__faBlockAds) {
      for (const element of marked) element.removeAttribute("data-fa-ad-hidden");
      marked.clear(); return;
    }
    // These are image advertisements embedded directly in otherwise ordinary site HTML/CDNs.
    // Domain blocking alone cannot remove them without also breaking legitimate images.
    for (const image of document.querySelectorAll('a[href] img')) {
      const anchor = image.closest('a[href]');
      try {
        if (new URL(anchor.href, location.href).origin === location.origin) continue;
        const bounds = image.getBoundingClientRect();
        const bannerLabel = /\b(?:banner|advertisement|reklam)\b/i.test(image.alt || '');
        const widePromotion = sportsLayout() && bounds.width >= 250 && bounds.height > 0 && bounds.width / bounds.height >= 3.5;
        if (bannerLabel || widePromotion) {
          hidePromotion(anchor.closest('.banner-container,.banner-container-std,.mobile-top-banner,.channel-ad-item,.ads-container') || anchor);
        }
      } catch (_) {}
    }
    if (ardaLayout()) {
      document.querySelectorAll('[id^="zone_"]').forEach(e => { if (/^zone_\d+$/.test(e.id)) hidePromotion(e); });
      document.querySelectorAll('a[href*="1xbet.com"]').forEach(hidePromotion);
    }
    if (netsporLayout()) document.querySelectorAll('.banner-container.fullwidth,#splash-banner').forEach(hidePromotion);
    if (siteId() === 'patronspor' || /(^|\.)patronizle\d*\./i.test(location.hostname)) {
      document.querySelectorAll('.background-link,.hellobar,.player-promo').forEach(hidePromotion);
    }
    if (siteId() === 'mahsunsports' || /(^|\.)mahsunsports\d*\./i.test(location.hostname)) {
      document.querySelectorAll('#openWheelBtn').forEach(hidePromotion);
    }
    if (sportsLayout()) {
      document.querySelectorAll('.ads-container,.ads_single_font,.match-flow-ad,#ad-hellobar,#ad-top-banner,#ad-player-top,#ad-player-bottom,#ad-background').forEach(hidePromotion);
      document.querySelectorAll('iframe[src]').forEach(frame => {
        if (!advertisingFrame(frame)) return;
        hidePromotion(frame);
        let ancestor = frame.parentElement;
        for (let i = 0; ancestor && ancestor !== document.body && i < 4; i++, ancestor = ancestor.parentElement) {
          if (/^(?:zone_\d+|a[0-9a-f]{16})$/i.test(ancestor.id)) hidePromotion(ancestor);
        }
      });
    }
    // Player documents have no parent page's banner layout; match the observed ad-only overlay.
    if (document.querySelector('#preroll-container #preroll-video') && document.querySelector('#skip-ad-btn[onclick*="skipAd"]')) {
      document.querySelectorAll('#splash-banner').forEach(hidePromotion);
    }
    for (const element of marked) if (!element.isConnected) marked.delete(element);
  }
  let style;
  function sync() {
    if (!document.documentElement) return;
    if (!style) {
      style = document.createElement("style");
      // Restrict cosmetics to dedicated banner slots, never the player/pre-roll container.
      style.textContent = '[data-fa-ad-hidden="true"],ins.adsbygoogle,iframe[id^="google_ads_iframe"],.ad-banner,.advertisement-banner,.popunder{display:none!important}';
    }
    if (!style.isConnected) (document.head || document.documentElement).appendChild(style);
    style.disabled = !window.__faBlockAds;
    filterPromotions();
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
    // Netspor's observed player creates the match only inside this explicit skip handler.
    // Hiding the pre-roll alone leaves a blank player, and the button initially has display:none.
    const preroll = document.querySelector('#preroll-container');
    const directSkip = preroll && preroll.querySelector('#skip-ad-btn[onclick*="skipAd"]');
    if (directSkip && document.readyState !== "loading" && preroll.querySelector('video#preroll-video') && visible(preroll) && Date.now() - (clicked.get(directSkip) || 0) >= 3000) {
      clicked.set(directSkip, Date.now());
      try { action(); directSkip.click(); } catch (_) {}
    }
    const now = Date.now(); const seen = new Set();
    for (const e of document.querySelectorAll('button,a,[role="button"],div,span,' + KNOWN)) {
      if (e.childElementCount > 3 && !e.matches(KNOWN)) continue;
      const labels = [e.getAttribute("aria-label"), e.getAttribute("title"), e.textContent].map(normalize).filter(Boolean);
      const known = e.matches(KNOWN);
      const explicit = labels.some(t => t.length < 60 && STRICT.test(t));
      if (!known && !explicit && !labels.some(t => t.length < 60 && LOOSE.test(t) && inAdCtx(e))) continue;
      const target = e.closest('button,a,[role="button"],[onclick]') || e;
      // Click the leaf label once when a non-semantic wrapper has the same text.
      const textLabel = normalize(e.textContent);
      if (!known && textLabel && target === e && Array.from(e.children).some(c => normalize(c.textContent) === textLabel)) continue;
      if (seen.has(target)) continue; seen.add(target);
      if (!known && !explicit && target === e && !e.matches('button,a,[role="button"]') && !e.onclick && !e.hasAttribute("onclick")) continue;
      if (target.disabled || target.getAttribute("aria-disabled") === "true" || !visible(target)) continue;
      // Wait for countdown controls to become available rather than repeatedly pressing them.
      if (labels.some(t => /\b[1-9][0-9]*\s*(?:s|sn|sec|seconds?|saniye)?\b/.test(t))) continue;
      if (now - (clicked.get(target) || 0) < 3000) continue;
      clicked.set(target, now);
      try { action(); target.click(); } catch (_) {}
    }
    for (const video of document.querySelectorAll("video")) {
      if (!visible(video, false)) continue;
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
