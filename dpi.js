// Runs GoodbyeDPI silently alongside the app and stops it when the app closes. No service is installed.
// The app itself asks Windows for administrator rights once when it opens (GoodbyeDPI cannot run without them),
// then starts GoodbyeDPI directly as a hidden child process and writes what it says to dpi.log.
const fs = require("fs");
const path = require("path");
const cp = require("child_process");

// Option 1 of Settings > Network > DPI options. The option chosen in Settings is passed in by the app (ctx.args) and wins over args.txt.
const DEFAULT_ARGS = "-5 --dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253";

const deps = { spawn: cp.spawn, execFile: cp.execFile, spawnSync: cp.spawnSync, platform: () => process.platform, arch: () => process.arch };
let ctx = null, child = null, startedAt = 0, last = { state: "off", detail: "not-started" };

function log(msg) {
  try { if (ctx && ctx.logFile) fs.appendFileSync(ctx.logFile, new Date().toISOString() + " " + msg + "\n"); } catch {}
}
const run = (cmd, args) => new Promise(res => deps.execFile(cmd, args, { windowsHide: true }, (err, out) => res({ err, out: String(out || "") })));

function stopChild() { if (child) { const c = child; child = null; try { c.kill(); } catch {} } }
function stop() {
  stopChild();
  if (deps.platform() === "win32") { try { deps.spawnSync("taskkill", ["/F", "/IM", "goodbyedpi.exe"], { windowsHide: true }); } catch {} }
}

// macOS uses SpoofDPI as a loopback proxy. The app applies that proxy only to
// its Electron sessions; it never changes the user's network service settings.
async function startMac(c) {
  if (c) ctx = c;
  if (!ctx || deps.platform() !== "darwin") { last = { state: "na" }; return false; }
  stopChild();
  try {
    const { isPackaged, resourcesPath, appDir, logFile } = ctx;
    const base = isPackaged ? path.join(resourcesPath, "spoofdpi") : path.join(appDir, "spoofdpi");
    const bin = path.join(base, deps.arch() === "arm64" ? "arm64" : "x64", "spoofdpi");
    try { if (logFile) fs.writeFileSync(logFile, ""); } catch {}
    log("SpoofDPI binary: " + bin);
    if (!fs.existsSync(bin)) { log("NOT FOUND: " + bin); last = { state: "off", detail: "missing" }; return false; }
    const cur = child = deps.spawn(bin, ["-addr", "127.0.0.1", "-port", "8080", "-system-proxy=false"], { cwd: path.dirname(bin), stdio: ["ignore", "pipe", "pipe"] });
    startedAt = Date.now(); last = { state: "starting" };
    const onData = d => log("SpoofDPI: " + String(d).trim().replace(/\r?\n/g, " | "));
    cur.stdout.on("data", onData); cur.stderr.on("data", onData);
    cur.on("error", e => { log("could not start: " + e.message); if (child === cur) { child = null; last = { state: "off", detail: "exited" }; } });
    cur.on("exit", (code, sig) => { log("exited, code " + code + (sig ? ", signal " + sig : "")); if (child === cur) { child = null; last = { state: "off", detail: "exited" }; } });
    return true;
  } catch (e) { log("start failed: " + (e && e.message)); last = { state: "off", detail: "exited" }; return false; }
}

// turned off by the user from the menu: stop it and stay off until they turn it on again
function disable() { stop(); last = { state: "off", detail: "manual" }; log("turned off from the menu"); }

async function start(c) {
  if (c) ctx = c;
  if (deps.platform() === "darwin") return startMac(c);
  if (!ctx || deps.platform() !== "win32") { last = { state: "na" }; return false; }
  stopChild();
  try {
    const { isPackaged, resourcesPath, appDir, logFile } = ctx;
    const base = isPackaged ? path.join(resourcesPath, "goodbyedpi") : path.join(appDir, "goodbyedpi");
    const exe = path.join(base, deps.arch() === "x64" ? "x86_64" : "x86", "goodbyedpi.exe");
    try { if (logFile) fs.writeFileSync(logFile, ""); } catch {}
    log("GoodbyeDPI folder: " + base);
    if (!fs.existsSync(exe)) { log("NOT FOUND: " + exe); last = { state: "off", detail: "missing" }; return false; }

    // needs administrator rights ("net session" only works when elevated)
    if ((await run("net", ["session"])).err) { log("The app is not running as administrator."); last = { state: "off", detail: "not-admin" }; return false; }

    await run("taskkill", ["/F", "/IM", "goodbyedpi.exe"]);          // leftovers from a crash
    let args = DEFAULT_ARGS;
    if (ctx.args) args = ctx.args;                                 // chosen in Settings > Network
    else try {
      const line = fs.readFileSync(path.join(base, "args.txt"), "utf8").split(/\r?\n/).map(l => l.trim()).find(l => l && !l.startsWith("#"));
      if (line) args = line;
    } catch {}
    log("starting: goodbyedpi.exe " + args);
    const cur = child = deps.spawn(exe, args.split(/\s+/).filter(Boolean), { cwd: path.dirname(exe), windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
    startedAt = Date.now(); last = { state: "starting" };
    const onData = d => log("goodbyedpi: " + String(d).trim().replace(/\r?\n/g, " | "));
    cur.stdout.on("data", onData); cur.stderr.on("data", onData);
    cur.on("error", e => { log("could not start: " + e.message); if (child === cur) { child = null; last = { state: "off", detail: "exited" }; } });
    cur.on("exit", (code, sig) => { log("exited, code " + code + (sig ? ", signal " + sig : "")); if (child === cur) { child = null; last = { state: "off", detail: "exited" }; } });
    return true;
  } catch (e) { log("start failed: " + (e && e.message)); last = { state: "off", detail: "exited" }; return false; }
}

// { state: "on" | "starting" | "off" | "na", detail? }
async function status() {
  if (deps.platform() !== "win32" && deps.platform() !== "darwin") return { state: "na" };
  if (child && child.exitCode === null) return Date.now() - startedAt < 2500 ? { state: "starting" } : { state: "on" };
  return last.state === "starting" ? { state: "off", detail: "exited" } : last;
}

module.exports = { start, stop, disable, status, DEFAULT_ARGS, _deps: deps };
