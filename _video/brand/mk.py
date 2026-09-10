from PIL import Image, ImageDraw, ImageFont, ImageFilter
import math

BOLD="/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
REG ="/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
def f(p,s): return ImageFont.truetype(p,s,index=2)   # index 2 = KR

NAVY=(13,27,42); NAVY2=(24,44,62)
TEAL=(20,120,110); TEAL2=(45,212,191); YEL=(250,204,21); WHITE=(237,244,250)

# ---------- profile 800x800 : app icon upscaled ----------
ic = Image.open('/sessions/awesome-beautiful-galileo/mnt/CallRadar/app/src/main/ic_launcher-playstore.png').convert('RGB')
ic.resize((800,800), Image.LANCZOS).save('profile.png')

# ---------- banner 2048x1152 ----------
W,H=2048,1152
im=Image.new('RGB',(W,H),NAVY)
d=ImageDraw.Draw(im)
# vertical gradient
for y in range(H):
    t=y/H
    c=(int(13+(24-13)*t), int(27+(44-27)*t), int(42+(62-42)*t))
    d.line([(0,y),(W,y)],fill=c)

# radar rings, centered on the safe-area right side
cx,cy=1560,576
ring=Image.new('RGB',(W,H),(0,0,0))
rd=ImageDraw.Draw(ring)
for r in range(160,1500,175):
    rd.ellipse([cx-r,cy-r,cx+r,cy+r],outline=TEAL,width=5)
ring=ring.filter(ImageFilter.GaussianBlur(1.2))
im=Image.blend(im,Image.blend(im,ring,0.0),0.0)  # noop keeps type
im=Image.composite(Image.blend(im,ring,0.55), im, ring.convert('L').point(lambda v:255 if v>8 else 0))
d=ImageDraw.Draw(im)

# sweep beam
for i in range(150):
    a=math.radians(-32 - i*0.10)
    L=1400
    d.line([(cx,cy),(cx+L*math.cos(a),cy+L*math.sin(a))],
           fill=(int(45*(1-i/150)+13), int(212*(1-i/150)*0.55+27), int(191*(1-i/150)*0.55+42)), width=3)
d.line([(cx,cy),(cx+1400*math.cos(math.radians(-32)),cy+1400*math.sin(math.radians(-32)))],fill=TEAL2,width=7)

# blips
for (bx,by,r) in [(1290,250,16),(1960,820,13),(1500,960,11),(1790,200,10),(1660,690,12)]:
    d.ellipse([bx-r,by-r,bx+r,by+r],fill=YEL)

# --- safe area text (center 1235x338) ---
x0=430; base=420
d.text((x0,base), "콜레이더", font=f(BOLD,175), fill=WHITE)
d.text((x0+8,base+250), "택시 기사 데이터 브리핑", font=f(BOLD,70), fill=YEL)
d.text((x0+10,base+372), "매일 저녁  ·  공항 대기  ·  야구 파장  ·  행사  ·  실제 운행 기록",
       font=f(REG,44), fill=(150,180,196))

im.save('banner.png', quality=95)
print("ok", im.size)
