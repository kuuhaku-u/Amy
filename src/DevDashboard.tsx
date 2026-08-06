import { useEffect, useMemo, useState } from "react";
import { apiLogs, clearApiLogs, subscribeApiLogs, type ApiLogEntry } from "./devlog";

export default function DevDashboard({ online, pendingCount }: { online: boolean; pendingCount: number }) {
  const [logs, setLogs] = useState<ApiLogEntry[]>(apiLogs());
  useEffect(() => subscribeApiLogs(() => setLogs([...apiLogs()])), []);
  const stats = useMemo(() => {
    const completed = logs.filter((log) => log.status != null);
    return { requests: logs.length, failures: logs.filter((log) => log.error || (log.status || 0) >= 400).length,
      average: completed.length ? Math.round(completed.reduce((sum, log) => sum + log.durationMs, 0) / completed.length) : 0 };
  }, [logs]);
  return <section className="dev-dashboard">
    <div className="dev-heading"><div><span className="eyebrow">Android engineering</span><h2>Developer dashboard</h2></div><button onClick={() => clearApiLogs()}>Clear logs</button></div>
    <div className="dev-kpis"><article><span>Network</span><strong className={online ? "ok" : "bad"}>{online ? "Online" : "Offline"}</strong></article><article><span>API requests</span><strong>{stats.requests}</strong></article><article><span>Failures</span><strong className={stats.failures ? "bad" : "ok"}>{stats.failures}</strong></article><article><span>Avg latency</span><strong>{stats.average} ms</strong></article></div>
    <article className="dev-info"><h3>Runtime</h3><dl><div><dt>API base</dt><dd>{import.meta.env.VITE_API_BASE_URL || "Same origin"}</dd></div><div><dt>Mode</dt><dd>{import.meta.env.MODE}</dd></div><div><dt>Queued saves</dt><dd>{pendingCount}</dd></div><div><dt>Platform</dt><dd>Android · Capacitor</dd></div><div><dt>User agent</dt><dd>{navigator.userAgent}</dd></div></dl></article>
    <article className="api-console"><div><h3>API console</h3><small>Latest 100 requests · tokens and bodies excluded</small></div>{logs.length === 0 ? <p>No requests recorded in this session.</p> : <div className="api-log-list">{logs.map((log) => <div className={`api-log ${(log.error || (log.status || 0) >= 400) ? "failed" : "passed"}`} key={log.id}><span className="api-method">{log.method}</span><code>{log.path}</code><b>{log.status ?? "ERR"}</b><small>{log.durationMs} ms · {new Date(log.time).toLocaleTimeString()}</small>{log.error && <p>{log.error}</p>}</div>)}</div>}</article>
  </section>;
}
