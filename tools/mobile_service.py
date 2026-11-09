"""LAN sync and update service for Personal Dashboard Android.

The service accepts only a configured bearer token, stores a durable SQLite
copy of mobile sessions, and mirrors accepted sessions into the local
ActivityWatch-compatible aw-server bucket API.
"""
from __future__ import annotations
from contextlib import contextmanager

import hashlib
import datetime
import re
import threading
import time
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import secrets
import sqlite3
import urllib.error
import urllib.request
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "output"
MANIFEST = OUTPUT / "update-manifest.json"
STATE = Path(os.environ.get("PERSONAL_DASHBOARD_MOBILE_STATE", str(ROOT / ".local" / "mobile-service")))
DB = STATE / "mobile.sqlite3"
TOKEN_PATH = STATE / "pairing-token.txt"
HOST = os.environ.get("PERSONAL_DASHBOARD_MOBILE_HOST", "0.0.0.0")
PORT = int(os.environ.get("PERSONAL_DASHBOARD_MOBILE_PORT", "5668"))
AW_SERVER = os.environ.get("PERSONAL_DASHBOARD_AW_SERVER", "http://127.0.0.1:5600")
FORWARD_LOCK = threading.Lock()


def token() -> str:
    STATE.mkdir(parents=True, exist_ok=True)
    if not TOKEN_PATH.exists():
        TOKEN_PATH.write_text(secrets.token_urlsafe(24), encoding="ascii")
    return TOKEN_PATH.read_text(encoding="ascii").strip()


@contextmanager
def database():
    connection = sqlite3.connect(DB, timeout=30)
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def init_db() -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    with database() as db:
        db.execute(
            """CREATE TABLE IF NOT EXISTS sessions (
                id TEXT NOT NULL, device TEXT NOT NULL, name TEXT NOT NULL,
                role TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL,
                pkg TEXT NOT NULL, app TEXT NOT NULL, activity TEXT,
                purpose TEXT NOT NULL, received INTEGER NOT NULL,
                forwarded INTEGER DEFAULT 0, PRIMARY KEY(device,id)
            )"""
        )
    token()


def aw_request(method: str, path: str, body: object = None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        AW_SERVER + path, data=data, method=method,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 409:
            return
        raise


def ensure_bucket(device: str, name: str) -> str:
    bucket = "aw-watcher-android_" + device
    if bucket not in aw_request("GET", "/api/0/buckets/"):
        aw_request("POST", "/api/0/buckets/" + bucket, {
            "type": "currentwindow",
            "hostname": device,
            "client": "personal-dashboard-mobile",
        })
    return bucket


def mirror_event(row: dict) -> None:
    bucket = ensure_bucket(row["device"], row["name"])
    event = {
        "timestamp": __import__("datetime").datetime.fromtimestamp(
            row["start"] / 1000, __import__("datetime").timezone.utc
        ).isoformat().replace("+00:00", "Z"),
        "duration": (row["end"] - row["start"]) / 1000.0,
        "data": {
            "app": row["app"],
            "package": row["pkg"],
            "classname": row.get("activity", ""),
            "purpose": row["purpose"],
            "device_role": row["role"],
            "device": row["device"],
            "session_id": row["id"],
        },
    }
    query = urllib.parse.urlencode({
        "start": event["timestamp"],
        "end": datetime.datetime.fromtimestamp(row["end"]/1000, datetime.timezone.utc).isoformat(),
        "limit": -1,
    })
    existing = aw_request("GET", f"/api/0/buckets/{bucket}/events?{query}")
    if not any(e.get("data", {}).get("session_id") == row["id"] for e in existing):
        aw_request("POST", f"/api/0/buckets/{bucket}/events", [event])


def forward_pending():
    if not FORWARD_LOCK.acquire(blocking=False):
        return
    try:
        with database() as db:
            db.row_factory = sqlite3.Row
            rows = db.execute("SELECT * FROM sessions WHERE forwarded=0 ORDER BY start LIMIT 500").fetchall()
        for row in rows:
            try:
                mirror_event(dict(row))
            except Exception:
                return
            with database() as db:
                db.execute("UPDATE sessions SET forwarded=1 WHERE device=? AND id=?", (row["device"], row["id"]))
    finally:
        FORWARD_LOCK.release()


def retry_worker():
    while True:
        try:
            forward_pending()
        except Exception:
            pass
        time.sleep(20)


def validate_session(row: object) -> dict:
    if not isinstance(row, dict):
        raise ValueError("session must be an object")
    keys = ["id", "device", "name", "role", "start", "end", "pkg", "app", "purpose"]
    if any(not row.get(key) and key not in {"start", "end"} for key in keys):
        raise ValueError("session is missing required fields")
    if row["role"] not in {"work", "personal"} or row["purpose"] not in {"work", "personal"}:
        raise ValueError("invalid role or purpose")
    for key in ("id", "pkg", "app"):
        if not isinstance(row.get(key), str) or not 0 < len(row[key]) <= 512:
            raise ValueError("invalid " + key)
    start, end = row["start"], row["end"]
    if type(start) is not int or type(end) is not int or start <= 0 or end <= start or end - start > 86400000 or end > time.time()*1000+300000:
        raise ValueError("invalid session interval")
    return {
        "id": str(row["id"]), "device": str(row["device"]), "name": str(row["name"]),
        "role": row["role"], "start": start, "end": end, "pkg": str(row["pkg"]),
        "app": str(row["app"]), "activity": str(row.get("activity", "")),
        "purpose": row["purpose"],
    }


def accept(payload: dict) -> int:
    if not isinstance(payload, dict):
        raise ValueError("invalid payload")
    sessions = payload.get("sessions")
    if not isinstance(sessions, list) or len(sessions) > 500:
        raise ValueError("sessions must contain at most 500 items")
    device, name, role = str(payload.get("device", "")), str(payload.get("name", "")), payload.get("role")
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}",device) or not 0 < len(name) <= 80 or role not in {"work", "personal"}:
        raise ValueError("invalid device information")
    accepted = []
    with database() as db:
        for raw in sessions:
            if not isinstance(raw, dict):
                raise ValueError("invalid session")
            row = validate_session({**raw, "device": device, "name": name, "role": raw.get("role",role)})
            inserted = db.execute(
                "INSERT OR IGNORE INTO sessions(id,device,name,role,start,end,pkg,app,activity,purpose,received) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                (*row.values(), __import__("time").time_ns()),
            ).rowcount
            if inserted:
                accepted.append(row)
    return len(sessions)


def release():
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8-sig"))
    name = manifest["apk"]
    if not isinstance(name,str) or Path(name).name != name or not name.endswith(".apk"):
        raise ValueError("invalid APK")
    path = OUTPUT / name
    if hashlib.sha256(path.read_bytes()).hexdigest() != manifest["sha256"]:
        raise ValueError("APK hash mismatch")
    return manifest,path


def shared_state():
    today = datetime.datetime.now().replace(hour=0,minute=0,second=0,microsecond=0).timestamp()*1000
    now = int(time.time()*1000)
    with database() as db:
        db.row_factory=sqlite3.Row
        rows=db.execute("""SELECT device,name,role,purpose,
            SUM(MIN(end,?)-MAX(start,?)) ms FROM sessions
            WHERE end>? AND start<? AND end>start GROUP BY device,purpose""",
            (now,today,today,now)).fetchall()
        pending=db.execute("SELECT COUNT(*) FROM sessions WHERE forwarded=0").fetchone()[0]
    return {"devices":[dict(row) for row in rows],"pending":pending,"updated":int(time.time()*1000)}


class Handler(BaseHTTPRequestHandler):
    server_version = "PersonalDashboardMobile/0.1"

    def log_message(self, fmt, *args):
        pass

    def authorized(self) -> bool:
        return secrets.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token())

    def json(self, status: int, value: object) -> None:
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if not self.authorized():
            self.json(401, {"error": "unauthorized"})
            return
        if self.path == "/mobile/v1/update":
            try:
                manifest,apk = release()
            except (OSError,ValueError,KeyError):
                self.json(503, {"error": "APK is not built"})
                return
            self.json(200, manifest)
        elif self.path == "/mobile/v1/apk":
            try:
                manifest,apk = release()
            except (OSError,ValueError,KeyError):
                self.json(404, {"error": "APK is not built"})
                return
            data = apk.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        elif self.path == "/mobile/v1/state":
            self.json(200,shared_state())
        else:
            self.json(404, {"error": "not found"})

    def do_POST(self):
        if not self.authorized():
            self.json(401, {"error": "unauthorized"})
            return
        if self.path != "/mobile/v1/sync":
            self.json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 4 * 1024 * 1024:
                raise ValueError("request too large")
            payload = json.loads(self.rfile.read(length))
            count = accept(payload)
            self.json(200, {"accepted": count})
        except (ValueError, TypeError, json.JSONDecodeError) as error:
            self.json(400, {"error": str(error)})
        except Exception:
            self.json(500, {"error": "server error"})


if __name__ == "__main__":
    init_db()
    threading.Thread(target=retry_worker,daemon=True).start()
    print(f"Personal Dashboard mobile service listening on {HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
