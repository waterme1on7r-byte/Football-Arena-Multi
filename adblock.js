// Built-in ad / pop-under rules + an editable list (blocklist.txt in the app data folder).
const fs = require("fs");

const DOMAINS = `
doubleclick.net googlesyndication.com googleadservices.com adservice.google.com imasdk.googleapis.com
adnxs.com adsrvr.org taboola.com outbrain.com pubmatic.com rubiconproject.com criteo.com criteo.net
amazon-adsystem.com moatads.com adform.net mgid.com revcontent.com a-ads.com bidvertiser.com
popads.net popcash.net propellerads.com propu.sh onclickads.net onclkds.com
profitableratecpm.com highperformanceformat.com effectivegatecpm.com effectiveratecpm.com
adsterra.com juicyads.com exoclick.com exosrv.com exdynsrv.com trafficjunky.net clickadu.com
hilltopads.net adcash.com ad-maven.com admaven.com adsco.re yandexadexchange.net an.yandex.ru
`.split(/\s+/).filter(Boolean);

const PATHS = /(\/pagead\/|\/adserver|\/adsbygoogle|\/vast[\/.?_-]|\/preroll|\/pre-roll|\/popunder|\/popads|\/ima3\.js|\/ads\/(banner|js|video))/i;

const TEMPLATE = `# Football Arena - your own block list. One entry per line, then restart the app.
# A domain blocks that site and everything under it:      example-ads.com
# Any other text blocks every address that contains it:    /banner/overlay
# Lines starting with # are ignored.
`;

const base = new Set(DOMAINS);
let custom = new Set(), fragments = [], enabled = true;
const setEnabled = on => { enabled = !!on; };

function loadCustom(file) {
  try {
    if (!fs.existsSync(file)) fs.writeFileSync(file, TEMPLATE, "utf8");
    custom = new Set(); fragments = [];
    for (let line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
      line = line.trim().toLowerCase();
      if (!line || line.startsWith("#")) continue;
      if (/^[a-z0-9.-]+\.[a-z]{2,}$/.test(line)) custom.add(line); else fragments.push(line);
    }
  } catch {}
}

function hostBlocked(host) {
  let h = host.toLowerCase();
  while (h) {
    if (base.has(h) || custom.has(h)) return true;
    const i = h.indexOf(".");
    if (i < 0) break;
    h = h.slice(i + 1);
  }
  return false;
}

function isAdUrl(u) {
  if (!enabled) return false;
  try {
    const x = new URL(u);
    if (hostBlocked(x.hostname)) return true;
    const s = x.href.toLowerCase();
    if (fragments.some(f => s.includes(f))) return true;
    return PATHS.test(x.pathname + x.search);
  } catch { return false; }
}

module.exports = { isAdUrl, loadCustom, setEnabled, TEMPLATE };
