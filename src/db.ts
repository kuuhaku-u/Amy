import type { QueuedSubmission } from "./types";

const DB_NAME = "monthly-spend";
const DB_VERSION = 1;

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains("queue")) db.createObjectStore("queue", { keyPath: "submissionId" });
      if (!db.objectStoreNames.contains("config")) db.createObjectStore("config");
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function setConfig(key: string, value: string): Promise<void> {
  const db = await openDb();
  await transactionDone(db, "config", "readwrite", (store) => store.put(value, key));
}

export async function enqueue(item: QueuedSubmission): Promise<void> {
  const db = await openDb();
  await transactionDone(db, "queue", "readwrite", (store) => store.put(item));
}

export async function queuedItems(): Promise<QueuedSubmission[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const request = db.transaction("queue", "readonly").objectStore("queue").getAll();
    request.onsuccess = () => resolve((request.result as QueuedSubmission[]).sort((a, b) => a.createdAt - b.createdAt));
    request.onerror = () => reject(request.error);
  });
}

export async function removeQueued(id: string): Promise<void> {
  const db = await openDb();
  await transactionDone(db, "queue", "readwrite", (store) => store.delete(id));
}

function transactionDone(
  db: IDBDatabase,
  storeName: string,
  mode: IDBTransactionMode,
  operation: (store: IDBObjectStore) => IDBRequest
): Promise<void> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(storeName, mode);
    operation(transaction.objectStore(storeName));
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
  });
}

