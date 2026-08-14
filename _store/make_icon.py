from PIL import Image, ImageDraw
import math

SS = 1024          # supersample render size
OUT = 512
s = SS / 108.0     # scale from 108-viewport

def S(v): return v * s

navy = (10, 14, 26, 255)     # #0A0E1A
amber = (245, 158, 11)       # #F59E0B
green = (16, 185, 129)       # #10B981

img = Image.new("RGBA", (SS, SS), navy)
draw = ImageDraw.Draw(img)

def circle_outline(cx, cy, r, color, width, alpha):
    ov = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    bbox = [S(cx - r), S(cy - r), S(cx + r), S(cy + r)]
    od.ellipse(bbox, outline=color + (int(255 * alpha),), width=max(1, int(S(width))))
    img.alpha_composite(ov)

def dot(cx, cy, r, color, alpha=1.0):
    ov = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    bbox = [S(cx - r), S(cy - r), S(cx + r), S(cy + r)]
    od.ellipse(bbox, fill=color + (int(255 * alpha),))
    img.alpha_composite(ov)

# radar rings
circle_outline(54, 54, 34, amber, 2, 1.0)
circle_outline(54, 54, 24, amber, 1.5, 0.6)
circle_outline(54, 54, 14, amber, 1.0, 0.3)

# sweep line 54,54 -> 75,33
draw.line([S(54), S(54), S(75), S(33)], fill=amber + (255,), width=max(1, int(S(2))))

# center dot
dot(54, 54, 3, amber)
# green call dot
dot(72, 36, 3, green)
# faint amber dot
dot(45, 65, 2, amber, 0.5)

# "C" arc: ellipse center (62,88) rx10 ry8, gap on right
bbox = [S(62 - 10), S(88 - 8), S(62 + 10), S(88 + 8)]
draw.arc(bbox, 45, 315, fill=amber + (255,), width=max(1, int(S(2.5))))

out = img.convert("RGB").resize((OUT, OUT), Image.LANCZOS)
out.save(r"C:\CallRadar\_store\onestore_icon_512.png")
print("SAVED 512 icon")
