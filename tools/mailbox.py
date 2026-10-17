"""Loopback-only preview of development mail; contains temporary sign-in secrets."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import html
import tempfile

MAILBOX = Path(tempfile.gettempdir()) / "techedin-mailbox"


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def do_GET(self):
        if self.headers.get('Host') not in {'127.0.0.1:8025', 'localhost:8025'}:
            self.send_error(403)
            return
        if self.path != "/":
            self.send_error(404)
            return
        messages = sorted(MAILBOX.glob("*.eml"), key=lambda p: p.stat().st_mtime, reverse=True)[:100]
        content = "<!doctype html><meta charset=utf-8><title>Techedin — e-mails locais</title><h1>E-mails de teste</h1><p>Disponíveis apenas neste computador. Atualize a página após solicitar um link.</p>"
        content += "".join("<hr><pre style='white-space:pre-wrap'>" + html.escape(p.read_text(encoding="utf-8")) + "</pre>" for p in messages)
        content += "<p>Nenhum e-mail recebido.</p>" if not messages else ""
        data = content.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print("Caixa local: http://127.0.0.1:8025 — não publica nem envia e-mails reais.")
    ThreadingHTTPServer(("127.0.0.1", 8025), Handler).serve_forever()
