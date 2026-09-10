# -*- coding: utf-8 -*-
"""lines.json -> s01.png .. (1080x1920 세로)"""
import os, sys, json, math
from PIL import Image, ImageDraw, ImageFont

BOLD = r"C:\Windows\Fonts\malgunbd.ttf"
REG  = r"C:\Windows\Fonts\malgun.ttf"
W, H = 1080, 1920
NAVY = (13, 27, 42)
TEAL = (20, 140, 126)
YEL  = (250, 204, 21)
WHITE = (240, 246, 251)
GREY = (150, 178, 196)


def F(p, s):
    return ImageFont.truetype(p, s)


def base():
    im = Image.new("RGB", (W, H), NAVY)
    d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=(int(13 + 11 * t), int(27 + 17 * t), int(42 + 20 * t)))
    cx, cy = 1000, 260
    for r in range(180, 1000, 150):
        d.arc([cx - r, cy - r, cx + r, cy + r], 90, 200, fill=TEAL, width=3)
    return im, d


def wrap(d, txt, font, maxw):
    out, line = [], ""
    for ch in txt:
        if ch == "\n":
            out.append(line); line = ""; continue
        t = line + ch
        if d.textlength(t, font=font) > maxw and line:
            out.append(line); line = ch
        else:
            line = t
    if line:
        out.append(line)
    return out


def slide(path, kicker, big, sub, accent=YEL, bigsize=130):
    im, d = base()
    y = 560
    if kicker:
        d.text((90, y), kicker, font=F(BOLD, 50), fill=GREY)
        y += 110
    f = F(BOLD, bigsize)
    for ln in wrap(d, big, f, W - 180):
        d.text((90, y), ln, font=f, fill=accent)
        y += int(bigsize * 1.22)
    if sub:
        y += 50
        fs = F(REG, 52)
        for ln in wrap(d, sub, fs, W - 180):
            d.text((90, y), ln, font=fs, fill=WHITE)
            y += 76
    # 쇼츠는 아래 약 320px 를 UI 가 덮는다. 그 위로 올린다.
    d.text((90, H - 430), "콜레이더  ·  택시 기사 데이터 브리핑", font=F(REG, 38),
           fill=(110, 140, 158))
    d.ellipse([W - 260, H - 442, W - 224, H - 406], fill=YEL)
    im.save(path)


def main():
    d = sys.argv[1]
    lines = json.load(open(os.path.join(d, "lines.json"), encoding="utf-8"))
    for i, L in enumerate(lines, 1):
        slide(os.path.join(d, "s%02d.png" % i), L.get("kicker", ""), L["big"],
              L.get("sub", ""), tuple(L.get("accent", YEL)), L.get("size", 130))
    print("SLIDES", len(lines))


if __name__ == "__main__":
    main()
