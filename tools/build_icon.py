"""WFR Dash Bridge launcher icon: the Wayfindr foreground with the arrow recoloured orange and an
orange BRIDGE band (option B). Writes a preview and, with a res dir, the adaptive icon layers."""
import colorsys
import sys
from PIL import Image, ImageDraw, ImageFont

D = sys.argv[1]
RES = sys.argv[2] if len(sys.argv) > 2 else None
src = Image.open(f"{D}/ic_launcher_foreground.png").convert("RGB")
FONT = "C:/Windows/Fonts/segoeuib.ttf"
ORANGE = (255, 138, 61)
NAVY = src.getpixel((110, 216))
ORANGE_H = 22 / 360


def orange_arrow(im):
    """Shift the blue arrow's hue to the app's orange, keeping its light-to-dark gradient."""
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b = px[x, y]
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if 0.47 < h < 0.72 and s > 0.35 and v > 0.25:
                # cyan top -> light orange, deep blue bottom -> deeper orange
                light = (0.6 - h) / 0.13  # 1 at cyan, ~-0.9 at deep blue
                nh = ORANGE_H + 0.02 * max(0.0, min(1.0, light))
                nr, ng, nb = colorsys.hsv_to_rgb(nh, min(1.0, s * 0.95), v)
                px[x, y] = (int(nr * 255), int(ng * 255), int(nb * 255))
    return im


def spaced(draw, cx, y, text, f, fill, spacing):
    widths = [draw.textlength(c, font=f) for c in text]
    x = cx - (sum(widths) + spacing * (len(text) - 1)) / 2
    for c, w in zip(text, widths):
        draw.text((x, y), c, font=f, fill=fill)
        x += w + spacing


im = orange_arrow(src.copy())
d = ImageDraw.Draw(im)
d.rounded_rectangle((216 - 88, 318 - 23, 216 + 88, 318 + 23), radius=23, fill=ORANGE)
spaced(d, 216, 318 - 23, "BRIDGE", ImageFont.truetype(FONT, 30), (28, 15, 5), 4)
im.save(f"{D}/fg-final.png")

# Themed (monochrome) layer: bright artwork opaque, navy and the band's text transparent.
mono = Image.new("RGBA", im.size, (255, 255, 255, 0))
mp, ip = mono.load(), im.load()
for y in range(im.height):
    for x in range(im.width):
        v = max(ip[x, y])
        mp[x, y] = (255, 255, 255, max(0, min(255, (v - 70) * 3)))
mono.save(f"{D}/mono-final.png")

# Preview at home-screen size
view = im.crop((72, 72, 360, 360))
sheet = Image.new("RGB", (560, 300), (46, 54, 82))
for i, (size, shape) in enumerate([(220, "squircle"), (132, "squircle"), (132, "circle")]):
    v = view.resize((size, size), Image.Resampling.LANCZOS)
    m = Image.new("L", (size * 4, size * 4), 0)
    md = ImageDraw.Draw(m)
    if shape == "circle":
        md.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else:
        md.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=int(size * 1.2), fill=255)
    m = m.resize((size, size), Image.Resampling.LANCZOS)
    x = [20, 270, 410][i]
    sheet.paste(v, (x, 40 if size == 220 else 70), m)
f = ImageFont.truetype(FONT, 16)
for cx in (336, 476):
    w = ImageDraw.Draw(sheet).textlength("WFR Dash Bridge", font=f)
    ImageDraw.Draw(sheet).text((cx - w / 2, 212), "WFR Dash Bridge", font=f, fill=(255, 255, 255))
sheet.save(f"{D}/final-preview.png")

if RES:
    for folder, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        import os
        os.makedirs(f"{RES}/mipmap-{folder}", exist_ok=True)
        im.resize((px, px), Image.Resampling.LANCZOS).save(f"{RES}/mipmap-{folder}/ic_launcher_foreground.png", optimize=True)
        mono.resize((px, px), Image.Resampling.LANCZOS).save(f"{RES}/mipmap-{folder}/ic_launcher_monochrome.png", optimize=True)
print("navy", NAVY)
