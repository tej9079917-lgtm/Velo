'use strict';

const { contextBridge, ipcRenderer } = require('electron');

function onEvent(channel, handler) {
  const listener = (_event, payload) => handler(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
}

contextBridge.exposeInMainWorld('velo', {
  // State & Window
  loadState: () => ipcRenderer.invoke('state:load'),
  saveState: (partial) => ipcRenderer.invoke('state:save', partial),
  setContentProtection: (enabled) => ipcRenderer.invoke('window:set-content-protection', enabled),

  // Downloads & Media
  listDownloads: () => ipcRenderer.invoke('downloads:list'),
  downloadUrl: (payload) => ipcRenderer.invoke('downloads:url', payload),
  downloadImageFormat: (payload) => ipcRenderer.invoke('downloads:image-format', payload),
  openExternalFile: (filePath) => ipcRenderer.invoke('downloads:open-external', filePath),
  showInFolder: (filePath) => ipcRenderer.invoke('downloads:show-in-folder', filePath),
  deleteDownload: (id) => ipcRenderer.invoke('downloads:delete', id),

  // PDF & Local Files
  printWebviewToPdf: (payload) => ipcRenderer.invoke('pdf:print-webview', payload),
  pickLocalFiles: (payload) => ipcRenderer.invoke('files:pick', payload),
  imagesToPdf: (payload) => ipcRenderer.invoke('pdf:images-to-pdf', payload),

  // Real Internet Speed Test
  runSpeedTest: () => ipcRenderer.invoke('speedtest:run'),
  cancelSpeedTest: () => ipcRenderer.invoke('speedtest:cancel'),

  // 3-Layer Encrypted Password Vault
  pwStatus: () => ipcRenderer.invoke('pw:status'),
  pwSetup: (passphrase) => ipcRenderer.invoke('pw:setup', passphrase),
  pwUnlock: (passphrase) => ipcRenderer.invoke('pw:unlock', passphrase),
  pwLock: () => ipcRenderer.invoke('pw:lock'),
  pwList: () => ipcRenderer.invoke('pw:list'),
  pwSave: (item) => ipcRenderer.invoke('pw:save', item),
  pwDelete: (id) => ipcRenderer.invoke('pw:delete', id),
  pwCopySecret: (secret) => ipcRenderer.invoke('pw:copy-secret', secret),

  // Stealth Secret Media Vault
  vaultStatus: () => ipcRenderer.invoke('vault:status'),
  tryOmniboxCode: (input) => ipcRenderer.invoke('vault:try-omnibox-code', input),
  vaultChangeCode: (newCode) => ipcRenderer.invoke('vault:change-code', newCode),
  vaultLock: () => ipcRenderer.invoke('vault:lock'),
  vaultList: () => ipcRenderer.invoke('vault:list'),
  vaultHideDialog: () => ipcRenderer.invoke('vault:hide-dialog'),
  vaultHideExistingPath: (filePath) => ipcRenderer.invoke('vault:hide-existing-path', filePath),
  vaultOpenItem: (id) => ipcRenderer.invoke('vault:open-item', id),
  vaultExportItem: (id) => ipcRenderer.invoke('vault:export-item', id),
  vaultDeleteItem: (id) => ipcRenderer.invoke('vault:delete-item', id),

  // Events
  onTrackerBlocked: (cb) => onEvent('velo:tracker-blocked', cb),
  onDownloadUpdated: (cb) => onEvent('velo:download-updated', cb),
  onSpeedTestProgress: (cb) => onEvent('velo:speedtest-progress', cb),
  onOpenExternalTarget: (cb) => onEvent('velo:open-external-target', cb),
  onVaultsAutoLocked: (cb) => onEvent('velo:vaults-auto-locked', cb)
});
