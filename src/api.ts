import { queuedItems, removeQueued } from "./db";
import type { SpendResponse } from "./types";

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

async function request<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers
    }
  });
  const body = (await response.json().catch(() => ({}))) as { error?: string };
  if (!response.ok) throw new ApiError(body.error || "The server could not complete the request.", response.status);
  return body as T;
}

export function loadDate(date: string, token: string): Promise<SpendResponse> {
  return request(`/api/monthly-spend?date=${encodeURIComponent(date)}`, token);
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

