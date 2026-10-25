"""Draws the app icon, the TV banner and the site's images (Pillow). Usage: python3 tools/make_art.py"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/assets/fonts/InterVariable.ttf")
STOPS = [(0.0, (30, 40, 98)), (0.62, (58, 52, 120)), (1.0, (122, 84, 150))]   # the evening sky: indigo -> violet


def gradient(w, h, angle=90):
    """Three-color gradient (90: top to bottom, like the sky)."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    a = math.radians(angle)
    dx, dy = math.cos(a), math.sin(a)
    lo = min(0, w * dx, h * dy, w * dx + h * dy)
    hi = max(0, w * dx, h * dy, w * dx + h * dy)
    for y in range(h):
        for x in range(w):
            t = ((x * dx + y * dy) - lo) / (hi - lo)
            for i in range(len(STOPS) - 1):
                t0, c0 = STOPS[i]
                t1, c1 = STOPS[i + 1]
                if t <= t1:
                    f = (t - t0) / (t1 - t0)
                    px[x, y] = tuple(round(c0[k] + (c1[k] - c0[k]) * f) for k in range(3))
                    break
    return img


def sparkle(draw, cx, cy, r, fill):
    """Four-point star."""
    pts = []
    for i in range(8):
        ang = math.pi / 2 * (i // 2) + (0 if i % 2 == 0 else math.pi / 4)
        rad = r if i % 2 == 0 else r * 0.26
        pts.append((cx + rad * math.cos(ang - math.pi / 2), cy + rad * math.sin(ang - math.pi / 2)))
    draw.polygon(pts, fill=fill)


def glow(img, cx, cy, r, color, alpha):
    """A soft round light (the horizon's warmth) composited onto img."""
    s = img.width
    light = Image.new("RGBA", img.size, color + (0,))
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse((cx - r, cy - r, cx + r, cy + r), fill=int(255 * alpha))
    light.putalpha(m.filter(ImageFilter.GaussianBlur(r * 0.45)))
    img.alpha_composite(light)


def moon(draw, cx, cy, r, fill, cut):
    """A crescent: a full disc with a disc of `cut` color taken out up and to the right."""
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill)
    o = r * 0.42
    draw.ellipse((cx - r + o, cy - r - o * 0.55, cx + r + o, cy + r - o * 0.55), fill=cut)


def mark(size, bg=True):
    """The mark: a TV whose screen holds a crescent moon and a star, on the evening-sky squircle (bg) or alone."""
    s = size * 4
    img = gradient(s, s).convert("RGBA") if bg else Image.new("RGBA", (s, s), (0, 0, 0, 0))
    if bg:
        glow(img, s * 0.5, s * 1.08, s * 0.62, (255, 178, 128), 0.75)   # warm light at the horizon
    if bg:
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.23), fill=255)
        img.putalpha(mask)
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    white = (255, 255, 255, 255)
    # screen
    x0, y0, x1, y1 = s * 0.2, s * 0.27, s * 0.8, s * 0.66
    d.rounded_rectangle((x0, y0, x1, y1), radius=int(s * 0.07), outline=white, width=int(s * 0.055))
    # stand
    d.rounded_rectangle((s * 0.36, s * 0.715, s * 0.64, s * 0.765), radius=int(s * 0.025), fill=white)
    # the evening inside the screen: a crescent moon and one star
    moon(d, s * 0.45, s * 0.47, s * 0.105, white, (0, 0, 0, 0))
    sparkle(d, s * 0.64, s * 0.37, s * 0.05, white)
    if bg:
        shadow = layer.copy().filter(ImageFilter.GaussianBlur(s * 0.02))
        tint = Image.new("RGBA", (s, s), (8, 10, 40, 0))
        tint.putalpha(shadow.getchannel("A").point(lambda v: int(v * 0.22)))
        img.alpha_composite(tint, (0, int(s * 0.012)))
    img.alpha_composite(layer)
    return img.resize((size, size), Image.LANCZOS)


def font(px, weight=600):
    f = ImageFont.truetype(FONT, px)
    try:
        axes = f.get_variation_axes()
        values = []
        for a in axes:
            name = a["name"] if isinstance(a["name"], str) else a["name"].decode()
            values.append(weight if "eight" in name else 32 if "ptical" in name else a.get("default", 0))
        f.set_variation_by_axes(values)
    except Exception:
        pass
    return f


def banner(w=640, h=360):
    s = 4
    img = gradient(w * s, h * s).convert("RGBA")
    glow(img, w * s * 0.5, h * s * 1.25, h * s * 0.8, (255, 178, 128), 0.7)
    m = mark(int(h * 0.5 * s), bg=False)
    d = ImageDraw.Draw(img)
    f = font(int(h * 0.15 * s), 650)
    text = "My Kids TV"
    box = d.textbbox((0, 0), text, font=f)
    gap = int(w * 0.02 * s)
    total = m.width + gap + (box[2] - box[0])
    x = (w * s - total) // 2
    img.alpha_composite(m, (x, int((h * s - m.height) / 2)))
    y = (h * s - (box[3] - box[1])) // 2 - box[1]
    d.text((x + m.width + gap - box[0], y), text, font=f, fill=(255, 255, 255, 255))
    return img.resize((w, h), Image.LANCZOS)


if __name__ == "__main__":
    res = os.path.join(ROOT, "app/res/drawable-nodpi")
    os.makedirs(res, exist_ok=True)
    mark(192).save(os.path.join(res, "icon.png"))
    banner().save(os.path.join(res, "banner.png"))
    for path, px in (("webos/icon.png", 80), ("webos/largeIcon.png", 130), ("tizen/icon.png", 512), ("docs/images/icon.png", 512)):
        mark(px).save(os.path.join(ROOT, path))
    print("ok")
