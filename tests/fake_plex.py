"""Fake Plex Media Server for testing the launcher's Continue watching row.

Local only. Serves the two endpoints the launcher calls, with made-up data:
  GET /hubs/continueWatching?X-Plex-Token=...   -> JSON MediaContainer with 3 items
  GET /library/fake/thumb{1,2,3}.jpg             -> generated PNG (served as image/png)

Default port 32499 (not the real Plex port 32400, so it cannot be confused with a real server).
The emulator reaches this PC at 10.0.2.2:32499.

Usage:
  python -I fake_plex.py [port]
Stop with Ctrl+C. Any token value is accepted; it is not checked.
"""
import json
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

HOST = "127.0.0.1"
DEFAULT_PORT = 32499

ITEMS = [
    {"title": "Fake Episode One", "grandparentTitle": "Fake Show Alpha", "type": "episode",
     "thumb": "/library/fake/thumb1.jpg"},
    {"title": "The Second Fake Ep", "grandparentTitle": "Fake Show Beta", "type": "episode",
     "thumb": "/library/fake/thumb2.jpg"},
    {"title": "Fake Movie Three", "grandparentTitle": None, "type": "movie",
     "thumb": "/library/fake/thumb3.jpg"},
]

COLOURS = [(200, 60, 60), (60, 140, 200), (80, 180, 90)]


def make_png(rgb, w=320, h=180):
    """A solid-colour PNG with a lighter band, built by hand (no PIL needed)."""
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter type: none
        band = (y > h // 3) and (y < 2 * h // 3)
        r, g, b = rgb
        if band:
            r, g, b = min(255, r + 60), min(255, g + 60), min(255, b + 60)
        raw.extend(bytes((r, g, b)) * w)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)  # 8-bit RGB
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


THUMBS = {"/library/fake/thumb%d.jpg" % (i + 1): make_png(c) for i, c in enumerate(COLOURS)}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        # log the path only; never the query string (it carries the token)
        sys.stdout.write("%s %s\n" % (self.command, urlparse(self.path).path))

    def _send(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/hubs/continueWatching":
            body = json.dumps({"MediaContainer": {"size": len(ITEMS), "Metadata": ITEMS}}).encode("utf-8")
            self._send(200, "application/json", body)
        elif path in THUMBS:
            self._send(200, "image/png", THUMBS[path])
        else:
            self._send(404, "text/plain", b"not found")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    server = HTTPServer((HOST, port), Handler)
    print("fake plex on http://%s:%d (emulator: http://10.0.2.2:%d)" % (HOST, port, port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
