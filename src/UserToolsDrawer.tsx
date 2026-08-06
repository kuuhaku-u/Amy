import { useEffect, useMemo, useState } from "react";
import { loadHistory, loadReceipts, logFood, uploadReceipt } from "./api";
import { expenseFields, type ExpenseKey, type HistoryEntry } from "./types";

type Tool = "merchants" | "heatmap" | "questions" | "whatif" | "voice" | "receipts" | "foodlog";
type Receipt = { id: string; date: string; name: string; image: string; createdAt: number };
const money = (n: number) => new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(n || 0);

export default function UserToolsDrawer({ open, onClose, date, sheet, token, onVoiceExpense }: {
  open: boolean; onClose: () => void; date: string; sheet: string; token: string;
  onVoiceExpense: (category: ExpenseKey, amount: number) => void;
}) {
  const [tool, setTool] = useState<Tool>("merchants");
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [askAmount, setAskAmount] = useState("");
  const [whatAmount, setWhatAmount] = useState("");
  const [whatCategory, setWhatCategory] = useState<ExpenseKey>("others");
  const [voiceMessage, setVoiceMessage] = useState("Tap the microphone and say “add 120 for lunch”.");
  const [receipts, setReceipts] = useState<Receipt[]>([]);
  const [receiptStatus, setReceiptStatus] = useState("");
  const [receiptKind, setReceiptKind] = useState<"food" | "spend">("spend");
  const [foodEntry, setFoodEntry] = useState({ meal: "LUNCH" as "BREAKFAST" | "LUNCH" | "SNACK" | "DINNER", food: "", amount: "", notes: "" });
  const [foodStatus, setFoodStatus] = useState("");
  const [foodImage, setFoodImage] = useState<File | null>(null);
  const [foodPreview, setFoodPreview] = useState("");
  const month = date.slice(0, 7);
  const total = history.reduce((sum, row) => sum + row.total, 0);
  const settings = useMemo(() => { try { return JSON.parse(localStorage.getItem(`moneyManager:${sheet}:${month}`) || "{}"); } catch { return {}; } }, [month, sheet]);
  const income = Number(settings.income) || 0;

  useEffect(() => {
    if (!open || !sheet) return;
    const last = new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).getDate();
    loadHistory(`${month}-01`, `${month}-${last}`, sheet, token).then(setHistory).catch(() => setHistory([]));
  }, [month, open, sheet, token]);
  useEffect(() => { if (open && sheet) loadReceipts(date, sheet, token).then((items) => setReceipts(items.map((item) => ({ id: item.id, date: item.date, name: "", image: item.imageBase64, createdAt: 0 })))).catch(() => readReceipts(date).then(setReceipts)); }, [date, open, sheet, token]);

  const merchants = useMemo(() => {
    const totals = new Map<string, number>();
    history.forEach((row) => expenseFields.forEach((field) => {
      const comment = row.comments[field.key]?.trim();
      if (comment && (row.values[field.key] || 0) > 0) totals.set(comment, (totals.get(comment) || 0) + (row.values[field.key] || 0));
    }));
    return [...totals].sort((a, b) => b[1] - a[1]).slice(0, 10);
  }, [history]);
  const maxMerchant = Math.max(1, ...merchants.map(([, amount]) => amount));
  const daily = new Map(history.map((row) => [Number(row.date.slice(-2)), row.total]));
  const maxDay = Math.max(1, ...daily.values());
  const days = new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).getDate();

  const listen = () => {
    const SpeechRecognition = (window as unknown as { SpeechRecognition?: new () => SpeechRecognitionLike; webkitSpeechRecognition?: new () => SpeechRecognitionLike }).SpeechRecognition
      || (window as unknown as { webkitSpeechRecognition?: new () => SpeechRecognitionLike }).webkitSpeechRecognition;
    if (!SpeechRecognition) { setVoiceMessage("Voice recognition is not supported by this browser."); return; }
    const recognition = new SpeechRecognition(); recognition.lang = "en-IN";
    recognition.onresult = (event) => {
      const text = event.results[0][0].transcript.toLowerCase();
      const amount = Number(text.match(/\d+(?:\.\d{1,2})?/)?.[0]);
      const category = expenseFields.find((field) => text.includes(field.label.toLowerCase()));
      if (!amount || !category) { setVoiceMessage(`I heard “${text}”. Include an amount and category.`); return; }
      onVoiceExpense(category.key, amount); setVoiceMessage(`Added ${money(amount)} to ${category.label}.`);
    };
    recognition.onerror = () => setVoiceMessage("I could not understand that. Please try again."); recognition.start(); setVoiceMessage("Listening…");
  };
  const addReceipt = (file?: File) => {
    if (!file) return; setReceiptStatus("Uploading to Google Drive…"); const reader = new FileReader(); reader.onload = async () => {
      const receipt = { id: crypto.randomUUID(), date, name: file.name, image: String(reader.result), createdAt: Date.now() };
      try {
        const uploaded = await uploadReceipt(file, date, sheet, receiptKind, token);
        await saveReceipt(receipt); setReceipts((current) => [receipt, ...current]);
        window.dispatchEvent(new Event("receiptSaved"));
        setReceiptStatus(`${uploaded.name} saved in ${uploaded.folderName}.`);
      } catch (error) { setReceiptStatus(error instanceof Error ? error.message : "Receipt upload failed."); }
    }; reader.readAsDataURL(file);
  };
  const saveFood = async () => {
    if (!foodEntry.food.trim() || !foodEntry.amount) { setFoodStatus("Enter a food item and amount."); return; }
    setFoodStatus("Saving food log…");
    try {
      const uploaded = foodImage ? await uploadReceipt(foodImage, date, sheet, "food", token) : null;
      await logFood({ date, meal: foodEntry.meal, food: foodEntry.food.trim(), amount: Number(foodEntry.amount), notes: foodEntry.notes.trim(), ...(uploaded ? { receiptFileId: uploaded.fileId } : {}) }, token);
      setFoodStatus(uploaded ? "Food and image saved to Google Sheets and Drive." : "Food saved to the Food Log sheet.");
      setFoodEntry((current) => ({ ...current, food: "", amount: "", notes: "" })); setFoodImage(null); setFoodPreview("");
    } catch (error) { setFoodStatus(error instanceof Error ? error.message : "Could not save food."); }
  };
  const selectFoodImage = (file?: File) => {
    if (!file) return; setFoodImage(file); const reader = new FileReader(); reader.onload = () => setFoodPreview(String(reader.result)); reader.readAsDataURL(file);
  };

  return <><button className="tools-trigger" onClick={onClose} aria-label="Open money tools">☰</button><div className={`drawer-backdrop ${open ? "open" : ""}`} onClick={onClose} />
    <aside className={`tools-drawer ${open ? "open" : ""}`} aria-hidden={!open}>
      <header><div><span className="eyebrow">Money toolkit</span><h2>Track smarter</h2></div><button onClick={onClose}>×</button></header>
      <nav>{([{ key: "merchants", label: "Merchant insights", icon: "⌂" }, { key: "heatmap", label: "Spending heatmap", icon: "▦" }, { key: "questions", label: "Smart questions", icon: "?" }, { key: "whatif", label: "What-if", icon: "↝" }, { key: "voice", label: "Voice entry", icon: "◉" }, { key: "foodlog", label: "Food log", icon: "♨" }, { key: "receipts", label: "Receipts", icon: "▧" }] as const).map((item) => <button className={tool === item.key ? "active" : ""} onClick={() => setTool(item.key)} key={item.key}><b>{item.icon}</b>{item.label}</button>)}</nav>
      <div className="tool-content">
        {tool === "merchants" && <ToolCard title="Where your money went">{merchants.length ? merchants.map(([name, amount]) => <div className="merchant-row" key={name}><div><span>{name}</span><strong>{money(amount)}</strong></div><i><b style={{ width: `${amount / maxMerchant * 100}%` }} /></i></div>) : <Empty text="Add merchant names in expense comments to build insights." />}</ToolCard>}
        {tool === "heatmap" && <ToolCard title={`${new Date(`${month}-01T00:00:00`).toLocaleDateString("en-IN", { month: "long", year: "numeric" })} heatmap`}><div className="heatmap">{Array.from({ length: days }, (_, i) => { const amount = daily.get(i + 1) || 0; return <span title={`${i + 1}: ${money(amount)}`} style={{ opacity: amount ? .25 + amount / maxDay * .75 : .1 }} key={i}>{i + 1}</span>; })}</div><p className="tool-note">Darker days had higher spending.</p></ToolCard>}
        {tool === "questions" && <ToolCard title="Can I afford it?"><label className="tool-field"><span>Purchase amount</span><input inputMode="decimal" value={askAmount} onChange={(e) => setAskAmount(e.target.value)} placeholder="₹ 0" /></label>{askAmount && <Answer amount={Number(askAmount)} income={income} spent={total} />}</ToolCard>}
        {tool === "whatif" && <ToolCard title="Preview a purchase"><div className="tool-fields"><input inputMode="decimal" value={whatAmount} onChange={(e) => setWhatAmount(e.target.value)} placeholder="Amount" /><select value={whatCategory} onChange={(e) => setWhatCategory(e.target.value as ExpenseKey)}>{expenseFields.map((f) => <option value={f.key} key={f.key}>{f.label}</option>)}</select></div><div className="whatif-result"><p><span>Current spend</span><strong>{money(total)}</strong></p><p><span>After purchase</span><strong>{money(total + Number(whatAmount || 0))}</strong></p><p><span>Balance after</span><strong>{income ? money(income - total - Number(whatAmount || 0)) : "Set income in Planning"}</strong></p></div></ToolCard>}
        {tool === "voice" && <ToolCard title="Add by voice"><button className="voice-button" onClick={listen}>● Start listening</button><p className="voice-message">{voiceMessage}</p></ToolCard>}
        {tool === "foodlog" && <ToolCard title={`Log food for ${date}`}><div className="food-log-form"><label><span>Meal</span><select value={foodEntry.meal} onChange={(e) => setFoodEntry({ ...foodEntry, meal: e.target.value as typeof foodEntry.meal })}><option value="BREAKFAST">Breakfast</option><option value="LUNCH">Lunch</option><option value="SNACK">Snack</option><option value="DINNER">Dinner</option></select></label><label><span>Food item</span><input value={foodEntry.food} placeholder="e.g. Masala dosa" onChange={(e) => setFoodEntry({ ...foodEntry, food: e.target.value })} /></label><label><span>Amount</span><input inputMode="decimal" value={foodEntry.amount} placeholder="₹ 0" onChange={(e) => /^\d*(\.\d{0,2})?$/.test(e.target.value) && setFoodEntry({ ...foodEntry, amount: e.target.value })} /></label><label><span>Notes</span><input value={foodEntry.notes} placeholder="Optional" onChange={(e) => setFoodEntry({ ...foodEntry, notes: e.target.value })} /></label><div className="image-source-buttons"><label>📷 Take photo<input type="file" accept="image/*" capture="environment" onChange={(e) => selectFoodImage(e.target.files?.[0])} /></label><label>▧ Choose gallery<input type="file" accept="image/*" onChange={(e) => selectFoodImage(e.target.files?.[0])} /></label></div>{foodPreview && <div className="food-preview"><img src={foodPreview} alt="Selected food" /><button onClick={() => { setFoodImage(null); setFoodPreview(""); }}>×</button></div>}<button onClick={saveFood}>Save food</button></div>{foodStatus && <p className="voice-message">{foodStatus}</p>}</ToolCard>}
        {tool === "receipts" && <ToolCard title={`Receipts for ${date}`}><div className="receipt-kind"><button className={receiptKind === "food" ? "active" : ""} onClick={() => setReceiptKind("food")}>Food receipt</button><button className={receiptKind === "spend" ? "active" : ""} onClick={() => setReceiptKind("spend")}>Other spending</button></div><div className="image-source-buttons"><label>📷 Take photo<input type="file" accept="image/*" capture="environment" onChange={(e) => addReceipt(e.target.files?.[0])} /></label><label>▧ Gallery / file<input type="file" accept="image/*,application/pdf" onChange={(e) => addReceipt(e.target.files?.[0])} /></label></div>{receiptStatus && <p className="voice-message">{receiptStatus}</p>}<div className="receipt-grid">{receipts.map((receipt) => <figure key={receipt.id}><img src={receipt.image} alt="Receipt" /></figure>)}</div>{!receipts.length && <Empty text="No receipt attached to this date." />}</ToolCard>}
      </div>
    </aside></>;
}

function ToolCard({ title, children }: { title: string; children: React.ReactNode }) { return <section className="tool-card"><h3>{title}</h3>{children}</section>; }
function Empty({ text }: { text: string }) { return <p className="tool-empty">{text}</p>; }
function Answer({ amount, income, spent }: { amount: number; income: number; spent: number }) { const left = income - spent - amount; return <div className={`smart-answer ${!income || left < 0 ? "caution" : "safe"}`}><strong>{!income ? "Set your income first" : left >= 0 ? "Yes, within current cashflow" : "This exceeds your current balance"}</strong><span>{income ? `${money(left)} would remain.` : "Open Money → Planning to set monthly income."}</span></div>; }
interface SpeechRecognitionLike { lang: string; start(): void; onresult: (event: { results: { 0: { 0: { transcript: string } } } }) => void; onerror: () => void; }

function receiptDb(): Promise<IDBDatabase> { return new Promise((resolve, reject) => { const request = indexedDB.open("monthly-spend-receipts", 1); request.onupgradeneeded = () => request.result.createObjectStore("receipts", { keyPath: "id" }); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); }); }
async function saveReceipt(receipt: Receipt) { const db = await receiptDb(); return new Promise<void>((resolve, reject) => { const tx = db.transaction("receipts", "readwrite"); tx.objectStore("receipts").put(receipt); tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); }); }
async function readReceipts(date: string) { const db = await receiptDb(); return new Promise<Receipt[]>((resolve, reject) => { const request = db.transaction("receipts").objectStore("receipts").getAll(); request.onsuccess = () => resolve((request.result as Receipt[]).filter((r) => r.date === date).sort((a, b) => b.createdAt - a.createdAt)); request.onerror = () => reject(request.error); }); }
async function deleteReceipt(id: string) { const db = await receiptDb(); return new Promise<void>((resolve, reject) => { const tx = db.transaction("receipts", "readwrite"); tx.objectStore("receipts").delete(id); tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); }); }
