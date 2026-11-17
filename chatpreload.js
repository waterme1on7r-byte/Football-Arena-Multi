const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("host", {
  config: () => ipcRenderer.invoke("chat:config"),
  minimize: () => ipcRenderer.send("chat:minimize"),
  unread: n => ipcRenderer.send("chat:unread", Number(n) || 0),
  onVisible: cb => ipcRenderer.on("chat:visible", (_e, v) => cb(!!v)),
  // settings made in the main window's Settings pane
  onSettings: cb => ipcRenderer.on("chat:settings", (_e, d) => cb(d)),
  onNick: cb => ipcRenderer.on("chat:nick", (_e, n) => cb(String(n || ""))),
  onUnban: cb => ipcRenderer.on("chat:unban", (_e, u) => cb(String(u || ""))),
  nick: n => ipcRenderer.send("chat:nick", String(n || "")),
  admin: d => ipcRenderer.send("chat:admin", d),
});
