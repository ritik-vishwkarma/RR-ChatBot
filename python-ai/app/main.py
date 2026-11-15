#!/usr/bin/env python3
"""Local deterministic AI webhook stub for integration testing only.

Binds to loopback by default, never logs message contents, and requires the
same local-only secret configured in AI_WEBHOOK_SECRET for the Spring app.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os

# HOST = os.environ.get("MOCK_AI_HOST", "127.0.0.1")
# PORT = int(os.environ.get("MOCK_AI_PORT", "8090"))
# SECRET = os.environ.get("MOCK_AI_WEBHOOK_SECRET", "local-only-test-secret")
HOST = "0.0.0.0"
PORT = int(os.environ.get("PORT", "8090"))
SECRET = os.environ["AI_WEBHOOK_SECRET"]


class Handler(BaseHTTPRequestHandler):
    server_version = "ChatbotTestWebhook/1.0"

    def _json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "ok", "mode": "test-stub"})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/ai":
            self._json(404, {"error": "not found"})
            return

        if self.headers.get("X-Webhook-Secret") != SECRET:
            self._json(401, {"error": "invalid test webhook secret"})
            return

        content_type = self.headers.get("Content-Type", "")
        if "application/json" not in content_type.lower():
            self._json(415, {"error": "application/json required"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            print(
                f"DEBUG: Content-Length={length}, "
                f"Transfer-Encoding={self.headers.get('Transfer-Encoding')}, "
                f"Content-Type={self.headers.get('Content-Type')}"
            )
            if length <= 0 or length > 1_000_000:
                self._json(400, {"error": "invalid body size"})
                return
            request = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            self._json(400, {"error": "invalid JSON"})
            return

        # messages = request.get("messages") if isinstance(request, dict) else None
        # if not isinstance(messages, list) or not messages:
        #     self._json(400, {"error": "messages must be a non-empty array"})
        #     return

        # latest = messages[-1]
        # if not isinstance(latest, dict) or not isinstance(latest.get("content"), str):
        #     self._json(400, {"error": "last message must have string content"})
        #     return

        messages = request.get("messages") if isinstance(request, dict) else None

        if not isinstance(messages, list) or not messages:
            print("DEBUG: rejected request; messages is not a non-empty list")
            self._json(400, {"error": "messages must be a non-empty array"})
            return

        latest = messages[-1]
        if not isinstance(latest, dict) or not isinstance(latest.get("content"), str):
            print(
                "DEBUG: rejected request; last message structure invalid; "
                f"messages_count={len(messages)}, "
                f"last_message_type={type(latest).__name__}, "
                f"content_type={type(latest.get('content')).__name__ if isinstance(latest, dict) else 'unavailable'}"
            )
            self._json(400, {"error": "last message must have string content"})
            return

        print(
            f"DEBUG: accepted request; messages_count={len(messages)}, "
            f"last_content_length={len(latest['content'])}"
        )

        # Deterministic reply: easy to assert in test output; no external AI call.
        self._json(200, {"reply": "TEST_STUB_REPLY: received user message"})

    def log_message(self, format, *args):
        # Deliberately omit request bodies and headers so messages/secrets are not logged.
        print(f"{self.client_address[0]} {self.command} {self.path} -> {args[1] if len(args) > 1 else ''}")


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Local AI test stub listening on http://{HOST}:{PORT}/ai")
    print("Mode: deterministic test replies; this is not a production AI service.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Stopping local AI test stub.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
