# -*- coding: utf-8 -*-
"""채널에 올라간 영상 목록을 실제로 확인한다."""
import os
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
yt = build("youtube", "v3", credentials=creds)

ch = yt.channels().list(part="contentDetails,snippet", mine=True).execute()
c = ch["items"][0]
print("CHANNEL", c["id"], c["snippet"]["title"])
up = c["contentDetails"]["relatedPlaylists"]["uploads"]

items = yt.playlistItems().list(part="contentDetails", playlistId=up,
                                maxResults=25).execute().get("items", [])
ids = [i["contentDetails"]["videoId"] for i in items]
print("COUNT", len(ids))
if ids:
    vs = yt.videos().list(part="snippet,status,processingDetails",
                          id=",".join(ids)).execute()
    for v in vs.get("items", []):
        print("-", v["id"],
              "|", v["status"]["privacyStatus"],
              "|", v["status"].get("uploadStatus"),
              "|", (v.get("processingDetails") or {}).get("processingStatus"),
              "|", v["snippet"]["publishedAt"],
              "|", v["snippet"]["title"][:40])
