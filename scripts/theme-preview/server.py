#!/usr/bin/env python3
"""Serve the Camiguin Market CSS theme preview locally.

Run::

    python3 scripts/theme-preview/server.py
"""

import base64
import http.server
import io
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CSS = ROOT / "app/src/main/assets/camiguin-theme.css"
ISLAND = ROOT / "app/src/main/assets/camiguin-island.jpg"


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        # Serve from project root so the page can load the CSS file by its real path.
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def end_headers(self):
        # Batteries-included for the preview page only.
        if self.path in ("/preview.html", "/", "/index.html"):
            self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_GET(self):
        if self.path in ("/preview.html", "/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(_preview_html().encode("utf-8"))
            return

        if self.path == "/app/src/main/assets/camiguin-island.jpg":
            if ISLAND.exists():
                self._serve_file(ISLAND, "image/jpeg")
                return
            self._serve_fallback_island()
            return

        super().do_GET()

    def _serve_file(self, path, content_type):
        data = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _serve_fallback_island(self):
        data = base64.b64decode(_FALLBACK_ISLAND_PNG)
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)


def _preview_html():
    return """<!DOCTYPE html>
<html lang="en" data-theme="light">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>Camiguin Market — Theme Preview</title>
  <link rel="stylesheet" href="app/src/main/assets/camiguin-theme.css" />
  <style>
    /* Sparse page chrome so the app theme is the star. */
    .preview-shell { max-width: 480px; margin: 0 auto; }
    .demo-body { display: flex; flex-direction: column; min-height: 100vh; }
    .demo-card-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .demo-nav { padding-bottom: 64px; }
    .demo-header { padding-bottom: 12px; }
    .demo-footer { padding: 12px; border-top: 1px solid var(--border-color); }
    .demo-spacer { flex: 1; }
    .demo-note { font-size: 12px; color: var(--text-secondary); }
    .offline-badged { border-left: 3px solid var(--status-danger); padding-left: 8px; }
  </style>
</head>
<body class="demo-body">
  <div class="preview-shell">

    <div class="hero-island-screen demo-header">
      <div>
        <div class="app-header">
          <span>Camiguin Market</span>
          <span>🛒</span>
        </div>
        <div class="search-bar" style="margin-top: 12px;">
          <span>🔍</span>
          <input type="text" placeholder="Search a place or seller…" />
        </div>
      </div>
      <div class="demo-spacer"></div>
      <div style="font-size: 12px; max-width: 320px; text-align: right;">
        Shop Local. Support Camiguin.
      </div>
    </div>

    <div class="card" style="margin: 12px;">
      <div class="demo-note">Featured Products</div>
      <div class="demo-card-grid" style="margin-top: 8px;">
        <div class="card">
          <div style="font-weight: 600;">Fresh Catch 🐟</div>
          <div class="badge-pending" style="margin-top: 6px;">Pending</div>
          <div style="font-size: 14px; color: var(--text-secondary);">Mambajao • ₱180</div>
        </div>
        <div class="card">
          <div style="font-weight: 600;">Coconut Oil</div>
          <div class="badge-confirmed" style="margin-top: 6px;">Confirmed</div>
          <div style="font-size: 14px; color: var(--text-secondary);">Guinsiliban • ₱250</div>
        </div>
      </div>
    </div>

    <div class="demo-spacer"></div>

    <nav class="bottom-nav demo-nav">
      <a class="nav-item active" href="#">Home</a>
      <a class="nav-item" href="#">Categories</a>
      <a class="nav-item" href="#">Sell</a>
      <a class="nav-item" href="#">Cart</a>
      <a class="nav-item" href="#">Profile</a>
    </nav>

    <div class="demo-footer offline-badged">
      <button class="btn-secondary" id="toggleTheme" style="margin-bottom: 8px;">
        Switch to dark mode
      </button>
      <div class="demo-note">
        Island photo: camiguin-island.jpg<br />
        Source: https://gcdnb.pbrd.co/images/gxu9zmcm33yO.jpg<br />
        Offline fallback is built into the preview server and the CSS.
      </div>
    </div>
  </div>

  <script>
    const btn = document.getElementById('toggleTheme');
    btn.addEventListener('click', () => {
      const html = document.documentElement;
      const nextDark = html.getAttribute('data-theme') !== 'dark';
      html.setAttribute('data-theme', nextDark ? 'dark' : 'light');
      btn.textContent = nextDark
        ? 'Switch to light mode'
        : 'Switch to dark mode';
    });
  </script>
</body>
</html>
"""


# Tiny blue gradient placeholder for the island hero when the real photo is missing.
# Generated once so the preview still looks like Camiguin Market even fully offline.
_FALLBACK_ISLAND_PNG = (
    "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAIklEQVQIW2NkYPj/n4EBCxgBAOD"
    "UB/6j+oE3mQE4w0g5V3scAAAAASUVORK5CYII="
)


def main():
    port = 8042
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            print(f"Usage: {sys.argv[0]} [port]", file=sys.stderr)
            sys.exit(2)

    if not CSS.exists():
        print(f"Missing: {CSS}", file=sys.stderr)
        sys.exit(1)

    print(f"Serving preview at http://127.0.0.1:{port}/preview.html")
    print(f"Theme file: {CSS}")
    print(f"Sample island photo: {ISLAND}")
    http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
