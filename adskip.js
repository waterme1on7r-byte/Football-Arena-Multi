// Runs inside every page and frame of a site: presses "Reklamı geç" / "Skip ad" as soon as it
// becomes available, fast-forwards short ad videos and hides pre-roll overlays.
(() => {
  if (window.__faAd) return; window.__faAd = true;
  const STRICT = [/^reklam[ıi]\s*(ge[cç]|atla|kapat)(\s*[›>»])?$/, /^skip\s*ads?(\s*[›>»])?$/, /^ad\s*skip$/];
  const LOOSE = /^(ge[cç]|atla|skip|kapat|close|[×✕✖x])(\s*[›>»])?$/;
  const CTX = /(^|[\s_-])(ad|ads|advert|advertisement|reklam|vast|preroll|pre-roll|adsbygoogle)([\s_-]|$)/;
  const tokens = e => {
    const c = e.className && e.className.baseVal !== undefined ? e.className.baseVal : e.className;
    return (String(c || "") + " " + (e.id || "")).replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  };
  const inAdCtx = e => { for (let i = 0; e && i < 6; i++, e = e.parentElement) if (CTX.test(tokens(e))) return true; return false; };
  const visible = e => {
    const r = e.getBoundingClientRect(); if (r.width <= 0 || r.height <= 0) return false;
    const cs = getComputedStyle(e);
    return cs.visibility !== "hidden" && cs.display !== "none" && cs.opacity !== "0" && cs.pointerEvents !== "none";
  };
  const clicked = new WeakMap();

  function scan() {
    if (document.hidden || window.__faAdOff) return;   // __faAdOff: switched off in Settings
    const now = Date.now();
    for (const e of document.querySelectorAll("button,a,[role=button],div,span,p")) {
      if (e.childElementCount > 3 || e.disabled || e.getAttribute("aria-disabled") === "true") continue;
      const raw = (e.textContent || "").trim(); if (!raw || raw.length > 24) continue;
      const t = raw.toLocaleLowerCase("tr");
      if (!(STRICT.some(r => r.test(t)) || (LOOSE.test(t) && inAdCtx(e)))) continue;
      if (now - (clicked.get(e) || 0) < 3000 || !visible(e)) continue;
      clicked.set(e, now);
      try { e.click(); } catch {}
    }
    for (const v of document.querySelectorAll("video")) {
      const d = v.duration; if (!isFinite(d) || d <= 0 || d > 180) continue;   // live streams are not finite
      if (CTX.test((v.currentSrc || v.src || "").toLowerCase().replace(/[\/.?=&]/g, " ")) || inAdCtx(v)) {
        try { v.muted = true; v.currentTime = d; } catch {}
      }
    }
  }
  const style = document.createElement("style");
  style.textContent = '[class*="preroll" i],[id*="preroll" i],[class*="pre-roll" i],[id*="pre-roll" i],[class*="popunder" i],ins.adsbygoogle,[id^="google_ads"]{display:none!important}';
  (document.head || document.documentElement).append(style);

  let timer = 0;
  new MutationObserver(() => { clearTimeout(timer); timer = setTimeout(scan, 400); }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ["class", "style", "disabled"] });
  setInterval(scan, 1500);
  scan();
})();
