export interface ApiLogEntry {
  id: string;
  time: number;
  method: string;
  path: string;
  status: number | null;
  durationMs: number;
  error?: string;
}

let entries: ApiLogEntry[] = [];
const listeners = new Set<() => void>();

export function recordApiLog(entry: Omit<ApiLogEntry, "id" | "time">) {
  entries = [{ ...entry, id: crypto.randomUUID(), time: Date.now() }, ...entries].slice(0, 100);
  listeners.forEach((listener) => listener());
}

export function apiLogs() { return entries; }
export function clearApiLogs() { entries = []; listeners.forEach((listener) => listener()); }
export function subscribeApiLogs(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }
