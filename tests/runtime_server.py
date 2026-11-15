"""Isolated Android integration endpoint; never writes to the user's AW server."""
import importlib.util
from http.server import ThreadingHTTPServer
from pathlib import Path

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("service", root / "tools/mobile_service.py")
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)
service.STATE = root / ".local/runtime-service"
service.DB = service.STATE / "mobile.sqlite3"
service.TOKEN_PATH = service.STATE / "pairing-token.txt"
service.STATE.mkdir(parents=True, exist_ok=True)
service.TOKEN_PATH.write_text("runtime-test-token", encoding="ascii")
service.aw_request = lambda *args, **kwargs: []
service.init_db()
ThreadingHTTPServer(("127.0.0.1", 5670), service.Handler).serve_forever()
