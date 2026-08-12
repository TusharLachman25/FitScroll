/**
 * Offline support for the FitScroll PWA.
 *
 * Two caches with different policies:
 *
 *  - the app shell is small and changes with every deploy, so it is
 *    network-first with a cache fallback; a stale shell would leave someone
 *    staring at an old balance after an update.
 *  - the MediaPipe wasm bundle and the pose model are tens of megabytes and
 *    immutable at their versioned URLs, so they are cache-first. Re-downloading
 *    them on a gym wifi would make the camera unusable.
 */

const VERSION = 'v1';
const SHELL_CACHE = `fitscroll-shell-${VERSION}`;
const MODEL_CACHE = `fitscroll-model-${VERSION}`;

const SHELL_ASSETS = [
  './',
  './index.html',
  './css/styles.css',
  './js/app.js',
  './js/bank.js',
  './js/counter.js',
  './js/pose.js',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
];

const MODEL_HOSTS = ['cdn.jsdelivr.net', 'storage.googleapis.com'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      // addAll is atomic: one 404 would leave the whole shell uncached, so each
      // asset is added independently.
      .then((cache) => Promise.allSettled(SHELL_ASSETS.map((asset) => cache.add(asset))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) =>
        Promise.all(
          names
            .filter((name) => name !== SHELL_CACHE && name !== MODEL_CACHE)
            .map((name) => caches.delete(name)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  if (MODEL_HOSTS.includes(url.hostname)) {
    event.respondWith(cacheFirst(request, MODEL_CACHE));
    return;
  }

  if (url.origin === self.location.origin) {
    event.respondWith(networkFirst(request, SHELL_CACHE));
  }
});

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const hit = await cache.match(request);
  if (hit) return hit;

  const response = await fetch(request);
  // Opaque cross-origin responses are cached too: they cannot be inspected,
  // but replaying one still beats a failed model load with no network.
  if (response.ok || response.type === 'opaque') {
    cache.put(request, response.clone());
  }
  return response;
}

async function networkFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  try {
    const response = await fetch(request);
    if (response.ok) cache.put(request, response.clone());
    return response;
  } catch (error) {
    const hit = await cache.match(request);
    if (hit) return hit;
    throw error;
  }
}
