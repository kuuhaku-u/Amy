import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiError, createSheet, loadAnalytics, loadDate, loadSheets, recordTransaction, syncQueued } from "./api";
import { enqueue, queuedItems, setConfig } from "./db";
import { expenseFields, type AnalyticsResponse, type ExpenseKey, type SpendResponse } from "./types";
import { Capture, isNativeAndroid, type CaptureDraft } from "./native";
import MoneyManager from "./MoneyManager";
import UserToolsDrawer from "./UserToolsDrawer";

type FormValues = Record<ExpenseKey, string>;
type Comments = Record<ExpenseKey, string>;
type Status = "idle" | "loading" | "saving" | "queued" | "saved" | "error";
type Theme = "light" | "dark" | "sanrio";

const themes: { key: Theme; label: string; icon: string }[] = [
  { key: "light", label: "Light", icon: "☀" },
  { key: "dark", label: "Dark", icon: "☾" },
  { key: "sanrio", label: "Sanrio", icon: "♡" }
];

const sanrioCharacters = [
  { name: "Hello Kitty", image: "https://corporate.sanrio.co.jp/en/business-info/images/img_hello-kitty.png" },
  { name: "My Melody", image: "https://corporate.sanrio.co.jp/en/business-info/images/img_my-melody.png" },
  { name: "Cinnamoroll", image: "https://corporate.sanrio.co.jp/en/business-info/images/img_cinnamoroll.png" },
  { name: "Kuromi", image: "https://corporate.sanrio.co.jp/en/business-info/images/img_kuromi.png" },
  { name: "Pochacco", image: "https://corporate.sanrio.co.jp/en/business-info/images/img_pochacco.png" },
  { name: "Pompompurin", image: "https://corporate.sanrio.co.jp/en/business-info/images/img_pompompurin.png" },
  { name: "Little Twin Stars", image: "https://corporate.sanrio.co.jp/en/business-info/images/img_little-twin-stars.png" },
  { name: "Hanamaruobake", image: "https://corporate.sanrio.co.jp/en/business-info/images/img_hanamaruobake.png" }
] as const;

const emptyValues = (): FormValues => Object.fromEntries(expenseFields.map(({ key }) => [key, ""])) as FormValues;
const emptyComments = (): Comments => Object.fromEntries(expenseFields.map(({ key }) => [key, ""])) as Comments;
const today = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
};

function monthSheetName(date: string): string {
  return new Date(`${date.slice(0, 7)}-01T00:00:00`).toLocaleDateString("en-IN", { month: "long", year: "numeric" });
}

function matchingMonthSheet(names: string[], date: string): string | undefined {
  const monthDate = new Date(`${date.slice(0, 7)}-01T00:00:00`);
  const candidates = [
    monthSheetName(date),
    monthDate.toLocaleDateString("en-IN", { month: "short", year: "numeric" }),
    date.slice(0, 7),
    monthDate.toLocaleDateString("en-IN", { month: "long" }),
    monthDate.toLocaleDateString("en-IN", { month: "short" })
  ].map((name) => name.toLocaleLowerCase());
  return names.find((name) => candidates.includes(name.trim().toLocaleLowerCase()));
}

function lastDateForMonthSheet(name: string, currentDate: string): string | undefined {
  const normalized = name.trim().toLocaleLowerCase();
  const isoMatch = normalized.match(/^(\d{4})-(0[1-9]|1[0-2])$/);
  let year = Number(currentDate.slice(0, 4));
  let month = isoMatch ? Number(isoMatch[2]) : 0;
  if (isoMatch) year = Number(isoMatch[1]);
  if (!isoMatch) {
    const monthNames = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"];
    const monthIndex = monthNames.findIndex((item) => normalized === item || normalized === item.slice(0, 3)
      || normalized.startsWith(`${item} `) || normalized.startsWith(`${item.slice(0, 3)} `));
    if (monthIndex < 0) return undefined;
    month = monthIndex + 1;
    const yearMatch = normalized.match(/\b(\d{4})\b/);
    if (yearMatch) year = Number(yearMatch[1]);
  }
  const lastDay = new Date(year, month, 0).getDate();
  return `${year}-${String(month).padStart(2, "0")}-${String(lastDay).padStart(2, "0")}`;
}

function money(value: number): string {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(value || 0);
}

function tokenFromLocation(): string {
  const match = window.location.hash.match(/^#access=(.+)$/);
  if (match) {
    const token = decodeURIComponent(match[1]);
    if (!isNativeAndroid()) localStorage.setItem("monthlySpendAccess", token);
    history.replaceState(null, "", window.location.pathname + window.location.search);
    return token;
  }
  return isNativeAndroid() ? "" : localStorage.getItem("monthlySpendAccess") || "";
}

export default function App() {
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem("monthlySpendTheme");
    return saved === "dark" || saved === "sanrio" ? saved : "light";
  });
  const [sanrioCharacter, setSanrioCharacter] = useState(() => {
    const saved = localStorage.getItem("monthlySpendSanrioCharacter");
    return sanrioCharacters.find((character) => character.name === saved) ?? sanrioCharacters[0];
  });
  const [token, setToken] = useState(tokenFromLocation);
  const [tokenInput, setTokenInput] = useState("");
  const [date, setDate] = useState(today);
  const [values, setValues] = useState<FormValues>(emptyValues);
  const [loadedValues, setLoadedValues] = useState<FormValues>(emptyValues);
  const [touched, setTouched] = useState<Set<ExpenseKey>>(new Set());
  const [comments, setComments] = useState<Comments>(emptyComments);
  const [loadedComments, setLoadedComments] = useState<Comments>(emptyComments);
  const [touchedComments, setTouchedComments] = useState<Set<ExpenseKey>>(new Set());
  const [summary, setSummary] = useState({ total: 0, weekTotal: 0, monthlySpend: 0 });
  const [status, setStatus] = useState<Status>("idle");
  const [message, setMessage] = useState("");
  const [pendingCount, setPendingCount] = useState(0);
  const [online, setOnline] = useState(navigator.onLine);
  const [view, setView] = useState<"expenses" | "insights" | "money" | "inbox">("expenses");
  const [sheets, setSheets] = useState<string[]>([]);
  const [sheet, setSheet] = useState(() => localStorage.getItem("monthlySpendSheet") || "");
  const [creatingSheet, setCreatingSheet] = useState(false);
  const [undoSnapshot, setUndoSnapshot] = useState<{ date: string; sheet: string; values: FormValues; comments: Comments } | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const nativeAndroid = isNativeAndroid();

  const refreshPending = useCallback(async () => setPendingCount((await queuedItems()).length), []);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("monthlySpendTheme", theme);
  }, [theme]);

  useEffect(() => {
    if (!nativeAndroid) return;
    if (token) Capture.saveAccessToken({ token }).catch(() => undefined);
    else Capture.getAccessToken().then(({ token: saved }) => { if (saved) setToken(saved); }).catch(() => undefined);
  }, [nativeAndroid, token]);

  useEffect(() => {
    if (!nativeAndroid) return;
    Capture.listDrafts().then(({ drafts }) => { if (drafts.length) setView("inbox"); }).catch(() => undefined);
    let handle: { remove(): Promise<void> } | undefined;
    Capture.addListener("draftAvailable", () => setView("inbox")).then((listener) => { handle = listener; });
    return () => { handle?.remove(); };
  }, [nativeAndroid]);

  useEffect(() => {
    localStorage.setItem("monthlySpendSanrioCharacter", sanrioCharacter.name);
  }, [sanrioCharacter]);

  const sync = useCallback(async () => {
    if (!token || !navigator.onLine) return;
    setStatus("saving");
    try {
      const result = await syncQueued(token);
      await refreshPending();
      if (result.synced > 0) {
        setStatus("saved");
        setMessage(`${result.synced} saved ${result.synced === 1 ? "entry" : "entries"} synced.`);
      } else setStatus("idle");
    } catch (error) {
      setStatus("error");
      setMessage(error instanceof Error ? error.message : "Sync failed.");
    }
  }, [refreshPending, token]);

  useEffect(() => {
    if (token) setConfig("accessToken", token);
    refreshPending();
  }, [refreshPending, token]);

  useEffect(() => {
    if (!token || !online) return;
    loadSheets(token).then((items) => {
      const names = items.map((item) => item.name);
      setSheets(names);
      setSheet((current) => matchingMonthSheet(names, date) || (names.includes(current) ? current : (names[0] || "")));
    }).catch((error) => { setStatus("error"); setMessage(error instanceof Error ? error.message : "Could not load tables."); });
  }, [online, token]);

  useEffect(() => { if (sheet) localStorage.setItem("monthlySpendSheet", sheet); }, [sheet]);

  useEffect(() => {
    const handleOnline = () => { setOnline(true); sync(); };
    const handleOffline = () => setOnline(false);
    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    navigator.serviceWorker?.addEventListener("message", refreshPending);
    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
      navigator.serviceWorker?.removeEventListener("message", refreshPending);
    };
  }, [refreshPending, sync]);

  useEffect(() => {
    if (!token || !online || !sheet) {
      if (!online) {
        setStatus("queued");
        setMessage("Offline. You can still enter amounts and save them for later.");
      }
      return;
    }
    let active = true;
    setStatus("loading");
    setMessage("Loading this date…");
    loadDate(date, sheet, token)
      .then((response) => {
        if (!active) return;
        applyResponse(response);
        setStatus("idle");
        setMessage(response.exists ? "Existing entry loaded." : "No entry yet for this date.");
      })
      .catch((error) => {
        if (!active) return;
        setStatus("error");
        setMessage(error instanceof Error ? error.message : "Could not load this date.");
      });
    return () => { active = false; };
  }, [date, online, sheet, token]);

  const applyResponse = (response: SpendResponse) => {
    const nextValues = Object.fromEntries(expenseFields.map(({ key }) => [key, response.values[key] ?? ""])) as FormValues;
    setValues(nextValues);
    setLoadedValues(nextValues);
    const nextComments = Object.fromEntries(expenseFields.map(({ key }) => [key, response.comments?.[key] ?? ""])) as Comments;
    setComments(nextComments); setLoadedComments(nextComments);
    setSummary({ total: response.total, weekTotal: response.weekTotal, monthlySpend: response.monthlySpend });
    setTouched(new Set());
    setTouchedComments(new Set());
  };

  const previewTotal = useMemo(
    () => ["travel", "breakfast", "lunch", "eveSnack", "dinner"]
      .reduce((sum, key) => sum + previewAmount(values[key as ExpenseKey], loadedValues[key as ExpenseKey]), 0),
    [loadedValues, values]
  );

  const updateValue = (key: ExpenseKey, value: string) => {
    if (value !== "" && !/^\d*(\.\d{0,2})?$/.test(value) && !/^[+-]\d*(\.\d{0,2})?(,\d*(\.\d{0,2})?)*$/.test(value)) return;
    setValues((current) => ({ ...current, [key]: value }));
    setTouched((current) => new Set(current).add(key));
    setStatus("idle");
    setMessage("");
  };

  const applyMath = (key: ExpenseKey) => {
    const adjustment = parseAdjustment(values[key]);
    if (adjustment == null) return;
    const result = (Number(loadedValues[key]) || 0) + adjustment;
    if (result < 0) {
      setValues((current) => ({ ...current, [key]: loadedValues[key] }));
      setStatus("error");
      setMessage(`${expenseFields.find((field) => field.key === key)?.label} cannot be less than zero.`);
      return;
    }
    setValues((current) => ({ ...current, [key]: String(Math.round(result * 100) / 100) }));
    setStatus("idle");
    setMessage("");
  };

  const updateComment = (key: ExpenseKey, comment: string) => {
    setComments((current) => ({ ...current, [key]: comment }));
    setTouchedComments((current) => new Set(current).add(key));
    setStatus("idle"); setMessage("");
  };
  const addVoiceExpense = (key: ExpenseKey, amount: number) => {
    const next = Math.round((previewAmount(values[key], loadedValues[key]) + amount) * 100) / 100;
    setValues((current) => ({ ...current, [key]: String(next) }));
    setTouched((current) => new Set(current).add(key));
    setStatus("idle"); setMessage(`${money(amount)} added to ${expenseFields.find((field) => field.key === key)?.label}. Review and save it.`);
  };

  const changeDate = (nextDate: string) => {
    setDate(nextDate);
    const matchingSheet = matchingMonthSheet(sheets, nextDate);
    if (matchingSheet) setSheet(matchingSheet);
    setValues(emptyValues());
    setLoadedValues(emptyValues());
    setTouched(new Set());
    setComments(emptyComments());
    setLoadedComments(emptyComments());
    setTouchedComments(new Set());
    setSummary({ total: 0, weekTotal: 0, monthlySpend: 0 });
  };

  const changeSheet = (nextSheet: string) => {
    const lastDate = lastDateForMonthSheet(nextSheet, date);
    if (lastDate) changeDate(lastDate);
    setSheet(nextSheet);
  };

  const save = async () => {
    if (touched.size === 0 && touchedComments.size === 0) {
      setStatus("error");
      setMessage("Change at least one amount or comment before saving.");
      return;
    }
    // Include a comment's associated amount so comment-only saves also work with
    // servers that still require at least one amount field in every update.
    const beforeSave = { date, sheet, values: { ...loadedValues }, comments: { ...loadedComments } };
    const changedAmountKeys = new Set([...touched, ...touchedComments]);
    const changes = Object.fromEntries(
      [...changedAmountKeys].map((key) => [key, values[key] === "" ? null : Number(values[key])])
    );
    const commentChanges = Object.fromEntries([...touchedComments].map((key) => [key, comments[key]]));
    await enqueue({ submissionId: crypto.randomUUID(), date, sheetName: sheet, changes, comments: commentChanges, createdAt: Date.now() });
    await refreshPending();
    setTouched(new Set());
    setTouchedComments(new Set());
    setStatus("queued");
    setMessage(online ? "Saving to Google Sheets…" : "Saved on this phone. It will sync when online.");

    if ("serviceWorker" in navigator) {
      const registration = await navigator.serviceWorker.ready;
      const syncRegistration = registration as ServiceWorkerRegistration & { sync?: { register(tag: string): Promise<void> } };
      await syncRegistration.sync?.register("sync-monthly-spend").catch(() => undefined);
    }
    if (online) {
      try {
        await syncQueued(token);
        await refreshPending();
        const response = await loadDate(date, sheet, token);
        applyResponse(response);
        setUndoSnapshot(beforeSave);
        setStatus("saved");
        setMessage("Saved to Monthly Spend.");
      } catch (error) {
        setStatus(error instanceof ApiError && error.status < 500 ? "error" : "queued");
        setMessage(error instanceof Error ? error.message : "Still queued for sync.");
      }
    }
  };

  const undoLastSave = async () => {
    if (!undoSnapshot || !online) return;
    setStatus("saving"); setMessage("Undoing last save…");
    const changes = Object.fromEntries(expenseFields.map(({ key }) => [key, undoSnapshot.values[key] === "" ? null : Number(undoSnapshot.values[key])]));
    await enqueue({ submissionId: crypto.randomUUID(), date: undoSnapshot.date, sheetName: undoSnapshot.sheet,
      changes, comments: undoSnapshot.comments, createdAt: Date.now() });
    try {
      await syncQueued(token);
      const response = await loadDate(undoSnapshot.date, undoSnapshot.sheet, token);
      if (date === undoSnapshot.date && sheet === undoSnapshot.sheet) applyResponse(response);
      setUndoSnapshot(null); setStatus("saved"); setMessage("Last save was undone.");
    } catch (error) { setStatus("error"); setMessage(error instanceof Error ? error.message : "Could not undo the save."); }
    await refreshPending();
  };

  const addMonthlySheet = async () => {
    if (!sheet || !online) return;
    const target = monthSheetName(date);
    setCreatingSheet(true); setMessage("");
    try {
      const created = await createSheet(target, sheet, token);
      setSheets((current) => [...current, created.name]);
      setSheet(created.name);
      setStatus("saved"); setMessage(`${created.name} created with the same columns as ${sheet}.`);
    } catch (error) { setStatus("error"); setMessage(error instanceof Error ? error.message : "Could not create table."); }
    finally { setCreatingSheet(false); }
  };

  const unlock = async () => {
    const next = tokenInput.trim();
    if (!next) return;
    if (nativeAndroid) await Capture.saveAccessToken({ token: next });
    else localStorage.setItem("monthlySpendAccess", next);
    await setConfig("accessToken", next);
    setToken(next);
    setTokenInput("");
  };

  if (!token) {
    return (
      <><CharacterBackground theme={theme} character={sanrioCharacter} /><main className="lock-screen">
        <ThemePicker theme={theme} onChange={setTheme} />
        <div className="brand-mark">₹</div>
        <h1>Monthly Spend</h1>
        <p>Open your private access link, or enter the access key for this device.</p>
        <label className="access-field">
          <span>Access key</span>
          <input type="password" value={tokenInput} onChange={(event) => setTokenInput(event.target.value)} />
        </label>
        <button className="primary-button" onClick={unlock}>Unlock app</button>
      </main></>
    );
  }

  return (
    <><CharacterBackground theme={theme} character={sanrioCharacter} /><UserToolsDrawer open={drawerOpen} onClose={() => setDrawerOpen((current) => !current)} date={date} sheet={sheet} token={token} onVoiceExpense={addVoiceExpense} /><main className="app-shell">
      <header className="topbar">
        <div>
          <span className="eyebrow">Personal ledger</span>
          <h1>Monthly Spend</h1>
        </div>
        <div className={`network ${online ? "online" : "offline"}`}>
          <span />{online ? "Online" : "Offline"}
        </div>
      </header>

      <ThemePicker theme={theme} onChange={setTheme} />

      <nav className={`view-tabs ${nativeAndroid ? "four" : "three"}`} aria-label="App sections">
        <button className={view === "expenses" ? "active" : ""} onClick={() => setView("expenses")}>＋ Expenses</button>
        <button className={view === "insights" ? "active" : ""} onClick={() => setView("insights")}>⌁ Insights</button>
        <button className={view === "money" ? "active" : ""} onClick={() => setView("money")}>₹ Money</button>
        {nativeAndroid && <button className={view === "inbox" ? "active" : ""} onClick={() => setView("inbox")}>▣ Inbox</button>}
      </nav>

      {theme === "sanrio" && view === "expenses" && (
        <SanrioFriends selected={sanrioCharacter.name} onSelect={setSanrioCharacter} />
      )}

      <section className="table-filter">
        <label><span>Display table</span><select value={sheet} onChange={(event) => changeSheet(event.target.value)} disabled={!sheets.length}>
          {sheets.map((name) => <option value={name} key={name}>{name}</option>)}
        </select></label>
        <button onClick={addMonthlySheet} disabled={!sheet || !online || creatingSheet}>{creatingSheet ? "Creating…" : "＋ New month table"}</button>
      </section>

      {view === "insights" ? <AnalyticsView date={date} sheet={sheet} sheets={sheets} token={token} online={online} onDateChange={changeDate} />
        : view === "money" ? <MoneyManager date={date} sheet={sheet} token={token} online={online} />
        : view === "inbox" ? <CaptureInbox sheet={sheet} token={token} online={online} /> : <>

      <section className="date-card">
        <label htmlFor="spend-date">Entry date</label>
        <input id="spend-date" type="date" value={date} onChange={(event) => changeDate(event.target.value)} />
      </section>

      <section className="summary-grid" aria-label="Spending summary">
        <article className="summary-card accent">
          <span>Monthly spend</span>
          <strong>{money(summary.monthlySpend)}</strong>
        </article>
        <article className="summary-card">
          <span>Week total</span>
          <strong>{money(summary.weekTotal)}</strong>
        </article>
        <article className="summary-card">
          <span>Daily total</span>
          <strong>{money(touched.size ? previewTotal : summary.total)}</strong>
        </article>
      </section>

      <section className="form-section">
        <div className="section-heading">
          <div><span className="eyebrow">Amounts in INR</span><h2>Expenses</h2><small className="math-hint">Use commas for multiple adjustments: +2,3 adds 5</small></div>
          {pendingCount > 0 && <button className="sync-button" onClick={sync}>{pendingCount} pending · Sync</button>}
        </div>
        <div className="expense-grid">
          {expenseFields.map((field) => (
            <label className={`expense-field ${touched.has(field.key) ? "changed" : ""}`} key={field.key}>
              <span className="field-icon">{field.icon}</span>
              <span className="field-copy"><span>{field.label}</span><small>₹ INR</small></span>
              <input
                aria-label={`${field.label} amount`}
                type="text"
                inputMode="decimal"
                placeholder="0"
                value={values[field.key]}
                onChange={(event) => updateValue(field.key, event.target.value)}
                onBlur={() => applyMath(field.key)}
              />
              <input className={`comment-input ${touchedComments.has(field.key) ? "changed" : ""}`}
                aria-label={`${field.label} comment`} type="text" maxLength={500} placeholder="Add a comment…"
                value={comments[field.key]} onChange={(event) => updateComment(field.key, event.target.value)} />
            </label>
          ))}
        </div>
      </section>

      <div className={`status-message ${status}`} role="status">
        <span>{status === "saving" || status === "loading" ? "↻" : status === "saved" ? "✓" : status === "error" ? "!" : status === "queued" ? "↑" : "•"}</span>
        <p>{message || "Only changed fields will be updated."}</p>
        {undoSnapshot && online && status !== "saving" && <button className="undo-button" onClick={undoLastSave}>Undo</button>}
      </div>

      <footer className="save-bar">
        <button className="primary-button" disabled={status === "loading" || status === "saving"} onClick={save}>
          {online ? "Save entry" : "Save offline"}
        </button>
      </footer>
      </>}
    </main></>
  );
}

function parseAdjustment(value: string): number | null {
  if (!/^[+-]\d+(\.\d{1,2})?(,\d+(\.\d{1,2})?)*$/.test(value)) return null;
  const direction = value[0] === "+" ? 1 : -1;
  return direction * value.slice(1).split(",").reduce((sum, item) => sum + Number(item), 0);
}

function previewAmount(value: string, loaded: string): number {
  const adjustment = parseAdjustment(value);
  if (adjustment != null) return Math.max(0, (Number(loaded) || 0) + adjustment);
  return Number(value) || 0;
}

function AnalyticsView({ date, sheet, sheets, token, online, onDateChange }: {
  date: string; sheet: string; sheets: string[]; token: string; online: boolean; onDateChange: (date: string) => void;
}) {
  const [analytics, setAnalytics] = useState<AnalyticsResponse | null>(null);
  const [previousSheetTotal, setPreviousSheetTotal] = useState<number | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!online) { setError("Analytics needs an internet connection to read the sheet."); setLoading(false); return; }
    let active = true;
    setLoading(true);
    setError("");
    if (!sheet) return;
    const previousDate = new Date(`${date.slice(0, 7)}-01T00:00:00`);
    previousDate.setMonth(previousDate.getMonth() - 1);
    const previousDateValue = `${previousDate.getFullYear()}-${String(previousDate.getMonth() + 1).padStart(2, "0")}-01`;
    const previousSheet = matchingMonthSheet(sheets, previousDateValue);
    Promise.all([
      loadAnalytics(date, sheet, token),
      previousSheet ? loadAnalytics(previousDateValue, previousSheet, token) : Promise.resolve(null)
    ]).then(([result, previous]) => {
      if (!active) return;
      setAnalytics(result);
      setPreviousSheetTotal(previous?.monthlyTotal ?? null);
    })
      .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : "Could not load analytics."); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [date, online, sheet, sheets, token]);

  if (loading) return <section className="analytics-state">Loading your spending analysis…</section>;
  if (error || !analytics) return <section className="analytics-state error">{error || "No analytics available."}</section>;
  const maxDay = Math.max(1, ...analytics.daily.map((item) => item.total));
  const maxCategory = Math.max(1, ...analytics.categories.map((item) => item.total));
  const monthLabel = new Date(`${analytics.month}-01T00:00:00`).toLocaleDateString("en-IN", { month: "long", year: "numeric" });
  const previousMonthLabel = new Date(`${analytics.month}-01T00:00:00`).toLocaleDateString("en-IN", { month: "short", year: "numeric" });
  const previousMonthDate = new Date(`${analytics.month}-01T00:00:00`);
  previousMonthDate.setMonth(previousMonthDate.getMonth() - 1);
  const previousLabel = previousMonthDate.toLocaleDateString("en-IN", { month: "short", year: "numeric" });
  const previousTotal = previousSheetTotal ?? analytics.previousMonthTotal;
  const comparisonPercent = previousTotal === 0 ? null : Math.round((analytics.monthlyTotal - previousTotal) * 1000 / previousTotal) / 10;
  const comparisonMax = Math.max(analytics.monthlyTotal, previousTotal, 1);
  const change = (value: number | null) => value == null ? "No comparison yet" : `${Math.abs(value)}% ${value >= 0 ? "higher" : "lower"}`;

  return <section className="analytics-view">
    <div className="analytics-heading"><div><span className="eyebrow">Free, exact calculations</span><h2>{monthLabel}</h2></div>
      <label className="analysis-date"><span>Analyze date</span><input type="date" value={date} onChange={(event) => onDateChange(event.target.value)} /></label>
    </div>
    <div className="analytics-kpis">
      <article><span>Month total</span><strong>{money(analytics.monthlyTotal)}</strong><small>{change(comparisonPercent)} than last month</small></article>
      <article><span>Recorded-day average</span><strong>{money(analytics.averageRecordedDay)}</strong><small>Highest: {analytics.highestCategory}</small></article>
    </div>

    <article className="comparison-card">
      <div className="chart-title"><h3>Monthly comparison</h3><span>{previousLabel} → {previousMonthLabel}</span></div>
      <div className="comparison-visual" aria-label={`${monthLabel} spending compared with ${previousLabel}`}>
        <div className="comparison-column previous">
          <strong>{money(previousTotal)}</strong>
          <div className="comparison-track"><i style={{ height: `${Math.max(4, previousTotal / comparisonMax * 100)}%` }} /></div>
          <span>{previousLabel}</span>
        </div>
        <div className={`comparison-change ${(comparisonPercent ?? 0) > 0 ? "increase" : "decrease"}`}>
          <b>{comparisonPercent == null ? "—" : comparisonPercent > 0 ? "↑" : comparisonPercent < 0 ? "↓" : "="}</b>
          <strong>{comparisonPercent == null ? "No baseline" : `${Math.abs(comparisonPercent)}%`}</strong>
          <small>{comparisonPercent == null ? "Previous month was zero" : comparisonPercent > 0 ? "more spent" : comparisonPercent < 0 ? "less spent" : "no change"}</small>
        </div>
        <div className="comparison-column current">
          <strong>{money(analytics.monthlyTotal)}</strong>
          <div className="comparison-track"><i style={{ height: `${Math.max(4, analytics.monthlyTotal / comparisonMax * 100)}%` }} /></div>
          <span>{previousMonthLabel}</span>
        </div>
      </div>
    </article>

    <article className="chart-card">
      <div className="chart-title"><h3>Daily spending</h3><span>{analytics.daily.length} recorded days</span></div>
      {analytics.daily.length === 0 ? <p className="empty-chart">No entries this month.</p> :
        <div className="daily-chart" aria-label="Daily spending bar chart">
          {analytics.daily.map((item) => <div className="day-column" key={item.date} title={`${item.date}: ${money(item.total)}`}>
            <span className="day-value">{item.total > 0 ? `₹${Math.round(item.total)}` : ""}</span>
            <i style={{ height: `${Math.max(3, item.total / maxDay * 100)}%` }} />
            <small>{Number(item.date.slice(-2))}</small>
          </div>)}
        </div>}
    </article>

    <article className="chart-card">
      <div className="chart-title"><h3>Categories</h3><span>Monthly breakdown</span></div>
      <div className="category-chart">{analytics.categories.map((item) => <div className="category-row" key={item.key}>
        <div><span>{item.label}</span><strong>{money(item.total)}</strong></div>
        <i><b style={{ width: `${item.total / maxCategory * 100}%` }} /></i>
      </div>)}</div>
    </article>

    <article className="week-card">
      <span className="eyebrow">Week of {new Date(`${analytics.week.start}T00:00:00`).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}</span>
      <div><p><small>This week</small><strong>{money(analytics.week.total)}</strong></p><p><small>Previous week</small><strong>{money(analytics.week.previousTotal)}</strong></p></div>
      <span className={`change-pill ${(analytics.week.changePercent ?? 0) > 0 ? "up" : "down"}`}>{change(analytics.week.changePercent)}</span>
    </article>

    <article className="insight-card"><div className="chart-title"><h3>What stands out</h3><span>No AI needed</span></div>
      <ul>{analytics.insights.map((insight) => <li key={insight}>{insight}</li>)}</ul>
    </article>
  </section>;
}

function CaptureInbox({ sheet, token, online }: { sheet: string; token: string; online: boolean }) {
  const [drafts, setDrafts] = useState<CaptureDraft[]>([]);
  const [permission, setPermission] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const [{ drafts: next }, access] = await Promise.all([Capture.listDrafts(), Capture.notificationAccess()]);
    setDrafts(next);
    setPermission(access.granted);
  }, []);

  useEffect(() => {
    Capture.ensureNotificationPermission().catch(() => undefined);
    refresh().catch(() => setMessage("Could not read the local capture inbox."));
    window.addEventListener("focus", refresh);
    let handle: { remove(): Promise<void> } | undefined;
    Capture.addListener("draftAvailable", refresh).then((listener) => { handle = listener; });
    return () => { handle?.remove(); window.removeEventListener("focus", refresh); };
  }, [refresh]);

  const scan = async () => {
    setBusy(true);
    setMessage("");
    try {
      const result = await Capture.scanImage();
      await refresh();
      setMessage(result.created ? "Image scanned. Review the new draft below." : "No transaction amount was found.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Image scan was cancelled.");
    } finally { setBusy(false); }
  };

  return <section className="capture-view">
    <div className="capture-heading"><div><span className="eyebrow">On-device capture</span><h2>Transaction inbox</h2></div>
      <button className="scan-button" disabled={busy} onClick={scan}>▧ Scan image</button>
    </div>
    <article className={`permission-card ${permission ? "granted" : ""}`}>
      <div><strong>{permission ? "Notification access enabled" : "Enable notification access"}</strong>
        <p>{permission ? "Likely payments will appear here as drafts." : "Android requires approval in system settings."}</p></div>
      {!permission && <button onClick={() => Capture.openNotificationAccess()}>Open settings</button>}
    </article>
    {!online && <p className="capture-notice">You can review drafts offline, but confirmation needs internet.</p>}
    {message && <p className="capture-notice">{message}</p>}
    <div className="draft-list">
      {drafts.length === 0 ? <div className="empty-inbox"><span>♡</span><strong>Inbox is clear</strong><p>Payment notifications and scanned images will wait here for review.</p></div>
        : drafts.map((draft) => <DraftCard draft={draft} sheet={sheet} token={token} online={online} key={draft.id}
          onDone={async () => { await refresh(); setMessage("Transaction handled."); }} />)}
    </div>
    <p className="privacy-note">Raw notification text and images are discarded after local parsing. Only confirmed fields are sent.</p>
  </section>;
}

function DraftCard({ draft, sheet, token, online, onDone }: {
  draft: CaptureDraft; sheet: string; token: string; online: boolean; onDone: () => Promise<void>;
}) {
  const [amount, setAmount] = useState(draft.amount?.toString() ?? "");
  const [merchant, setMerchant] = useState(draft.merchant);
  const [date, setDate] = useState(draft.date);
  const [category, setCategory] = useState<ExpenseKey | "">(draft.category ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const confirm = async () => {
    const numericAmount = Number(amount);
    if (!online) { setError("Connect to the internet before confirming."); return; }
    if (!Number.isFinite(numericAmount) || numericAmount <= 0) { setError("Enter a valid amount."); return; }
    if (draft.type === "DEBIT" && !category) { setError("Choose a category for this expense."); return; }
    setSaving(true); setError("");
    try {
      const { deviceId } = await Capture.deviceId();
      await recordTransaction({
        eventId: draft.eventId, date, sheetName: sheet, occurredAt: draft.occurredAt, type: draft.type,
        amount: numericAmount, ...(category ? { category } : {}), merchant: merchant.trim(),
        source: draft.source, captureMethod: draft.captureMethod, deviceId
      }, token);
      await Capture.completeDraft({ id: draft.id, merchant: merchant.trim(), ...(category ? { category } : {}) });
      await onDone();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "Could not confirm transaction."); }
    finally { setSaving(false); }
  };

  const dismiss = async () => { await Capture.dismissDraft({ id: draft.id }); await onDone(); };

  return <article className="draft-card">
    <div className="draft-title"><span className={`type-badge ${draft.type.toLowerCase()}`}>{draft.type === "DEBIT" ? "Expense" : "Credit · log only"}</span>
      <small>{draft.captureMethod === "IMAGE" ? "Image" : draft.source}</small></div>
    <div className="draft-fields">
      <label><span>Amount</span><div className="amount-input"><b>₹</b><input inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} /></div></label>
      <label><span>Date</span><input type="date" value={date} onChange={(event) => setDate(event.target.value)} /></label>
      <label className="wide"><span>Merchant</span><input value={merchant} maxLength={160} onChange={(event) => setMerchant(event.target.value)} /></label>
      {draft.type === "DEBIT" && <label className="wide"><span>Category</span><select value={category} onChange={(event) => setCategory(event.target.value as ExpenseKey | "")}>
        <option value="">Choose category</option>{expenseFields.map((field) => <option value={field.key} key={field.key}>{field.label}</option>)}
      </select></label>}
    </div>
    {error && <p className="draft-error">{error}</p>}
    <div className="draft-actions"><button className="dismiss-button" onClick={dismiss}>Dismiss</button>
      <button className="confirm-button" disabled={saving} onClick={confirm}>{saving ? "Saving…" : draft.type === "DEBIT" ? "Add expense" : "Log credit"}</button></div>
  </article>;
}

function ThemePicker({ theme, onChange }: { theme: Theme; onChange: (theme: Theme) => void }) {
  return (
    <div className="theme-picker" role="radiogroup" aria-label="App theme">
      {themes.map((option) => (
        <button
          type="button"
          role="radio"
          aria-checked={theme === option.key}
          className={theme === option.key ? "active" : ""}
          onClick={() => onChange(option.key)}
          key={option.key}
        >
          <span aria-hidden="true">{option.icon}</span>{option.label}
        </button>
      ))}
    </div>
  );
}

function CharacterBackground({ theme, character }: { theme: Theme; character: (typeof sanrioCharacters)[number] }) {
  if (theme !== "sanrio") return null;
  return (
    <div className="character-background" aria-hidden="true">
      <img className="corner-character left" src={character.image} alt="" />
      <img className="corner-character right" src={character.image} alt="" />
    </div>
  );
}

function SanrioFriends({ selected, onSelect }: {
  selected: string;
  onSelect: (character: (typeof sanrioCharacters)[number]) => void;
}) {

  return (
    <aside className="sanrio-friends" aria-label="Sanrio friends">
      <div className="sanrio-ribbon"><span>♡</span> Little spending buddies <span>♡</span></div>
      <div className="character-strip">
        {sanrioCharacters.map((friend, index) => (
          <button type="button" aria-pressed={selected === friend.name}
            className={`character-card tilt-${(index % 3) + 1} ${selected === friend.name ? "selected" : ""}`}
            onClick={() => onSelect(friend)} key={friend.name}>
            <img src={friend.image} alt={friend.name} loading="lazy" />
            <span>{friend.name}</span>
          </button>
        ))}
      </div>
      <a href="https://www.sanrio.com/pages/2026-sanrio-character-wallpapers" target="_blank" rel="noreferrer">
        Official Sanrio character art · personal use
      </a>
    </aside>
  );
}
