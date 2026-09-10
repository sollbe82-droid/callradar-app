# -*- coding: utf-8 -*-
"""
유튜브 업로드.  python yt_upload.py <폴더>
폴더 안에 final.mp4 와 meta.json 이 있어야 한다.

meta.json 예:
{ "title": "...", "description": "...", "tags": ["..."],
  "categoryId": "22", "privacyStatus": "private" }
"""
import os, sys, json
from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

BASE  = r"C:\CallRadar\_video"
TOKEN = os.path.join(BASE, "token.json")
SCOPES = ["https://www.googleapis.com/auth/youtube.upload",
          "https://www.googleapis.com/auth/youtube"]

DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BASE, "short_20260908")
VIDEO = os.path.join(DIR, "final.mp4")
META  = json.load(open(os.path.join(DIR, "meta.json"), encoding="utf-8"))

creds = Credentials.from_authorized_user_file(TOKEN, SCOPES)
if not creds.valid:
    creds.refresh(Request())
    open(TOKEN, "w", encoding="utf-8").write(creds.to_json())

yt = build("youtube", "v3", credentials=creds)

body = {
    "snippet": {
        "title": META["title"][:100],
        "description": META["description"][:5000],
        "tags": META.get("tags", []),
        "categoryId": META.get("categoryId", "22"),
        "defaultLanguage": "ko",
        "defaultAudioLanguage": "ko",
    },
    "status": {
        # ★ 처음 2주는 반드시 private. 사람이 한 번 보고 공개로 바꾼다.
        "privacyStatus": META.get("privacyStatus", "private"),
        "selfDeclaredMadeForKids": False,
    },
}

media = MediaFileUpload(VIDEO, chunksize=4 * 1024 * 1024, resumable=True,
                        mimetype="video/mp4")
req = yt.videos().insert(part="snippet,status", body=body, media_body=media)

resp = None
while resp is None:
    status, resp = req.next_chunk()
    if status:
        print("UPLOAD %d%%" % int(status.progress() * 100))

vid = resp["id"]
print("VIDEO_ID", vid)
print("URL https://www.youtube.com/watch?v=" + vid)
print("STUDIO https://studio.youtube.com/video/%s/edit" % vid)

# 업로드 후 실제 상태를 되읽는다 — 미감사 프로젝트는 강제로 비공개 잠금이 걸릴 수 있다.
chk = yt.videos().list(part="status,processingDetails", id=vid).execute()
for it in chk.get("items", []):
    st = it["status"]
    print("PRIVACY", st.get("privacyStatus"),
          "UPLOAD_STATUS", st.get("uploadStatus"),
          "REJECT", st.get("rejectionReason"),
          "FAILURE", st.get("failureReason"))
