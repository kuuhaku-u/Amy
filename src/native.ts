import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import type { ExpenseKey } from "./types";

export type CaptureType = "DEBIT" | "CREDIT";
export type CaptureMethod = "NOTIFICATION" | "IMAGE";

export interface CaptureDraft {
  id: string;
  eventId: string;
  type: CaptureType;
  amount: number | null;
  merchant: string;
  date: string;
  occurredAt: string;
  source: string;
  captureMethod: CaptureMethod;
  category: ExpenseKey | null;
  confidence: number;
}

interface CapturePlugin {
  listDrafts(): Promise<{ drafts: CaptureDraft[] }>;
  dismissDraft(options: { id: string }): Promise<void>;
  completeDraft(options: { id: string; merchant: string; category?: string }): Promise<void>;
  scanImage(): Promise<{ created: boolean }>;
  openNotificationAccess(): Promise<void>;
  ensureNotificationPermission(): Promise<void>;
  notificationAccess(): Promise<{ granted: boolean }>;
  saveAccessToken(options: { token: string }): Promise<void>;
  getAccessToken(): Promise<{ token: string }>;
  deviceId(): Promise<{ deviceId: string }>;
  addListener(eventName: "draftAvailable", listener: () => void): Promise<PluginListenerHandle>;
}

export const Capture = registerPlugin<CapturePlugin>("ExpenseCapture");
export const isNativeAndroid = () => Capacitor.isNativePlatform() && Capacitor.getPlatform() === "android";
