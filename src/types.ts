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

export interface QueuedSubmission {
  submissionId: string;
  date: string;
  changes: Partial<Record<ExpenseKey, number | null>>;
  createdAt: number;
}
