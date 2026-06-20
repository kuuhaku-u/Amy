from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from ollama import chat
import gspread
from google.oauth2.service_account import Credentials
from datetime import datetime
import json, re

app = FastAPI()

# ---------------- CORS ----------------
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------- GOOGLE SHEETS ----------------
SCOPES = [
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive"
]

creds = Credentials.from_service_account_file(
    "credentials.json",
    scopes=SCOPES
)

client = gspread.authorize(creds)

sheet = client.open_by_key(
    "15H6mV-pqFdxoiHdgtgDVNHPN3H86PFB4u-cWrPNDd0U"
).sheet1


# ---------------- SCHEMA ----------------
HEADERS = [
    "timestamp",
    "amount",
    "currency",
    "category",
    "product",
    "brand",
    "description",
    "confidence"
]


# ---------------- SAFE INIT (NO CORRUPTION) ----------------
def init_sheet():
    values = sheet.get_all_values()

    # empty sheet → add headers
    if len(values) == 0:
        sheet.append_row(HEADERS)
        return

    # missing or wrong headers → fix safely
    if values[0] != HEADERS:
        sheet.clear()
        sheet.append_row(HEADERS)

init_sheet()


# ---------------- WRITE TOOL ----------------
def write_expense(data):
    if not data or not data.get("amount"):
        return "Skipped invalid expense"

    row = [
        datetime.now().isoformat(),
        float(data.get("amount")),
        data.get("currency") or "INR",
        data.get("category") or "other",
        data.get("product") or "unknown",
        data.get("brand") or "unknown",
        data.get("description") or "",
        float(data.get("confidence") or 0)
    ]

    sheet.append_row(row, value_input_option="USER_ENTERED")
    return "Saved ✔"


# ---------------- READ TOOL (CLEAN) ----------------
def read_expenses(limit=10):
    values = sheet.get_all_values()

    if len(values) <= 1:
        return []

    headers = values[0]
    rows = values[1:]

    result = []

    for r in rows:
        if not r or len(r) < len(headers):
            continue
        if all(x == "" for x in r):
            continue

        result.append(dict(zip(headers, r)))

    return result[-limit:]


# ---------------- INSIGHTS ----------------
def analyze():
    data = read_expenses(50)

    response = chat(
        model="qwen2.5:7b",
        messages=[{
            "role": "user",
            "content": f"""
You are a senior fintech analyst.

Analyze this data:

{json.dumps(data, indent=2)}

Return:
- total spend per category
- top brands
- spending habits
- risks (overspending)
- 3 savings tips
"""
        }]
    )

    return response["message"]["content"]


# ---------------- TOOL PROMPT (SMART AI) ----------------
TOOLS = """
You are an AI financial assistant.

RULES:
- Output ONLY JSON
- No extra text
- Always choose correct tool

------------------------------------------------
TOOL: write_expense
------------------------------------------------
Use when user mentions spending, buying, paying.

Infer intelligently:

CATEGORY RULES:
food → food delivery, restaurants, groceries
transport → Uber, Ola, fuel, taxi
shopping → Amazon, Flipkart, clothing
bills → rent, electricity, subscriptions
health → pharmacy, hospital
entertainment → Netflix, games
other → unknown

BRAND RULES:
Uber ride → Uber
Zomato order → Zomato
McDonalds → McDonald's
Amazon → Amazon
Netflix → Netflix

OUTPUT:
{
  "tool": "write_expense",
  "data": {
    "amount": number,
    "currency": "INR",
    "category": string,
    "product": string,
    "brand": string,
    "description": string,
    "confidence": number
  }
}

------------------------------------------------
TOOL: read_expenses
------------------------------------------------
Use for history / last transactions /speding queries / common questions .

Return:
{ "tool": "read_expenses" }

------------------------------------------------
TOOL: insights
------------------------------------------------
Use for:
- spending analysis
- summary
- savings advice

Return:
{ "tool": "insights" }
"""


# ---------------- INPUT MODEL ----------------
class Input(BaseModel):
    text: str


# ---------------- STREAM API ----------------
@app.post("/stream")
async def stream(input: Input):

    async def run():
        yield "Thinking...\n"

        decision = chat(
            model="qwen2.5:7b",
            format="json",
            messages=[{
                "role": "user",
                "content": TOOLS + "\n\nUser input:\n" + input.text
            }]
        )

        content = decision["message"]["content"]

        match = re.search(r"\{.*\}", content, re.DOTALL)
        if not match:
            yield f"Error parsing JSON:\n{content}"
            return

        action = json.loads(match.group(0))
        tool = action.get("tool")

        # ---------------- WRITE ----------------
        if tool == "write_expense":
            data = action["data"]
            write_expense(data)

            yield "\n📝 Saved Expense:\n"
            yield json.dumps(data, indent=2)

        # ---------------- READ ----------------
        elif tool == "read_expenses":
            data = read_expenses(10)

            yield "\n📊 Last Expenses:\n"
            yield json.dumps(data, indent=2)

        # ---------------- INSIGHTS ----------------
        elif tool == "insights":
            result = analyze()

            yield "\n🧠 Insights:\n"
            yield result

        else:
            yield "\n❌ Unknown tool"

        yield "\nDONE"

    return StreamingResponse(run(), media_type="text/plain")
