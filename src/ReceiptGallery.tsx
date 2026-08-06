import { useCallback, useEffect, useMemo, useState } from "react";
import { loadAllReceipts } from "./api";
import type { ReceiptImage } from "./types";

export default function ReceiptGallery({ sheet, token, online }: { sheet: string; token: string; online: boolean }) {
  const [receipts, setReceipts] = useState<ReceiptImage[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [selectedReceipt, setSelectedReceipt] = useState<ReceiptImage | null>(null);
  const refresh = useCallback(() => {
    if (!online || !sheet) return;
    setLoading(true); setError("");
    loadAllReceipts(sheet, token).then(setReceipts)
      .catch((reason) => setError(reason instanceof Error ? reason.message : "Could not load images."))
      .finally(() => setLoading(false));
  }, [online, sheet, token]);
  useEffect(() => { refresh(); window.addEventListener("receiptSaved", refresh); return () => window.removeEventListener("receiptSaved", refresh); }, [refresh]);
  useEffect(() => {
    if (!selectedReceipt) return;
    const previousOverflow = document.body.style.overflow;
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === "Escape") setSelectedReceipt(null); };
    document.body.style.overflow = "hidden";
    window.addEventListener("keydown", closeOnEscape);
    return () => { document.body.style.overflow = previousOverflow; window.removeEventListener("keydown", closeOnEscape); };
  }, [selectedReceipt]);
  const groups = useMemo(() => {
    const grouped = new Map<string, ReceiptImage[]>();
    receipts.forEach((receipt) => grouped.set(receipt.date, [...(grouped.get(receipt.date) || []), receipt]));
    return [...grouped].sort(([a], [b]) => b.localeCompare(a));
  }, [receipts]);
  return <section className="receipt-gallery-view"><div className="gallery-heading"><div><span className="eyebrow">Receipt storage</span><h2>Images</h2></div><button onClick={refresh}>↻ Refresh</button></div>
    {loading ? <p className="gallery-state">Loading receipt images…</p> : error ? <p className="gallery-state error">{error}</p> : !groups.length ? <p className="gallery-state">No receipt images in {sheet}.</p> : groups.map(([date, items]) => <section className="gallery-date" key={date}><h3>{new Date(`${date}T00:00:00`).toLocaleDateString("en-IN", { day: "numeric", month: "long", year: "numeric" })}</h3><div>{items.map((receipt) => <button onClick={() => setSelectedReceipt(receipt)} key={receipt.id} aria-label={`Open receipt from ${date}`}><img src={receipt.imageBase64} alt={`Receipt from ${date}`} /><span>{receipt.kind === "food" ? "Food" : "Spend"}</span></button>)}</div></section>)}
    {selectedReceipt && <div className="receipt-lightbox" role="dialog" aria-modal="true" aria-label="Receipt image viewer" onClick={() => setSelectedReceipt(null)}><button className="receipt-lightbox-close" onClick={() => setSelectedReceipt(null)} aria-label="Close image">×</button><img src={selectedReceipt.imageBase64} alt={`Receipt from ${selectedReceipt.date}`} onClick={(event) => event.stopPropagation()} /></div>}
  </section>;
}
