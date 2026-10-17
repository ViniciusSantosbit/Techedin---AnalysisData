const CACHE = 'techedin-static-v3';
const PUBLIC = ['./offline.html', './manifest.webmanifest', './icons/techedin-180.png', './icons/techedin-192.png', './icons/techedin-512.png', './icons/techedin-maskable-512.png'];
self.addEventListener('install', event => { event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(PUBLIC)).then(() => self.skipWaiting())); });
self.addEventListener('activate', event => { event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('techedin-static-') && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);
    if (event.request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
    if (event.request.mode === 'navigate') event.respondWith(fetch(event.request).then(response => response.status >= 500 ? caches.match('./offline.html') : response).catch(() => caches.match('./offline.html')));
    else if (PUBLIC.some(path => new URL(path, self.registration.scope).href === url.href)) event.respondWith(caches.match(event.request).then(cached => cached || fetch(event.request)));
});
self.addEventListener('push', event => { event.waitUntil(self.registration.showNotification('Techedin Â· Vagas para explorar', {
    body: 'Abra o app para conferir as novidades nas suas sugestÃµes.', icon: new URL('./icons/techedin-192.png', self.registration.scope).href,
    badge: new URL('./icons/techedin-192.png', self.registration.scope).href, tag: 'techedin-jobs', renotify: false
})); });
self.addEventListener('notificationclick', event => {
    event.notification.close();
    const target = new URL('./#recommendations-section', self.registration.scope).href;
    event.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(async clients => {
        for (const client of clients) if (new URL(client.url).origin === self.location.origin) { if ('navigate' in client) await client.navigate(target); return client.focus(); }
        return self.clients.openWindow(target);
    }));
});
