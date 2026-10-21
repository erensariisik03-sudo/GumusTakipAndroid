"""Gümüş Takip kapak ve simge üretici (Pillow + numpy). Çalıştır: python3 tools/make_art.py"""
import math, random, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

def grad(w, h, stops, horizontal=False):
    n = w if horizontal else h
    t = np.linspace(0, 1, n)
    pos = [s[0] for s in stops]
    ch = [np.interp(t, pos, [s[1][c] for s in stops]) for c in range(3)]
    arr = np.stack(ch, axis=-1)
    arr = arr[None, :, :] if horizontal else arr[:, None, :]
    arr = np.repeat(arr, h if horizontal else w, axis=0 if horizontal else 1)
    return Image.fromarray(arr.astype("uint8"), "RGB")

def hexc(s): s = s.lstrip("#"); return tuple(int(s[i:i+2], 16) for i in (0, 2, 4))

def fill_poly(canvas, pts, stops, horizontal=False, band=None):
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    x0, y0, x1, y1 = int(min(xs)), int(min(ys)), int(max(xs)) + 1, int(max(ys)) + 1
    w, h = x1 - x0, y1 - y0
    g = grad(w, h, stops, horizontal)
    if band is not None:  # parlak dikey şerit (metal yansıması)
        c, wd, amt = band
        xsr = np.linspace(0, 1, w)
        prof = np.exp(-((xsr - c) ** 2) / (2 * wd ** 2))[None, :, None] * amt
        a = np.asarray(g, float) + prof
        g = Image.fromarray(np.clip(a, 0, 255).astype("uint8"), "RGB")
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).polygon([(x - x0, y - y0) for x, y in pts], fill=255)
    canvas.paste(g.convert("RGBA"), (x0, y0), m)

def glow(canvas, draw_fn, radius, alpha=1.0):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer))
    blur = layer.filter(ImageFilter.GaussianBlur(radius))
    if alpha != 1.0:
        a = blur.getchannel("A").point(lambda v: int(v * alpha)); blur.putalpha(a)
    canvas.alpha_composite(blur)

def catmull(pts, per=16):
    out = []
    P = [pts[0]] + pts + [pts[-1]]
    for i in range(1, len(P) - 2):
        p0, p1, p2, p3 = P[i-1], P[i], P[i+1], P[i+2]
        for k in range(per):
            t = k / per
            out.append(tuple(0.5 * ((2*p1[j]) + (-p0[j]+p2[j])*t + (2*p0[j]-5*p1[j]+4*p2[j]-p3[j])*t*t + (-p0[j]+3*p1[j]-3*p2[j]+p3[j])*t**3) for j in (0, 1)))
    out.append(pts[-1]); return out

def ingot(canvas, cx, cy, W, H, shade=1.0, text="Ag", sub=None, ss=1):
    L, B = cx - W / 2, cy + H / 2
    T = cy - H / 2; M = B - 0.66 * H; s = W * 0.055; t = W * 0.075
    sh = lambda c: tuple(int(v * shade) for v in c)
    # gölge
    glow(canvas, lambda d: d.ellipse([L + W*0.03, B - H*0.02, L + W*0.97, B + H*0.16], fill=(0, 0, 0, 190)), 14 * ss)
    # yan/alt kenar (koyu)
    edge = [(L, B), (L + W, B), (L + W - s, M), (L + s, M)]
    fill_poly(canvas, edge, [(0, sh(hexc("#C9D0D9"))), (0.55, sh(hexc("#A3ACB8"))), (1, sh(hexc("#6C7582")))],
              band=(0.30, 0.10, 55 * shade))
    top = [(L + s, M), (L + W - s, M), (L + W - s - t, T), (L + s + t, T)]
    fill_poly(canvas, top, [(0, sh(hexc("#D5DBE3"))), (1, sh(hexc("#EEF1F5")))], band=(0.5, 0.25, 12))
    lay = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    d.line(edge + [edge[0]], fill=(255, 255, 255, 120), width=max(1, int(2.2 * ss)), joint="curve")
    d.line(top + [top[0]], fill=(255, 255, 255, 150), width=max(1, int(2.2 * ss)), joint="curve")
    # gravür yazı
    if text:
        fs = int(H * 0.40)
        f = ImageFont.truetype(FONT_B, fs)
        bb = d.textbbox((0, 0), text, font=f)
        tx, ty = cx - (bb[2] - bb[0]) / 2 - bb[0], (M + B) / 2 - (bb[3] - bb[1]) / 2 - bb[1] - (H * 0.01 if sub else 0) - (H*0.03 if sub else 0)
        d.text((tx + 2 * ss, ty + 2 * ss), text, font=f, fill=(255, 255, 255, 190))
        d.text((tx, ty), text, font=f, fill=sh(hexc("#5E6875")) + (255,))
        if sub:
            f2 = ImageFont.truetype(FONT_B, int(H * 0.11))
            b2 = d.textbbox((0, 0), sub, font=f2)
            sx = cx - (b2[2] - b2[0]) / 2 - b2[0]; sy = B - H * 0.16
            d.text((sx + ss, sy + ss), sub, font=f2, fill=(255, 255, 255, 170))
            d.text((sx, sy), sub, font=f2, fill=sh(hexc("#5E6875")) + (255,))
    canvas.alpha_composite(lay)

def sparkle(canvas, x, y, r, ss=1):
    def dr(d):
        d.polygon([(x, y - r), (x + r*0.16, y - r*0.16), (x + r, y), (x + r*0.16, y + r*0.16), (x, y + r), (x - r*0.16, y + r*0.16), (x - r, y), (x - r*0.16, y - r*0.16)], fill=(255, 255, 255, 255))
    glow(canvas, dr, r * 0.35)
    dr(ImageDraw.Draw(canvas))

def chart(canvas, box, seed, lw, ss=1, rise=0.5, noise=0.05, n=12, fill=True):
    x0, y0, x1, y1 = box; w, h = x1 - x0, y1 - y0
    random.seed(seed); pts = []
    for i in range(n + 1):
        u = i / n
        y = y1 - h * (0.12 + rise * u ** 1.1) + random.uniform(-1, 1) * h * noise * (0.4 if i in (0, n) else 1)
        pts.append((x0 + w * u, y))
    pts[-1] = (pts[-1][0], min(pts[-1][1], pts[-2][1] - h * 0.04))
    sm = catmull(pts)
    green = (52, 211, 153)
    if fill:
        poly = sm + [(canvas.size[0], sm[-1][1]), (canvas.size[0], y1 + h * 0.2), (x0, y1 + h * 0.2)]
        g = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        a = np.zeros((canvas.size[1], canvas.size[0]), dtype="uint8")
        ys = np.arange(canvas.size[1])
        fade = np.clip(1 - (ys - (y0)) / (h * 1.25), 0, 1) * 95
        a[:] = fade[:, None].astype("uint8")
        solid = Image.new("RGBA", canvas.size, green + (0,)); solid.putalpha(Image.fromarray(a, "L"))
        m = Image.new("L", canvas.size, 0); ImageDraw.Draw(m).polygon(poly, fill=255)
        g.paste(solid, (0, 0), m); canvas.alpha_composite(g)
    glow(canvas, lambda d: d.line(sm, fill=green + (255,), width=int(lw * 2.2), joint="curve"), lw * 2.2, 0.9)
    d = ImageDraw.Draw(canvas)
    d.line(sm, fill=(110, 240, 190, 255), width=int(lw), joint="curve")
    ex, ey = sm[-1]
    glow(canvas, lambda dd: dd.ellipse([ex - lw*1.6, ey - lw*1.6, ex + lw*1.6, ey + lw*1.6], fill=green + (255,)), lw * 2.4)
    d.ellipse([ex - lw*1.1, ey - lw*1.1, ex + lw*1.1, ey + lw*1.1], fill=(235, 255, 247, 255))

def background(w, h):
    img = grad(w, h, [(0, hexc("#0A1020")), (0.55, hexc("#10213A")), (1, hexc("#0B1526"))]).convert("RGBA")
    # yumuşak mavi ışık
    glow(img, lambda d: d.ellipse([w*0.30, h*0.05, w*0.95, h*0.80], fill=(59, 130, 246, 90)), min(w, h) * 0.18)
    return img

def banner(path):
    SS = 2; W, H = 1440 * SS, 840 * SS
    img = background(W, H)
    gl = Image.new("RGBA", (W, H), (0, 0, 0, 0)); gd = ImageDraw.Draw(gl)
    for i in range(1, 7):  # ince ızgara
        y = H * i / 7; gd.line([(0, y), (W, y)], fill=(255, 255, 255, 14), width=SS)
    img.alpha_composite(gl)
    chart(img, (0, H * 0.18, W * 0.955, H * 0.98), seed=11, lw=7 * SS, ss=SS, rise=0.62, noise=0.06, n=14)
    ingot(img, W * 0.40, H * 0.70, W * 0.34, H * 0.34, shade=0.80, text=None, ss=SS)
    ingot(img, W * 0.54, H * 0.58, W * 0.42, H * 0.43, shade=1.0, text="Ag", sub="FINE SILVER 999.9", ss=SS)
    sparkle(img, W * 0.77, H * 0.27, 34 * SS, SS); sparkle(img, W * 0.31, H * 0.40, 18 * SS, SS); sparkle(img, W * 0.84, H * 0.50, 12 * SS, SS)
    img.convert("RGB").resize((W // SS, H // SS), Image.LANCZOS).save(path, optimize=True)

def icon_art(size, scale, with_bg):
    SS = 2; S = size * SS
    img = background(S, S) if with_bg else Image.new("RGBA", (S, S), (0, 0, 0, 0))
    c = S / 2
    iw = S * 0.62 * scale; ih = iw * 0.52
    chart(img, (c - iw * 0.70, c - ih * 1.15, c + iw * 0.66, c + ih * 0.95), seed=5, lw=max(3, 0.018 * S * scale), ss=SS, rise=0.55, noise=0.05, n=6, fill=False)
    ingot(img, c, c + ih * 0.12, iw, ih, shade=1.0, text="Ag", ss=SS)
    sparkle(img, c + iw * 0.46, c - ih * 0.78, S * 0.05 * scale, SS)
    return img.resize((size, size), Image.LANCZOS)

def save_icons():
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in dens.items():
        d = os.path.join(RES, "mipmap-" + name); os.makedirs(d, exist_ok=True)
        base = icon_art(px * 4, 1.0, True).resize((px, px), Image.LANCZOS)
        m = Image.new("L", (px, px), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, px - 1, px - 1], radius=int(px * 0.22), fill=255)
        sq = Image.new("RGBA", (px, px), (0, 0, 0, 0)); sq.paste(base, (0, 0), m); sq.save(os.path.join(d, "ic_launcher.png"))
        mc = Image.new("L", (px, px), 0); ImageDraw.Draw(mc).ellipse([0, 0, px - 1, px - 1], fill=255)
        rd = Image.new("RGBA", (px, px), (0, 0, 0, 0)); rd.paste(base, (0, 0), mc); rd.save(os.path.join(d, "ic_launcher_round.png"))
    fg = icon_art(432, 0.74, False)
    os.makedirs(os.path.join(RES, "drawable-nodpi"), exist_ok=True)
    fg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"), optimize=True)
    icon_art(512, 1.0, True).save("/tmp/icon_preview.png")

if __name__ == "__main__":
    banner(os.path.join(RES, "drawable-nodpi", "gumus_cover.png"))
    save_icons()
    print("ok")
