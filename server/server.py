import asyncio
import hmac
import json
import os
import sqlite3
import time

from fastapi import Depends, FastAPI, HTTPException, Query, Request

DB_PATH = os.environ.get("NOTIFORWARD_DB", "notifications.db")
TOKEN = os.environ.get("NOTIFORWARD_TOKEN", "")
if not TOKEN:
    raise RuntimeError(
        "NOTIFORWARD_TOKEN is not set. Export it (docker compose reads it from the "
        "environment or server/.env) and use the same value in the app and notiwatch."
    )
FIELDS = [
    "app",
    "app_name",
    "title",
    "text",
    "big_text",
    "sub_text",
    "lines",
    "category",
    "posted_at_ms",
    "key",
    "device",
]
COLUMNS = ["id", "received_at", *FIELDS]

db = sqlite3.connect(DB_PATH, check_same_thread=False)
db.row_factory = sqlite3.Row

# readers and the writer share one connection
# WAL keeps them from blocking each other.
db.execute("PRAGMA journal_mode=WAL")
db.execute("PRAGMA busy_timeout=5000")
db.execute("""CREATE TABLE IF NOT EXISTS notifications(
  id INTEGER PRIMARY KEY,
  received_at REAL NOT NULL,
  app TEXT, app_name TEXT, title TEXT, text TEXT, big_text TEXT, sub_text TEXT,
  lines TEXT, category TEXT, posted_at_ms INTEGER, key TEXT, device TEXT,
  raw TEXT NOT NULL)""")

# safeguard for possible future fields
existing = {row[1] for row in db.execute("PRAGMA table_info(notifications)")}
for col in FIELDS:
    if col not in existing:
        db.execute(f"ALTER TABLE notifications ADD COLUMN {col}")
db.execute("CREATE INDEX IF NOT EXISTS notifications_app ON notifications(app)")
db.commit()

app = FastAPI(title="NotiForward")
new_rows = asyncio.Condition()


def check_token(req: Request) -> None:
    if not hmac.compare_digest(req.headers.get("authorization", ""), f"Bearer {TOKEN}"):
        raise HTTPException(status_code=401, detail="bad token")


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/notify", dependencies=[Depends(check_token)])
async def notify(req: Request):
    data = await req.json()
    if not isinstance(data, dict):
        raise HTTPException(status_code=400, detail="expected a JSON object")
    cur = db.execute(
        f"INSERT INTO notifications(received_at, {', '.join(FIELDS)}, raw) "
        f"VALUES (?, {', '.join('?' * len(FIELDS))}, ?)",
        [
            time.time(),
            *[data.get(f) for f in FIELDS],
            json.dumps(data, ensure_ascii=False),
        ],
    )
    db.commit()
    async with new_rows:
        new_rows.notify_all()
    return {"ok": True, "id": cur.lastrowid}


def query(after: int, limit: int, latest: int | None) -> list[dict]:
    cols = ", ".join(COLUMNS)
    if latest is not None:
        sql = (
            f"SELECT * FROM (SELECT {cols} FROM notifications WHERE id > ? "
            f"ORDER BY id DESC LIMIT ?) ORDER BY id"
        )
        args = [after, latest]
    else:
        sql = f"SELECT {cols} FROM notifications WHERE id > ? ORDER BY id LIMIT ?"
        args = [after, limit]
    return [dict(r) for r in db.execute(sql, args)]


@app.get("/notifications", dependencies=[Depends(check_token)])
async def notifications(
    after: int = Query(0, ge=0),
    limit: int = Query(100, ge=1, le=1000),
    latest: int | None = Query(None, ge=1, le=1000),
    wait: float = Query(0, ge=0, le=60),
):
    rows = query(after, limit, latest)
    deadline = time.monotonic() + wait
    while not rows and latest is None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        async with new_rows:
            try:
                await asyncio.wait_for(new_rows.wait(), remaining)
            except TimeoutError:
                break
        rows = query(after, limit, latest)
    return rows
