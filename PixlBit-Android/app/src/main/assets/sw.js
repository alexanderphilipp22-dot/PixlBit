// Bei jeder Aenderung an der App-Shell (index.html) hochzaehlen: der neue Name
// erzwingt im activate-Event das Loeschen des alten Caches, damit die Fassade
// nicht aus einem veralteten Cache ausgeliefert wird.
// v2.8.2: Pixel-Bett v2 (Magic/Modus/CRC16) + byte-genaues Bild-Einlesen. Der
// Namenswechsel stellt sicher, dass keine gecachte alte index.html ausgeliefert wird.
const CACHE_NAME = 'calc-v2.8.2-cache';

// Nur die absolut sicheren lokalen Kerndateien im install-Event cachen
const ASSETS_TO_CACHE = [
  './',
  'index.html',
  'manifest.json',
  'icon-192.png',
  'icon-512.png'
];

// Installieren des Service Workers und fehlerresistentes Cachen der lokalen App Shell
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      console.log('[Service Worker] Caching local App Shell assets');
      
      // Einzelne Promises cachen und Fehler abfangen, damit der gesamte Install-Prozess NIE fehlschlägt
      const cachePromises = ASSETS_TO_CACHE.map((url) => {
        return cache.add(url).catch((err) => {
          console.error(`[Service Worker] Fehler beim Cachen der lokalen Ressource ${url}:`, err);
        });
      });
      
      return Promise.all(cachePromises);
    }).then(() => self.skipWaiting())
  );
});

// Aktivieren und alten Cache löschen
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((cache) => {
          if (cache !== CACHE_NAME) {
            console.log('[Service Worker] Clearing Old Cache:', cache);
            return caches.delete(cache);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

// Hilfsfunktion zum Laden und Cachen von externen Ressourcen (CDNs)
async function fetchAndCacheExternal(request, cache) {
  try {
    // Standard-Fetch mit der Original-Anfrage durchführen
    const response = await fetch(request);
    
    // Wenn die Antwort erfolgreich ist oder opaque (status === 0), im Cache ablegen
    if (response.status === 200 || response.status === 0) {
      await cache.put(request, response.clone());
    }
    return response;
  } catch (err) {
    // Bei Fehlschlagen (z. B. CORS-Probleme oder Offline) probieren wir einen Fallback mit no-cors
    // (sofern der Original-Modus nicht explizit 'cors' verlangt, wie bei Fonts)
    if (request.mode !== 'cors') {
      try {
        const noCorsRequest = new Request(request.url, {
          method: 'GET',
          mode: 'no-cors',
          credentials: 'omit'
        });
        const response = await fetch(noCorsRequest);
        await cache.put(request, response.clone());
        return response;
      } catch (innerErr) {
        console.error('[Service Worker] Fallback-Fetch mit no-cors ebenfalls fehlgeschlagen:', innerErr);
      }
    }

    console.warn('[Service Worker] Netzwerk-Request fehlgeschlagen für:', request.url, err);
    
    // Falls offline, versuchen wir die Ressource aus dem Cache zu laden
    const cachedResponse = await caches.match(request);
    if (cachedResponse) {
      return cachedResponse;
    }
    throw err;
  }
}

// Netzwerk-Requests abfangen und intelligente Caching-Strategien anwenden
self.addEventListener('fetch', (event) => {
  // Nur GET-Requests verarbeiten
  if (event.request.method !== 'GET') return;

  const requestUrl = new URL(event.request.url);
  const isExternal = requestUrl.origin !== self.location.origin;

  if (isExternal) {
    // Externe CDN-Ressourcen: Stale-While-Revalidate mit hoher Fehlertoleranz und no-cors Unterstützung
    event.respondWith(
      caches.open(CACHE_NAME).then(async (cache) => {
        const cachedResponse = await cache.match(event.request);
        if (cachedResponse) {
          // Im Hintergrund aktualisieren (stille Ausführung)
          fetchAndCacheExternal(event.request, cache).catch(() => {});
          return cachedResponse;
        }
        // Falls noch nicht im Cache, direkt laden und im Cache speichern
        return fetchAndCacheExternal(event.request, cache);
      })
    );
  } else {
    // Lokale Ressourcen: Cache First, mit Fallback auf Netzwerk
    event.respondWith(
      caches.match(event.request).then((cachedResponse) => {
        if (cachedResponse) {
          return cachedResponse;
        }
        return fetch(event.request).catch((err) => {
          // Fallback für Navigationen im Offline-Modus
          if (event.request.mode === 'navigate') {
            return caches.match('index.html');
          }
          throw err;
        });
      })
    );
  }
});
