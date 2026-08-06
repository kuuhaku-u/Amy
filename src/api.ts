import { queuedItems, removeQueued } from "./db";
import type { AnalyticsResponse, CashflowResponse, DriveConnection, FoodLogRequest, HistoryEntry, ReceiptImage, ReceiptResponse, SheetInfo, SpendResponse, TransactionRequest, TransactionResponse } from "./types";
import { recordApiLog } from "./devlog";

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

async function request<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  const started = performance.now();
  const method = init?.method || "GET";
  let response: Response;
  try { response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
      ...(init?.body && !(init.body instanceof FormData) ? { "Content-Type": "application/json" } : {}),
      ...init?.headers
    }
  }); } catch (error) {
    recordApiLog({ method, path, status: null, durationMs: Math.round(performance.now() - started), error: error instanceof Error ? error.message : "Network error" });
    throw error;
  }
  const body = (await response.json().catch(() => ({}))) as { error?: string };
  recordApiLog({ method, path, status: response.status, durationMs: Math.round(performance.now() - started), ...(response.ok ? {} : { error: body.error || response.statusText }) });
  if (!response.ok) throw new ApiError(body.error || "The server could not complete the request.", response.status);
  return body as T;
}

export function loadDate(date: string, sheet: string, token: string): Promise<SpendResponse> {
  return request(`/api/monthly-spend?date=${encodeURIComponent(date)}&sheet=${encodeURIComponent(sheet)}`, token);
}

export function loadAnalytics(date: string, sheet: string, token: string): Promise<AnalyticsResponse> {
  return request(`/api/monthly-spend/analytics?date=${encodeURIComponent(date)}&sheet=${encodeURIComponent(sheet)}`, token);
}

export function loadSheets(token: string): Promise<SheetInfo[]> {
  return request("/api/monthly-spend/sheets", token);
}

export function createSheet(name: string, sourceSheet: string, token: string): Promise<SheetInfo> {
  return request("/api/monthly-spend/sheets", token, { method: "POST", body: JSON.stringify({ name, sourceSheet }) });
}

export function loadHistory(from: string, to: string, sheet: string, token: string): Promise<HistoryEntry[]> {
  return request(`/api/monthly-spend/history?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&sheet=${encodeURIComponent(sheet)}`, token);
}

export function loadCashflow(token: string): Promise<CashflowResponse> {
  return request("/api/monthly-spend/cashflow", token);
}

export function uploadReceipt(file: File, date: string, sheet: string, kind: "food" | "spend", token: string): Promise<ReceiptResponse> {
  const body = new FormData(); body.append("file", file); body.append("date", date); body.append("sheet", sheet); body.append("kind", kind);
  return request("/api/monthly-spend/receipts", token, { method: "POST", body });
}

export function loadReceipts(date: string, sheet: string, token: string): Promise<ReceiptImage[]> {
  return request(`/api/monthly-spend/receipts?date=${encodeURIComponent(date)}&sheet=${encodeURIComponent(sheet)}`, token);
}

export function loadAllReceipts(sheet: string, token: string): Promise<ReceiptImage[]> {
  return request(`/api/monthly-spend/receipts?sheet=${encodeURIComponent(sheet)}`, token);
}

export function loadDriveConnection(token: string): Promise<DriveConnection> {
  return request("/api/monthly-spend/drive/status", token);
}

export function logFood(entry: FoodLogRequest, token: string): Promise<{ status: string }> {
  return request("/api/monthly-spend/food-log", token, { method: "POST", body: JSON.stringify(entry) });
}

export function savePrivateIncome(month: string, income: number, token: string): Promise<{ income: number }> {
  return request("/api/monthly-spend/private-income", token, { method: "POST", body: JSON.stringify({ month, income }) });
}

export function unlockPrivateIncome(month: string, token: string): Promise<{ income: number }> {
  return request("/api/monthly-spend/private-income/unlock", token, { method: "POST", body: JSON.stringify({ month }) });
}

export function recordTransaction(transaction: TransactionRequest, token: string): Promise<TransactionResponse> {
  return request("/api/monthly-spend/transactions", token, {
    method: "POST",
    body: JSON.stringify(transaction)
  });
}

export async function syncQueued(token: string): Promise<{ synced: number; remaining: number }> {
  const items = await queuedItems();
  let synced = 0;
  for (const item of items) {
    try {
      await request<SpendResponse>("/api/monthly-spend", token, {
        method: "PUT",
        body: JSON.stringify(item)
      });
      await removeQueued(item.submissionId);
      synced += 1;
    } catch (error) {
      if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 429) throw error;
      break;
    }
  }
  return { synced, remaining: items.length - synced };
}
