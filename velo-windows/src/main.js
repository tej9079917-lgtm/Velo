'use strict';

const {
  app,
  BrowserWindow,
  ipcMain,
  dialog,
  shell,
  clipboard,
  session,
  nativeImage,
  webContents
} = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const https = require('https');
const { pathToFileURL } = require('url');
const cryptoEngine = require('./crypto-engine');

// Ensure single instance on Windows so double-clicking PDFs/MP4s/Images opens in existing Velo window
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
}

const AD_BLOCK_HOSTS = [
  'doubleclick.net',
  'googlesyndication.com',
  'googleadservices.com',
  'adservice.google.com',
  'pagead2.googlesyndication.com',
  'adnxs.com',
  'amazon-adsystem.com',
  'taboola.com',
  'outbrain.com',
  'scorecardresearch.com',
  'criteo.com',
  'pubmatic.com',
  'rubiconproject.com',
  'openx.net',
  'advertising.com',
  'moatads.com',
  'quantserve.com',
  'hotjar.com',
  'mixpanel.com',
  'ads-twitter.com',
  'analytics.tiktok.com',
  'pixel.facebook.com',
  'connect.facebook.net'
];

const SUPPORTED_MEDIA_EXTS = new Set([
  '.pdf', '.mp4', '.webm', '.mkv', '.mov', '.avi', '.m4v',
  '.mp3', '.wav', '.ogg', '.m4a', '.flac',
  '.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp'
]);

const DEFAULT_VAULT_TRIGGER = '#0000';

let mainWindow = null;
let pendingLaunchTargets = [];
let adBlockEnabled = true;
let totalBlockedCount = 0;

// In-memory cryptographic session keys (never written to disk)
let passwordMasterKey = null;
let mediaVaultMasterKey = null;
let clipboardClearTimer = null;
let lastCopiedSecret = null;
let activeSpeedTestAbort = null;

// --- File Paths ---
function userDataPath(...parts) {
  return path.join(app.getPath('userData'), ...parts);
}

function getDownloadsDir() {
  const dir = path.join(app.getPath('downloads'), 'Velo');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function getVaultFilesDir() {
  const dir = userDataPath('velo_secret_vault');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function getVaultPreviewDir() {
  const dir = userDataPath('velo_vault_preview');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function wipeVaultPreviews() {
  try {
    const dir = userDataPath('velo_vault_preview');
    if (fs.existsSync(dir)) {
      for (const entry of fs.readdirSync(dir)) {
        try {
          fs.unlinkSync(path.join(dir, entry));
        } catch {}
      }
    }
  } catch {}
}

function readJsonSafe(filePath, fallback) {
  try {
    if (!fs.existsSync(filePath)) return fallback;
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    return fallback;
  }
}

function writeJsonAtomic(filePath, data) {
  const tmpPath = `${filePath}.${crypto.randomBytes(4).toString('hex')}.tmp`;
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(tmpPath, JSON.stringify(data, null, 2), 'utf8');
  fs.renameSync(tmpPath, filePath);
}

function sendToRenderer(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload);
  }
}

function sanitizeFilename(name, fallback = 'download') {
  const cleaned = String(name || fallback)
    .replace(/[<>:"/\\|?*\x00-\x1f]/g, '_')
    .replace(/\s+/g, ' ')
    .trim();
  return cleaned.slice(0, 140) || fallback;
}

function uniqueDestPath(dir, filename) {
  const parsed = path.parse(sanitizeFilename(filename));
  let candidate = path.join(dir, `${parsed.name}${parsed.ext}`);
  let idx = 1;
  while (fs.existsSync(candidate)) {
    candidate = path.join(dir, `${parsed.name} (${idx++})${parsed.ext}`);
  }
  return candidate;
}

function classifyMediaType(filePathOrMime) {
  const lower = String(filePathOrMime || '').toLowerCase();
  if (lower.includes('pdf') || lower.endsWith('.pdf')) return 'pdf';
  if (
    lower.startsWith('video/') ||
    /\.(mp4|webm|mkv|mov|avi|m4v)$/.test(lower)
  ) return 'video';
  if (
    lower.startsWith('audio/') ||
    /\.(mp3|wav|ogg|m4a|flac|aac)$/.test(lower)
  ) return 'audio';
  if (
    lower.startsWith('image/') ||
    /\.(jpg|jpeg|png|webp|gif|bmp|svg)$/.test(lower)
  ) return 'image';
  return 'file';
}

function mimeFromExt(filePath) {
  const ext = path.extname(String(filePath || '')).toLowerCase();
  const map = {
    '.pdf': 'application/pdf',
    '.mp4': 'video/mp4',
    '.webm': 'video/webm',
    '.mkv': 'video/x-matroska',
    '.mov': 'video/quicktime',
    '.mp3': 'audio/mpeg',
    '.wav': 'audio/wav',
    '.ogg': 'audio/ogg',
    '.m4a': 'audio/mp4',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.png': 'image/png',
    '.webp': 'image/webp',
    '.gif': 'image/gif'
  };
  return map[ext] || 'application/octet-stream';
}

// --- Downloads Store ---
const DOWNLOADS_DB_PATH = () => userDataPath('velo-downloads.json');
const STATE_DB_PATH = () => userDataPath('velo-state.json');
const PW_VAULT_PATH = () => userDataPath('velo-password-vault.json');
const MEDIA_VAULT_PATH = () => userDataPath('velo-media-vault.json');

function loadDownloads() {
  return readJsonSafe(DOWNLOADS_DB_PATH(), []);
}

function upsertDownloadRecord(record) {
  const list = loadDownloads();
  const idx = list.findIndex((item) => item.id === record.id);
  if (idx >= 0) {
    list[idx] = { ...list[idx], ...record };
  } else {
    list.unshift(record);
  }
  const trimmed = list.slice(0, 200);
  writeJsonAtomic(DOWNLOADS_DB_PATH(), trimmed);
  sendToRenderer('velo:download-updated', record);
  return record;
}

// --- Session Ad-Blocker & Download Hook ---
const hookedSessions = new WeakSet();

function attachSessionHooks(ses) {
  if (!ses || hookedSessions.has(ses)) return;
  hookedSessions.add(ses);

  // Ad & Tracker Blocker
  ses.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (details, callback) => {
    if (!adBlockEnabled) {
      return callback({ cancel: false });
    }
    try {
      const host = new URL(details.url).hostname.toLowerCase();
      const matched = AD_BLOCK_HOSTS.some((bad) => host === bad || host.endsWith(`.${bad}`));
      if (matched) {
        totalBlockedCount += 1;
        sendToRenderer('velo:tracker-blocked', { total: totalBlockedCount, host });
        return callback({ cancel: true });
      }
    } catch {}
    callback({ cancel: false });
  });

  // Native Chromium Download Interceptor -> saves directly into Downloads/Velo
  ses.on('will-download', (_event, item) => {
    const id = crypto.randomUUID();
    const downloadsDir = getDownloadsDir();
    const rawName = item.getFilename() || 'download';
    const savePath = uniqueDestPath(downloadsDir, rawName);
    item.setSavePath(savePath);

    const record = {
      id,
      filename: path.basename(savePath),
      filePath: savePath,
      fileUrl: pathToFileURL(savePath).href,
      url: item.getURL(),
      mimeType: item.getMimeType() || mimeFromExt(savePath),
      mediaType: classifyMediaType(item.getMimeType() || savePath),
      receivedBytes: 0,
      totalBytes: item.getTotalBytes(),
      state: 'in-progress',
      createdAt: Date.now()
    };
    upsertDownloadRecord(record);

    item.on('updated', (_e, state) => {
      upsertDownloadRecord({
        ...record,
        receivedBytes: item.getReceivedBytes(),
        totalBytes: item.getTotalBytes(),
        state: state === 'interrupted' ? 'interrupted' : 'in-progress'
      });
    });

    item.once('done', (_e, state) => {
      let finalSize = item.getReceivedBytes();
      try {
        if (fs.existsSync(savePath)) {
          finalSize = fs.statSync(savePath).size;
        }
      } catch {}
      upsertDownloadRecord({
        ...record,
        receivedBytes: finalSize,
        totalBytes: finalSize || item.getTotalBytes(),
        state: state === 'completed' ? 'completed' : state
      });
    });
  });
}

// --- Parse CLI Args for Windows File Associations ---
function extractTargetsFromArgv(argv) {
  const targets = [];
  for (const arg of argv.slice(1)) {
    if (!arg || arg.startsWith('-')) continue;
    if (/^https?:\/\//i.test(arg)) {
      targets.push({ kind: 'url', value: arg });
      continue;
    }
    try {
      const resolved = path.resolve(arg);
      if (fs.existsSync(resolved) && fs.statSync(resolved).isFile()) {
        const ext = path.extname(resolved).toLowerCase();
        if (SUPPORTED_MEDIA_EXTS.has(ext)) {
          targets.push({
            kind: 'file',
            filePath: resolved,
            fileUrl: pathToFileURL(resolved).href,
            filename: path.basename(resolved),
            mimeType: mimeFromExt(resolved),
            mediaType: classifyMediaType(resolved)
          });
        }
      }
    } catch {}
  }
  return targets;
}

// --- Create Main Browser Window ---
function createMainWindow() {
  const iconCandidate = path.join(__dirname, '..', 'build', 'icon.png');
  const winOpts = {
    width: 1360,
    height: 860,
    minWidth: 960,
    minHeight: 620,
    backgroundColor: '#090D16',
    title: 'Velo Browser',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      webviewTag: true,
      sandbox: false
    }
  };
  if (fs.existsSync(iconCandidate)) {
    winOpts.icon = iconCandidate;
  }

  mainWindow = new BrowserWindow(winOpts);
  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  // Prevent main shell from navigating away from Velo UI
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith('file://')) {
      event.preventDefault();
    }
  });

  mainWindow.webContents.on('did-finish-load', () => {
    if (pendingLaunchTargets.length > 0) {
      for (const target of pendingLaunchTargets) {
        sendToRenderer('velo:open-external-target', target);
      }
      pendingLaunchTargets = [];
    }
  });

  // Auto-lock sensitive vaults when window is minimized
  mainWindow.on('minimize', () => {
    lockAllSensitiveVaults();
  });

  mainWindow.on('closed', () => {
    lockAllSensitiveVaults();
    mainWindow = null;
  });
}

function lockAllSensitiveVaults() {
  if (passwordMasterKey) {
    passwordMasterKey.fill(0);
    passwordMasterKey = null;
  }
  if (mediaVaultMasterKey) {
    mediaVaultMasterKey.fill(0);
    mediaVaultMasterKey = null;
  }
  wipeVaultPreviews();
  if (mainWindow && !mainWindow.isDestroyed()) {
    try { mainWindow.setContentProtection(false); } catch {}
    sendToRenderer('velo:vaults-auto-locked', {});
  }
}

// --- Direct Fetch Helper (Uses Browser Session Cookies + Referer) ---
async function fetchBufferWithSession(url, referer) {
  if (url.startsWith('data:')) {
    const match = url.match(/^data:([^;]+);base64,(.+)$/);
    if (!match) throw new Error('Unsupported data URL format.');
    return {
      buffer: Buffer.from(match[2], 'base64'),
      contentType: match[1]
    };
  }
  const ses = session.fromPartition('persist:velo-main');
  const headers = {
    'User-Agent': ses.getUserAgent()
  };
  if (referer && /^https?:\/\//i.test(referer)) {
    headers.Referer = referer;
  }
  const response = await ses.fetch(url, { headers, redirect: 'follow' });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} while downloading resource.`);
  }
  const arrayBuf = await response.arrayBuffer();
  return {
    buffer: Buffer.from(arrayBuf),
    contentType: response.headers.get('content-type') || 'application/octet-stream'
  };
}

// --- Offscreen HTML -> PDF Renderer ---
async function renderHtmlToPdfBuffer(htmlContent, pageSize = 'A4') {
  const hiddenWin = new BrowserWindow({
    show: false,
    width: 1200,
    height: 1600,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  try {
    const dataUrl = `data:text/html;charset=utf-8,${encodeURIComponent(htmlContent)}`;
    await hiddenWin.loadURL(dataUrl);
    await new Promise((r) => setTimeout(r, 250));
    const pdfBuf = await hiddenWin.webContents.printToPDF({
      printBackground: true,
      pageSize,
      margins: { top: 0.25, bottom: 0.25, left: 0.25, right: 0.25 }
    });
    return pdfBuf;
  } finally {
    if (!hiddenWin.isDestroyed()) hiddenWin.destroy();
  }
}

// --- Offscreen Image -> WebP Converter ---
async function convertImageBufferToWebp(imgBuffer, mime) {
  const hiddenWin = new BrowserWindow({
    show: false,
    width: 64,
    height: 64,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  try {
    await hiddenWin.loadURL('data:text/html,<!doctype html><html><body></body></html>');
    const b64 = imgBuffer.toString('base64');
    const safeMime = (mime || 'image/png').split(';')[0];
    const webpDataUrl = await hiddenWin.webContents.executeJavaScript(`
      new Promise((resolve, reject) => {
        const img = new Image();
        img.onload = () => {
          const c = document.createElement('canvas');
          c.width = img.naturalWidth || img.width;
          c.height = img.naturalHeight || img.height;
          const ctx = c.getContext('2d');
          ctx.drawImage(img, 0, 0);
          resolve(c.toDataURL('image/webp', 0.95));
        };
        img.onerror = () => reject(new Error('Image decode failed'));
        img.src = ${JSON.stringify(`data:${safeMime};base64,${b64}`)};
      });
    `);
    const comma = webpDataUrl.indexOf(',');
    return Buffer.from(webpDataUrl.slice(comma + 1), 'base64');
  } finally {
    if (!hiddenWin.isDestroyed()) hiddenWin.destroy();
  }
}

// --- App Lifecycle ---
app.on('session-created', (ses) => {
  attachSessionHooks(ses);
});

app.on('second-instance', (_event, argv) => {
  const targets = extractTargetsFromArgv(argv);
  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
    for (const target of targets) {
      sendToRenderer('velo:open-external-target', target);
    }
  }
});

app.whenReady().then(() => {
  const savedState = readJsonSafe(STATE_DB_PATH(), {});
  if (typeof savedState.adBlockEnabled === 'boolean') {
    adBlockEnabled = savedState.adBlockEnabled;
  }

  attachSessionHooks(session.defaultSession);
  attachSessionHooks(session.fromPartition('persist:velo-main'));

  pendingLaunchTargets = extractTargetsFromArgv(process.argv);
  wipeVaultPreviews();
  createMainWindow();
});

app.on('before-quit', () => {
  lockAllSensitiveVaults();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

// =========================================================================
// IPC HANDLERS
// =========================================================================

// --- State & Settings ---
ipcMain.handle('state:load', () => {
  const defaults = {
    theme: 'dark',
    adBlockEnabled: true,
    searchEngine: 'google',
    appsBarVisible: true,
    bookmarks: [
      { id: 'bm-1', title: 'Google', url: 'https://www.google.com' },
      { id: 'bm-2', title: 'YouTube', url: 'https://www.youtube.com' },
      { id: 'bm-3', title: 'GitHub', url: 'https://github.com' },
      { id: 'bm-4', title: 'Wikipedia', url: 'https://www.wikipedia.org' }
    ],
    history: []
  };
  const saved = readJsonSafe(STATE_DB_PATH(), {});
  return { ...defaults, ...saved, totalBlockedCount };
});

ipcMain.handle('state:save', (_event, partial) => {
  const current = readJsonSafe(STATE_DB_PATH(), {});
  const next = { ...current, ...partial };
  if (typeof next.adBlockEnabled === 'boolean') {
    adBlockEnabled = next.adBlockEnabled;
  }
  writeJsonAtomic(STATE_DB_PATH(), next);
  return { ok: true };
});

ipcMain.handle('window:set-content-protection', (_event, enabled) => {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.setContentProtection(Boolean(enabled));
  }
  return { ok: true };
});

// --- Universal Media & Image Downloader ---
ipcMain.handle('downloads:list', () => loadDownloads());

ipcMain.handle('downloads:url', async (_event, { url, filename, referer }) => {
  if (!url || (!/^https?:\/\//i.test(url) && !url.startsWith('data:'))) {
    throw new Error('Only HTTP, HTTPS, or Data URLs can be downloaded.');
  }
  const id = crypto.randomUUID();
  const downloadsDir = getDownloadsDir();
  const inferredName = filename || (() => {
    try {
      const p = new URL(url).pathname;
      const base = path.basename(p);
      return base && base.includes('.') ? base : `velo_media_${Date.now()}.mp4`;
    } catch {
      return `velo_media_${Date.now()}.bin`;
    }
  })();

  const savePath = uniqueDestPath(downloadsDir, inferredName);
  upsertDownloadRecord({
    id,
    filename: path.basename(savePath),
    filePath: savePath,
    fileUrl: pathToFileURL(savePath).href,
    url,
    mimeType: mimeFromExt(savePath),
    mediaType: classifyMediaType(savePath),
    receivedBytes: 0,
    totalBytes: 0,
    state: 'in-progress',
    createdAt: Date.now()
  });

  try {
    const { buffer, contentType } = await fetchBufferWithSession(url, referer);
    fs.writeFileSync(savePath, buffer);
    const done = upsertDownloadRecord({
      id,
      filename: path.basename(savePath),
      filePath: savePath,
      fileUrl: pathToFileURL(savePath).href,
      url,
      mimeType: contentType || mimeFromExt(savePath),
      mediaType: classifyMediaType(contentType || savePath),
      receivedBytes: buffer.length,
      totalBytes: buffer.length,
      state: 'completed',
      createdAt: Date.now()
    });
    return done;
  } catch (err) {
    upsertDownloadRecord({
      id,
      state: 'interrupted',
      error: err.message
    });
    throw err;
  }
});

ipcMain.handle('downloads:image-format', async (_event, { url, referer, format, suggestedName }) => {
  const fmt = String(format || 'original').toLowerCase();
  const { buffer, contentType } = await fetchBufferWithSession(url, referer);
  const downloadsDir = getDownloadsDir();
  const baseName = sanitizeFilename(suggestedName || `velo_image_${Date.now()}`).replace(/\.[^.]+$/, '');

  let outBuffer = buffer;
  let ext = '.jpg';
  let outMime = contentType || 'image/jpeg';

  if (fmt === 'original') {
    if (contentType.includes('png')) { ext = '.png'; outMime = 'image/png'; }
    else if (contentType.includes('webp')) { ext = '.webp'; outMime = 'image/webp'; }
    else if (contentType.includes('gif')) { ext = '.gif'; outMime = 'image/gif'; }
    else if (contentType.includes('svg')) { ext = '.svg'; outMime = 'image/svg+xml'; }
    else { ext = '.jpg'; outMime = 'image/jpeg'; }
  } else if (fmt === 'png') {
    const img = nativeImage.createFromBuffer(buffer);
    if (img.isEmpty()) throw new Error('Unable to decode image for PNG conversion.');
    outBuffer = img.toPNG();
    ext = '.png';
    outMime = 'image/png';
  } else if (fmt === 'jpeg' || fmt === 'jpg') {
    const img = nativeImage.createFromBuffer(buffer);
    if (img.isEmpty()) throw new Error('Unable to decode image for JPEG conversion.');
    outBuffer = img.toJPEG(95);
    ext = '.jpg';
    outMime = 'image/jpeg';
  } else if (fmt === 'webp') {
    if (contentType.includes('webp')) {
      outBuffer = buffer;
    } else {
      outBuffer = await convertImageBufferToWebp(buffer, contentType);
    }
    ext = '.webp';
    outMime = 'image/webp';
  } else if (fmt === 'pdf') {
    const safeMime = (contentType || 'image/png').split(';')[0];
    const dataUri = `data:${safeMime};base64,${buffer.toString('base64')}`;
    const html = `<!doctype html><html><head><style>
      html,body{margin:0;padding:0;background:#fff;display:flex;align-items:center;justify-content:center;min-height:100vh;}
      img{max-width:100%;max-height:100vh;object-fit:contain;}
    </style></head><body><img src="${dataUri}" /></body></html>`;
    outBuffer = await renderHtmlToPdfBuffer(html, 'A4');
    ext = '.pdf';
    outMime = 'application/pdf';
  } else {
    throw new Error(`Unsupported target format: ${fmt}`);
  }

  const savePath = uniqueDestPath(downloadsDir, `${baseName}${ext}`);
  fs.writeFileSync(savePath, outBuffer);

  return upsertDownloadRecord({
    id: crypto.randomUUID(),
    filename: path.basename(savePath),
    filePath: savePath,
    fileUrl: pathToFileURL(savePath).href,
    url,
    mimeType: outMime,
    mediaType: classifyMediaType(outMime),
    receivedBytes: outBuffer.length,
    totalBytes: outBuffer.length,
    state: 'completed',
    createdAt: Date.now()
  });
});

ipcMain.handle('downloads:open-external', async (_event, filePath) => {
  if (!filePath || !fs.existsSync(filePath)) throw new Error('File no longer exists.');
  const errMsg = await shell.openPath(filePath);
  if (errMsg) throw new Error(errMsg);
  return { ok: true };
});

ipcMain.handle('downloads:show-in-folder', (_event, filePath) => {
  if (filePath && fs.existsSync(filePath)) {
    shell.showItemInFolder(filePath);
    return { ok: true };
  }
  shell.openPath(getDownloadsDir());
  return { ok: true };
});

ipcMain.handle('downloads:delete', (_event, id) => {
  const list = loadDownloads();
  const target = list.find((d) => d.id === id);
  if (target && target.filePath && fs.existsSync(target.filePath)) {
    try { fs.unlinkSync(target.filePath); } catch {}
  }
  const filtered = list.filter((d) => d.id !== id);
  writeJsonAtomic(DOWNLOADS_DB_PATH(), filtered);
  return { ok: true };
});

// --- PDF Viewer & Converter ---
ipcMain.handle('pdf:print-webview', async (_event, { webContentsId, title }) => {
  const targetWc = webContents.fromId(Number(webContentsId));
  if (!targetWc) throw new Error('Active webpage is not ready to export as PDF.');
  const pdfBuf = await targetWc.printToPDF({
    printBackground: true,
    pageSize: 'A4',
    margins: { top: 0.35, bottom: 0.35, left: 0.35, right: 0.35 }
  });
  const baseName = sanitizeFilename(title || `velo_page_${Date.now()}`);
  const savePath = uniqueDestPath(getDownloadsDir(), `${baseName}.pdf`);
  fs.writeFileSync(savePath, pdfBuf);

  return upsertDownloadRecord({
    id: crypto.randomUUID(),
    filename: path.basename(savePath),
    filePath: savePath,
    fileUrl: pathToFileURL(savePath).href,
    url: targetWc.getURL(),
    mimeType: 'application/pdf',
    mediaType: 'pdf',
    receivedBytes: pdfBuf.length,
    totalBytes: pdfBuf.length,
    state: 'completed',
    createdAt: Date.now()
  });
});

ipcMain.handle('files:pick', async (_event, { kind, multiple }) => {
  const filtersByKind = {
    pdf: [{ name: 'PDF Documents', extensions: ['pdf'] }],
    video: [{ name: 'Videos', extensions: ['mp4', 'webm', 'mkv', 'mov', 'avi', 'm4v'] }],
    audio: [{ name: 'Audio Files', extensions: ['mp3', 'wav', 'ogg', 'm4a', 'flac'] }],
    image: [{ name: 'Images', extensions: ['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp'] }],
    media: [{ name: 'Videos, Images, PDFs & Audio', extensions: ['mp4', 'webm', 'mkv', 'mov', 'jpg', 'jpeg', 'png', 'webp', 'gif', 'pdf', 'mp3', 'wav', 'ogg', 'm4a'] }]
  };
  const properties = ['openFile'];
  if (multiple) properties.push('multiSelections');

  const res = await dialog.showOpenDialog(mainWindow, {
    title: 'Select Files',
    properties,
    filters: filtersByKind[kind] || filtersByKind.media
  });
  if (res.canceled || !res.filePaths.length) return [];
  return res.filePaths.map((fp) => ({
    filePath: fp,
    fileUrl: pathToFileURL(fp).href,
    filename: path.basename(fp),
    mimeType: mimeFromExt(fp),
    mediaType: classifyMediaType(fp),
    byteSize: fs.statSync(fp).size
  }));
});

ipcMain.handle('pdf:images-to-pdf', async (_event, { filePaths, outputName }) => {
  if (!Array.isArray(filePaths) || filePaths.length === 0) {
    throw new Error('Select at least one image.');
  }
  const pages = [];
  for (const fp of filePaths) {
    if (!fs.existsSync(fp)) continue;
    const buf = fs.readFileSync(fp);
    const mime = mimeFromExt(fp);
    const dataUri = `data:${mime};base64,${buf.toString('base64')}`;
    pages.push(`<div class="page"><img src="${dataUri}" /></div>`);
  }
  if (!pages.length) throw new Error('No readable images selected.');

  const html = `<!doctype html><html><head><style>
    @page { size: A4; margin: 10mm; }
    html,body { margin: 0; padding: 0; background: #fff; }
    .page { page-break-after: always; display: flex; align-items: center; justify-content: center; width: 100%; height: 96vh; }
    .page:last-child { page-break-after: auto; }
    img { max-width: 100%; max-height: 100%; object-fit: contain; }
  </style></head><body>${pages.join('')}</body></html>`;

  const pdfBuf = await renderHtmlToPdfBuffer(html, 'A4');
  const baseName = sanitizeFilename(outputName || `velo_images_${Date.now()}`).replace(/\.pdf$/i, '');
  const savePath = uniqueDestPath(getDownloadsDir(), `${baseName}.pdf`);
  fs.writeFileSync(savePath, pdfBuf);

  return upsertDownloadRecord({
    id: crypto.randomUUID(),
    filename: path.basename(savePath),
    filePath: savePath,
    fileUrl: pathToFileURL(savePath).href,
    url: 'velo://pdf-converter',
    mimeType: 'application/pdf',
    mediaType: 'pdf',
    receivedBytes: pdfBuf.length,
    totalBytes: pdfBuf.length,
    state: 'completed',
    createdAt: Date.now()
  });
});

// --- Real Internet Speed Test Meter ---
function httpsPing(url, signal) {
  return new Promise((resolve, reject) => {
    const start = performance.now();
    const req = https.get(url, { headers: { 'Cache-Control': 'no-store' } }, (res) => {
      res.resume();
      res.on('end', () => resolve(performance.now() - start));
    });
    req.on('error', reject);
    if (signal) {
      signal.addEventListener('abort', () => {
        req.destroy(new Error('Cancelled'));
      }, { once: true });
    }
  });
}

function httpsMeasureDownload(url, signal, onTick) {
  return new Promise((resolve, reject) => {
    const start = performance.now();
    let bytes = 0;
    let lastEmit = start;
    const req = https.get(url, { headers: { 'Cache-Control': 'no-store' } }, (res) => {
      if (res.statusCode && res.statusCode >= 400) {
        reject(new Error(`HTTP ${res.statusCode}`));
        return;
      }
      res.on('data', (chunk) => {
        bytes += chunk.length;
        const now = performance.now();
        if (now - lastEmit >= 120) {
          const secs = Math.max((now - start) / 1000, 0.01);
          onTick((bytes * 8) / (secs * 1e6));
          lastEmit = now;
        }
      });
      res.on('end', () => {
        const secs = Math.max((performance.now() - start) / 1000, 0.01);
        resolve((bytes * 8) / (secs * 1e6));
      });
    });
    req.on('error', reject);
    if (signal) {
      signal.addEventListener('abort', () => req.destroy(new Error('Cancelled')), { once: true });
    }
  });
}

function httpsMeasureUpload(url, totalBytes, signal, onTick) {
  return new Promise((resolve, reject) => {
    const payload = crypto.randomBytes(totalBytes);
    const start = performance.now();
    const req = https.request(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/octet-stream',
        'Content-Length': payload.length
      }
    }, (res) => {
      res.resume();
      res.on('end', () => {
        const secs = Math.max((performance.now() - start) / 1000, 0.01);
        const mbps = (payload.length * 8) / (secs * 1e6);
        onTick(mbps);
        resolve(mbps);
      });
    });
    req.on('error', reject);
    if (signal) {
      signal.addEventListener('abort', () => req.destroy(new Error('Cancelled')), { once: true });
    }
    const chunkSize = 128 * 1024;
    let offset = 0;
    const writeNext = () => {
      while (offset < payload.length) {
        const slice = payload.subarray(offset, Math.min(offset + chunkSize, payload.length));
        offset += slice.length;
        const ok = req.write(slice);
        const secs = Math.max((performance.now() - start) / 1000, 0.02);
        onTick((offset * 8) / (secs * 1e6));
        if (!ok) {
          req.once('drain', writeNext);
          return;
        }
      }
      req.end();
    };
    writeNext();
  });
}

ipcMain.handle('speedtest:run', async () => {
  if (activeSpeedTestAbort) {
    activeSpeedTestAbort.abort();
  }
  const ac = new AbortController();
  activeSpeedTestAbort = ac;

  try {
    // 1. Ping & Jitter
    sendToRenderer('velo:speedtest-progress', { phase: 'ping', mbps: 0, progress: 0.1 });
    const pings = [];
    for (let i = 0; i < 5; i++) {
      const ms = await httpsPing(`https://speed.cloudflare.com/__down?bytes=0&t=${Date.now()}_${i}`, ac.signal);
      pings.push(ms);
    }
    const avgPing = pings.reduce((a, b) => a + b, 0) / pings.length;
    let jitterSum = 0;
    for (let i = 1; i < pings.length; i++) jitterSum += Math.abs(pings[i] - pings[i - 1]);
    const jitter = pings.length > 1 ? jitterSum / (pings.length - 1) : 0;

    sendToRenderer('velo:speedtest-progress', {
      phase: 'download',
      pingMs: Math.round(avgPing),
      jitterMs: Math.round(jitter),
      mbps: 0,
      progress: 0.25
    });

    // 2. Download (10 MB)
    const downloadMbps = await httpsMeasureDownload(
      `https://speed.cloudflare.com/__down?bytes=10000000&t=${Date.now()}`,
      ac.signal,
      (liveMbps) => {
        sendToRenderer('velo:speedtest-progress', {
          phase: 'download',
          pingMs: Math.round(avgPing),
          jitterMs: Math.round(jitter),
          mbps: Number(liveMbps.toFixed(1)),
          progress: 0.55
        });
      }
    );

    // 3. Upload (4 MB)
    sendToRenderer('velo:speedtest-progress', {
      phase: 'upload',
      pingMs: Math.round(avgPing),
      jitterMs: Math.round(jitter),
      downloadMbps: Number(downloadMbps.toFixed(1)),
      mbps: 0,
      progress: 0.72
    });

    const uploadMbps = await httpsMeasureUpload(
      'https://speed.cloudflare.com/__up',
      4 * 1024 * 1024,
      ac.signal,
      (liveMbps) => {
        sendToRenderer('velo:speedtest-progress', {
          phase: 'upload',
          pingMs: Math.round(avgPing),
          jitterMs: Math.round(jitter),
          downloadMbps: Number(downloadMbps.toFixed(1)),
          mbps: Number(liveMbps.toFixed(1)),
          progress: 0.9
        });
      }
    );

    const result = {
      phase: 'done',
      pingMs: Math.round(avgPing),
      jitterMs: Math.round(jitter),
      downloadMbps: Number(downloadMbps.toFixed(1)),
      uploadMbps: Number(uploadMbps.toFixed(1)),
      testedAt: Date.now()
    };
    sendToRenderer('velo:speedtest-progress', result);
    return result;
  } finally {
    if (activeSpeedTestAbort === ac) activeSpeedTestAbort = null;
  }
});

ipcMain.handle('speedtest:cancel', () => {
  if (activeSpeedTestAbort) {
    activeSpeedTestAbort.abort();
    activeSpeedTestAbort = null;
  }
  return { ok: true };
});

// --- 3-Layer Multi-Layer Encrypted Password Vault (MLV2:) ---
function readPasswordVaultStore() {
  return readJsonSafe(PW_VAULT_PATH(), { config: null, items: [] });
}

ipcMain.handle('pw:status', () => {
  const store = readPasswordVaultStore();
  return {
    configured: Boolean(store.config),
    unlocked: Boolean(passwordMasterKey),
    count: Array.isArray(store.items) ? store.items.length : 0,
    keyFingerprint: store.config ? store.config.keyFingerprint : null,
    iterations: store.config ? store.config.iterations : cryptoEngine.KDF_ITERATIONS
  };
});

ipcMain.handle('pw:setup', (_event, passphrase) => {
  if (!passphrase || passphrase.length < 6) {
    throw new Error('Master passphrase must be at least 6 characters.');
  }
  const { config, masterKey } = cryptoEngine.createVaultConfig(passphrase);
  if (passwordMasterKey) passwordMasterKey.fill(0);
  passwordMasterKey = masterKey;
  writeJsonAtomic(PW_VAULT_PATH(), { config, items: [] });
  return { ok: true, keyFingerprint: config.keyFingerprint };
});

ipcMain.handle('pw:unlock', (_event, passphrase) => {
  const store = readPasswordVaultStore();
  if (!store.config) throw new Error('Password vault is not set up yet.');
  const masterKey = cryptoEngine.verifyAndUnlockVault(passphrase || '', store.config);
  if (!masterKey) {
    throw new Error('Incorrect master passphrase.');
  }
  if (passwordMasterKey) passwordMasterKey.fill(0);
  passwordMasterKey = masterKey;
  return { ok: true, keyFingerprint: store.config.keyFingerprint };
});

ipcMain.handle('pw:lock', () => {
  if (passwordMasterKey) {
    passwordMasterKey.fill(0);
    passwordMasterKey = null;
  }
  return { ok: true };
});

ipcMain.handle('pw:list', () => {
  if (!passwordMasterKey) throw new Error('Vault is locked.');
  const store = readPasswordVaultStore();
  return (store.items || []).map((entry) => {
    const jsonStr = cryptoEngine.decryptMultiLayer(entry.ciphertext, entry.iv, passwordMasterKey);
    const payload = JSON.parse(jsonStr);
    return {
      id: entry.id,
      siteName: payload.siteName || '',
      siteUrl: payload.siteUrl || '',
      username: payload.username || '',
      password: payload.password || '',
      notes: payload.notes || '',
      updatedAt: entry.updatedAt || Date.now()
    };
  });
});

ipcMain.handle('pw:save', (_event, item) => {
  if (!passwordMasterKey) throw new Error('Vault is locked.');
  const store = readPasswordVaultStore();
  const id = item.id || crypto.randomUUID();
  const payload = JSON.stringify({
    siteName: String(item.siteName || '').trim(),
    siteUrl: String(item.siteUrl || '').trim(),
    username: String(item.username || '').trim(),
    password: String(item.password || ''),
    notes: String(item.notes || '').trim()
  });
  const encrypted = cryptoEngine.encryptMultiLayer(payload, passwordMasterKey);
  const record = {
    id,
    ciphertext: encrypted.ciphertext,
    iv: encrypted.iv,
    updatedAt: Date.now()
  };
  const items = Array.isArray(store.items) ? store.items : [];
  const idx = items.findIndex((x) => x.id === id);
  if (idx >= 0) items[idx] = record;
  else items.unshift(record);
  writeJsonAtomic(PW_VAULT_PATH(), { ...store, items });
  return { ok: true, id };
});

ipcMain.handle('pw:delete', (_event, id) => {
  if (!passwordMasterKey) throw new Error('Vault is locked.');
  const store = readPasswordVaultStore();
  const items = (store.items || []).filter((x) => x.id !== id);
  writeJsonAtomic(PW_VAULT_PATH(), { ...store, items });
  return { ok: true };
});

ipcMain.handle('pw:copy-secret', (_event, secret) => {
  const str = String(secret || '');
  clipboard.writeText(str);
  lastCopiedSecret = str;
  if (clipboardClearTimer) clearTimeout(clipboardClearTimer);
  clipboardClearTimer = setTimeout(() => {
    try {
      if (clipboard.readText() === lastCopiedSecret) {
        clipboard.writeText('');
      }
    } catch {}
    lastCopiedSecret = null;
  }, 30000);
  return { ok: true, clearSeconds: 30 };
});

// --- Stealth Secret Media Vault (Chunked AES-256-GCM + Deletes Originals) ---
function readMediaVaultStore() {
  const existing = readJsonSafe(MEDIA_VAULT_PATH(), null);
  if (existing && existing.config) return existing;
  // Auto-initialize default stealth trigger "#0000" on first launch
  const { config } = cryptoEngine.createVaultConfig(DEFAULT_VAULT_TRIGGER);
  const initial = { config, isDefaultCode: true, items: [] };
  writeJsonAtomic(MEDIA_VAULT_PATH(), initial);
  return initial;
}

ipcMain.handle('vault:status', () => {
  const store = readMediaVaultStore();
  return {
    unlocked: Boolean(mediaVaultMasterKey),
    isDefaultCode: Boolean(store.isDefaultCode),
    count: Array.isArray(store.items) ? store.items.length : 0
  };
});

// Stealth omnibox check: returns { matched: true } ONLY when input exactly matches secret vault code
ipcMain.handle('vault:try-omnibox-code', (_event, rawInput) => {
  const code = String(rawInput || '').trim();
  if (!code || code.length < 3 || code.length > 64) return { matched: false };
  const store = readMediaVaultStore();
  const masterKey = cryptoEngine.verifyAndUnlockVault(code, store.config);
  if (!masterKey) return { matched: false };
  if (mediaVaultMasterKey) mediaVaultMasterKey.fill(0);
  mediaVaultMasterKey = masterKey;
  return { matched: true, isDefaultCode: Boolean(store.isDefaultCode) };
});

ipcMain.handle('vault:change-code', (_event, newCode) => {
  if (!mediaVaultMasterKey) throw new Error('Secret Vault is locked.');
  const trimmed = String(newCode || '').trim();
  if (trimmed.length < 4) throw new Error('Secret code must be at least 4 characters (e.g. #7788).');

  const store = readMediaVaultStore();
  const { config: newConfig, masterKey: newKey } = cryptoEngine.createVaultConfig(trimmed);
  const vaultDir = getVaultFilesDir();
  const tmpDir = getVaultPreviewDir();

  // Re-encrypt every stored file & metadata record under the new master key
  const reEncryptedItems = [];
  for (const item of (store.items || [])) {
    const metaJson = cryptoEngine.decryptMultiLayer(item.metaCiphertext, item.metaIv, mediaVaultMasterKey);
    const tmpPlain = path.join(tmpDir, `rekey_${crypto.randomUUID()}.bin`);
    try {
      cryptoEngine.decryptVaultFileToPath(vaultDir, item.encryptedFileName, item.fileIv, tmpPlain, mediaVaultMasterKey);
      const nextFile = cryptoEngine.encryptFileToVault(tmpPlain, vaultDir, newKey);
      const nextMeta = cryptoEngine.encryptMultiLayer(metaJson, newKey);
      // Remove old encrypted blob
      try { fs.unlinkSync(path.join(vaultDir, path.basename(item.encryptedFileName))); } catch {}
      reEncryptedItems.push({
        id: item.id,
        encryptedFileName: nextFile.encryptedFileName,
        fileIv: nextFile.ivBase64,
        byteSize: nextFile.byteSize,
        metaCiphertext: nextMeta.ciphertext,
        metaIv: nextMeta.iv,
        addedAt: item.addedAt
      });
    } finally {
      try { fs.unlinkSync(tmpPlain); } catch {}
    }
  }

  mediaVaultMasterKey.fill(0);
  mediaVaultMasterKey = newKey;
  writeJsonAtomic(MEDIA_VAULT_PATH(), {
    config: newConfig,
    isDefaultCode: false,
    items: reEncryptedItems
  });
  return { ok: true };
});

ipcMain.handle('vault:lock', () => {
  if (mediaVaultMasterKey) {
    mediaVaultMasterKey.fill(0);
    mediaVaultMasterKey = null;
  }
  wipeVaultPreviews();
  return { ok: true };
});

ipcMain.handle('vault:list', () => {
  if (!mediaVaultMasterKey) throw new Error('Secret Vault is locked.');
  const store = readMediaVaultStore();
  return (store.items || []).map((item) => {
    const meta = JSON.parse(
      cryptoEngine.decryptMultiLayer(item.metaCiphertext, item.metaIv, mediaVaultMasterKey)
    );
    return {
      id: item.id,
      displayName: meta.displayName,
      mimeType: meta.mimeType,
      mediaType: meta.mediaType,
      byteSize: item.byteSize,
      addedAt: item.addedAt
    };
  });
});

function hidePathsIntoVault(filePaths) {
  if (!mediaVaultMasterKey) throw new Error('Secret Vault is locked.');
  const store = readMediaVaultStore();
  const vaultDir = getVaultFilesDir();
  const items = Array.isArray(store.items) ? [...store.items] : [];
  const added = [];
  const warnings = [];

  for (const fp of filePaths) {
    if (!fs.existsSync(fp)) continue;
    const filename = path.basename(fp);
    const mimeType = mimeFromExt(fp);
    const mediaType = classifyMediaType(fp);

    const encFile = cryptoEngine.encryptFileToVault(fp, vaultDir, mediaVaultMasterKey);
    const encryptedFullPath = path.join(vaultDir, encFile.encryptedFileName);
    if (!fs.existsSync(encryptedFullPath) || fs.statSync(encryptedFullPath).size === 0) {
      throw new Error(`Encryption verification failed for ${filename}. Original file kept safe.`);
    }

    const metaEnc = cryptoEngine.encryptMultiLayer(JSON.stringify({
      displayName: filename,
      mimeType,
      mediaType
    }), mediaVaultMasterKey);

    const record = {
      id: crypto.randomUUID(),
      encryptedFileName: encFile.encryptedFileName,
      fileIv: encFile.ivBase64,
      byteSize: encFile.byteSize,
      metaCiphertext: metaEnc.ciphertext,
      metaIv: metaEnc.iv,
      addedAt: Date.now()
    };
    items.unshift(record);
    added.push({
      id: record.id,
      displayName: filename,
      mimeType,
      mediaType,
      byteSize: record.byteSize,
      addedAt: record.addedAt
    });

    // Permanently remove the original unencrypted file from Windows Explorer
    try {
      fs.unlinkSync(fp);
    } catch (err) {
      warnings.push(`Hidden in vault, but Windows locked original file ${filename}: ${err.message}`);
    }
  }

  writeJsonAtomic(MEDIA_VAULT_PATH(), { ...store, items });
  return { added, warnings };
}

ipcMain.handle('vault:hide-dialog', async () => {
  if (!mediaVaultMasterKey) throw new Error('Secret Vault is locked.');
  const res = await dialog.showOpenDialog(mainWindow, {
    title: 'Select Files to Encrypt & Hide in Velo Secret Vault',
    buttonLabel: 'Encrypt & Hide',
    properties: ['openFile', 'multiSelections'],
    filters: [
      {
        name: 'All Vault Media (Video, Image, PDF, Audio)',
        extensions: ['mp4', 'webm', 'mkv', 'mov', 'avi', 'jpg', 'jpeg', 'png', 'webp', 'gif', 'pdf', 'mp3', 'wav', 'ogg', 'm4a']
      },
      { name: 'All Files', extensions: ['*'] }
    ]
  });
  if (res.canceled || !res.filePaths.length) return { added: [], warnings: [] };
  return hidePathsIntoVault(res.filePaths);
});

ipcMain.handle('vault:hide-existing-path', (_event, filePath) => {
  if (!filePath || !fs.existsSync(filePath)) throw new Error('File not found.');
  return hidePathsIntoVault([filePath]);
});

ipcMain.handle('vault:open-item', (_event, id) => {
  if (!mediaVaultMasterKey) throw new Error('Secret Vault is locked.');
  const store = readMediaVaultStore();
  const item = (store.items || []).find((x) => x.id === id);
  if (!item) throw new Error('Vault item not found.');
  const meta = JSON.parse(
    cryptoEngine.decryptMultiLayer(item.metaCiphertext, item.metaIv, mediaVaultMasterKey)
  );
  const ext = path.extname(meta.displayName || '') || '.bin';
  const previewPath = path.join(getVaultPreviewDir(), `${item.id}${ext}`);
  cryptoEngine.decryptVaultFileToPath(
    getVaultFilesDir(),
    item.encryptedFileName,
    item.fileIv,
    previewPath,
    mediaVaultMasterKey
  );
  return {
    id: item.id,
    displayName: meta.displayName,
    mimeType: meta.mimeType,
    mediaType: meta.mediaType,
    filePath: previewPath,
    fileUrl: pathToFileURL(previewPath).href
  };
});

ipcMain.handle('vault:export-item', async (_event, id) => {
  if (!mediaVaultMasterKey) throw new Error('Secret Vault is locked.');
  const store = readMediaVaultStore();
  const item = (store.items || []).find((x) => x.id === id);
  if (!item) throw new Error('Vault item not found.');
  const meta = JSON.parse(
    cryptoEngine.decryptMultiLayer(item.metaCiphertext, item.metaIv, mediaVaultMasterKey)
  );
  const res = await dialog.showSaveDialog(mainWindow, {
    title: 'Restore File From Secret Vault',
    defaultPath: path.join(getDownloadsDir(), sanitizeFilename(meta.displayName))
  });
  if (res.canceled || !res.filePath) return { canceled: true };
  cryptoEngine.decryptVaultFileToPath(
    getVaultFilesDir(),
    item.encryptedFileName,
    item.fileIv,
    res.filePath,
    mediaVaultMasterKey
  );
  return { canceled: false, filePath: res.filePath };
});

ipcMain.handle('vault:delete-item', (_event, id) => {
  if (!mediaVaultMasterKey) throw new Error('Secret Vault is locked.');
  const store = readMediaVaultStore();
  const item = (store.items || []).find((x) => x.id === id);
  if (item) {
    try {
      fs.unlinkSync(path.join(getVaultFilesDir(), path.basename(item.encryptedFileName)));
    } catch {}
  }
  const items = (store.items || []).filter((x) => x.id !== id);
  writeJsonAtomic(MEDIA_VAULT_PATH(), { ...store, items });
  return { ok: true };
});
