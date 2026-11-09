import importlib.util
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

spec = importlib.util.spec_from_file_location("service", Path(__file__).parents[1]/"tools/mobile_service.py")
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        service.STATE = Path(self.temp.name)
        service.DB = service.STATE/"test.sqlite"
        service.TOKEN_PATH = service.STATE/"token"
        service.init_db()
        self.payload = {
            "device": "android-test1", "name": "Test phone", "role": "personal",
            "sessions": [{"id": "app:1000", "start": 1000, "end": 4000,
                          "pkg": "app", "app": "App", "purpose": "work", "role": "work"}]}

    def tearDown(self):
        self.temp.cleanup()

    def count(self, clause=""):
        with closing(sqlite3.connect(service.DB)) as db:
            return db.execute("SELECT COUNT(*) FROM sessions "+clause).fetchone()[0]

    def test_duplicate_and_two_devices(self):
        self.assertEqual(service.accept(self.payload), 1)
        service.accept(self.payload)
        self.assertEqual(self.count(), 1)
        self.payload["device"] = "android-test2"
        service.accept(self.payload)
        self.assertEqual(self.count(), 2)
        with closing(sqlite3.connect(service.DB)) as db:
            self.assertEqual(db.execute("SELECT role FROM sessions LIMIT 1").fetchone()[0], "work")

    def test_batch_atomicity(self):
        self.payload["sessions"].append({"id": "bad"})
        with self.assertRaises(ValueError):
            service.accept(self.payload)
        self.assertEqual(self.count(), 0)

    def test_failed_forward_retries(self):
        service.accept(self.payload)
        with patch.object(service, "mirror_event", side_effect=OSError("offline")):
            service.forward_pending()
        self.assertEqual(self.count("WHERE forwarded=0"), 1)
        with patch.object(service, "mirror_event") as mirror:
            service.forward_pending()
            service.forward_pending()
            self.assertEqual(mirror.call_count, 1)
        self.assertEqual(self.count("WHERE forwarded=0"), 0)

    def test_aw_protocol_and_timeout_deduplication(self):
        service.accept(self.payload)
        with closing(sqlite3.connect(service.DB)) as db:
            db.row_factory = sqlite3.Row
            row = dict(db.execute("SELECT * FROM sessions").fetchone())
        with patch.object(service, "aw_request", side_effect=[{}, {}, [], None]) as request:
            service.mirror_event(row)
            calls = request.call_args_list
            self.assertEqual(calls[1].args[1], "/api/0/buckets/aw-watcher-android_android-test1")
            self.assertEqual(calls[-1].args[0], "POST")
            self.assertEqual(calls[-1].args[2][0]["duration"], 3)
        with patch.object(service, "aw_request", side_effect=[
                {"aw-watcher-android_android-test1": {}},
                [{"data": {"session_id": row["id"]}}]]) as request:
            service.mirror_event(row)
            self.assertEqual(request.call_count, 2)

    def test_authentication_and_real_http(self):
        server = service.ThreadingHTTPServer(("127.0.0.1", 0), service.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        root = "http://127.0.0.1:"+str(server.server_port)
        try:
            with self.assertRaises(urllib.error.HTTPError) as denied:
                urllib.request.urlopen(root+"/mobile/v1/state")
            self.assertEqual(denied.exception.code, 401)
            request = urllib.request.Request(root+"/mobile/v1/sync",
                data=json.dumps(self.payload).encode(), headers={
                    "Authorization": "Bearer "+service.token(), "Content-Type": "application/json"})
            with urllib.request.urlopen(request) as response:
                self.assertEqual(json.load(response)["accepted"], 1)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()

    def test_release_integrity(self):
        manifest, path = service.release()
        self.assertEqual(manifest["packageName"], "local.personaldashboard.mobile")
        self.assertGreater(path.stat().st_size, 1000000)

    def test_daily_summary_multiple_sessions_and_midnight(self):
        midnight = int(__import__("datetime").datetime.now().replace(
            hour=0, minute=0, second=0, microsecond=0).timestamp()*1000)
        self.payload["sessions"] = [
            {"id": "one", "start": midnight+1000, "end": midnight+61000,
             "pkg": "app", "app": "App", "purpose": "personal"},
            {"id": "two", "start": midnight+120000, "end": midnight+180000,
             "pkg": "app", "app": "App", "purpose": "personal"},
            {"id": "cross", "start": midnight-60000, "end": midnight+30000,
             "pkg": "work", "app": "Work", "purpose": "work"},
            {"id": "old", "start": midnight-120000, "end": midnight-60000,
             "pkg": "app", "app": "App", "purpose": "personal"},
        ]
        service.accept(self.payload)
        devices=service.shared_state()["devices"]
        self.assertEqual(len(devices),1)
        self.assertEqual(devices[0]["ms"],150000)
        self.assertNotIn("purpose",devices[0])

    def test_device_role_changes_without_rewriting_or_reuploading_sessions(self):
        service.accept(self.payload)
        self.payload.update(role="work",name="Work phone",sessions=[])
        service.accept(self.payload)
        devices=service.shared_state()["devices"]
        self.assertEqual(len(devices),1)
        self.assertEqual((devices[0]["role"],devices[0]["name"]),("work","Work phone"))
        self.assertEqual(self.count(),1)
        with patch.object(service,"aw_request",side_effect=[{},None,None]) as request:
            service.forward_devices()
            event=request.call_args_list[-1].args[2][0]
            self.assertEqual(event["data"],{"device":"android-test1","name":"Work phone","role":"work"})
        with patch.object(service,"aw_request") as request:
            service.forward_devices()
            request.assert_not_called()

    def test_empty_phone_registered_and_two_roles_stay_separate(self):
        self.payload["sessions"]=[]
        service.accept(self.payload)
        self.payload.update(device="android-work",role="work",name="Work")
        service.accept(self.payload)
        self.assertEqual({row["role"] for row in service.shared_state()["devices"]},{"work","personal"})
        self.assertEqual([row["ms"] for row in service.shared_state()["devices"]],[0,0])


if __name__ == "__main__":
    unittest.main()
