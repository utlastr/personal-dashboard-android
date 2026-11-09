"""Upload a saved phone-engine snapshot to the paired local PC companion."""
from datetime import datetime
import json
from pathlib import Path
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    snapshot = Path(sys.argv[1]).resolve()
    if snapshot.parent != (ROOT/".local"/"phone-audit").resolve():
        raise ValueError("Use a snapshot from this project's private phone-audit directory")
    device = snapshot.stem.removeprefix("aw-watcher-android_")
    events = json.loads(snapshot.read_text(encoding="utf-8"))
    sessions = []
    for event in events:
        data = event["data"]
        start = round(datetime.fromisoformat(event["timestamp"].replace("Z","+00:00")).timestamp()*1000)
        sessions.append({
            "id": data["session_id"], "start": start,
            "end": start+round(event["duration"]*1000),
            "pkg": data["package"], "app": data["app"],
            "activity": data.get("classname",""),
            "purpose": data["purpose"], "role": data["device_role"],
        })
    sessions.sort(key=lambda row: row["start"])
    token = (ROOT/".local"/"mobile-service"/"pairing-token.txt").read_text().strip()
    count = 0
    for offset in range(0,len(sessions),300):
        batch = sessions[offset:offset+300]
        payload = {"device": device, "name": "PHB110", "role": sessions[-1]["role"], "sessions": batch}
        request = urllib.request.Request(
            "http://127.0.0.1:5668/mobile/v1/sync",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"})
        with urllib.request.urlopen(request,timeout=20) as response:
            accepted=json.load(response)["accepted"]
        if accepted!=len(batch):
            raise ValueError("Server did not acknowledge the full batch")
        count+=accepted
    print(json.dumps({"device":device,"accepted":count,"source":str(snapshot)}))


if __name__ == "__main__":
    main()
