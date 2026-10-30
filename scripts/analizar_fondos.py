#!/usr/bin/env python3
"""
Analiza cada fondo: separa el cielo del paisaje y decide dónde va el reloj, de forma que
las cumbres tapen solo el borde inferior de las cifras (efecto profundidad del iPhone).

Entrada:  carpeta con NN.jpg (1920x1080) y Antonio.ttf
Salida:   NN.mask.png (máscara del paisaje, 480x270, borde suave) + layout.json + previews/

Uso: python3 scripts/analizar_fondos.py <carpeta_fondos> <carpeta_salida_assets> <carpeta_previews>
"""
import json, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080          # pantalla
MW, MH = 480, 270          # tamaño de la máscara
FONT_PX = 360              # 180 sp × 2 (densidad 320)
FONT_WGHT = 200
OVERLAP = 0.20             # parte del reloj (en altura) que tapan las cumbres
MIN_TOP = 48               # margen superior mínimo (px)
DOCK_TOP = 852 - 36        # el reloj nunca pasa de aquí (borde superior del dock menos margen)
MAX_COVERAGE = 0.45        # si la montaña taparía más que esto, no se usa el efecto
GRAD_T = 9.0               # umbral de gradiente para "cielo suave" (0-255)


def skyline_mask(img):
    """Devuelve (mask 0..255 de MWxMH, línea del horizonte por columna en px de pantalla, es_cielo_claro).

    Método: el cielo (con sus nubes) es una región CONECTADA con el borde superior en la que los
    cambios son suaves; la silueta de la montaña es un borde fuerte. Se inunda desde arriba por los
    píxeles de gradiente bajo y lo que queda sin inundar es el paisaje.
    """
    small = img.convert("RGB").resize((MW, MH), Image.LANCZOS).filter(ImageFilter.GaussianBlur(1.4))
    a = np.asarray(small).astype(np.float32)
    lum = a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114
    sky_ref = a[:6].reshape(-1, 3).mean(axis=0)
    bright_top = sky_ref.mean() > 70

    def grad(ch):
        gx = np.zeros_like(ch); gy = np.zeros_like(ch)
        gx[:, 1:-1] = (ch[:, 2:] - ch[:, :-2]) / 2
        gy[1:-1, :] = (ch[2:, :] - ch[:-2, :]) / 2
        return np.hypot(gx, gy)

    g = np.maximum(grad(lum), np.maximum(grad(a[..., 0]), np.maximum(grad(a[..., 1]), grad(a[..., 2]))) * 0.8)
    ref_lum = float(lum[:6].mean())
    # candidato a cielo: suave, de un brillo parecido al cielo (o nube clara), y nunca en el cuarto inferior
    soft = (g < GRAD_T) & ((np.abs(lum - ref_lum) < 80) | (lum > 190))
    soft[int(MH * 0.78):, :] = False

    sky = np.zeros((MH, MW), dtype=bool)
    sky[0, :] = soft[0, :]
    for _ in range(MH + MW):
        grown = sky.copy()
        grown[1:, :] |= sky[:-1, :]
        grown[:-1, :] |= sky[1:, :]
        grown[:, 1:] |= sky[:, :-1]
        grown[:, :-1] |= sky[:, 1:]
        grown &= soft
        if (grown == sky).all():
            break
        sky = grown

    # El paisaje es lo NO-cielo que está conectado con el borde inferior (así las nubes sueltas,
    # que no se inundaron desde arriba, no cuentan como montaña).
    nonsky = ~sky
    ground = np.zeros_like(sky)
    ground[-1, :] = nonsky[-1, :]
    for _ in range(MH + MW):
        grown = ground.copy()
        grown[1:, :] |= ground[:-1, :]
        grown[:-1, :] |= ground[1:, :]
        grown[:, 1:] |= ground[:, :-1]
        grown[:, :-1] |= ground[:, 1:]
        grown &= nonsky
        if (grown == ground).all():
            break
        ground = grown

    line = np.full(MW, MH, dtype=float)
    for x in range(MW):
        nz = np.flatnonzero(ground[:, x])
        line[x] = nz[0] if len(nz) else MH
    pad = np.pad(line, 4, mode="edge")
    med = np.array([np.median(pad[i:i + 9]) for i in range(MW)])
    pad = np.pad(med, 2, mode="edge")
    smooth = np.array([pad[i:i + 5].mean() for i in range(MW)])

    mask = np.zeros((MH, MW), dtype=np.uint8)
    ys = np.arange(MH)[:, None]
    mask[ys >= smooth[None, :]] = 255
    m = Image.fromarray(mask, "L").filter(ImageFilter.GaussianBlur(1.0))
    return m, smooth * (H / MH), bright_top


def clock_font():
    f = ImageFont.truetype(FONT_FILE, FONT_PX)
    try:
        f.set_variation_by_axes([FONT_WGHT])
    except Exception:
        pass
    return f


def plan(line_screen, sky_ok, font):
    """Posición del reloj (top de las cifras, px) y si se usa el primer plano."""
    bbox = font.getbbox("22:38")
    digit_h = bbox[3] - bbox[1]
    digit_w = bbox[2] - bbox[0]
    default_top = 48 * 2
    max_top = DOCK_TOP - digit_h
    if not sky_ok:
        return dict(digitTop=int(min(default_top, max_top)), digitH=digit_h, useFg=False, coverage=0.0, peakY=None)

    # columnas que ocupa el reloj (centrado)
    x0 = (W - digit_w) // 2
    cols = slice(int(x0 / W * MW), int((x0 + digit_w) / W * MW) + 1)
    seg = line_screen[cols]
    peak = float(seg.min())
    if peak > H * 0.55:   # la montaña queda muy abajo (lago, bosque…): reloj arriba sin efecto
        return dict(digitTop=int(min(default_top, max_top)), digitH=digit_h, useFg=False, coverage=0.0, peakY=int(peak))
    wanted = peak - digit_h * (1 - OVERLAP)
    top = int(min(max(wanted, MIN_TOP), max_top))
    bottom = top + digit_h
    cov = float(np.clip((bottom - seg) / digit_h, 0, 1).mean())
    use_fg = 0.01 <= cov <= MAX_COVERAGE
    if cov > MAX_COVERAGE:          # taparía demasiado: subir el reloj hasta que no tape
        top = int(max(MIN_TOP, peak - digit_h * 0.92))
        bottom = top + digit_h
        cov = float(np.clip((bottom - seg) / digit_h, 0, 1).mean())
        use_fg = 0.01 <= cov <= MAX_COVERAGE
    return dict(digitTop=top, digitH=digit_h, useFg=use_fg, coverage=round(cov, 3), peakY=int(peak))


def preview(img, mask, p, font, out_path, bright):
    base = img.convert("RGBA")
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    text = "22:38"
    bbox = font.getbbox(text)
    x = (W - (bbox[2] - bbox[0])) // 2 - bbox[0]
    y = p["digitTop"] - bbox[1]
    fill = (28, 36, 48, 200) if bright else (255, 255, 255, 215)
    d.text((x + 4, y + 10), text, font=font, fill=(0, 0, 0, 90))
    d.text((x, y), text, font=font, fill=fill, stroke_width=2, stroke_fill=(255, 255, 255, 110))
    comp = Image.alpha_composite(base, layer)
    if p["useFg"]:
        fg = base.copy()
        fg.putalpha(mask.resize((W, H), Image.BILINEAR))
        comp = Image.alpha_composite(comp, fg)
    # dock de muestra para ver que no choca
    d2 = ImageDraw.Draw(comp)
    for i in range(5):
        x0 = 104 + i * 440
        d2.rounded_rectangle([x0, 880, x0 + 392, 1100], radius=40, fill=(255, 255, 255, 70), outline=(255, 255, 255, 120), width=2)
    comp.convert("RGB").resize((960, 540), Image.LANCZOS).save(out_path, quality=86)


def main():
    global FONT_FILE
    src, out_assets, out_prev = sys.argv[1], sys.argv[2], sys.argv[3]
    FONT_FILE = os.path.join(src, "Antonio.ttf")
    if not os.path.exists(FONT_FILE):
        FONT_FILE = os.path.join(src, "..", "fonts", "Antonio.ttf")
    os.makedirs(out_assets, exist_ok=True)
    os.makedirs(out_prev, exist_ok=True)
    font = clock_font()
    layout = {}
    for name in sorted(os.listdir(src)):
        if not name.lower().endswith(".jpg"):
            continue
        img = Image.open(os.path.join(src, name)).convert("RGB")
        if img.size != (W, H):
            img = img.resize((W, H), Image.LANCZOS)
        mask, line, bright_top = skyline_mask(img)
        sky_frac = float(line.mean()) / H
        sky_ok = bright_top and 0.12 <= sky_frac <= 0.92
        p = plan(line, sky_ok, font)
        # ¿cielo claro detrás del reloj? (para el cristal ahumado)
        band = np.asarray(img.resize((48, 27)).convert("L"))[2:14, 12:36]
        bright = float(band.mean()) > 150
        p["bright"] = bright
        p["skyFraction"] = round(sky_frac, 3)
        layout[name] = p
        if p["useFg"]:
            mask.save(os.path.join(out_assets, name.replace(".jpg", ".mask.png")))
        preview(img, mask, p, font, os.path.join(out_prev, name), bright)
        print(f"{name}: cielo={sky_frac:.2f} pico={p['peakY']} top={p['digitTop']} tapa={p['coverage']:.0%} fg={p['useFg']} claro={bright}")
    with open(os.path.join(out_assets, "layout.json"), "w") as f:
        json.dump(layout, f, indent=1)
    # hoja de contactos
    names = sorted(layout)
    cols = 3
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * 640, rows * 376), (17, 17, 17))
    dr = ImageDraw.Draw(sheet)
    for i, n in enumerate(names):
        im = Image.open(os.path.join(out_prev, n)).resize((640, 360))
        sheet.paste(im, ((i % cols) * 640, (i // cols) * 376))
        dr.text(((i % cols) * 640 + 8, (i // cols) * 376 + 362), f"{n}  fg={layout[n]['useFg']} tapa={layout[n]['coverage']:.0%}", fill=(255, 255, 255))
    sheet.save(os.path.join(out_prev, "hoja.jpg"), quality=85)


if __name__ == "__main__":
    main()
