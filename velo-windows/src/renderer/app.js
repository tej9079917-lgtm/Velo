'use strict';

(() => {
  const velo = window.velo;
  const NEWTAB_URL = 'velo://newtab';

  // --- Application State ---
  const state = {
    theme: 'dark',
    adBlockEnabled: true,
    searchEngine: 'google',
    appsBarVisible: true,
    totalBlockedCount: 0,
    bookmarks: [],
    history: [],
    tabs: [],
    activeTabId: null,
    splitOpen: false,
    downloads: [],
    detectedMedia: { videos: [], images: [] },
    interceptedStreams: [],
    imageViewer: { filePath: null, scale: 1, rotation: 0 },
    vaultFilter: 'all',
    vaultItems: [],
    speedTesting: false
  };

  const tabWebviews = new Map();
  let splitWebview = null;
  let revealTimers = new Map();

  // --- DOM Helpers (strictly safe textContent, never innerHTML with dynamic data) ---
  const $ = (id) => document.getElementById(id);

  function el(tag, attrs = {}, children = []) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === 'className') node.className = v;
      else if (k === 'text') node.textContent = v;
      else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2).toLowerCase(), v);
      else if (v !== undefined && v !== null) node.setAttribute(k, String(v));
    }
    for (const child of children) {
      if (typeof child === 'string') node.appendChild(document.createTextNode(child));
      else if (child) node.appendChild(child);
    }
    return node;
  }

  function clearChildren(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  function toast(message) {
    const host = $('toast-host');
    const node = el('div', { className: 'toast', text: String(message) });
    host.appendChild(node);
    setTimeout(() => {
      if (node.parentNode) node.parentNode.removeChild(node);
    }, 3200);
  }

  function formatBytes(bytes) {
    const n = Number(bytes) || 0;
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
    return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  function buildSearchUrl(query) {
    const q = encodeURIComponent(query);
    if (state.searchEngine === 'duckduckgo') return `https://duckduckgo.com/?q=${q}`;
    if (state.searchEngine === 'bing') return `https://www.bing.com/search?q=${q}`;
    return `https://www.google.com/search?q=${q}`;
  }

  function normalizeUserInputToUrl(raw) {
    const text = String(raw || '').trim();
    if (!text) return NEWTAB_URL;
    if (/^https?:\/\//i.test(text) || /^file:\/\//i.test(text)) return text;
    if (!text.includes(' ') && /^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+(:\d+)?(\/.*)?$/.test(text)) {
      return `https://${text}`;
    }
    return buildSearchUrl(text);
  }

  function persistState() {
    velo.saveState({
      theme: state.theme,
      adBlockEnabled: state.adBlockEnabled,
      searchEngine: state.searchEngine,
      appsBarVisible: state.appsBarVisible,
      bookmarks: state.bookmarks,
      history: state.history.slice(0, 120)
    }).catch(() => {});
  }

  function applyTheme() {
    document.documentElement.setAttribute('data-theme', state.theme);
    $('apps-bar').classList.toggle('hidden', !state.appsBarVisible);
    $('setting-adblock').checked = state.adBlockEnabled;
    $('setting-appsbar').checked = state.appsBarVisible;
    $('setting-search-engine').value = state.searchEngine;
    const shieldBtn = $('btn-shield');
    shieldBtn.classList.toggle('shield-on', state.adBlockEnabled);
    $('shield-count').textContent = String(state.totalBlockedCount);
  }

  // --- Modal Helpers ---
  function openModal(id) {
    $(id).classList.remove('hidden');
  }

  function closeModal(id) {
    $(id).classList.add('hidden');
    if (id === 'modal-player') {
      $('velo-video-el').pause();
      $('velo-audio-el').pause();
    }
    if (id === 'modal-passwords' || id === 'modal-secret-vault') {
      velo.setContentProtection(false).catch(() => {});
    }
  }

  // --- Tab Management ---
  function getActiveTab() {
    return state.tabs.find((t) => t.id === state.activeTabId) || null;
  }

  function createTab(initialUrl = NEWTAB_URL, options = {}) {
    const id = `tab-${Date.now()}-${Math.random().toString(16).slice(2, 7)}`;
    const isPrivate = Boolean(options.isPrivate);
    const tab = {
      id,
      title: options.title || (isPrivate ? 'Private Tab' : 'New Tab'),
      url: initialUrl,
      isPrivate,
      loading: false,
      canGoBack: false,
      canGoForward: false
    };
    state.tabs.push(tab);

    if (initialUrl !== NEWTAB_URL) {
      ensureWebviewForTab(tab, initialUrl);
    }

    selectTab(id);
    return tab;
  }

  function ensureWebviewForTab(tab, urlToLoad) {
    let wv = tabWebviews.get(tab.id);
    if (!wv) {
      wv = document.createElement('webview');
      wv.setAttribute('allowpopups', '');
      wv.setAttribute('partition', tab.isPrivate ? `velo-private-${tab.id}` : 'persist:velo-main');
      wv.className = 'inactive-wv';

      wv.addEventListener('did-start-loading', () => {
        tab.loading = true;
        if (state.activeTabId === tab.id) syncNavChrome();
      });

      wv.addEventListener('did-stop-loading', () => {
        tab.loading = false;
        tab.canGoBack = wv.canGoBack();
        tab.canGoForward = wv.canGoForward();
        if (state.activeTabId === tab.id) {
          syncNavChrome();
          scanActiveWebviewMedia().catch(() => {});
        }
      });

      wv.addEventListener('page-title-updated', (e) => {
        if (e.title) {
          tab.title = e.title;
          renderTabs();
        }
      });

      const onNavUpdate = (e) => {
        if (!e.url) return;
        tab.url = e.url;
        tab.canGoBack = wv.canGoBack();
        tab.canGoForward = wv.canGoForward();
        if (!tab.isPrivate && /^https?:\/\//i.test(e.url)) {
          recordHistory(tab.title || e.url, e.url);
        }
        if (state.activeTabId === tab.id) {
          syncNavChrome();
        }
      };
      wv.addEventListener('did-navigate', onNavUpdate);
      wv.addEventListener('did-navigate-in-page', onNavUpdate);

      wv.addEventListener('new-window', (e) => {
        if (e.url) createTab(e.url, { isPrivate: tab.isPrivate });
      });

      $('webview-host').appendChild(wv);
      tabWebviews.set(tab.id, wv);
    }

    if (urlToLoad && urlToLoad !== NEWTAB_URL) {
      wv.src = urlToLoad;
    }
    return wv;
  }

  function selectTab(id) {
    state.activeTabId = id;
    const activeTab = getActiveTab();
    for (const [tid, wv] of tabWebviews.entries()) {
      const visible = activeTab && activeTab.id === tid && activeTab.url !== NEWTAB_URL;
      wv.classList.toggle('inactive-wv', !visible);
    }
    const showStart = !activeTab || activeTab.url === NEWTAB_URL;
    $('start-page').classList.toggle('hidden', !showStart);
    state.detectedMedia = { videos: [], images: [] };
    state.interceptedStreams = [];
    updateMediaBadges();
    renderTabs();
    syncNavChrome();
    if (!showStart) {
      syncInterceptedStreamsForActiveTab().catch(() => {});
      scanActiveWebviewMedia().catch(() => {});
    }
  }

  function closeTab(id) {
    const idx = state.tabs.findIndex((t) => t.id === id);
    if (idx < 0) return;
    state.tabs.splice(idx, 1);
    const wv = tabWebviews.get(id);
    if (wv) {
      if (wv.parentNode) wv.parentNode.removeChild(wv);
      tabWebviews.delete(id);
    }
    if (state.tabs.length === 0) {
      createTab(NEWTAB_URL);
      return;
    }
    if (state.activeTabId === id) {
      const nextTab = state.tabs[Math.min(idx, state.tabs.length - 1)];
      selectTab(nextTab.id);
    } else {
      renderTabs();
    }
  }

  function navigateActiveTab(url) {
    const tab = getActiveTab() || createTab(NEWTAB_URL);
    tab.url = url;
    if (url === NEWTAB_URL) {
      tab.title = tab.isPrivate ? 'Private Tab' : 'New Tab';
      selectTab(tab.id);
      return;
    }
    ensureWebviewForTab(tab, url);
    selectTab(tab.id);
  }

  function renderTabs() {
    const strip = $('tabs-strip');
    clearChildren(strip);
    for (const tab of state.tabs) {
      const item = el('div', {
        className: `tab-item${tab.id === state.activeTabId ? ' active' : ''}${tab.isPrivate ? ' private-tab' : ''}`,
        role: 'tab',
        'aria-selected': tab.id === state.activeTabId ? 'true' : 'false',
        onClick: () => selectTab(tab.id)
      }, [
        el('span', { className: 'tab-title', text: `${tab.isPrivate ? '[Private] ' : ''}${tab.title || 'New Tab'}` }),
        el('button', {
          className: 'tab-close',
          title: 'Close tab',
          text: '×',
          onClick: (e) => {
            e.stopPropagation();
            closeTab(tab.id);
          }
        })
      ]);
      strip.appendChild(item);
    }
  }

  function syncNavChrome() {
    const tab = getActiveTab();
    const isStart = !tab || tab.url === NEWTAB_URL;
    $('omnibox-input').value = isStart ? '' : tab.url;
    $('btn-back').disabled = isStart || !tab.canGoBack;
    $('btn-forward').disabled = isStart || !tab.canGoForward;
    $('btn-reload').textContent = tab && tab.loading ? '✕' : '↻';
    const lock = $('omnibox-lock-indicator');
    if (isStart) {
      lock.textContent = 'V';
    } else if (tab.url.startsWith('https://')) {
      lock.textContent = 'HTTPS';
    } else {
      lock.textContent = 'WEB';
    }
    const isBookmarked = tab && state.bookmarks.some((b) => b.url === tab.url);
    $('btn-bookmark').textContent = isBookmarked ? '★' : '☆';
  }

  // --- Bookmarks & History ---
  function renderBookmarks() {
    const grid = $('bookmarks-grid');
    clearChildren(grid);
    for (const bm of state.bookmarks) {
      let host = bm.url;
      try { host = new URL(bm.url).hostname; } catch {}
      grid.appendChild(el('button', {
        className: 'bookmark-tile',
        onClick: () => navigateActiveTab(bm.url)
      }, [
        el('strong', { text: bm.title }),
        el('span', { className: 'bookmark-host', text: host })
      ]));
    }
  }

  function toggleCurrentBookmark() {
    const tab = getActiveTab();
    if (!tab || tab.url === NEWTAB_URL) return;
    const idx = state.bookmarks.findIndex((b) => b.url === tab.url);
    if (idx >= 0) {
      state.bookmarks.splice(idx, 1);
      toast('Bookmark removed');
    } else {
      state.bookmarks.unshift({
        id: `bm-${Date.now()}`,
        title: tab.title || tab.url,
        url: tab.url
      });
      toast('Bookmarked');
    }
    persistState();
    renderBookmarks();
    syncNavChrome();
  }

  function recordHistory(title, url) {
    if (state.history[0] && state.history[0].url === url) return;
    state.history.unshift({ title, url, visitedAt: Date.now() });
    state.history = state.history.slice(0, 120);
    persistState();
  }

  function renderHistory() {
    const list = $('history-list');
    clearChildren(list);
    if (!state.history.length) {
      list.appendChild(el('div', { className: 'empty-state', text: 'No browsing history recorded.' }));
      return;
    }
    for (const entry of state.history.slice(0, 40)) {
      list.appendChild(el('div', { className: 'list-card' }, [
        el('div', { className: 'list-card-info' }, [
          el('div', { className: 'list-card-title', text: entry.title || entry.url }),
          el('div', { className: 'list-card-sub', text: entry.url })
        ]),
        el('div', { className: 'list-card-actions' }, [
          el('button', {
            className: 'small-btn',
            text: 'Open',
            onClick: () => {
              closeModal('modal-settings');
              navigateActiveTab(entry.url);
            }
          })
        ])
      ]));
    }
  }

  // --- Omnibox Submission + Stealth Secret Vault Detection ---
  async function handleOmniboxSubmit(rawValue) {
    const text = String(rawValue || '').trim();
    if (!text) return;

    // Check stealth secret vault code in main process BEFORE ever touching the network
    try {
      const check = await velo.tryOmniboxCode(text);
      if (check && check.matched) {
        $('omnibox-input').value = '';
        $('start-search-input').value = '';
        await openSecretVaultModal(check.isDefaultCode);
        return;
      }
    } catch {}

    navigateActiveTab(normalizeUserInputToUrl(text));
  }

  // --- Universal Webpage Video & Full-Size Image Sniffer ---
  async function scanActiveWebviewMedia() {
    const tab = getActiveTab();
    if (!tab || tab.url === NEWTAB_URL) {
      state.detectedMedia = { videos: [], images: [] };
      updateMediaBadges();
      return;
    }
    const wv = tabWebviews.get(tab.id);
    if (!wv) return;

    const snifferScript = `
      (() => {
        const videos = [];
        const seenV = new Set();
        const addVid = (url, kind, label) => {
          if (!url || url.startsWith('blob:') || seenV.has(url)) return;
          seenV.add(url);
          videos.push({ url, kind, label });
        };
        document.querySelectorAll('video').forEach((v, i) => {
          if (v.currentSrc) addVid(v.currentSrc, 'video', v.title || ('Page Video #' + (i + 1)));
          if (v.src) addVid(v.src, 'video', v.title || ('Page Video #' + (i + 1)));
          v.querySelectorAll('source').forEach((s) => {
            if (s.src) addVid(s.src, 'video', s.type || ('Video Source #' + (i + 1)));
          });
        });
        document.querySelectorAll('audio').forEach((a, i) => {
          if (a.currentSrc) addVid(a.currentSrc, 'audio', 'Page Audio #' + (i + 1));
          if (a.src) addVid(a.src, 'audio', 'Page Audio #' + (i + 1));
          a.querySelectorAll('source').forEach((s) => {
            if (s.src) addVid(s.src, 'audio', 'Audio Source #' + (i + 1));
          });
        });
        document.querySelectorAll('a[href]').forEach((a) => {
          const href = a.href || '';
          if (/\\.(mp4|webm|mkv|mov|m3u8|mp3|wav|m4a)(\\?|$)/i.test(href)) {
            addVid(href, 'video', a.textContent.trim().slice(0, 50) || 'Linked Media');
          }
        });

        // Full-size image resolution helper (checks srcset largest candidate, data-src, parent anchor, og:image)
        const images = [];
        const seenI = new Set();
        const pickBestSrcset = (srcset) => {
          if (!srcset) return '';
          let bestUrl = '';
          let bestScore = -1;
          srcset.split(',').forEach((entry) => {
            const parts = entry.trim().split(/\\s+/);
            if (!parts[0]) return;
            const desc = parts[1] || '1x';
            const num = parseFloat(desc) || 1;
            const score = desc.endsWith('w') ? num : num * 1000;
            if (score > bestScore) {
              bestScore = score;
              bestUrl = parts[0];
            }
          });
          return bestUrl;
        };

        const addImg = (rawUrl, w, h, alt) => {
          if (!rawUrl || rawUrl.startsWith('blob:')) return;
          let abs = rawUrl;
          try { abs = new URL(rawUrl, location.href).href; } catch {}
          if (seenI.has(abs)) return;
          seenI.add(abs);
          images.push({ url: abs, width: w || 0, height: h || 0, alt: (alt || '').slice(0, 60) });
        };

        const ogImg = document.querySelector('meta[property="og:image"]');
        if (ogImg && ogImg.content) addImg(ogImg.content, 1200, 630, 'Page Hero Image');

        document.querySelectorAll('img').forEach((img) => {
          const w = img.naturalWidth || img.width || 0;
          const h = img.naturalHeight || img.height || 0;
          if (w < 80 && h < 80) return;
          const best =
            pickBestSrcset(img.srcset) ||
            img.getAttribute('data-full-url') ||
            img.getAttribute('data-original') ||
            img.getAttribute('data-src') ||
            img.currentSrc ||
            img.src;
          addImg(best, w, h, img.alt || 'Web Image');
        });

        return { videos: videos.slice(0, 30), images: images.slice(0, 48) };
      })();
    `;

    try {
      const result = await wv.executeJavaScript(snifferScript, false);
      state.detectedMedia = {
        videos: Array.isArray(result?.videos) ? result.videos : [],
        images: Array.isArray(result?.images) ? result.images : []
      };
      await syncInterceptedStreamsForActiveTab();
      updateMediaBadges();
    } catch {
      await syncInterceptedStreamsForActiveTab().catch(() => {});
      updateMediaBadges();
    }
  }

  function getActiveWebContentsId() {
    const tab = getActiveTab();
    if (!tab || tab.url === NEWTAB_URL) return null;
    const wv = tabWebviews.get(tab.id);
    if (!wv || typeof wv.getWebContentsId !== 'function') return null;
    try {
      return wv.getWebContentsId();
    } catch {
      return null;
    }
  }

  async function syncInterceptedStreamsForActiveTab() {
    const wcId = getActiveWebContentsId();
    if (!wcId) {
      state.interceptedStreams = [];
      updateMediaBadges();
      return;
    }
    const streams = await velo.listInterceptedStreams(wcId);
    state.interceptedStreams = Array.isArray(streams) ? streams : [];
    updateMediaBadges();
  }

  function getMergedVideoStreams() {
    const seen = new Set();
    const merged = [];
    const activeTabTitle = getActiveTab()?.title || '';
    // Network-intercepted M3U8/MP4 streams take priority
    for (const s of state.interceptedStreams) {
      if (!s || !s.url || seen.has(s.url)) continue;
      seen.add(s.url);
      const fmt = String(s.format || 'mp4').toLowerCase();
      const isHls = fmt === 'm3u8' || fmt === 'hls' || /\.m3u8(\?|$)/i.test(s.url);
      const tag = isHls ? 'HLS M3U8' : fmt.toUpperCase();
      const sizeStr = s.contentLength ? ` • ${formatBytes(s.contentLength)}` : '';
      merged.push({
        url: s.url,
        format: isHls ? 'm3u8' : fmt,
        mimeType: s.mimeType || (isHls ? 'application/vnd.apple.mpegurl' : ''),
        referer: s.referer || (getActiveTab()?.url || ''),
        origin: s.origin || '',
        filename: activeTabTitle && activeTabTitle !== 'New Tab' ? activeTabTitle : '',
        webContentsId: s.webContentsId || getActiveWebContentsId(),
        label: `[${tag}] ${s.label || 'Intercepted Video Stream'}${sizeStr}`,
        intercepted: true
      });
    }
    // Append DOM-detected video/audio tags not already intercepted
    for (const v of state.detectedMedia.videos) {
      if (!v || !v.url || seen.has(v.url)) continue;
      seen.add(v.url);
      const isHls = /\.m3u8(\?|$)/i.test(v.url);
      merged.push({
        url: v.url,
        format: isHls ? 'm3u8' : (v.kind === 'audio' ? 'audio' : 'mp4'),
        mimeType: isHls ? 'application/vnd.apple.mpegurl' : '',
        referer: getActiveTab()?.url || '',
        origin: '',
        filename: activeTabTitle && activeTabTitle !== 'New Tab' ? activeTabTitle : '',
        webContentsId: getActiveWebContentsId(),
        label: v.label || (isHls ? '[HLS M3U8] Detected Stream' : 'Detected Page Video'),
        intercepted: false
      });
    }
    return merged;
  }

  async function triggerStreamDownload(streamObj) {
    const tab = getActiveTab();
    const wcId = streamObj.webContentsId || getActiveWebContentsId();
    const isHls = streamObj.format === 'm3u8' || streamObj.format === 'hls' || /\.m3u8(\?|$)/i.test(streamObj.url);
    toast(isHls ? 'Downloading HLS M3U8 stream to Downloads\\Velo...' : 'Downloading video stream to Downloads\\Velo...');
    const rec = await velo.downloadStream({
      url: streamObj.url,
      mimeType: streamObj.mimeType || (isHls ? 'application/vnd.apple.mpegurl' : ''),
      referer: streamObj.referer || (tab ? tab.url : ''),
      origin: streamObj.origin || '',
      filename: streamObj.filename || '',
      webContentsId: wcId
    });
    toast(`Saved ${rec.filename} to Downloads\\Velo`);
    return rec;
  }

  function updateMediaBadges() {
    const mergedVideos = getMergedVideoStreams();
    const total = mergedVideos.length + state.detectedMedia.images.length;
    $('media-count').textContent = String(total);
    const fab = $('fab-save-media');
    if (total > 0 && getActiveTab() && getActiveTab().url !== NEWTAB_URL) {
      fab.classList.remove('hidden');
      if (mergedVideos.length === 1) {
        const tag = mergedVideos[0].format === 'm3u8' ? 'M3U8' : String(mergedVideos[0].format || 'MP4').toUpperCase();
        $('fab-media-label').textContent = `Download Video (${tag})`;
      } else if (mergedVideos.length > 1) {
        $('fab-media-label').textContent = `Download Video (${mergedVideos.length} streams)`;
      } else {
        $('fab-media-label').textContent = `Save Media (${total})`;
      }
    } else {
      fab.classList.add('hidden');
    }
  }

  function renderDetectedMediaModal() {
    const tab = getActiveTab();
    const referer = tab ? tab.url : '';
    const vList = $('detected-videos-list');
    clearChildren(vList);

    const mergedVideos = getMergedVideoStreams();
    if (!mergedVideos.length) {
      vList.appendChild(el('div', {
        className: 'empty-state',
        text: 'No M3U8/MP4 video streams detected yet. Start playing a video on the page to intercept its stream automatically, or paste a URL above.'
      }));
    } else {
      for (const vid of mergedVideos) {
        vList.appendChild(el('div', { className: 'list-card' }, [
          el('div', { className: 'list-card-info' }, [
            el('div', { className: 'list-card-title', text: vid.label || 'Detected Stream' }),
            el('div', { className: 'list-card-sub', text: vid.url })
          ]),
          el('div', { className: 'list-card-actions' }, [
            el('button', {
              className: 'primary-btn',
              text: vid.streamType === 'hls' ? 'Download Stream' : 'Download Video',
              onClick: async () => {
                try {
                  await triggerStreamDownload(vid);
                } catch (err) {
                  toast(`Stream download failed: ${err.message}`);
                }
              }
            })
          ])
        ]));
      }
    }

    const iGrid = $('detected-images-grid');
    clearChildren(iGrid);
    if (!state.detectedMedia.images.length) {
      iGrid.appendChild(el('div', { className: 'empty-state', text: 'No full-size images detected on this page.' }));
    } else {
      for (const img of state.detectedMedia.images) {
        const preview = el('img', { src: img.url, alt: img.alt || 'Image' });
        iGrid.appendChild(el('div', { className: 'media-img-card' }, [
          preview,
          el('div', { className: 'media-img-meta' }, [
            el('span', {
              className: 'media-img-dim',
              text: img.width && img.height ? `${img.width} × ${img.height}px` : 'Full Resolution'
            }),
            el('button', {
              className: 'primary-btn',
              text: 'Save Full Size',
              onClick: async () => {
                const format = $('image-format-select').value;
                try {
                  toast(`Saving image as ${format.toUpperCase()}...`);
                  const rec = await velo.downloadImageFormat({
                    url: img.url,
                    referer,
                    format,
                    suggestedName: img.alt || `velo_image_${Date.now()}`
                  });
                  toast(`Saved ${rec.filename}`);
                } catch (err) {
                  toast(`Image save failed: ${err.message}`);
                }
              }
            })
          ])
        ]));
      }
    }
  }

  // --- Downloads Manager ---
  async function refreshDownloads() {
    state.downloads = await velo.listDownloads();
    renderDownloads();
  }

  function renderDownloads() {
    const list = $('downloads-list');
    clearChildren(list);
    if (!state.downloads.length) {
      list.appendChild(el('div', { className: 'empty-state', text: 'No downloads yet.' }));
      return;
    }
    for (const d of state.downloads) {
      const segPart = d.segmentProgress ? ` • Segments ${d.segmentProgress}` : '';
      const subText = `${d.state.toUpperCase()}${segPart} • ${formatBytes(d.receivedBytes || d.totalBytes)} • ${d.mimeType || ''}`;
      const actions = [
        el('button', {
          className: 'primary-btn',
          text: 'Open in Velo',
          onClick: () => openFileInVelo(d)
        }),
        el('button', {
          className: 'small-btn',
          text: 'Folder',
          onClick: () => velo.showInFolder(d.filePath)
        }),
        el('button', {
          className: 'small-btn danger-btn',
          text: 'Delete',
          onClick: async () => {
            await velo.deleteDownload(d.id);
            await refreshDownloads();
          }
        })
      ];

      list.appendChild(el('div', { className: 'list-card' }, [
        el('div', { className: 'list-card-info' }, [
          el('div', { className: 'list-card-title', text: d.filename }),
          el('div', { className: 'list-card-sub', text: subText })
        ]),
        el('div', { className: 'list-card-actions' }, actions)
      ]));
    }
  }

  // --- Built-in Viewers (Video, Audio, Image, PDF) ---
  function openFileInVelo(fileInfo) {
    if (!fileInfo || !fileInfo.fileUrl) return;
    const type = fileInfo.mediaType || 'file';
    closeModal('modal-downloads');

    if (type === 'video' || type === 'audio') {
      openMediaPlayer(fileInfo);
    } else if (type === 'image') {
      openImageViewer(fileInfo);
    } else if (type === 'pdf') {
      openPdfViewer(fileInfo);
    } else if (fileInfo.filePath) {
      velo.openExternalFile(fileInfo.filePath).catch((err) => toast(err.message));
    }
  }

  function openMediaPlayer(fileInfo) {
    const videoEl = $('velo-video-el');
    const audioEl = $('velo-audio-el');
    videoEl.pause();
    audioEl.pause();

    $('player-title').textContent = fileInfo.filename || fileInfo.displayName || 'Velo Media Player';
    $('player-subtitle').textContent = fileInfo.mimeType || 'Local Media';

    if (fileInfo.mediaType === 'audio') {
      videoEl.classList.add('hidden');
      audioEl.classList.remove('hidden');
      audioEl.src = fileInfo.fileUrl;
      audioEl.playbackRate = Number($('player-speed-select').value) || 1;
      audioEl.play().catch(() => {});
    } else {
      audioEl.classList.add('hidden');
      videoEl.classList.remove('hidden');
      videoEl.src = fileInfo.fileUrl;
      videoEl.playbackRate = Number($('player-speed-select').value) || 1;
      videoEl.play().catch(() => {});
    }
    openModal('modal-player');
  }

  function applyImageTransform() {
    $('velo-image-el').style.transform = `scale(${state.imageViewer.scale}) rotate(${state.imageViewer.rotation}deg)`;
    $('btn-img-zoom-reset').textContent = `${Math.round(state.imageViewer.scale * 100)}%`;
  }

  function openImageViewer(fileInfo) {
    state.imageViewer = { filePath: fileInfo.filePath || null, scale: 1, rotation: 0 };
    $('image-viewer-title').textContent = fileInfo.filename || fileInfo.displayName || 'Velo Image Viewer';
    $('image-viewer-meta').textContent = fileInfo.mimeType || 'Full-resolution preview';
    $('velo-image-el').src = fileInfo.fileUrl;
    applyImageTransform();
    openModal('modal-image');
  }

  function openPdfViewer(fileInfo) {
    if (fileInfo && fileInfo.fileUrl) {
      $('pdf-viewer-title').textContent = fileInfo.filename || fileInfo.displayName || 'PDF Viewer';
      $('pdf-empty-state').classList.add('hidden');
      const frame = $('pdf-embed-frame');
      frame.classList.remove('hidden');
      frame.src = fileInfo.fileUrl;
    }
    openModal('modal-pdf');
  }

  // --- Speed Test Gauge Canvas ---
  function drawSpeedGauge(mbps, phaseLabel) {
    const canvas = $('speed-gauge-canvas');
    const ctx = canvas.getContext('2d');
    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const cx = w / 2;
    const cy = h - 26;
    const radius = 122;
    const startAngle = Math.PI;
    const endAngle = Math.PI * 2;

    // Background arc
    ctx.lineWidth = 18;
    ctx.lineCap = 'round';
    ctx.strokeStyle = '#1e293b';
    ctx.beginPath();
    ctx.arc(cx, cy, radius, startAngle, endAngle);
    ctx.stroke();

    // Progress arc (logarithmic scale up to 500 Mbps)
    const clamped = Math.max(0, Math.min(Number(mbps) || 0, 500));
    const ratio = clamped === 0 ? 0 : Math.min(1, Math.log10(clamped + 1) / Math.log10(501));
    const grad = ctx.createLinearGradient(cx - radius, cy, cx + radius, cy);
    grad.addColorStop(0, '#38bdf8');
    grad.addColorStop(1, '#10b981');
    ctx.strokeStyle = grad;
    ctx.beginPath();
    ctx.arc(cx, cy, radius, startAngle, startAngle + Math.PI * ratio);
    ctx.stroke();

    // Center readout
    ctx.fillStyle = getComputedStyle(document.documentElement).getPropertyValue('--text').trim() || '#f1f5f9';
    ctx.font = 'bold 34px "Segoe UI", sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText(clamped.toFixed(1), cx, cy - 32);

    ctx.fillStyle = '#94a3b8';
    ctx.font = '600 13px "Segoe UI", sans-serif';
    ctx.fillText('Mbps', cx, cy - 10);

    if (phaseLabel) {
      $('speed-phase-label').textContent = phaseLabel;
    }
  }

  // --- 3-Layer Password Vault ---
  async function openPasswordVaultModal() {
    await velo.setContentProtection(true).catch(() => {});
    const status = await velo.pwStatus();
    $('pw-auth-view').classList.toggle('hidden', status.unlocked);
    $('pw-unlocked-view').classList.toggle('hidden', !status.unlocked);
    $('btn-pw-lock').classList.toggle('hidden', !status.unlocked);

    if (!status.unlocked) {
      $('pw-master-input').value = '';
      $('pw-master-confirm').value = '';
      if (!status.configured) {
        $('pw-auth-heading').textContent = 'Create 3-Layer Encrypted Vault';
        $('pw-auth-desc').textContent = 'Protected with PBKDF2 (210,000 iterations), Ephemeral DEK AES-256-GCM, CBC-HMAC, and Outer GCM Seal.';
        $('pw-master-confirm').classList.remove('hidden');
        $('pw-auth-submit').textContent = 'Initialize Vault';
      } else {
        $('pw-auth-heading').textContent = 'Unlock Password Vault';
        $('pw-auth-desc').textContent = `Key Fingerprint: ${status.keyFingerprint} • Screen capture protection active.`;
        $('pw-master-confirm').classList.add('hidden');
        $('pw-auth-submit').textContent = 'Unlock Vault';
      }
    } else {
      await renderPasswordEntries();
    }
    openModal('modal-passwords');
  }

  async function renderPasswordEntries() {
    const list = $('pw-entries-list');
    clearChildren(list);
    for (const timer of revealTimers.values()) clearTimeout(timer);
    revealTimers.clear();

    const entries = await velo.pwList();
    if (!entries.length) {
      list.appendChild(el('div', { className: 'empty-state', text: 'No saved logins yet. Add your first encrypted credential above.' }));
      return;
    }

    for (const item of entries) {
      const secretSpan = el('span', { text: '••••••••••••' });
      let revealed = false;

      const toggleBtn = el('button', {
        className: 'small-btn',
        text: 'Show (10s)',
        onClick: () => {
          if (revealed) {
            revealed = false;
            secretSpan.textContent = '••••••••••••';
            toggleBtn.textContent = 'Show (10s)';
            if (revealTimers.has(item.id)) clearTimeout(revealTimers.get(item.id));
            return;
          }
          revealed = true;
          secretSpan.textContent = item.password;
          toggleBtn.textContent = 'Hide';
          const t = setTimeout(() => {
            revealed = false;
            secretSpan.textContent = '••••••••••••';
            toggleBtn.textContent = 'Show (10s)';
          }, 10000);
          revealTimers.set(item.id, t);
        }
      });

      list.appendChild(el('div', { className: 'list-card' }, [
        el('div', { className: 'list-card-info' }, [
          el('div', { className: 'list-card-title', text: item.siteName || item.siteUrl }),
          el('div', { className: 'list-card-sub' }, [
            document.createTextNode(`${item.username} • `),
            secretSpan
          ])
        ]),
        el('div', { className: 'list-card-actions' }, [
          toggleBtn,
          el('button', {
            className: 'primary-btn',
            text: 'Copy',
            onClick: async () => {
              await velo.pwCopySecret(item.password);
              toast('Copied! Clipboard auto-clears in 30s.');
            }
          }),
          el('button', {
            className: 'small-btn danger-btn',
            text: 'Delete',
            onClick: async () => {
              await velo.pwDelete(item.id);
              await renderPasswordEntries();
            }
          })
        ])
      ]));
    }
  }

  // --- Stealth Secret Media Vault ---
  async function openSecretVaultModal(isDefaultCode) {
    await velo.setContentProtection(true).catch(() => {});
    $('vault-status-note').textContent = isDefaultCode
      ? 'Unlocked with default code (#0000). Change your secret code below for maximum privacy.'
      : 'Encrypted in Velo with chunked AES-256-GCM. Original files are removed from Windows Explorer.';
    await refreshSecretVaultItems();
    openModal('modal-secret-vault');
  }

  async function refreshSecretVaultItems() {
    state.vaultItems = await velo.vaultList();
    renderSecretVaultItems();
  }

  function renderSecretVaultItems() {
    const list = $('vault-items-list');
    clearChildren(list);
    const filtered = state.vaultFilter === 'all'
      ? state.vaultItems
      : state.vaultItems.filter((x) => x.mediaType === state.vaultFilter);

    if (!filtered.length) {
      list.appendChild(el('div', {
        className: 'empty-state',
        text: 'No hidden files in this category. Click "+ Hide Files" to encrypt videos, photos, PDFs, or audio and remove originals from Explorer.'
      }));
      return;
    }

    for (const item of filtered) {
      list.appendChild(el('div', { className: 'list-card' }, [
        el('div', { className: 'list-card-info' }, [
          el('div', { className: 'list-card-title', text: item.displayName }),
          el('div', {
            className: 'list-card-sub',
            text: `${item.mediaType.toUpperCase()} • ${formatBytes(item.byteSize)} • Encrypted (.vlt)`
          })
        ]),
        el('div', { className: 'list-card-actions' }, [
          el('button', {
            className: 'primary-btn',
            text: 'Open',
            onClick: async () => {
              try {
                const decrypted = await velo.vaultOpenItem(item.id);
                openFileInVelo(decrypted);
              } catch (err) {
                toast(`Decrypt failed: ${err.message}`);
              }
            }
          }),
          el('button', {
            className: 'small-btn',
            text: 'Restore',
            onClick: async () => {
              try {
                const res = await velo.vaultExportItem(item.id);
                if (!res.canceled) toast('Restored file to Windows folder');
              } catch (err) {
                toast(err.message);
              }
            }
          }),
          el('button', {
            className: 'small-btn danger-btn',
            text: 'Delete',
            onClick: async () => {
              await velo.vaultDeleteItem(item.id);
              await refreshSecretVaultItems();
              toast('Encrypted item permanently deleted');
            }
          })
        ])
      ]));
    }
  }

  // --- Split-Screen ---
  function toggleSplitScreen(forceState) {
    state.splitOpen = typeof forceState === 'boolean' ? forceState : !state.splitOpen;
    $('split-pane').classList.toggle('hidden', !state.splitOpen);
    if (state.splitOpen && !splitWebview) {
      splitWebview = document.createElement('webview');
      splitWebview.setAttribute('allowpopups', '');
      splitWebview.setAttribute('partition', 'persist:velo-main');
      splitWebview.src = 'https://www.google.com';
      $('split-url-input').value = 'https://www.google.com';
      $('split-webview-host').appendChild(splitWebview);
    }
  }

  // --- Wire UI Events ---
  function wireUiEvents() {
    $('btn-new-tab').addEventListener('click', () => createTab(NEWTAB_URL));
    $('btn-new-private').addEventListener('click', () => createTab(NEWTAB_URL, { isPrivate: true }));
    $('btn-split-toggle').addEventListener('click', () => toggleSplitScreen());
    $('btn-split-close').addEventListener('click', () => toggleSplitScreen(false));
    $('btn-split-go').addEventListener('click', () => {
      if (!splitWebview) return;
      const target = normalizeUserInputToUrl($('split-url-input').value);
      splitWebview.src = target;
      $('split-url-input').value = target;
    });

    $('btn-theme-toggle').addEventListener('click', () => {
      state.theme = state.theme === 'dark' ? 'light' : 'dark';
      applyTheme();
      persistState();
    });

    $('btn-back').addEventListener('click', () => {
      const tab = getActiveTab();
      const wv = tab && tabWebviews.get(tab.id);
      if (wv && wv.canGoBack()) wv.goBack();
    });
    $('btn-forward').addEventListener('click', () => {
      const tab = getActiveTab();
      const wv = tab && tabWebviews.get(tab.id);
      if (wv && wv.canGoForward()) wv.goForward();
    });
    $('btn-reload').addEventListener('click', () => {
      const tab = getActiveTab();
      const wv = tab && tabWebviews.get(tab.id);
      if (!wv) return;
      if (tab.loading) wv.stop();
      else wv.reload();
    });
    $('btn-home').addEventListener('click', () => navigateActiveTab(NEWTAB_URL));
    $('btn-bookmark').addEventListener('click', toggleCurrentBookmark);

    $('omnibox-form').addEventListener('submit', (e) => {
      e.preventDefault();
      handleOmniboxSubmit($('omnibox-input').value);
    });
    $('start-search-form').addEventListener('submit', (e) => {
      e.preventDefault();
      handleOmniboxSubmit($('start-search-input').value);
    });

    $('btn-shield').addEventListener('click', () => {
      state.adBlockEnabled = !state.adBlockEnabled;
      applyTheme();
      persistState();
      toast(state.adBlockEnabled ? 'Ad & Tracker Shield enabled' : 'Ad & Tracker Shield paused');
    });

    // All-in-One Apps Bar
    document.querySelectorAll('.app-chip[data-app-url]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const url = btn.getAttribute('data-app-url');
        const title = btn.getAttribute('data-app-title');
        createTab(url, { title });
      });
    });

    $('btn-open-local-video').addEventListener('click', async () => {
      const files = await velo.pickLocalFiles({ kind: 'video', multiple: false });
      if (files[0]) openMediaPlayer(files[0]);
    });
    $('btn-open-local-image').addEventListener('click', async () => {
      const files = await velo.pickLocalFiles({ kind: 'image', multiple: false });
      if (files[0]) openImageViewer(files[0]);
    });
    $('btn-open-local-pdf').addEventListener('click', async () => {
      const files = await velo.pickLocalFiles({ kind: 'pdf', multiple: false });
      if (files[0]) openPdfViewer(files[0]);
    });

    // Quick start tiles
    document.querySelectorAll('.tool-tile[data-open-modal]').forEach((tile) => {
      tile.addEventListener('click', () => {
        const target = tile.getAttribute('data-open-modal');
        if (target === 'pdf') openPdfViewer(null);
        else if (target === 'speedtest') { drawSpeedGauge(0, 'Ready to test'); openModal('modal-speedtest'); }
        else if (target === 'passwords') openPasswordVaultModal();
        else if (target === 'downloads') { refreshDownloads(); openModal('modal-downloads'); }
      });
    });

    // Media Grabber Modal & Direct Stream Download FAB
    const openMediaModal = async () => {
      await scanActiveWebviewMedia();
      renderDetectedMediaModal();
      openModal('modal-media');
    };
    $('btn-media-grabber').addEventListener('click', openMediaModal);
    $('fab-save-media').addEventListener('click', async () => {
      const mergedVideos = getMergedVideoStreams();
      // If exactly one video stream is detected on the current page, save it directly in one click
      if (mergedVideos.length === 1) {
        try {
          await triggerStreamDownload(mergedVideos[0]);
        } catch (err) {
          toast(`Download failed: ${err.message}`);
          await openMediaModal();
        }
      } else {
        await openMediaModal();
      }
    });
    $('btn-rescan-media').addEventListener('click', async () => {
      await scanActiveWebviewMedia();
      renderDetectedMediaModal();
      toast('Page rescanned for video streams & full-size images');
    });
    $('btn-download-manual').addEventListener('click', async () => {
      const url = $('manual-media-url').value.trim();
      if (!url) return;
      try {
        const isStream = /\.(m3u8|mp4|webm|mkv|mov)(\?|$)/i.test(url);
        if (isStream) {
          await triggerStreamDownload({
            url,
            format: /\.m3u8(\?|$)/i.test(url) ? 'm3u8' : 'mp4',
            referer: getActiveTab()?.url || ''
          });
        } else {
          toast('Downloading...');
          const rec = await velo.downloadUrl({ url, referer: getActiveTab()?.url || '' });
          toast(`Saved ${rec.filename}`);
        }
        $('manual-media-url').value = '';
      } catch (err) {
        toast(`Download failed: ${err.message}`);
      }
    });

    // Downloads Modal
    $('btn-downloads').addEventListener('click', async () => {
      await refreshDownloads();
      openModal('modal-downloads');
    });
    $('btn-open-downloads-folder').addEventListener('click', () => velo.showInFolder(null));

    // Player Controls
    $('btn-player-pick').addEventListener('click', async () => {
      const files = await velo.pickLocalFiles({ kind: 'media', multiple: false });
      if (files[0]) openFileInVelo(files[0]);
    });
    $('player-speed-select').addEventListener('change', (e) => {
      const rate = Number(e.target.value) || 1;
      $('velo-video-el').playbackRate = rate;
      $('velo-audio-el').playbackRate = rate;
    });
    $('btn-player-loop').addEventListener('click', () => {
      const next = !$('velo-video-el').loop;
      $('velo-video-el').loop = next;
      $('velo-audio-el').loop = next;
      $('btn-player-loop').textContent = `Loop: ${next ? 'On' : 'Off'}`;
    });
    $('btn-player-pip').addEventListener('click', async () => {
      try {
        if (document.pictureInPictureElement) await document.exitPictureInPicture();
        else await $('velo-video-el').requestPictureInPicture();
      } catch (err) {
        toast(`PiP unavailable: ${err.message}`);
      }
    });

    // Image Viewer Controls
    $('btn-img-zoom-in').addEventListener('click', () => {
      state.imageViewer.scale = Math.min(4, state.imageViewer.scale + 0.25);
      applyImageTransform();
    });
    $('btn-img-zoom-out').addEventListener('click', () => {
      state.imageViewer.scale = Math.max(0.25, state.imageViewer.scale - 0.25);
      applyImageTransform();
    });
    $('btn-img-zoom-reset').addEventListener('click', () => {
      state.imageViewer.scale = 1;
      state.imageViewer.rotation = 0;
      applyImageTransform();
    });
    $('btn-img-rotate').addEventListener('click', () => {
      state.imageViewer.rotation = (state.imageViewer.rotation + 90) % 360;
      applyImageTransform();
    });
    $('btn-img-to-pdf').addEventListener('click', async () => {
      if (!state.imageViewer.filePath) {
        toast('Save image locally first to convert.');
        return;
      }
      try {
        const rec = await velo.imagesToPdf({
          filePaths: [state.imageViewer.filePath],
          outputName: $('image-viewer-title').textContent
        });
        toast(`Created ${rec.filename}`);
        closeModal('modal-image');
        openPdfViewer(rec);
      } catch (err) {
        toast(err.message);
      }
    });

    // PDF Tools Modal
    $('btn-pdf-tools').addEventListener('click', () => openPdfViewer(null));
    $('btn-pdf-open-local').addEventListener('click', async () => {
      const files = await velo.pickLocalFiles({ kind: 'pdf', multiple: false });
      if (files[0]) openPdfViewer(files[0]);
    });
    $('btn-pdf-from-page').addEventListener('click', async () => {
      const tab = getActiveTab();
      const wv = tab && tabWebviews.get(tab.id);
      if (!tab || tab.url === NEWTAB_URL || !wv) {
        toast('Open a webpage first to export it as PDF.');
        return;
      }
      try {
        toast('Exporting webpage to PDF...');
        const rec = await velo.printWebviewToPdf({
          webContentsId: wv.getWebContentsId(),
          title: tab.title || 'velo_page'
        });
        toast(`Saved ${rec.filename}`);
        openPdfViewer(rec);
      } catch (err) {
        toast(`PDF export failed: ${err.message}`);
      }
    });
    $('btn-pdf-from-images').addEventListener('click', async () => {
      const files = await velo.pickLocalFiles({ kind: 'image', multiple: true });
      if (!files.length) return;
      try {
        toast(`Converting ${files.length} image(s) to PDF...`);
        const rec = await velo.imagesToPdf({
          filePaths: files.map((f) => f.filePath),
          outputName: `velo_images_${Date.now()}`
        });
        toast(`Created ${rec.filename}`);
        openPdfViewer(rec);
      } catch (err) {
        toast(`Conversion failed: ${err.message}`);
      }
    });

    // Speed Test Modal
    $('btn-speedtest').addEventListener('click', () => {
      drawSpeedGauge(0, 'Ready to test');
      openModal('modal-speedtest');
    });
    $('btn-start-speedtest').addEventListener('click', async () => {
      state.speedTesting = true;
      $('btn-start-speedtest').disabled = true;
      $('btn-cancel-speedtest').classList.remove('hidden');
      $('metric-ping').textContent = '-- ms';
      $('metric-jitter').textContent = '-- ms';
      $('metric-down').textContent = '-- Mbps';
      $('metric-up').textContent = '-- Mbps';
      drawSpeedGauge(0, 'Measuring ping...');
      try {
        await velo.runSpeedTest();
      } catch (err) {
        if (!String(err.message).includes('Cancelled')) {
          toast(`Speed test error: ${err.message}`);
        }
        drawSpeedGauge(0, 'Test stopped');
      } finally {
        state.speedTesting = false;
        $('btn-start-speedtest').disabled = false;
        $('btn-cancel-speedtest').classList.add('hidden');
      }
    });
    $('btn-cancel-speedtest').addEventListener('click', () => {
      velo.cancelSpeedTest().catch(() => {});
    });

    // Password Vault Modal
    $('btn-pw-vault').addEventListener('click', openPasswordVaultModal);
    $('btn-pw-lock').addEventListener('click', async () => {
      await velo.pwLock();
      closeModal('modal-passwords');
      toast('Password Vault locked');
    });
    $('pw-auth-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const pass = $('pw-master-input').value;
      const confirmInput = $('pw-master-confirm');
      const isSetup = !confirmInput.classList.contains('hidden');
      try {
        if (isSetup) {
          if (pass !== confirmInput.value) {
            toast('Passphrases do not match.');
            return;
          }
          await velo.pwSetup(pass);
          toast('3-Layer Password Vault created');
        } else {
          await velo.pwUnlock(pass);
        }
        await openPasswordVaultModal();
      } catch (err) {
        toast(err.message);
      }
    });
    $('pw-add-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      try {
        await velo.pwSave({
          siteName: $('pw-site-name').value,
          siteUrl: $('pw-site-url').value,
          username: $('pw-username').value,
          password: $('pw-secret').value
        });
        $('pw-add-form').reset();
        await renderPasswordEntries();
        toast('Password saved with 3-Layer MLV2 encryption');
      } catch (err) {
        toast(err.message);
      }
    });

    // Secret Media Vault Modal
    $('btn-vault-hide-files').addEventListener('click', async () => {
      try {
        const res = await velo.vaultHideDialog();
        if (res.added.length) {
          toast(`Encrypted & hid ${res.added.length} file(s). Originals removed from Explorer.`);
          await refreshSecretVaultItems();
        }
        if (res.warnings && res.warnings.length) {
          toast(res.warnings[0]);
        }
      } catch (err) {
        toast(err.message);
      }
    });
    $('btn-vault-lock').addEventListener('click', async () => {
      await velo.vaultLock();
      closeModal('modal-secret-vault');
      toast('Secret Vault locked & preview cache wiped');
    });
    $('vault-filter-bar').querySelectorAll('.filter-chip').forEach((btn) => {
      btn.addEventListener('click', () => {
        $('vault-filter-bar').querySelectorAll('.filter-chip').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        state.vaultFilter = btn.getAttribute('data-vault-filter') || 'all';
        renderSecretVaultItems();
      });
    });
    $('vault-code-form').addEventListener('submit', async (e) => {
      e.preventDefault();
      const nextCode = $('vault-new-code-input').value.trim();
      if (nextCode.length < 4) {
        toast('Secret code must be at least 4 characters.');
        return;
      }
      try {
        toast('Re-encrypting vault under new secret code...');
        await velo.vaultChangeCode(nextCode);
        $('vault-new-code-input').value = '';
        $('vault-status-note').textContent = 'Custom secret code active. Original files are removed from Windows Explorer.';
        toast('Secret code updated successfully!');
      } catch (err) {
        toast(err.message);
      }
    });

    // Settings Modal
    $('btn-settings').addEventListener('click', () => {
      renderHistory();
      openModal('modal-settings');
    });
    $('setting-adblock').addEventListener('change', (e) => {
      state.adBlockEnabled = e.target.checked;
      applyTheme();
      persistState();
    });
    $('setting-appsbar').addEventListener('change', (e) => {
      state.appsBarVisible = e.target.checked;
      applyTheme();
      persistState();
    });
    $('setting-search-engine').addEventListener('change', (e) => {
      state.searchEngine = e.target.value;
      persistState();
    });
    $('btn-clear-history').addEventListener('click', () => {
      state.history = [];
      persistState();
      renderHistory();
      toast('History cleared');
    });

    // Modal Close Buttons & Backdrop click
    document.querySelectorAll('.modal-close').forEach((btn) => {
      btn.addEventListener('click', () => closeModal(btn.getAttribute('data-close')));
    });
    document.querySelectorAll('.modal-backdrop').forEach((backdrop) => {
      backdrop.addEventListener('click', (e) => {
        if (e.target === backdrop) closeModal(backdrop.id);
      });
    });

    // Global Keyboard Shortcuts
    window.addEventListener('keydown', (e) => {
      if (e.ctrlKey && e.key.toLowerCase() === 't') {
        e.preventDefault();
        createTab(NEWTAB_URL);
      } else if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === 'n') {
        e.preventDefault();
        createTab(NEWTAB_URL, { isPrivate: true });
      } else if (e.ctrlKey && e.key.toLowerCase() === 'w') {
        e.preventDefault();
        if (state.activeTabId) closeTab(state.activeTabId);
      } else if (e.ctrlKey && e.key.toLowerCase() === 'l') {
        e.preventDefault();
        $('omnibox-input').focus();
        $('omnibox-input').select();
      } else if (e.ctrlKey && e.key.toLowerCase() === 'r') {
        e.preventDefault();
        const tab = getActiveTab();
        const wv = tab && tabWebviews.get(tab.id);
        if (wv) wv.reload();
      } else if (e.ctrlKey && e.key.toLowerCase() === 'j') {
        e.preventDefault();
        refreshDownloads().then(() => openModal('modal-downloads'));
      } else if (e.key === 'Escape') {
        document.querySelectorAll('.modal-backdrop:not(.hidden)').forEach((m) => closeModal(m.id));
      }
    });
  }

  // --- Wire IPC Events from Main Process ---
  function wireMainEvents() {
    velo.onTrackerBlocked(({ total }) => {
      state.totalBlockedCount = total;
      $('shield-count').textContent = String(total);
    });

    velo.onDownloadUpdated((record) => {
      const idx = state.downloads.findIndex((d) => d.id === record.id);
      if (idx >= 0) state.downloads[idx] = { ...state.downloads[idx], ...record };
      else state.downloads.unshift(record);
      if (!$('modal-downloads').classList.contains('hidden')) {
        renderDownloads();
      }
      if (record.state === 'completed') {
        toast(`Downloaded: ${record.filename}`);
      }
    });

    velo.onStreamsUpdated(({ webContentsId, streams }) => {
      const activeWcId = getActiveWebContentsId();
      if (!activeWcId || Number(webContentsId) !== Number(activeWcId)) {
        return;
      }
      state.interceptedStreams = Array.isArray(streams) ? streams : [];
      updateMediaBadges();
      if (!$('modal-media').classList.contains('hidden')) {
        renderDetectedMediaModal();
      }
    });

    velo.onSpeedTestProgress((data) => {
      if (typeof data.pingMs === 'number') $('metric-ping').textContent = `${data.pingMs} ms`;
      if (typeof data.jitterMs === 'number') $('metric-jitter').textContent = `${data.jitterMs} ms`;
      if (typeof data.downloadMbps === 'number') $('metric-down').textContent = `${data.downloadMbps} Mbps`;
      if (typeof data.uploadMbps === 'number') $('metric-up').textContent = `${data.uploadMbps} Mbps`;

      if (data.phase === 'ping') drawSpeedGauge(0, 'Measuring latency...');
      else if (data.phase === 'download') drawSpeedGauge(data.mbps || 0, 'Testing download speed...');
      else if (data.phase === 'upload') drawSpeedGauge(data.mbps || 0, 'Testing upload speed...');
      else if (data.phase === 'done') drawSpeedGauge(data.downloadMbps || 0, 'Speed test complete');
    });

    velo.onOpenExternalTarget((target) => {
      if (!target) return;
      if (target.kind === 'url' && target.value) {
        createTab(target.value);
      } else if (target.kind === 'file') {
        openFileInVelo(target);
      }
    });

    velo.onVaultsAutoLocked(() => {
      closeModal('modal-passwords');
      closeModal('modal-secret-vault');
    });
  }

  // --- Bootstrap ---
  async function init() {
    const saved = await velo.loadState();
    state.theme = saved.theme || 'dark';
    state.adBlockEnabled = saved.adBlockEnabled !== false;
    state.searchEngine = saved.searchEngine || 'google';
    state.appsBarVisible = saved.appsBarVisible !== false;
    state.totalBlockedCount = saved.totalBlockedCount || 0;
    state.bookmarks = Array.isArray(saved.bookmarks) ? saved.bookmarks : [];
    state.history = Array.isArray(saved.history) ? saved.history : [];

    applyTheme();
    renderBookmarks();
    wireUiEvents();
    wireMainEvents();
    drawSpeedGauge(0, 'Ready to test');
    createTab(NEWTAB_URL);
  }

  init().catch((err) => {
    toast(`Startup error: ${err.message}`);
  });
})();
