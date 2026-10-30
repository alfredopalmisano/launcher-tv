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
MW, MH = 960, 540          # tamaño de la máscara (la mitad de la pantalla; borde fino)
FONT_PX = 360              # 180 sp × 2 (densidad 320)
FONT_WGHT = 200
OVERLAP = 0.20             # parte del reloj (en altura) que tapan las cumbres
MIN_TOP = 48               # margen superior mínimo (px)
DOCK_TOP = 852 - 36        # el reloj nunca pasa de aquí (borde superior del dock menos margen)
MAX_COVERAGE = 0.45        # si la montaña taparía más que esto, no se usa el efecto
GRAD_T = 9.0               # umbral de gradiente para "cielo suave" (0-255)
METHOD = {"last": "?"}


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

    sky = sky_pixels_ml(img)
    METHOD["last"] = "ml" if sky is not None else "heurístico"
    if sky is None:
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
    m = Image.fromarray(mask, "L").filter(ImageFilter.GaussianBlur(0.8))
    return m, smooth * (H / MH), bright_top


_ML = {}


def _box(x, r):
    """Media en ventana (2r+1)² con sumas acumuladas (borde replicado)."""
    xp = np.pad(x, r, mode="edge")
    c = np.cumsum(np.cumsum(xp, axis=0), axis=1)
    c = np.pad(c, ((1, 0), (1, 0)))
    h, w = x.shape
    k = 2 * r + 1
    return (c[k:k + h, k:k + w] - c[:h, k:k + w] - c[k:k + h, :w] + c[:h, :w]) / (k * k)


def guided_filter(guide, p, r=8, eps=2e-3):
    """Filtro guiado (He et al.): ajusta el borde de la probabilidad p al borde real de la foto."""
    mI = _box(guide, r); mp = _box(p, r)
    cI = _box(guide * guide, r); cIp = _box(guide * p, r)
    vI = cI - mI * mI; cov = cIp - mI * mp
    a = cov / (vI + eps); b = mp - a * mI
    return _box(a, r) * guide + _box(b, r)


def sky_pixels_ml(img):
    """Cielo según un modelo de segmentación semántica (SegFormer entrenado en ADE20K: clase 2 = cielo).
    Devuelve una matriz booleana MHxMW o None si el modelo no está disponible."""
    try:
        import torch
        from transformers import SegformerForSemanticSegmentation, SegformerImageProcessor
    except Exception:
        return None
    try:
        if "model" not in _ML:
            name = os.environ.get("SEG_MODEL", "nvidia/segformer-b2-finetuned-ade-512-512")
            _ML["proc"] = SegformerImageProcessor.from_pretrained(name)
            _ML["model"] = SegformerForSemanticSegmentation.from_pretrained(name).eval()
        inputs = _ML["proc"](images=img.convert("RGB"), return_tensors="pt")
        with torch.no_grad():
            logits = _ML["model"](**inputs).logits
        up = torch.nn.functional.interpolate(logits, size=(MH, MW), mode="bilinear", align_corners=False)
        probs = torch.softmax(up, dim=1)[0]
        p_sky = probs[2].numpy().astype(np.float32)          # 2 = sky en ADE20K
        # Afinar el borde con la propia foto (filtro guiado): precisión al píxel en la silueta.
        guide = np.asarray(img.convert("L").resize((MW, MH), Image.LANCZOS)).astype(np.float32) / 255.0
        q = guided_filter(guide, p_sky, r=6, eps=1e-3)
        return q > 0.5
    except Exception as e:
        print("  (modelo no disponible:", e, ")")
        return None


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
    """Imita el reloj de cristal de la app: relleno con el fondo difuminado (aclarado u oscurecido),
    velo, brillo superior y sombra; después la montaña por delante."""
    base = img.convert("RGBA")
    text = "22:38"
    bbox = font.getbbox(text)
    x = (W - (bbox[2] - bbox[0])) // 2 - bbox[0]
    y = p["digitTop"] - bbox[1]

    # máscara de las cifras
    tmask = Image.new("L", base.size, 0)
    ImageDraw.Draw(tmask).text((x, y), text, font=font, fill=255)
    # sombra
    shadow = Image.new("RGBA", base.size, (0, 0, 0, 0))
    sh = Image.new("L", base.size, 0)
    ImageDraw.Draw(sh).text((x, y + 12), text, font=font, fill=140)
    sh = sh.filter(ImageFilter.GaussianBlur(18))
    shadow.putalpha(sh)
    comp = Image.alpha_composite(base, shadow)
    # relleno de cristal: fondo muy difuminado
    glass = img.resize((48, 27), Image.BILINEAR).resize((W, H), Image.BILINEAR).convert("RGB")
    g = np.asarray(glass).astype(np.float32)
    g = g * 0.92 + 28
    veil = np.array([255, 255, 255], dtype=np.float32); veil_a = 0.14
    g = g * (1 - veil_a) + veil * veil_a
    glass = Image.fromarray(np.clip(g, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    glass.putalpha(tmask)
    comp = Image.alpha_composite(comp, glass)
    # brillo superior (cristal)
    grad = np.zeros((H, W), dtype=np.uint8)
    top, bot = p["digitTop"], p["digitTop"] + p["digitH"]
    for yy in range(top, min(bot, H)):
        t = (yy - top) / max(1, (bot - top))
        grad[yy, :] = int(166 * (1 - t) ** 2) if t < 0.5 else int(30 * (t - 0.5) * 2)
    gloss = Image.new("RGBA", base.size, (255, 255, 255, 0))
    ga = Image.fromarray(grad, "L")
    ga = Image.fromarray((np.asarray(ga).astype(np.uint16) * np.asarray(tmask) // 255).astype(np.uint8), "L")
    gloss.putalpha(ga)
    comp = Image.alpha_composite(comp, gloss)
    # borde fino
    edge = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(edge).text((x, y), text, font=font, fill=(0, 0, 0, 0), stroke_width=2,
                              stroke_fill=(48, 58, 72, 85) if bright else (255, 255, 255, 100))
    comp = Image.alpha_composite(comp, edge)
    if p["useFg"]:
        fg = base.copy()
        fg.putalpha(mask.resize((W, H), Image.BILINEAR))
        comp = Image.alpha_composite(comp, fg)
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
    # opciones por foto (tercera columna de creditos.txt), p. ej. fg=no
    options = {}
    cred = os.path.join(src, "creditos.txt")
    if os.path.exists(cred):
        for ln in open(cred, encoding="utf-8"):
            parts = [x.strip() for x in ln.split("|")]
            if len(parts) >= 3:
                options[parts[0]] = parts[2]
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
        if "fg=no" in options.get(name, ""):
            p["useFg"] = False
            p["coverage"] = 0.0
            p["digitTop"] = int(min(48 * 2, DOCK_TOP - p["digitH"]))
        # ¿cielo claro detrás del reloj? (para el cristal ahumado)
        band = np.asarray(img.resize((48, 27)).convert("L"))[2:14, 12:36]
        bright = float(band.mean()) > 150
        p["bright"] = bright
        p["skyFraction"] = round(sky_frac, 3)
        layout[name] = p
        if p["useFg"]:
            # PNG con canal ALFA (la tele recorta con el alfa, no con el gris): opaco = montaña, transparente = cielo
            Image.merge("LA", [Image.new("L", mask.size, 255), mask]).save(os.path.join(out_assets, name.replace(".jpg", ".mask.png")))
        preview(img, mask, p, font, os.path.join(out_prev, name), bright)
        p["method"] = METHOD["last"]
        print(f"{name}: [{METHOD['last']}] cielo={sky_frac:.2f} pico={p['peakY']} top={p['digitTop']} tapa={p['coverage']:.0%} fg={p['useFg']} claro={bright}")
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
