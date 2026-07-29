#!/usr/bin/env python3
"""
Sign LightNews in from a computer, when the phone can't.

The Light Phone III browser is not a browser as far as most OAuth libraries are
concerned, and consent has to happen in one. So do it here instead: this runs the whole
authorization-code flow against a loopback redirect, exchanges the code for a refresh
token, and draws a QR carrying the result. Scan it in Newsletters -> Settings ->
Client ID -> SCAN QR and the app is signed in without ever opening a page.

The refresh token in that QR grants read and write access to the mailbox until revoked.
Treat the code on screen the way you'd treat a password: don't photograph it, don't leave
it up, and close the tab when the phone has it.

Needs a **Desktop app** OAuth client, not the Android one — a loopback redirect is the
only kind Google accepts from a script, and Android clients can't use it. Desktop clients
come with a "secret" that Google fully expects to ship inside installed apps, so it is
not confidential and the app stores it alongside the token.

    python3 scripts/authorize.py                       # prompts for id and secret
    python3 scripts/authorize.py ~/Downloads/client_secret_*.json

Stdlib only. Nothing is uploaded anywhere except Google's token endpoint.
"""

from __future__ import annotations

import base64
import glob
import hashlib
import http.server
import json
import os
import secrets
import sys
import tempfile
import threading
import urllib.parse
import urllib.request
import webbrowser

AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
SCOPES = "openid email https://www.googleapis.com/auth/gmail.modify"


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def load_client(argv: list[str]) -> tuple[str, str]:
    """From a downloaded client_secret JSON if given, otherwise ask."""
    path = None
    if len(argv) > 1:
        matches = glob.glob(os.path.expanduser(argv[1]))
        if not matches:
            sys.exit(f"no such file: {argv[1]}")
        path = matches[0]

    if path:
        with open(path) as handle:
            blob = json.load(handle)
        node = blob.get("installed") or blob.get("web") or blob
        client_id = node.get("client_id", "").strip()
        client_secret = node.get("client_secret", "").strip()
        if not client_id:
            sys.exit(f"{path} has no client_id — is it an OAuth client file?")
        return client_id, client_secret

    print("Google Cloud -> Clients -> Create client -> Desktop app, then paste it here.")
    client_id = input("Client ID:     ").strip()
    client_secret = input("Client secret: ").strip()
    if not client_id.endswith(".apps.googleusercontent.com"):
        sys.exit("that doesn't look like a Google client ID")
    return client_id, client_secret


class CodeCatcher(http.server.BaseHTTPRequestHandler):
    """One request, one code. Everything else gets a flat 404."""

    code: str | None = None
    state: str | None = None
    error: str | None = None

    def do_GET(self) -> None:  # noqa: N802 - name fixed by the stdlib
        query = urllib.parse.urlparse(self.path).query
        params = urllib.parse.parse_qs(query)
        if "code" not in params and "error" not in params:
            self.send_response(404)
            self.end_headers()
            return

        CodeCatcher.code = params.get("code", [None])[0]
        CodeCatcher.state = params.get("state", [None])[0]
        CodeCatcher.error = params.get("error", [None])[0]

        body = (
            b"<!doctype html><meta charset=utf-8>"
            b"<body style='background:#0b0b0c;color:#f2f2f2;font:16px -apple-system,sans-serif;"
            b"display:grid;place-items:center;height:100vh;margin:0'>"
            b"<p>Done. Go back to the terminal.</p>"
        )
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args: object) -> None:
        """Silence the default stderr access log."""


def consent(client_id: str, client_secret: str) -> dict:
    verifier = b64url(secrets.token_bytes(64))
    challenge = b64url(hashlib.sha256(verifier.encode("ascii")).digest())
    state = b64url(secrets.token_bytes(16))

    # Port 0 lets the OS pick; Google accepts any loopback port for a Desktop client.
    server = http.server.HTTPServer(("127.0.0.1", 0), CodeCatcher)
    redirect_uri = f"http://127.0.0.1:{server.server_port}/"

    url = AUTH_ENDPOINT + "?" + urllib.parse.urlencode(
        {
            "client_id": client_id,
            "redirect_uri": redirect_uri,
            "response_type": "code",
            "scope": SCOPES,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "state": state,
            # offline for a refresh token; consent because Google issues one on the first
            # grant only, and a silent re-auth would come back unable to renew itself.
            "access_type": "offline",
            "prompt": "consent",
        }
    )

    print("\nOpening the consent screen. Expect an 'unverified app' warning — that's the")
    print("restricted Gmail scope on an unverified project. Continue past it.\n")
    threading.Thread(target=webbrowser.open, args=(url,), daemon=True).start()
    print(f"If nothing opened, paste this into a browser:\n\n{url}\n")

    server.handle_request()
    server.server_close()

    if CodeCatcher.error:
        sys.exit(f"Google said: {CodeCatcher.error}")
    if not CodeCatcher.code:
        sys.exit("no authorization code came back")
    if CodeCatcher.state != state:
        sys.exit("state mismatch — something else answered the redirect, so stopping")

    form = urllib.parse.urlencode(
        {
            "grant_type": "authorization_code",
            "code": CodeCatcher.code,
            "client_id": client_id,
            "client_secret": client_secret,
            "redirect_uri": redirect_uri,
            "code_verifier": verifier,
        }
    ).encode("ascii")

    try:
        with urllib.request.urlopen(TOKEN_ENDPOINT, form) as response:
            return json.load(response)
    except urllib.error.HTTPError as err:
        sys.exit(f"token exchange failed: {err.read().decode('utf-8', 'replace')[:300]}")


def email_of(id_token: str) -> str:
    """The email claim, for display. Google issued this over TLS a second ago."""
    try:
        payload = id_token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload)).get("email", "")
    except Exception:  # noqa: BLE001 - a missing claim must not fail the whole run
        return ""


def show_qr(payload: str) -> None:
    """
    A local page that renders the QR, rather than a pip install.

    The same client-side library the docs page uses, so this needs nothing but a browser
    that is already open. The file lands in a temp directory; it holds a live credential,
    so delete it when the phone has scanned it.
    """
    html = """<!doctype html>
<html><head><meta charset="utf-8"><title>LightNews credentials</title>
<style>
  body { margin:0; min-height:100vh; display:grid; place-items:center; background:#0b0b0c;
         color:#f2f2f2; font:16px/1.5 -apple-system,BlinkMacSystemFont,sans-serif; padding:24px; }
  .card { text-align:center; max-width:420px; }
  h1 { font-size:20px; margin:0 0 4px; }
  p { color:#9a9a9d; font-size:14px; margin:0 0 20px; }
  .qrbox { background:#fff; padding:16px; border-radius:16px; display:inline-block; }
  .warn { margin-top:18px; font-size:12px; color:#c9a227; }
</style></head>
<body><div class="card">
  <h1>Scan this in Newsletters</h1>
  <p>Settings &rarr; Client ID &rarr; SCAN QR</p>
  <div class="qrbox" id="qrbox"></div>
  <p class="warn">This code contains a live refresh token for __EMAIL__.<br>
     Close this tab once the phone has it, and delete<br><code>__PATH__</code></p>
</div>
<script src="https://cdn.jsdelivr.net/npm/davidshimjs-qrcodejs@0.0.2/qrcode.min.js"></script>
<script>
  var payload = __PAYLOAD__;
  if (typeof QRCode === 'undefined') {
    document.getElementById('qrbox').textContent = 'QR library failed to load.';
  } else {
    new QRCode(document.getElementById('qrbox'),
               { text: payload, width: 360, height: 360, correctLevel: QRCode.CorrectLevel.M });
  }
</script>
</body></html>
"""
    handle, path = tempfile.mkstemp(prefix="lightnews-", suffix=".html")
    os.close(handle)
    filled = (
        html.replace("__PAYLOAD__", json.dumps(payload))
        .replace("__EMAIL__", json.loads(payload).get("email") or "your account")
        .replace("__PATH__", path)
    )
    with open(path, "w") as out:
        out.write(filled)
    os.chmod(path, 0o600)
    print(f"QR page: {path}")
    webbrowser.open("file://" + path)


def main() -> None:
    client_id, client_secret = load_client(sys.argv)
    tokens = consent(client_id, client_secret)

    refresh = tokens.get("refresh_token")
    if not refresh:
        sys.exit(
            "Google returned no refresh token. That happens when consent was granted "
            "before without access_type=offline — revoke the app at "
            "https://myaccount.google.com/permissions and run this again."
        )

    payload = json.dumps(
        {
            "client_id": client_id,
            "client_secret": client_secret,
            "refresh_token": refresh,
            "email": email_of(tokens.get("id_token", "")),
        },
        separators=(",", ":"),
    )

    print("\nSigned in. Scan the QR that just opened, in Newsletters -> Settings -> Client ID.")
    print("If you'd rather paste it, the payload is:\n")
    print(payload + "\n")
    show_qr(payload)


if __name__ == "__main__":
    main()
