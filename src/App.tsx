import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiError, loadAnalytics, loadDate, syncQueued } from "./api";
import { enqueue, queuedItems, setConfig } from "./db";
import { expenseFields, type AnalyticsResponse, type ExpenseKey, type SpendResponse } from "./types";

type FormValues = Record<ExpenseKey, string>;
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
const today = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
};

function money(value: number): string {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(value || 0);
}

function tokenFromLocation(): string {
  const match = window.location.hash.match(/^#access=(.+)$/);
  if (match) {
    const token = decodeURIComponent(match[1]);
    localStorage.setItem("monthlySpendAccess", token);
    history.replaceState(null, "", window.location.pathname + window.location.search);
    return token;
  }
  return localStorage.getItem("monthlySpendAccess") || "";
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
  const [touched, setTouched] = useState<Set<ExpenseKey>>(new Set());
  const [summary, setSummary] = useState({ total: 0, weekTotal: 0, monthlySpend: 0 });
  const [status, setStatus] = useState<Status>("idle");
  const [message, setMessage] = useState("");
  const [pendingCount, setPendingCount] = useState(0);
  const [online, setOnline] = useState(navigator.onLine);
  const [view, setView] = useState<"expenses" | "insights">("expenses");

  const refreshPending = useCallback(async () => setPendingCount((await queuedItems()).length), []);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("monthlySpendTheme", theme);
  }, [theme]);

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
    if (!token || !online) {
      if (!online) {
        setStatus("queued");
        setMessage("Offline. You can still enter amounts and save them for later.");
      }
      return;
    }
    let active = true;
    setStatus("loading");
    setMessage("Loading this date…");
    loadDate(date, token)
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
  }, [date, online, token]);

  const applyResponse = (response: SpendResponse) => {
    setValues(Object.fromEntries(expenseFields.map(({ key }) => [key, response.values[key] ?? ""])) as FormValues);
    setSummary({ total: response.total, weekTotal: response.weekTotal, monthlySpend: response.monthlySpend });
    setTouched(new Set());
  };

  const previewTotal = useMemo(
    () => ["travel", "breakfast", "lunch", "eveSnack", "dinner"]
      .reduce((sum, key) => sum + (Number(values[key as ExpenseKey]) || 0), 0),
    [values]
  );

  const updateValue = (key: ExpenseKey, value: string) => {
    if (value !== "" && !/^\d*(\.\d{0,2})?$/.test(value)) return;
    setValues((current) => ({ ...current, [key]: value }));
    setTouched((current) => new Set(current).add(key));
    setStatus("idle");
    setMessage("");
  };

  const changeDate = (nextDate: string) => {
    setDate(nextDate);
    setValues(emptyValues());
    setTouched(new Set());
    setSummary({ total: 0, weekTotal: 0, monthlySpend: 0 });
  };

  const save = async () => {
    if (touched.size === 0) {
      setStatus("error");
      setMessage("Change at least one amount before saving.");
      return;
    }
    const changes = Object.fromEntries(
      [...touched].map((key) => [key, values[key] === "" ? null : Number(values[key])])
    );
    await enqueue({ submissionId: crypto.randomUUID(), date, changes, createdAt: Date.now() });
    await refreshPending();
    setTouched(new Set());
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
        const response = await loadDate(date, token);
        applyResponse(response);
        setStatus("saved");
        setMessage("Saved to Monthly Spend.");
      } catch (error) {
        setStatus(error instanceof ApiError && error.status < 500 ? "error" : "queued");
        setMessage(error instanceof Error ? error.message : "Still queued for sync.");
      }
    }
  };

  const unlock = async () => {
    const next = tokenInput.trim();
    if (!next) return;
    localStorage.setItem("monthlySpendAccess", next);
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
    <><CharacterBackground theme={theme} character={sanrioCharacter} /><main className="app-shell">
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

      <nav className="view-tabs" aria-label="App sections">
        <button className={view === "expenses" ? "active" : ""} onClick={() => setView("expenses")}>＋ Expenses</button>
        <button className={view === "insights" ? "active" : ""} onClick={() => setView("insights")}>⌁ Insights</button>
      </nav>

      {theme === "sanrio" && view === "expenses" && (
        <SanrioFriends selected={sanrioCharacter.name} onSelect={setSanrioCharacter} />
      )}

      {view === "insights" ? <AnalyticsView date={date} token={token} online={online} /> : <>

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
          <div><span className="eyebrow">Amounts in INR</span><h2>Expenses</h2></div>
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
              />
            </label>
          ))}
        </div>
      </section>

      <div className={`status-message ${status}`} role="status">
        <span>{status === "saving" || status === "loading" ? "↻" : status === "saved" ? "✓" : status === "error" ? "!" : status === "queued" ? "↑" : "•"}</span>
        <p>{message || "Only changed fields will be updated."}</p>
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

function AnalyticsView({ date, token, online }: { date: string; token: string; online: boolean }) {
  const [analysisDate, setAnalysisDate] = useState(date);
  const [analytics, setAnalytics] = useState<AnalyticsResponse | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!online) { setError("Analytics needs an internet connection to read the sheet."); setLoading(false); return; }
    let active = true;
    setLoading(true);
    setError("");
    loadAnalytics(analysisDate, token).then((result) => { if (active) setAnalytics(result); })
      .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : "Could not load analytics."); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [analysisDate, online, token]);

  if (loading) return <section className="analytics-state">Loading your spending analysis…</section>;
  if (error || !analytics) return <section className="analytics-state error">{error || "No analytics available."}</section>;
  const maxDay = Math.max(1, ...analytics.daily.map((item) => item.total));
  const maxCategory = Math.max(1, ...analytics.categories.map((item) => item.total));
  const monthLabel = new Date(`${analytics.month}-01T00:00:00`).toLocaleDateString("en-IN", { month: "long", year: "numeric" });
  const change = (value: number | null) => value == null ? "No comparison yet" : `${Math.abs(value)}% ${value >= 0 ? "higher" : "lower"}`;

  return <section className="analytics-view">
    <div className="analytics-heading"><div><span className="eyebrow">Free, exact calculations</span><h2>{monthLabel}</h2></div>
      <label className="analysis-date"><span>Analyze date</span><input type="date" value={analysisDate} onChange={(event) => setAnalysisDate(event.target.value)} /></label>
    </div>
    <div className="analytics-kpis">
      <article><span>Month total</span><strong>{money(analytics.monthlyTotal)}</strong><small>{change(analytics.monthChangePercent)} than last month</small></article>
      <article><span>Recorded-day average</span><strong>{money(analytics.averageRecordedDay)}</strong><small>Highest: {analytics.highestCategory}</small></article>
    </div>

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
