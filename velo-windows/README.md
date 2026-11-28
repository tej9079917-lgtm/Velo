# Velo Browser for Windows (.exe)

Desktop Chromium build of **Velo Browser** for Windows 10 & Windows 11 (x64), packaged as both an NSIS installer (`VeloBrowser-Setup.exe`) and a standalone portable executable (`VeloBrowser-Portable.exe`).

## Features
- **All-in-One Apps Bar**: Instant desktop sessions for Instagram, WhatsApp Web, Spotify, Netflix, and Prime Video with persistent cookies.
- **Built-in Ad & Tracker Blocker**: Blocks ad and analytics trackers at the network layer with a live shield counter.
- **Universal Video & Full-Size Image Downloader**: Captures embedded videos without download links and extracts full-resolution webpage images in **Original, PNG, JPEG, WEBP, or PDF** directly to `Downloads/Velo`.
- **3-Layer Encrypted Password Vault (`MLV2:`)**: PBKDF2-HMAC-SHA256 (210,000 iterations) + Ephemeral DEK AES-256-GCM + HKDF AES-256-CBC/HMAC-SHA256 + Outer AES-256-GCM Seal, screen-capture protection, auto-lock, and 30s clipboard wipe.
- **Stealth Secret Media Vault**: Type your secret code (first-run default: `#0000`) directly into the top address bar to unlock. Encrypts Videos, Images, PDFs, and Audios in chunked AES-256-GCM `.vlt` files and removes originals from Windows Explorer.
- **PDF Viewer & Converter**: Open local PDFs, save any webpage to PDF, and convert images to PDF.
- **Built-in Internet Speed Test Meter**: Measures Ping, Jitter, Download Mbps, and Upload Mbps.
- **Windows File Associations**: Registered to open `.pdf`, `.mp4`, `.webm`, `.mkv`, `.jpg`, `.jpeg`, `.png`, and `.webp` files directly in Velo Browser.

## Building on Windows Locally
```bash
cd velo-windows
npm install
npm run build:win
```
Installers are generated in `velo-windows/dist/`:
- `VeloBrowser-Setup.exe` (Windows Installer)
- `VeloBrowser-Portable.exe` (Portable App)

## Automated GitHub Releases
Pushing to `main` (or triggering **Build and Publish Velo Browser Windows (.exe)** in GitHub Actions) automatically builds and attaches both `.exe` files to GitHub Releases.
