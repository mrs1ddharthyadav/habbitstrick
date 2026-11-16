const { app, BrowserWindow, Tray, Menu, Notification, shell, ipcMain, dialog, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { autoUpdater } = require('electron-updater');

app.setAppUserModelId('com.habbitstrick.desktop');

if (!app.requestSingleInstanceLock()) {
  app.quit();
}

const ICON = path.join(__dirname, 'icon.png');
const STATE = () => path.join(app.getPath('userData'), 'reminders.json');
let win = null;
let tray = null;
let quitting = false;
let payload = { date: '', reminders: [], doneIds: [] };
const fired = new Set();
let updateReady = false;

function loadPayload() {
  try { payload = JSON.parse(fs.readFileSync(STATE(), 'utf8')); } catch (e) { /* first run */ }
}

function showWindow() {
  if (!win) createWindow();
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function createWindow() {
  win = new BrowserWindow({
    width: 540,
    height: 900,
    minWidth: 380,
    minHeight: 560,
    backgroundColor: '#050505',
    title: 'Habbit Strick',
    icon: ICON,
    show: !process.argv.includes('--hidden'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: false,
      nodeIntegration: false,
    },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'app', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('file:')) { e.preventDefault(); if (/^https?:/.test(url)) shell.openExternal(url); }
  });
  win.on('close', (e) => {
    if (!quitting) { e.preventDefault(); win.hide(); }
  });
}

function createTray() {
  const img = nativeImage.createFromPath(ICON).resize({ width: 16, height: 16 });
  tray = new Tray(img);
  tray.setToolTip('Habbit Strick');
  tray.on('click', showWindow);
  rebuildTrayMenu();
}

function rebuildTrayMenu() {
  if (!tray) return;
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open Habbit Strick', click: showWindow },
    { label: 'Start with Windows', type: 'checkbox', checked: startupOn(), click: (m) => setStartup(m.checked) },
    { label: updateReady ? 'Restart to update' : 'Check for updates', click: () => (updateReady ? autoUpdater.quitAndInstall() : checkUpdate()) },
    { type: 'separator' },
    { label: 'Quit', click: () => { quitting = true; app.quit(); } },
  ]));
}

function startupOn() { return app.getLoginItemSettings().openAtLogin; }
function setStartup(on) {
  app.setLoginItemSettings({ openAtLogin: on, args: on ? ['--hidden'] : [] });
  try { fs.writeFileSync(path.join(app.getPath('userData'), 'startup-set'), '1'); } catch (e) { /* ignore */ }
  rebuildTrayMenu();
  return startupOn();
}

/* ---------- reminders ---------- */
function pad(n) { return n < 10 ? '0' + n : '' + n; }
function ymd(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }

function notify(title, body) {
  if (!Notification.isSupported()) return;
  const n = new Notification({ title, body, icon: ICON });
  n.on('click', showWindow);
  n.show();
}

function tickReminders() {
  const now = new Date();
  const today = ymd(now);
  const hm = pad(now.getHours()) + ':' + pad(now.getMinutes());
  const dow = (now.getDay() + 6) % 7;
  const doneToday = payload.date === today ? (payload.doneIds || []) : [];
  (payload.reminders || []).forEach((r) => {
    if (!r || !Array.isArray(r.times) || r.times.indexOf(hm) < 0) return;
    if (r.date && r.date !== today) return;
    if (r.from && r.from > today) return;
    if (Array.isArray(r.days) && r.days.length && r.days.indexOf(dow) < 0) return;
    if (Array.isArray(r.vac) && r.vac.some((v) => v && today >= v.a && today <= v.b)) return;
    if (doneToday.indexOf(r.id) >= 0) return;
    const key = today + '|' + r.id + '|' + hm;
    if (fired.has(key)) return;
    fired.add(key);
    notify(r.name || 'Habbit Strick', r.q ? 'Did you keep the rule today?' : 'Time for your task');
  });
  if (fired.size > 500) fired.clear();
}

/* ---------- updates ---------- */
function checkUpdate() {
  if (!app.isPackaged) return Promise.resolve('Updates only work in the installed app.');
  return autoUpdater.checkForUpdates().then((r) => {
    const v = r && r.updateInfo && r.updateInfo.version;
    if (r && r.isUpdateAvailable) return 'Version ' + v + ' found. Downloading in the background.';
    return 'You have the latest version.';
  }).catch((e) => 'Could not check: ' + (e && e.message ? e.message.split('\n')[0] : e));
}

function setupUpdater() {
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.on('update-downloaded', (info) => {
    updateReady = true;
    rebuildTrayMenu();
    const n = new Notification({ title: 'Habbit Strick update ready', body: 'Version ' + info.version + ' will install when you quit. Click to restart now.', icon: ICON });
    n.on('click', () => { quitting = true; autoUpdater.quitAndInstall(); });
    n.show();
  });
  autoUpdater.on('error', () => { /* offline or no release yet */ });
  if (app.isPackaged) {
    setTimeout(() => autoUpdater.checkForUpdates().catch(() => {}), 8000);
    setInterval(() => autoUpdater.checkForUpdates().catch(() => {}), 4 * 60 * 60 * 1000);
  }
}

/* ---------- IPC ---------- */
ipcMain.on('desk-info', (e) => { e.returnValue = { version: app.getVersion(), startup: startupOn() }; });
ipcMain.on('desk-sync', (e, json) => {
  try {
    payload = JSON.parse(json);
    fs.writeFileSync(STATE(), json);
  } catch (err) { /* ignore bad payload */ }
});
ipcMain.on('desk-test', () => notify('Habbit Strick', 'This is a test reminder. Reminders are working.'));
ipcMain.handle('desk-save', async (e, name, text) => {
  const r = await dialog.showSaveDialog(win, { defaultPath: String(name || 'file.txt').replace(/[\\/:*?"<>|]/g, '_') });
  if (r.canceled || !r.filePath) return false;
  try { fs.writeFileSync(r.filePath, String(text), 'utf8'); return true; } catch (err) { return false; }
});
ipcMain.handle('desk-open', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters: [{ name: 'Backup', extensions: ['json', 'txt'] }] });
  if (r.canceled || !r.filePaths[0]) return null;
  try { return fs.readFileSync(r.filePaths[0], 'utf8'); } catch (err) { return null; }
});
ipcMain.handle('desk-update', () => checkUpdate());
ipcMain.handle('desk-startup', () => setStartup(!startupOn()));

/* ---------- lifecycle ---------- */
app.on('second-instance', showWindow);
app.whenReady().then(() => {
  loadPayload();
  // Start with Windows is on by default the first time the app runs.
  if (app.isPackaged && !fs.existsSync(path.join(app.getPath('userData'), 'startup-set'))) setStartup(true);
  createWindow();
  createTray();
  setupUpdater();
  setInterval(tickReminders, 15000);
});
app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', () => { /* stay in the tray */ });
