const path = require('node:path');

const APP_URL = 'nxr://app/';
const CSP = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self' blob:; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; connect-src 'none'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'none'";
const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.wasm': 'application/wasm',
  '.txt': 'text/plain; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ico': 'image/x-icon', '.json': 'application/json',
};

function isAppUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'nxr:' && url.hostname === 'app' && !url.port && !url.username && !url.password;
  } catch { return false; }
}

function assetPath(value) {
  if (!isAppUrl(value)) return null;
  let pathname;
  try { pathname = decodeURIComponent(new URL(value).pathname); } catch { return null; }
  if (pathname === '/') return 'index.html';
  if (!pathname.startsWith('/') || /[\\\0]/.test(pathname)) return null;
  const parts = pathname.slice(1).split('/');
  if (parts.some(part => !part || part.startsWith('.'))) return null;
  if (!CONTENT_TYPES[path.posix.extname(pathname)]) return null;
  return parts.join('/');
}

module.exports = { APP_URL, CSP, CONTENT_TYPES, isAppUrl, assetPath };
