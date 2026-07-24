const CACHE = "monthly-spend-v1";
const SHELL = ["/", "/manifest.webmanifest", "/icon.svg"];
const DB_NAME = "monthly-spend";

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET" || new URL(event.request.url).pathname.startsWith("/api/")) return;
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        const copy = response.clone();
        caches.open(CACHE).then((cache) => cache.put(event.request, copy));
        return response;
      })
      .catch(() => caches.match(event.request).then((response) => response || caches.match("/")))
  );
});

function openDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains("queue")) db.createObjectStore("queue", { keyPath: "submissionId" });
      if (!db.objectStoreNames.contains("config")) db.createObjectStore("config");
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function readStore(storeName) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(storeName, "readonly");
    const request = tx.objectStore(storeName).getAll();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function getConfig(key) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const request = db.transaction("config", "readonly").objectStore("config").get(key);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function removeQueued(id) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction("queue", "readwrite");
    tx.objectStore("queue").delete(id);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

async function syncQueue() {
  const token = await getConfig("accessToken");
  if (!token) return;
  const items = (await readStore("queue")).sort((a, b) => a.createdAt - b.createdAt);
  for (const item of items) {
    let response;
    try {
      response = await fetch("/api/monthly-spend", {
        method: "PUT",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify(item)
      });
    } catch {
      return;
    }
    if (response.ok) await removeQueued(item.submissionId);
    else if (response.status < 500 && response.status !== 429) return;
    else return;
  }
  const clients = await self.clients.matchAll({ type: "window" });
  clients.forEach((client) => client.postMessage({ type: "SYNC_COMPLETE" }));
}

self.addEventListener("sync", (event) => {
  if (event.tag === "sync-monthly-spend") event.waitUntil(syncQueue());
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "SKIP_WAITING") self.skipWaiting();
  if (event.data?.type === "SYNC_NOW") event.waitUntil(syncQueue());
});
