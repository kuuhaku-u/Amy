import { useEffect, useMemo, useState } from "react";
import { loadAnalytics, loadCashflow, loadHistory } from "./api";
import { expenseFields, type AnalyticsResponse, type CashflowResponse, type ExpenseKey, type HistoryEntry } from "./types";

type Recurring = { id: string; name: string; amount: number; day: number; category: ExpenseKey; paid: boolean };
type Goal = { id: string; name: string; target: number; saved: number };
type Settings = { income: number; budgets: Partial<Record<ExpenseKey, number>>; recurring: Recurring[]; goals: Goal[] };
const initial: Settings = { income: 0, budgets: {}, recurring: [], goals: [] };
const cash = (n: number) => new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(n || 0);
const numeric = (value: unknown) => typeof value === "number" ? value : Number(String(value ?? "").replace(/[₹,$\s]/g, "")) || 0;

function understandCashflow(data: CashflowResponse | null) {
  if (!data?.rows.length) return null;
  const headerIndex = data.rows.slice(0, 10).reduce((best, row, index, rows) => {
    const score = row.filter((cell) => typeof cell === "string" && cell.trim() && !/^[-+₹\d,.]+$/.test(cell.trim())).length;
    const bestScore = rows[best]?.filter((cell) => typeof cell === "string" && cell.trim() && !/^[-+₹\d,.]+$/.test(cell.trim())).length || 0;
    return score > bestScore ? index : best;
  }, 0);
  const headers = data.rows[headerIndex].map((cell, index) => String(cell || `Column ${index + 1}`).trim());
  const find = (pattern: RegExp) => headers.findIndex((header) => pattern.test(header.toLowerCase()));
  const incomeCol = find(/income|inflow|credit|received|deposit/);
  const expenseCol = find(/expense|outflow|debit|spent|withdraw/);
  const amountCol = find(/^amount$|value/);
  const typeCol = find(/type|flow|transaction/);
  const balanceCol = find(/balance|closing|remaining/);
  const dateCol = find(/date|time|month/);
  const labelCol = find(/description|details|particular|category|name|source|note/);
  const rows = data.rows.slice(headerIndex + 1).filter((row) => row.some((cell) => String(cell ?? "").trim()));
  let inflow = 0, outflow = 0;
  rows.forEach((row) => {
    if (incomeCol >= 0) inflow += numeric(row[incomeCol]);
    if (expenseCol >= 0) outflow += numeric(row[expenseCol]);
    if (incomeCol < 0 && expenseCol < 0 && amountCol >= 0) {
      const amount = numeric(row[amountCol]); const type = String(row[typeCol] ?? "").toLowerCase();
      if (/income|credit|inflow|received/.test(type)) inflow += amount; else if (/expense|debit|outflow|spent/.test(type)) outflow += amount;
    }
  });
  const lastBalance = balanceCol >= 0 ? [...rows].reverse().map((row) => numeric(row[balanceCol])).find((value) => value !== 0) : undefined;
  return { headers, rows, inflow, outflow, balance: lastBalance ?? inflow - outflow, dateCol, labelCol, incomeCol, expenseCol, amountCol, typeCol };
}

export default function MoneyManager({ date, sheet, token, online }: { date: string; sheet: string; token: string; online: boolean }) {
  const key = `moneyManager:${sheet}:${date.slice(0, 7)}`;
  const [settings, setSettings] = useState<Settings>(initial);
  const [analytics, setAnalytics] = useState<AnalyticsResponse | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [cashflow, setCashflow] = useState<CashflowResponse | null>(null);
  const [cashflowError, setCashflowError] = useState("");
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<ExpenseKey | "">("");
  const [section, setSection] = useState<"dashboard" | "history" | "planning">("dashboard");

  useEffect(() => { try { setSettings(JSON.parse(localStorage.getItem(key) || "null") || initial); } catch { setSettings(initial); } }, [key]);
  const saveSettings = (next: Settings) => { setSettings(next); localStorage.setItem(key, JSON.stringify(next)); };
  useEffect(() => {
    if (!online || !sheet) return;
    const from = `${date.slice(0, 7)}-01`;
    const last = new Date(Number(date.slice(0, 4)), Number(date.slice(5, 7)), 0).getDate();
    Promise.all([loadAnalytics(date, sheet, token), loadHistory(from, `${date.slice(0, 7)}-${last}`, sheet, token)])
      .then(([a, h]) => { setAnalytics(a); setHistory(h); });
    loadCashflow(token).then((result) => { setCashflow(result); setCashflowError(""); })
      .catch((error) => { setCashflow(null); setCashflowError(error instanceof Error ? error.message : "Could not load Cashflow."); });
  }, [date, online, sheet, token]);

  const spent = analytics?.monthlyTotal || 0;
  const recurringDue = settings.recurring.filter((r) => !r.paid).reduce((s, r) => s + r.amount, 0);
  const remaining = settings.income - spent - recurringDue;
  const alerts = useMemo(() => {
    const result: string[] = [];
    for (const item of analytics?.categories || []) {
      const limit = settings.budgets[item.key];
      if (limit && item.total / limit >= .8) result.push(`${item.label} has used ${Math.round(item.total / limit * 100)}% of its budget.`);
    }
    if (remaining < 0) result.push(`Projected balance is ${cash(Math.abs(remaining))} below zero.`);
    const upcoming = settings.recurring.filter((r) => !r.paid && r.day <= new Date().getDate() + 7);
    if (upcoming.length) result.push(`${upcoming.length} recurring ${upcoming.length === 1 ? "payment is" : "payments are"} due soon.`);
    return result;
  }, [analytics, remaining, settings]);
  const filtered = history.filter((row) => expenseFields.some((f) => (!category || f.key === category) &&
    ((row.values[f.key] || 0) > 0 || row.comments[f.key]) && `${row.date} ${f.label} ${row.comments[f.key] || ""}`.toLowerCase().includes(query.toLowerCase())));
  const duplicates = history.filter((row, i) => row.total > 0 && history.some((other, j) => j > i && other.total === row.total && JSON.stringify(other.values) === JSON.stringify(row.values)));
  const recorded = new Set(history.map((h) => h.date));
  const now = new Date();
  const viewedMonth = date.slice(0, 7);
  const currentMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
  const monthDays = new Date(Number(date.slice(0, 4)), Number(date.slice(5, 7)), 0).getDate();
  const checkDays = viewedMonth < currentMonth ? monthDays : viewedMonth === currentMonth ? now.getDate() : 0;
  const missing = Array.from({ length: checkDays }, (_, i) => `${viewedMonth}-${String(i + 1).padStart(2, "0")}`).filter((d) => !recorded.has(d));
  const cashflowView = useMemo(() => understandCashflow(cashflow), [cashflow]);

  const download = (name: string, content: string, type: string) => {
    const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([content], { type })); a.download = name; a.click(); URL.revokeObjectURL(a.href);
  };
  const exportCsv = () => download(`${sheet}-expenses.csv`, ["Date,Category,Amount,Comment", ...filtered.flatMap((r) => expenseFields
    .filter((f) => (r.values[f.key] || 0) > 0 || r.comments[f.key]).map((f) => [r.date, f.label, r.values[f.key] || 0, JSON.stringify(r.comments[f.key] || "")].join(",")))].join("\n"), "text/csv");

  return <section className="manager-view">
    <nav className="manager-tabs">{(["dashboard", "history", "planning"] as const).map((item) => <button className={section === item ? "active" : ""} onClick={() => setSection(item)} key={item}>{item}</button>)}</nav>
    {section === "dashboard" && <>
      <div className="cashflow-grid"><article><span>Income</span><strong>{cash(settings.income)}</strong></article><article><span>Spent</span><strong>{cash(spent)}</strong></article><article className={remaining < 0 ? "danger" : "good"}><span>Projected balance</span><strong>{cash(remaining)}</strong></article><article><span>Savings rate</span><strong>{settings.income ? `${Math.round(remaining / settings.income * 100)}%` : "—"}</strong></article></div>
      <article className="manager-card"><h3>Budget health</h3>{(analytics?.categories || []).map((item) => { const limit = settings.budgets[item.key] || 0; const percent = limit ? item.total / limit * 100 : 0; return <div className="budget-row" key={item.key}><div><span>{item.label}</span><strong>{cash(item.total)} / {limit ? cash(limit) : "No limit"}</strong></div><i><b className={percent >= 100 ? "over" : percent >= 80 ? "warn" : ""} style={{ width: `${Math.min(100, percent)}%` }} /></i></div>; })}</article>
      <article className="manager-card"><h3>Money alerts</h3>{alerts.length ? <ul>{alerts.map((a) => <li key={a}>{a}</li>)}</ul> : <p className="muted">Everything looks on track.</p>}</article>
      <article className="manager-card"><h3>Weekly snapshot</h3><div className="snapshot"><p><span>This week</span><strong>{cash(analytics?.week.total || 0)}</strong></p><p><span>Previous</span><strong>{cash(analytics?.week.previousTotal || 0)}</strong></p><p><span>Top category</span><strong>{analytics?.highestCategory || "—"}</strong></p><p><span>Daily average</span><strong>{cash(analytics?.averageRecordedDay || 0)}</strong></p></div></article>
      <article className="manager-card cashflow-card"><div className="manager-heading"><h3>Cashflow sheet</h3>{cashflow && <span>{cashflow.sheetName}</span>}</div>
        {cashflowError ? <p className="muted">{cashflowError}</p> : !cashflow ? <p className="muted">Loading cashflow…</p> : !cashflowView ? <p className="muted">The Cashflow sheet is empty.</p> : <>
          <div className="cashflow-summary"><div className="in"><span>Money in</span><strong>{cash(cashflowView.inflow)}</strong></div><div className="out"><span>Money out</span><strong>{cash(cashflowView.outflow)}</strong></div><div className={cashflowView.balance < 0 ? "negative" : "balance"}><span>Net cashflow</span><strong>{cash(cashflowView.balance)}</strong></div></div>
          <div className="cashflow-meter" aria-label="Cash inflow versus outflow"><i style={{ width: `${cashflowView.inflow + cashflowView.outflow ? cashflowView.inflow / (cashflowView.inflow + cashflowView.outflow) * 100 : 50}%` }} /></div>
          <div className="cashflow-recent"><h4>Recent cash activity</h4>{cashflowView.rows.slice(-8).reverse().map((row, index) => {
            const incoming = cashflowView.incomeCol >= 0 ? numeric(row[cashflowView.incomeCol]) : cashflowView.typeCol >= 0 && /income|credit|inflow|received/.test(String(row[cashflowView.typeCol]).toLowerCase()) ? numeric(row[cashflowView.amountCol]) : 0;
            const outgoing = cashflowView.expenseCol >= 0 ? numeric(row[cashflowView.expenseCol]) : !incoming && cashflowView.amountCol >= 0 ? numeric(row[cashflowView.amountCol]) : 0;
            const label = cashflowView.labelCol >= 0 ? row[cashflowView.labelCol] : row.find((cell) => typeof cell === "string");
            return <div className="cashflow-item" key={index}><div><strong>{String(label || "Cash entry")}</strong><small>{cashflowView.dateCol >= 0 ? String(row[cashflowView.dateCol] ?? "") : ""}</small></div><b className={incoming ? "positive" : "negative"}>{incoming ? "+" : "−"}{cash(incoming || outgoing)}</b></div>;
          })}</div>
          <details className="cashflow-details"><summary>View source rows</summary><div className="cashflow-table-wrap"><table><thead><tr>{cashflowView.headers.map((header) => <th key={header}>{header}</th>)}</tr></thead><tbody>{cashflowView.rows.map((row, rowIndex) => <tr key={rowIndex}>{cashflowView.headers.map((_, cellIndex) => <td key={cellIndex}>{typeof row[cellIndex] === "number" ? Number(row[cellIndex]).toLocaleString("en-IN") : String(row[cellIndex] ?? "")}</td>)}</tr>)}</tbody></table></div></details>
        </>}
      </article>
    </>}
    {section === "history" && <>
      <div className="history-tools"><input placeholder="Search date, category or comment" value={query} onChange={(e) => setQuery(e.target.value)} /><select value={category} onChange={(e) => setCategory(e.target.value as ExpenseKey | "")}><option value="">All categories</option>{expenseFields.map((f) => <option value={f.key} key={f.key}>{f.label}</option>)}</select><button onClick={exportCsv}>Export CSV</button></div>
      <div className="quality-strip"><span>{missing.length} missing days</span><span>{duplicates.length} possible duplicates</span><button onClick={() => download(`${sheet}-backup.json`, JSON.stringify({ settings, history }, null, 2), "application/json")}>Backup JSON</button></div>
      <div className="history-list">{filtered.map((row) => <article key={row.date}><div><strong>{new Date(`${row.date}T00:00:00`).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}</strong><b>{cash(row.total)}</b></div>{expenseFields.filter((f) => (row.values[f.key] || 0) > 0 || row.comments[f.key]).map((f) => <p key={f.key}><span>{f.label}: {cash(row.values[f.key] || 0)}</span><small>{row.comments[f.key]}</small></p>)}</article>)}</div>
    </>}
    {section === "planning" && <>
      <article className="manager-card"><h3>Income and category budgets</h3><label className="plan-field"><span>Monthly income</span><input type="number" value={settings.income || ""} onChange={(e) => saveSettings({ ...settings, income: Number(e.target.value) })} /></label><div className="budget-inputs">{expenseFields.map((f) => <label key={f.key}><span>{f.label}</span><input type="number" placeholder="No limit" value={settings.budgets[f.key] || ""} onChange={(e) => saveSettings({ ...settings, budgets: { ...settings.budgets, [f.key]: Number(e.target.value) } })} /></label>)}</div></article>
      <ListEditor title="Recurring expenses" items={settings.recurring} onAdd={() => saveSettings({ ...settings, recurring: [...settings.recurring, { id: crypto.randomUUID(), name: "New payment", amount: 0, day: 1, category: "others", paid: false }] })} onChange={(items) => saveSettings({ ...settings, recurring: items as Recurring[] })} />
      <ListEditor title="Savings goals" items={settings.goals} onAdd={() => saveSettings({ ...settings, goals: [...settings.goals, { id: crypto.randomUUID(), name: "New goal", target: 0, saved: 0 }] })} onChange={(items) => saveSettings({ ...settings, goals: items as Goal[] })} />
    </>}
  </section>;
}

function ListEditor({ title, items, onAdd, onChange }: { title: string; items: (Recurring | Goal)[]; onAdd: () => void; onChange: (items: (Recurring | Goal)[]) => void }) {
  const update = (id: string, patch: Record<string, unknown>) => onChange(items.map((i) => i.id === id ? { ...i, ...patch } as Recurring | Goal : i));
  return <article className="manager-card"><div className="manager-heading"><h3>{title}</h3><button onClick={onAdd}>＋ Add</button></div>{items.map((item) => <div className="editable-row" key={item.id}><input value={item.name} onChange={(e) => update(item.id, { name: e.target.value })} /><input type="number" placeholder="Amount" value={("amount" in item ? item.amount : item.target) || ""} onChange={(e) => update(item.id, "amount" in item ? { amount: Number(e.target.value) } : { target: Number(e.target.value) })} />{"saved" in item && <input type="number" placeholder="Saved" value={item.saved || ""} onChange={(e) => update(item.id, { saved: Number(e.target.value) })} />}{"paid" in item && <button className={item.paid ? "paid" : ""} onClick={() => update(item.id, { paid: !item.paid })}>{item.paid ? "Paid" : "Due"}</button>}<button onClick={() => onChange(items.filter((i) => i.id !== item.id))}>×</button></div>)}</article>;
}
