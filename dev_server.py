#!/usr/bin/env python3
"""Static server + overrides API for local Carcassonne editing.

Usage:
  python3 dev_server.py --host 127.0.0.1 --port 8000

API:
  GET  /api/overrides   -> returns JSON content of everrides.json (or default payload)
  POST /api/overrides   -> writes posted JSON payload to everrides.json
"""

from __future__ import annotations

import argparse
import json
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
OVERRIDES_FILE = ROOT / "everrides.json"
DEFAULT_OVERRIDES = {
    "schema": {"coords": "normalized_0_1", "fillRule": "evenodd"},
    "tiles": {},
}


def normalize_overrides_payload(raw):
    out = raw if isinstance(raw, dict) else dict(DEFAULT_OVERRIDES)
    schema = out.get("schema")
    if not isinstance(schema, dict):
        schema = {}
    schema.setdefault("coords", "normalized_0_1")
    schema.setdefault("fillRule", "evenodd")
    out["schema"] = schema

    tiles = out.get("tiles")
    if not isinstance(tiles, dict):
        tiles = {}
    for tile_id, tile_entry in list(tiles.items()):
        if not isinstance(tile_entry, dict):
            tiles[tile_id] = {"features": {}}
            continue
        feats = tile_entry.get("features")
        if not isinstance(feats, dict):
            tile_entry["features"] = {}
    out["tiles"] = tiles
    return out


def load_overrides_file():
    if not OVERRIDES_FILE.exists():
        return dict(DEFAULT_OVERRIDES)
    with OVERRIDES_FILE.open("r", encoding="utf-8") as f:
        payload = json.load(f)
    return normalize_overrides_payload(payload)


def write_overrides_file(payload):
    payload = normalize_overrides_payload(payload)
    with NamedTemporaryFile("w", delete=False, dir=ROOT, encoding="utf-8") as tmp:
        json.dump(payload, tmp, indent=2)
        tmp.write("\n")
        tmp_path = Path(tmp.name)
    tmp_path.replace(OVERRIDES_FILE)


class CarcassonneHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def _write_json(self, payload, status=HTTPStatus.OK):
        body = json.dumps(payload, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/overrides":
            try:
                payload = load_overrides_file()
            except Exception as exc:  # pragma: no cover - defensive runtime path
                self.send_error(HTTPStatus.INTERNAL_SERVER_ERROR, f"Failed to read overrides: {exc}")
                return
            self._write_json(payload)
            return
        return super().do_GET()

    def do_POST(self):
        path = urlparse(self.path).path
        if path != "/api/overrides":
            self.send_error(HTTPStatus.NOT_FOUND, "Not found")
            return

        try:
            raw_len = self.headers.get("Content-Length", "0")
            content_len = int(raw_len)
        except ValueError:
            self.send_error(HTTPStatus.BAD_REQUEST, "Invalid Content-Length")
            return

        body = self.rfile.read(content_len)
        try:
            payload = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            self.send_error(HTTPStatus.BAD_REQUEST, "Invalid JSON payload")
            return

        if not isinstance(payload, dict):
            self.send_error(HTTPStatus.BAD_REQUEST, "Payload must be a JSON object")
            return

        try:
            write_overrides_file(payload)
        except Exception as exc:  # pragma: no cover - defensive runtime path
            self.send_error(HTTPStatus.INTERNAL_SERVER_ERROR, f"Failed to save overrides: {exc}")
            return

        self._write_json({"ok": True, "file": OVERRIDES_FILE.name})


def main():
    parser = argparse.ArgumentParser(description="Serve Carcassonne app with override write API")
    parser.add_argument("--host", default="127.0.0.1", help="Bind host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8000, help="Bind port (default: 8000)")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), CarcassonneHandler)
    print(f"Serving {ROOT} at http://{args.host}:{args.port}")
    print(f"Overrides file: {OVERRIDES_FILE}")
    server.serve_forever()


if __name__ == "__main__":
    main()
