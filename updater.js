// The app's own updates: electron-updater + GitHub Releases (the build workflow publishes them when a version tag is pushed).
// Checks at start and every few hours, downloads in the background, tells the window when a version is ready, and installs it
// when the app is closed (or right away if the user chooses Restart now). Never throws: if anything is missing the app just runs.
const { app } = require("electron");

let autoUpdater = null;
try { autoUpdater = require("electron-updater").autoUpdater; } catch (e) { console.error("electron-updater unavailable:", e && e.message); }

const EVERY_MS = 4 * 60 * 60 * 1000;
let emit = () => {}, state = { state: "idle" }, timer = 0, started = false, busy = false, installing = false;

const set = s => { state = s; try { emit(state); } catch {} };
const supported = () => !!autoUpdater && app.isPackaged && ["win32", "darwin"].includes(process.platform);

function init(onState, log) {
  emit = onState || emit;
  if (started) return; started = true;
  if (!supported()) { state = { state: "unsupported", reason: !autoUpdater ? "missing" : "dev" }; return; }
  const macManual = process.platform === "darwin";
  autoUpdater.autoDownload = !macManual;           // macOS installs are manual without a Developer ID signature
  autoUpdater.autoInstallOnAppQuit = !macManual;   // Windows "Later" = install when the app closes
  autoUpdater.autoRunAppAfterInstall = true;
  autoUpdater.allowPrerelease = false;
  autoUpdater.logger = { info: m => log && log("info " + m), warn: m => log && log("warn " + m), error: m => log && log("error " + m), debug() {} };
  autoUpdater.on("checking-for-update", () => set({ state: "checking" }));
  autoUpdater.on("update-available", i => set(macManual ? { state: "available", version: i && i.version } : { state: "downloading", version: i && i.version, percent: 0 }));
  autoUpdater.on("download-progress", p => set({ state: "downloading", version: state.version, percent: Math.round((p && p.percent) || 0) }));
  autoUpdater.on("update-not-available", () => { busy = false; set({ state: "uptodate" }); });
  autoUpdater.on("update-downloaded", i => { busy = false; set({ state: "ready", version: (i && i.version) || state.version }); });
  autoUpdater.on("error", e => { busy = false; set({ state: "error", message: String((e && e.message) || e).split("\n")[0].slice(0, 160) }); });
}

// A repeated check while one is running (or a download is waiting for restart) does nothing.
async function check() {
  if (!supported()) return state;
  if (busy || state.state === "ready" || state.state === "downloading") return state;
  busy = true;
  try { await autoUpdater.checkForUpdates(); } catch (e) { busy = false; set({ state: "error", message: String((e && e.message) || e).split("\n")[0].slice(0, 160) }); }
  return state;
}

function start() {
  if (!supported()) return;
  setTimeout(check, 12000);                        // not during start-up
  clearInterval(timer); timer = setInterval(check, EVERY_MS);
}

// Silent in-place install over the existing installation, then start the app again.
function installNow() {
  if (!supported() || state.state !== "ready") return false;
  installing = true;   // the app must leave quickly now: no clean-up delay
  try { autoUpdater.quitAndInstall(true, true); return true; } catch { installing = false; return false; }
}

function openReleasePage() {
  if (process.platform !== "darwin") return false;
  const { shell } = require("electron");
  return shell.openExternal("https://github.com/waterme1on7r-byte/football-arena/releases/latest").then(() => true).catch(() => false);
}

module.exports = { init, check, start, installNow, openReleasePage, installing: () => installing, status: () => state, supported };
