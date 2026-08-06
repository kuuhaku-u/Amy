export const expenseFields = [
  { key: "travel", label: "Travel", icon: "↗" },
  { key: "breakfast", label: "Breakfast", icon: "☀" },
  { key: "lunch", label: "Lunch", icon: "◐" },
  { key: "eveSnack", label: "Eve Snack", icon: "◇" },
  { key: "dinner", label: "Dinner", icon: "☾" },
  { key: "order", label: "Order", icon: "□" },
  { key: "others", label: "Others", icon: "+" },
  { key: "home", label: "Home", icon: "⌂" },
  { key: "rent", label: "Rent", icon: "₹" }
] as const;

export type ExpenseKey = (typeof expenseFields)[number]["key"];
export type ExpenseValues = Record<ExpenseKey, number | null>;

export interface SpendResponse {
  date: string;
  exists: boolean;
  values: ExpenseValues;
  comments: Partial<Record<ExpenseKey, string>>;
  total: number;
  weekTotal: number;
  monthlySpend: number;
}

export interface AnalyticsResponse {
  month: string;
  monthlyTotal: number;
  previousMonthTotal: number;
  monthChangePercent: number | null;
  averageRecordedDay: number;
  highestCategory: string;
  daily: { date: string; total: number }[];
  categories: { key: ExpenseKey; label: string; total: number }[];
  week: { start: string; total: number; previousTotal: number; changePercent: number | null };
  insights: string[];
}

export interface TransactionRequest {
  eventId: string;
  date: string;
  sheetName?: string;
  occurredAt: string;
  type: "DEBIT" | "CREDIT";
  amount: number;
  category?: ExpenseKey;
  merchant?: string;
  source: string;
  captureMethod: "NOTIFICATION" | "IMAGE";
  deviceId: string;
}

export interface TransactionResponse {
  status: "CREATED" | "DUPLICATE";
  summaryUpdated: boolean;
  spend: SpendResponse;
}

export interface QueuedSubmission {
  submissionId: string;
  date: string;
  sheetName?: string;
  changes: Partial<Record<ExpenseKey, number | null>>;
  comments?: Partial<Record<ExpenseKey, string>>;
  createdAt: number;
}

export interface SheetInfo { name: string; }
export interface HistoryEntry { date: string; values: ExpenseValues; comments: Partial<Record<ExpenseKey, string>>; total: number; }
export interface CashflowResponse { sheetName: string; rows: (string | number | boolean | null)[][]; }
export interface ReceiptResponse { fileId: string; name: string; folderName: string; }
export interface ReceiptImage { id: string; date: string; kind: "food" | "spend"; mimeType: string; imageBase64: string; }
export interface FoodLogRequest { date: string; meal: "BREAKFAST" | "LUNCH" | "SNACK" | "DINNER"; food: string; amount: number; notes?: string; receiptFileId?: string; }
