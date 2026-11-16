const { contextBridge, ipcRenderer } = require('electron');

const info = ipcRenderer.sendSync('desk-info');

contextBridge.exposeInMainWorld('Desktop', {
  version: info.version,
  startup: info.startup,
  sync: (json) => ipcRenderer.send('desk-sync', json),
  saveText: (name, text) => ipcRenderer.invoke('desk-save', name, text),
  openText: () => ipcRenderer.invoke('desk-open'),
  checkUpdate: () => ipcRenderer.invoke('desk-update'),
  toggleStartup: () => ipcRenderer.invoke('desk-startup'),
  test: () => ipcRenderer.send('desk-test'),
});
