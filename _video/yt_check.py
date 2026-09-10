# -*- coding: utf-8 -*-
"""token.json 이 살아 있는지, 어느 채널에 붙었는지 확인."""
import os, json
from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request
from googleapiclient.discovery import build

BASE  = r"C:\CallRadar\_video"
TOKEN = os.path.join(BASE, "token.json")
SCOPES = ["https://www.googleapis.com/auth/youtube.upload",
          "https://www.googleapis.com/auth/youtube"]

creds = Credentials.from_authorized_user_file(TOKEN, SCOPES)
if not creds.valid:
    creds.refresh(Request())
    open(TOKEN, "w", encoding="utf-8").write(creds.to_json())
    print("REFRESHED")

yt = build("youtube", "v3", credentials=creds)
r = yt.channels().list(part="snippet,statistics", mine=True).execute()
for it in r.get("items", []):
    print("CHANNEL", it["id"], it["snippet"]["title"],
          it.get("statistics", {}).get("subscriberCount"))
print("HAS_REFRESH", bool(creds.refresh_token))
