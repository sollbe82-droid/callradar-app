# -*- coding: utf-8 -*-
"""
미감사 프로젝트의 '비공개 잠금' 여부를 확인한다.
private -> unlisted 로 바꿔 보고, 되면 다시 private 로 되돌린다.
(공개(public)로는 절대 바꾸지 않는다.)
  python yt_privacy_test.py <videoId>
"""
import os, sys
from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request
from googleapiclient.discovery import build

BASE  = r"C:\CallRadar\_video"
TOKEN = os.path.join(BASE, "token.json")
SCOPES = ["https://www.googleapis.com/auth/youtube.upload",
          "https://www.googleapis.com/auth/youtube"]
VID = sys.argv[1]

creds = Credentials.from_authorized_user_file(TOKEN, SCOPES)
if not creds.valid:
    creds.refresh(Request())
    open(TOKEN, "w", encoding="utf-8").write(creds.to_json())
yt = build("youtube", "v3", credentials=creds)


def cur():
    r = yt.videos().list(part="status", id=VID).execute()
    return r["items"][0]["status"].get("privacyStatus")


print("BEFORE", cur())
try:
    yt.videos().update(part="status",
                       body={"id": VID, "status": {"privacyStatus": "unlisted",
                                                   "selfDeclaredMadeForKids": False}}).execute()
    print("AFTER_SET_UNLISTED", cur())
except Exception as e:
    print("CHANGE_FAILED", type(e).__name__, str(e)[:200])

try:
    yt.videos().update(part="status",
                       body={"id": VID, "status": {"privacyStatus": "private",
                                                   "selfDeclaredMadeForKids": False}}).execute()
    print("RESTORED", cur())
except Exception as e:
    print("RESTORE_FAILED", type(e).__name__, str(e)[:200])
