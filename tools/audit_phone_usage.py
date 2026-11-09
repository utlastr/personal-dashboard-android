"""Read the phone engine through an explicit ADB forward; do not modify phone data."""
from datetime import datetime
import json
from pathlib import Path
import sqlite3
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT/".local"/"phone-audit"
BASE = "http://127.0.0.1:5671"


def get(path):
    with urllib.request.urlopen(BASE+path, timeout=20) as response:
        return json.load(response)


def main():
    STATE.mkdir(parents=True, exist_ok=True)
    buckets = get("/api/0/buckets/")
    now = int(datetime.now().timestamp()*1000)
    today = int(datetime.now().replace(hour=0, minute=0, second=0, microsecond=0).timestamp()*1000)
    for bucket in buckets:
        if not bucket.startswith("aw-watcher-android_"):
            continue
        events = get(f"/api/0/buckets/{bucket}/events?limit=-1")
        snapshot = STATE/(bucket+".json")
        snapshot.write_text(json.dumps(events, ensure_ascii=False), encoding="utf-8")
        rows = []
        for event in events:
            start = round(datetime.fromisoformat(event["timestamp"].replace("Z","+00:00")).timestamp()*1000)
            end = start+round(event["duration"]*1000)
            data = event["data"]
            rows.append((data.get("session_id", str(event.get("id"))), start, end,
                         data.get("package",""), data.get("app",""), data.get("purpose","personal")))
        with sqlite3.connect(":memory:") as db:
            db.execute("CREATE TABLE sessions(id TEXT,start INTEGER,end INTEGER,pkg TEXT,app TEXT,purpose TEXT)")
            db.executemany("INSERT INTO sessions VALUES(?,?,?,?,?,?)", rows)
            buggy = db.execute("SELECT COUNT(*),SUM(end-MAX(start,?)) FROM sessions WHERE end>?",
                               (str(today),str(today))).fetchone()
            fixed = db.execute("""SELECT COUNT(*),SUM(MIN(end,CAST(? AS INTEGER))-MAX(start,CAST(? AS INTEGER)))
                                  FROM sessions WHERE end>? AND start<?""",
                               (str(now),str(today),str(today),str(now))).fetchone()
            purposes = db.execute("""SELECT purpose,SUM(end-MAX(start,?))/3600000.0,
                SUM(MIN(end,CAST(? AS INTEGER))-MAX(start,CAST(? AS INTEGER)))/3600000.0
                FROM sessions WHERE end>? AND start<? GROUP BY purpose""",
                (str(today),str(now),str(today),str(today),str(now))).fetchall()
            top = db.execute("""SELECT app,purpose,COUNT(*),SUM(end-MAX(start,?))/3600000.0
                FROM sessions WHERE end>? GROUP BY pkg,purpose ORDER BY 4 DESC LIMIT 8""",
                (str(today),str(today))).fetchall()
        today_rows = sorted((max(s,today),min(e,now)) for _,s,e,*_ in rows if e>today and s<now)
        union = 0
        end = today
        for start, finish in today_rows:
            union += max(0,finish-max(start,end))
            end = max(end,finish)
        print(json.dumps({
            "bucket": bucket, "events": len(rows), "todaySessions": buggy[0],
            "oldDisplayedHours": round((buggy[1] or 0)/3600000,6),
            "correctHours": round((fixed[1] or 0)/3600000,6),
            "nonOverlappingHours": round(union/3600000,6),
            "purposes": purposes, "oldTopApps":top,
            "snapshot": str(snapshot),
        }, ensure_ascii=False))


if __name__ == "__main__":
    main()
