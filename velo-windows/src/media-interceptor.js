'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { URL, pathToFileURL } = require('url');

const STREAM_EXT_RE = /\.(m3u8|mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg)(\?|$)/i;
const STREAM_URL_HINT_RE = /(playlist\.m3u8|master\.m3u8|index\.m3u8|chunklist.*\.m3u8|manifest\.m3u8|mime=video|videoplayback)/i;
const IGNORE_SEGMENT_RE = /\.(ts|m4s|vtt|srt|jpg|jpeg|png|webp|gif|svg|css|js|woff2?|json)(\?|$)/i;

const STREAM_MIME_PREFIXES = [
  'application/vnd.apple.mpegurl',
  'application/x-mpegurl',
  'audio/mpegurl',
  'application/mpegurl',
  'video/mp4',
  'video/webm',
  'video/quicktime',
  'video/x-matroska',
  'video/x-flv',
  'audio/mpeg',
  'audio/mp4',
  'audio/aac',
  'audio/webm',
  'audio/ogg'
];

function isM3u8UrlOrMime(url, mime) {
  const u = String(url || '').toLowerCase();
  const m = String(mime || '').toLowerCase();
  return (
    /\.m3u8(\?|$)/i.test(u) ||
    m.includes('mpegurl') ||
    m.includes('m3u8')
  );
}

function classifyStreamFormat(url, mime) {
  if (isM3u8UrlOrMime(url, mime)) return 'm3u8';
  const m = String(mime || '').toLowerCase();
  const u = String(url || '').toLowerCase();
  if (m.includes('webm') || /\.webm(\?|$)/i.test(u)) return 'webm';
  if (m.includes('matroska') || /\.mkv(\?|$)/i.test(u)) return 'mkv';
  if (m.includes('quicktime') || /\.mov(\?|$)/i.test(u)) return 'mov';
  if (m.startsWith('audio/') || /\.(mp3|m4a|aac|ogg)(\?|$)/i.test(u)) return 'audio';
  return 'mp4';
}

function normalizeStreamKey(rawUrl) {
  try {
    const parsed = new URL(rawUrl);
    // Strip transient range/cache-buster params so identical MP4 streams don't duplicate
    parsed.searchParams.delete('range');
    parsed.searchParams.delete('rn');
    parsed.searchParams.delete('rbuf');
    return parsed.href;
  } catch {
    return rawUrl;
  }
}

function deriveStreamLabel(rawUrl, format) {
  try {
    const parsed = new URL(rawUrl);
    const base = path.basename(parsed.pathname) || parsed.hostname;
    const cleanBase = decodeURIComponent(base).slice(0, 48);
    return `${format.toUpperCase()} • ${cleanBase} (${parsed.hostname})`;
  } catch {
    return `${format.toUpperCase()} Stream`;
  }
}

function createMediaInterceptor({ onStreamsChanged }) {
  // webContentsId -> Map<normalizedKey, StreamEntry>
  const streamsByTab = new Map();
  // requestId -> { referer, origin, userAgent }
  const requestHeadersById = new Map();
  const hookedSessions = new WeakSet();

  function getTabMap(wcId) {
    let map = streamsByTab.get(wcId);
    if (!map) {
      map = new Map();
      streamsByTab.set(wcId, map);
    }
    return map;
  }

  function listStreams(wcId) {
    const map = streamsByTab.get(Number(wcId));
    if (!map) return [];
    return Array.from(map.values()).sort((a, b) => {
      // Prioritize M3U8 master/playlists & larger MP4 streams first
      if (a.format === 'm3u8' && b.format !== 'm3u8') return -1;
      if (b.format === 'm3u8' && a.format !== 'm3u8') return 1;
      return (b.contentLength || 0) - (a.contentLength || 0) || b.detectedAt - a.detectedAt;
    });
  }

  function clearStreams(wcId) {
    const id = Number(wcId);
    if (streamsByTab.has(id)) {
      streamsByTab.delete(id);
      if (onStreamsChanged) onStreamsChanged(id, []);
    }
  }

  function recordStream(wcId, entry) {
    if (!wcId || wcId <= 0) return;
    const key = normalizeStreamKey(entry.url);
    const map = getTabMap(wcId);
    const prev = map.get(key);
    const merged = {
      id: prev ? prev.id : crypto.randomUUID(),
      webContentsId: wcId,
      url: entry.url,
      format: entry.format,
      mimeType: entry.mimeType || (prev && prev.mimeType) || '',
      contentLength: Math.max(Number(entry.contentLength) || 0, Number(prev && prev.contentLength) || 0),
      referer: entry.referer || (prev && prev.referer) || '',
      origin: entry.origin || (prev && prev.origin) || '',
      label: deriveStreamLabel(entry.url, entry.format),
      source: 'network',
      detectedAt: prev ? prev.detectedAt : Date.now()
    };
    map.set(key, merged);
    // Cap per-tab tracked streams to 40
    if (map.size > 40) {
      const oldestKey = map.keys().next().value;
      map.delete(oldestKey);
    }
    if (onStreamsChanged) {
      onStreamsChanged(wcId, listStreams(wcId));
    }
  }

  function attachToSession(ses) {
    if (!ses || hookedSessions.has(ses)) return;
    hookedSessions.add(ses);

    // 1. Capture Referer/Origin headers for media requests
    ses.webRequest.onBeforeSendHeaders({ urls: ['http://*/*', 'https://*/*'] }, (details, callback) => {
      if (details.webContentsId && details.webContentsId > 0) {
        const url = details.url || '';
        const headers = details.requestHeaders || {};
        const ref = headers.Referer || headers.referer || '';
        const origin = headers.Origin || headers.origin || '';

        if (!IGNORE_SEGMENT_RE.test(url) && (STREAM_EXT_RE.test(url) || STREAM_URL_HINT_RE.test(url) || details.resourceType === 'media' || details.resourceType === 'xhr')) {
          requestHeadersById.set(details.id, { referer: ref, origin });
          if (requestHeadersById.size > 400) {
            const firstKey = requestHeadersById.keys().next().value;
            requestHeadersById.delete(firstKey);
          }
        }

        // Immediate URL-based detection for M3U8/MP4 so download button lights up right away
        if (!IGNORE_SEGMENT_RE.test(url) && (STREAM_EXT_RE.test(url) || STREAM_URL_HINT_RE.test(url))) {
          const format = classifyStreamFormat(url, '');
          recordStream(details.webContentsId, {
            url,
            format,
            mimeType: format === 'm3u8' ? 'application/vnd.apple.mpegurl' : `video/${format}`,
            referer: ref,
            origin
          });
        }
      }
      callback({ cancel: false });
    });

    // 2. Response header inspection (catches extensionless MP4/M3U8 streams & CDN redirects!)
    ses.webRequest.onHeadersReceived({ urls: ['http://*/*', 'https://*/*'] }, (details, callback) => {
      try {
        const wcId = details.webContentsId;
        const url = details.url || '';
        const savedHeaders = requestHeadersById.get(details.id) || {};
        requestHeadersById.delete(details.id);

        if (wcId && wcId > 0 && details.statusCode >= 200 && details.statusCode < 400 && !IGNORE_SEGMENT_RE.test(url)) {
          const respHeaders = details.responseHeaders || {};
          let contentType = '';
          let contentLength = 0;
          let contentRangeTotal = 0;

          for (const [k, vals] of Object.entries(respHeaders)) {
            const lk = k.toLowerCase();
            const v = Array.isArray(vals) ? String(vals[0] || '') : String(vals || '');
            if (lk === 'content-type') contentType = v.split(';')[0].trim().toLowerCase();
            else if (lk === 'content-length') contentLength = parseInt(v, 10) || 0;
            else if (lk === 'content-range') {
              const m = v.match(/\/(\d+)$/);
              if (m) contentRangeTotal = parseInt(m[1], 10) || 0;
            }
          }

          const totalSize = contentRangeTotal || contentLength;
          const mimeMatches = STREAM_MIME_PREFIXES.some((prefix) => contentType.startsWith(prefix));
          const urlMatches = STREAM_EXT_RE.test(url) || STREAM_URL_HINT_RE.test(url);

          if (mimeMatches || urlMatches) {
            const format = classifyStreamFormat(url, contentType);
            // Skip tiny non-m3u8 video probes (< 64 KB) unless we don't know total size
            if (format === 'm3u8' || totalSize === 0 || totalSize >= 64 * 1024) {
              recordStream(wcId, {
                url,
                format,
                mimeType: contentType || (format === 'm3u8' ? 'application/vnd.apple.mpegurl' : 'video/mp4'),
                contentLength: totalSize,
                referer: savedHeaders.referer || '',
                origin: savedHeaders.origin || ''
              });
            }
          }
        }
      } catch {}
      callback({ cancel: false });
    });
  }

  return {
    attachToSession,
    listStreams,
    clearStreams
  };
}

// =========================================================================
// HLS (M3U8) & DIRECT VIDEO STREAM DOWNLOADER
// =========================================================================

function parseAttributes(line) {
  const attrs = {};
  const re = /([A-Z0-9-]+)=("([^"]*)"|([^,]*))/g;
  let match;
  while ((match = re.exec(line)) !== null) {
    attrs[match[1]] = match[3] !== undefined ? match[3] : match[4];
  }
  return attrs;
}

function resolveRelativeUrl(uri, baseUrl) {
  try {
    return new URL(uri, baseUrl).href;
  } catch {
    return uri;
  }
}

function buildFetchHeaders(ses, referer, origin) {
  const headers = {
    'User-Agent': ses.getUserAgent()
  };
  if (referer && /^https?:\/\//i.test(referer)) headers.Referer = referer;
  if (origin && /^https?:\/\//i.test(origin)) headers.Origin = origin;
  return headers;
}

async function fetchText(ses, url, headers) {
  const res = await ses.fetch(url, { headers, redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} fetching playlist`);
  return {
    text: await res.text(),
    finalUrl: res.url || url
  };
}

async function fetchBuffer(ses, url, headers) {
  const res = await ses.fetch(url, { headers, redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} fetching segment`);
  return Buffer.from(await res.arrayBuffer());
}

function parseMasterPlaylist(text, baseUrl) {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const variants = [];
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith('#EXT-X-STREAM-INF:')) {
      const attrs = parseAttributes(lines[i].slice('#EXT-X-STREAM-INF:'.length));
      const nextUri = lines[i + 1];
      if (nextUri && !nextUri.startsWith('#')) {
        variants.push({
          bandwidth: parseInt(attrs.BANDWIDTH || '0', 10) || 0,
          resolution: attrs.RESOLUTION || '',
          url: resolveRelativeUrl(nextUri, baseUrl)
        });
      }
    }
  }
  variants.sort((a, b) => b.bandwidth - a.bandwidth);
  return variants;
}

function parseMediaPlaylist(text, baseUrl) {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  let mediaSequence = 0;
  let initSegmentUrl = null;
  let currentKey = null; // { method, uri, iv }
  const segments = [];
  let seqOffset = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith('#EXT-X-MEDIA-SEQUENCE:')) {
      mediaSequence = parseInt(line.split(':')[1], 10) || 0;
    } else if (line.startsWith('#EXT-X-MAP:')) {
      const attrs = parseAttributes(line.slice('#EXT-X-MAP:'.length));
      if (attrs.URI) {
        initSegmentUrl = resolveRelativeUrl(attrs.URI, baseUrl);
      }
    } else if (line.startsWith('#EXT-X-KEY:')) {
      const attrs = parseAttributes(line.slice('#EXT-X-KEY:'.length));
      const method = (attrs.METHOD || 'NONE').toUpperCase();
      if (method === 'NONE') {
        currentKey = null;
      } else if (method === 'AES-128' && attrs.URI) {
        currentKey = {
          method: 'AES-128',
          uri: resolveRelativeUrl(attrs.URI, baseUrl),
          ivHex: attrs.IV ? attrs.IV.replace(/^0x/i, '') : null
        };
      } else {
        throw new Error(`Stream uses unsupported DRM/encryption method (${method}).`);
      }
    } else if (!line.startsWith('#')) {
      segments.push({
        url: resolveRelativeUrl(line, baseUrl),
        sequence: mediaSequence + seqOffset++,
        key: currentKey ? { ...currentKey } : null
      });
    }
  }

  return { initSegmentUrl, segments };
}

function ivForSequence(seq, ivHex) {
  if (ivHex) {
    const padded = ivHex.padStart(32, '0').slice(-32);
    return Buffer.from(padded, 'hex');
  }
  const iv = Buffer.alloc(16, 0);
  iv.writeUInt32BE(Math.floor(seq / 0x100000000), 8);
  iv.writeUInt32BE(seq >>> 0, 12);
  return iv;
}

async function downloadHlsStream({ ses, url, referer, origin, saveDir, baseName, uniqueDestPath, onProgress }) {
  const headers = buildFetchHeaders(ses, referer, origin);
  let { text, finalUrl } = await fetchText(ses, url, headers);

  if (!text.includes('#EXTM3U')) {
    throw new Error('URL did not return a valid M3U8 playlist.');
  }

  // Resolve master playlist -> highest-quality variant
  if (text.includes('#EXT-X-STREAM-INF:')) {
    const variants = parseMasterPlaylist(text, finalUrl);
    if (!variants.length) throw new Error('No playable streams found in master M3U8 playlist.');
    const best = variants[0];
    const resolved = await fetchText(ses, best.url, headers);
    text = resolved.text;
    finalUrl = resolved.finalUrl;
  }

  const { initSegmentUrl, segments } = parseMediaPlaylist(text, finalUrl);
  if (!segments.length) {
    throw new Error('No media segments found in M3U8 playlist.');
  }

  // Use .mp4 for fMP4 (init segment or .m4s) streams, .ts for MPEG-TS streams
  const isFmp4 = Boolean(initSegmentUrl) || /\.m4s(\?|$)/i.test(segments[0].url);
  const ext = isFmp4 ? '.mp4' : '.ts';
  const outMime = isFmp4 ? 'video/mp4' : 'video/mp2t';
  const savePath = uniqueDestPath(saveDir, `${baseName}${ext}`);

  const keyCache = new Map();
  async function getKeyBytes(keyUri) {
    if (keyCache.has(keyUri)) return keyCache.get(keyUri);
    const keyBuf = await fetchBuffer(ses, keyUri, headers);
    if (keyBuf.length !== 16) throw new Error('Invalid AES-128 HLS key length.');
    keyCache.set(keyUri, keyBuf);
    return keyBuf;
  }

  const outStream = fs.createWriteStream(savePath);
  let receivedBytes = 0;

  const writeBuffer = (buf) =>
    new Promise((resolve, reject) => {
      receivedBytes += buf.length;
      if (outStream.write(buf)) resolve();
      else outStream.once('drain', resolve);
      outStream.once('error', reject);
    });

  try {
    if (initSegmentUrl) {
      const initBuf = await fetchBuffer(ses, initSegmentUrl, headers);
      await writeBuffer(initBuf);
    }

    // Fetch segments in ordered batches of 4 for fast downloads while preserving exact playback order
    const BATCH_SIZE = 4;
    for (let i = 0; i < segments.length; i += BATCH_SIZE) {
      const batch = segments.slice(i, i + BATCH_SIZE);
      const buffers = await Promise.all(
        batch.map(async (seg) => {
          let data = await fetchBuffer(ses, seg.url, headers);
          if (seg.key && seg.key.method === 'AES-128') {
            const keyBytes = await getKeyBytes(seg.key.uri);
            const iv = ivForSequence(seg.sequence, seg.key.ivHex);
            const decipher = crypto.createDecipheriv('aes-128-cbc', keyBytes, iv);
            data = Buffer.concat([decipher.update(data), decipher.final()]);
          }
          return data;
        })
      );

      for (const buf of buffers) {
        await writeBuffer(buf);
      }

      if (onProgress) {
        const doneCount = Math.min(i + batch.length, segments.length);
        const estimatedTotal = Math.round((receivedBytes / doneCount) * segments.length);
        onProgress({
          receivedBytes,
          totalBytes: estimatedTotal,
          segmentsDone: doneCount,
          segmentsTotal: segments.length
        });
      }
    }

    await new Promise((resolve, reject) => {
      outStream.end((err) => (err ? reject(err) : resolve()));
    });

    return {
      filePath: savePath,
      fileUrl: pathToFileURL(savePath).href,
      filename: path.basename(savePath),
      mimeType: outMime,
      receivedBytes
    };
  } catch (err) {
    try { outStream.destroy(); } catch {}
    try { if (fs.existsSync(savePath)) fs.unlinkSync(savePath); } catch {}
    throw err;
  }
}

async function downloadDirectStream({ ses, url, referer, origin, savePath, onProgress }) {
  const headers = buildFetchHeaders(ses, referer, origin);
  const response = await ses.fetch(url, { headers, redirect: 'follow' });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} while downloading stream.`);
  }
  const contentType = response.headers.get('content-type') || 'video/mp4';
  const totalBytes = parseInt(response.headers.get('content-length') || '0', 10) || 0;
  const outStream = fs.createWriteStream(savePath);
  let receivedBytes = 0;
  let lastEmit = 0;

  try {
    if (response.body && typeof response.body.getReader === 'function') {
      const reader = response.body.getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        const buf = Buffer.from(value);
        receivedBytes += buf.length;
        if (!outStream.write(buf)) {
          await new Promise((resolve, reject) => {
            outStream.once('drain', resolve);
            outStream.once('error', reject);
          });
        }
        const now = Date.now();
        if (onProgress && now - lastEmit >= 200) {
          lastEmit = now;
          onProgress({ receivedBytes, totalBytes });
        }
      }
    } else {
      const buf = Buffer.from(await response.arrayBuffer());
      receivedBytes = buf.length;
      outStream.write(buf);
    }

    await new Promise((resolve, reject) => {
      outStream.end((err) => (err ? reject(err) : resolve()));
    });

    return {
      filePath: savePath,
      fileUrl: pathToFileURL(savePath).href,
      filename: path.basename(savePath),
      mimeType: contentType.split(';')[0].trim(),
      receivedBytes
    };
  } catch (err) {
    try { outStream.destroy(); } catch {}
    try { if (fs.existsSync(savePath)) fs.unlinkSync(savePath); } catch {}
    throw err;
  }
}

module.exports = {
  createMediaInterceptor,
  isM3u8UrlOrMime,
  classifyStreamFormat,
  parseMasterPlaylist,
  parseMediaPlaylist,
  downloadHlsStream,
  downloadDirectStream
};
