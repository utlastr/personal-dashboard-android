"""Verify the real phone snapshot reached PC ActivityWatch without duplication."""
from datetime import datetime
import json
from pathlib import Path
import sqlite3
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
snapshot = Path(sys.argv[1]).resolve()
if snapshot.parent != (ROOT/".local"/"phone-audit").resolve():
    raise ValueError("Use a private phone-audit snapshot")
expected = {e["data"]["session_id"]: e for e in json.loads(snapshot.read_text(encoding="utf-8"))}
bucket = snapshot.stem
device = bucket.removeprefix("aw-watcher-android_")
deadline = time.monotonic()+45
while True:
    db = sqlite3.connect((ROOT/".local"/"mobile-service"/"mobile.sqlite3").as_uri()+"?mode=ro",uri=True)
    try:
        total,pending = db.execute("SELECT COUNT(*),SUM(forwarded=0) FROM sessions WHERE device=?",(device,)).fetchone()
    finally:
        db.close()
    if pending == 0:
        break
    if time.monotonic() >= deadline:
        raise RuntimeError(f"Still forwarding {pending} of {total} sessions")
    time.sleep(1)
with urllib.request.urlopen(f"http://127.0.0.1:5600/api/0/buckets/{bucket}/events?limit=-1",timeout=10) as response:
    events = json.load(response)
actual = {e["data"]["session_id"]: e for e in events}
assert len(actual) == len(events), "Duplicate ActivityWatch session IDs"
assert set(actual) == set(expected), "Phone/PC session set mismatch"
for identity, source in expected.items():
    event = actual[identity]
    for key in ("app","package","purpose","device_role"):
        assert source["data"][key] == event["data"][key], f"{identity}: {key}"
    assert abs(source["duration"]-event["duration"]) < 0.000001, f"{identity}: duration"
    source_time = datetime.fromisoformat(source["timestamp"].replace("Z","+00:00"))
    actual_time = datetime.fromisoformat(event["timestamp"].replace("Z","+00:00"))
    assert source_time == actual_time, f"{identity}: timestamp"
print(json.dumps({"result":"PASS","phoneSessions":len(expected),"pcSessions":len(events),
                  "pending":pending,"duplicates":len(events)-len(actual)}))
