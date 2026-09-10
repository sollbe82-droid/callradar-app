# -*- coding: utf-8 -*-
"""
유튜브 최초 1회 인증.
브라우저를 자동으로 열지 않고, 인증 URL 을 auth_url.txt 에 써 둔다.
그 URL 을 어느 브라우저에서 열어 승인하면 token.json 이 만들어진다.

  pip install google-auth-oauthlib google-api-python-client
  python yt_auth.py
"""
import os, threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from google_auth_oauthlib.flow import Flow
from googleapiclient.discovery import build

BASE    = r"C:\CallRadar\_video"
SECRET  = os.path.join(BASE, "client_secret.json")
TOKEN   = os.path.join(BASE, "token.json")
URLFILE = os.path.join(BASE, "auth_url.txt")
PORT    = 8910
SCOPES  = ["https://www.googleapis.com/auth/youtube.upload",
           "https://www.googleapis.com/auth/youtube"]

flow = Flow.from_client_secrets_file(SECRET, SCOPES)
flow.redirect_uri = f"http://localhost:{PORT}/"
auth_url, _state = flow.authorization_url(access_type="offline", prompt="consent")

with open(URLFILE, "w", encoding="utf-8") as f:
    f.write(auth_url)

captured = {}


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        captured["path"] = self.path
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write("<h2>인증 완료. 이 창은 닫으셔도 됩니다.</h2>".encode("utf-8"))
        threading.Thread(target=self.server.shutdown, daemon=True).start()

    def log_message(self, *a):
        pass


srv = HTTPServer(("localhost", PORT), H)
srv.serve_forever()          # 승인 콜백이 올 때까지 대기
srv.server_close()

flow.fetch_token(authorization_response=f"http://localhost:{PORT}{captured['path']}")
creds = flow.credentials

with open(TOKEN, "w", encoding="utf-8") as f:
    f.write(creds.to_json())

with open(os.path.join(BASE, "auth_result.txt"), "w", encoding="utf-8") as f:
    f.write("TOKEN_SAVED %s\n" % TOKEN)
    f.write("HAS_REFRESH %s\n" % bool(creds.refresh_token))
    # 어떤 채널에 붙었는지 확인 — 채널을 잘못 고르면 엉뚱한 데 올라간다
    yt = build("youtube", "v3", credentials=creds)
    r = yt.channels().list(part="snippet", mine=True).execute()
    for it in r.get("items", []):
        f.write("CHANNEL %s %s\n" % (it["id"], it["snippet"]["title"]))
