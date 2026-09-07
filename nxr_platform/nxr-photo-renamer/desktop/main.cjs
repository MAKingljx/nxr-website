const { app, BrowserWindow, dialog, Menu, protocol, session } = require('electron');
const { readFile } = require('node:fs/promises');
const path = require('node:path');
const { APP_URL, CSP, CONTENT_TYPES, isAppUrl, assetPath } = require('./policy.cjs');

app.setName('NXR Photo Renamer');
// The renderer uses packaged resources only, including QR/OCR and WebP WASM.
app.commandLine.appendSwitch('disable-background-networking');
protocol.registerSchemesAsPrivileged([{ scheme: 'nxr', privileges: {
  standard: true, secure: true, supportFetchAPI: true, corsEnabled: true,
} }]);

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    const win = BrowserWindow.getAllWindows()[0];
    if (win) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); }
  });
  app.whenReady().then(async () => {
    const ses = session.defaultSession;
    ses.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*', 'ws://*/*', 'wss://*/*'] }, (_details, callback) => callback({ cancel: true }));
    ses.setPermissionCheckHandler((_contents, permission, origin) => permission === 'fileSystem' && isAppUrl(origin));
    ses.setPermissionRequestHandler((contents, permission, callback) => callback(permission === 'fileSystem' && isAppUrl(contents.getURL())));
    ses.on('file-system-access-restricted', async (_event, _details, callback) => {
      const { response } = await dialog.showMessageBox({ type: 'info', title: '请选择照片文件夹',
        message: '这个位置受系统保护，请选择存放照片的子文件夹。',
        buttons: ['重新选择', '取消'], defaultId: 0, cancelId: 1 });
      callback(response === 0 ? 'tryAgain' : 'deny');
    });
    await protocol.handle('nxr', async request => {
      const relative = assetPath(request.url);
      if (request.method !== 'GET' || !relative) return new Response('Not found', { status: 404 });
      try {
        const data = await readFile(path.join(__dirname, '..', 'dist', relative));
        return new Response(data, { headers: {
          'Content-Type': CONTENT_TYPES[path.extname(relative)],
          'Content-Security-Policy': CSP, 'X-Content-Type-Options': 'nosniff',
        } });
      } catch { return new Response('Not found', { status: 404 }); }
    });
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      ...(process.platform === 'darwin' ? [{ label: 'NXR 卡片图片命名', submenu: [
        { label: '关于 NXR 卡片图片命名', role: 'about' }, { type: 'separator' },
        { label: '隐藏', role: 'hide' }, { label: '退出', role: 'quit' },
      ] }] : []),
      { label: '文件', submenu: [{ label: '关闭窗口', role: 'close' }, ...(process.platform === 'win32' ? [{ label: '退出', role: 'quit' }] : [])] },
      { label: '编辑', submenu: [
        { label: '撤销', role: 'undo' }, { label: '重做', role: 'redo' }, { type: 'separator' },
        { label: '剪切', role: 'cut' }, { label: '复制', role: 'copy' }, { label: '粘贴', role: 'paste' }, { label: '全选', role: 'selectAll' },
      ] },
      { label: '显示', submenu: [
        { label: '实际大小', role: 'resetZoom' }, { label: '放大', role: 'zoomIn' }, { label: '缩小', role: 'zoomOut' },
        { type: 'separator' }, { label: '全屏', role: 'togglefullscreen' },
      ] },
    ]));
    app.setAboutPanelOptions({ applicationName: 'NXR 卡片图片命名', applicationVersion: app.getVersion(),
      copyright: '图片在本机识别、转换与恢复。' });
    await createWindow();
    app.on('activate', () => { if (!BrowserWindow.getAllWindows().length) void createWindow(); });
  }).catch(error => {
    dialog.showErrorBox('启动未完成', error.message);
    app.quit();
  });
}

async function createWindow() {
  const win = new BrowserWindow({ width: 1280, height: 860, minWidth: 900, minHeight: 640,
    show: false, backgroundColor: '#f7f8fa', title: 'NXR 卡片图片命名',
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false,
      webSecurity: true, spellcheck: false, devTools: !app.isPackaged },
  });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (event, url) => { if (!isAppUrl(url)) event.preventDefault(); });
  win.webContents.on('will-attach-webview', event => event.preventDefault());
  win.webContents.on('will-prevent-unload', event => {
    const response = dialog.showMessageBoxSync(win, { type: 'question', title: '文件正在处理',
      message: '转换或恢复尚未完成，是否关闭窗口？', detail: '关闭后可重新选择原文件夹，从改名记录恢复。',
      buttons: ['继续处理', '关闭窗口'], defaultId: 0, cancelId: 0 });
    // In Electron preventDefault here explicitly allows the pending unload.
    if (response === 1) event.preventDefault();
  });
  win.once('ready-to-show', () => win.show());
  await win.loadURL(APP_URL);
}

app.on('window-all-closed', () => app.quit());
