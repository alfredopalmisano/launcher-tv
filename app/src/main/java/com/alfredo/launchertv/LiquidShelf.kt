package com.alfredo.launchertv

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Estante "cristal": cristal líquido como el del Apple TV nuevo, con la física de quickLiquid
 * (github.com/amarnath3003/quickLiquid, MIT): un bisel convexo en el borde que dobla la foto de
 * detrás como una lente (refracción de Snell) y un canto iluminado.
 *
 * quickLiquid lo recalcula en cada fotograma con filtros del navegador; aquí no hace falta,
 * porque la foto de fondo la ponemos nosotros: se calcula UNA vez por foto en segundo plano y
 * el estante solo pega una imagen (coste por fotograma ≈ 0). El centro del estante es
 * transparente (se ve la foto real), así que solo se calcula la franja del bisel.
 */
object LiquidShelf {

    class Spec(
        /** Rectángulo del estante en píxeles de pantalla. */
        val left: Int, val top: Int, val width: Int, val height: Int,
        val radius: Float, val density: Float
    )

    private const val IOR = 1.5f
    private const val LUT_N = 512

    /**
     * @param photo la foto de fondo (del tamaño de la pantalla, como se ve con centerCrop).
     * @param zoom zoom del Ken Burns con el que comparar (a mitad de la foto se desvía la mitad).
     */
    fun render(photo: Bitmap, screenW: Int, screenH: Int, spec: Spec, zoom: Float = 1.035f): Bitmap? = try {
        val w = spec.width; val h = spec.height
        val d = spec.density
        // Más fino que el de quickLiquid: el estante es bajito y se ve a 3 m.
        val bezel = min(20f * d, min(w, h) / 2f)        // ancho del bisel
        val thick = 20f * d                             // grosor del "cristal"
        val strength = 16f * d                          // desplazamiento máximo en el borde
        val r = spec.radius.coerceAtMost(min(w, h) / 2f)
        val lut = refractionLut(bezel, thick)

        // Trozo de foto que hace falta (el estante más lo que se puede desplazar).
        val pad = (strength + 4).toInt()
        val sx0 = (spec.left - pad).coerceAtLeast(0); val sy0 = (spec.top - pad).coerceAtLeast(0)
        val sx1 = (spec.left + w + pad).coerceAtMost(screenW); val sy1 = (spec.top + h + pad).coerceAtMost(screenH)
        // Pantalla → foto (la foto está ampliada [zoom] alrededor del centro de la pantalla).
        val cx = screenW / 2f; val cy = screenH / 2f
        val px0 = ((cx + (sx0 - cx) / zoom) * photo.width / screenW).toInt().coerceIn(0, photo.width - 1)
        val py0 = ((cy + (sy0 - cy) / zoom) * photo.height / screenH).toInt().coerceIn(0, photo.height - 1)
        val px1 = ((cx + (sx1 - cx) / zoom) * photo.width / screenW).toInt().coerceIn(px0 + 1, photo.width)
        val py1 = ((cy + (sy1 - cy) / zoom) * photo.height / screenH).toInt().coerceIn(py0 + 1, photo.height)
        val pw = px1 - px0; val ph = py1 - py0
        val src = IntArray(pw * ph)
        photo.getPixels(src, 0, pw, px0, py0, pw, ph)
        boxBlur(src, pw, ph, (2 * d).toInt().coerceAtLeast(1))   // un pelín esmerilado: sin vetas

        fun sample(sxF: Float, syF: Float): Int {
            val fx = (cx + (sxF - cx) / zoom) * photo.width / screenW - px0
            val fy = (cy + (syF - cy) / zoom) * photo.height / screenH - py0
            val x = fx.coerceIn(0f, pw - 1.001f); val y = fy.coerceIn(0f, ph - 1.001f)
            val ix = x.toInt(); val iy = y.toInt(); val tx = x - ix; val ty = y - iy
            val c00 = src[iy * pw + ix]; val c10 = src[iy * pw + min(ix + 1, pw - 1)]
            val c01 = src[min(iy + 1, ph - 1) * pw + ix]; val c11 = src[min(iy + 1, ph - 1) * pw + min(ix + 1, pw - 1)]
            fun ch(sh: Int): Float {
                val a = ((c00 shr sh) and 255) * (1 - tx) + ((c10 shr sh) and 255) * tx
                val b = ((c01 shr sh) and 255) * (1 - tx) + ((c11 shr sh) and 255) * tx
                return a * (1 - ty) + b * ty
            }
            val rr = ch(16); val gg = ch(8); val bb = ch(0)
            // Un poco más de saturación y brillo, como un vidrio pulido.
            val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
            fun s(v: Float) = ((l + (v - l) * 1.25f) * 1.05f + 4f).coerceIn(0f, 255f).toInt()
            return (s(rr) shl 16) or (s(gg) shl 8) or s(bb)
        }

        // Luz desde arriba a la izquierda (−35°, como quickLiquid).
        val lx = -0.57f; val ly = -0.82f
        val out = IntArray(w * h)
        val hw = w / 2f; val hh = h / 2f
        for (y in 0 until h) {
            val py = y + 0.5f - hh
            for (x in 0 until w) {
                val pxl = x + 0.5f - hw
                // Distancia al borde del rectángulo redondeado (positiva hacia dentro) y normal hacia fuera.
                val qx = abs(pxl) - (hw - r); val qy = abs(py) - (hh - r)
                val ox = max(qx, 0f); val oy = max(qy, 0f)
                val outside = sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
                val dIn = -outside
                if (dIn <= 0f) continue                         // fuera: transparente
                var nx: Float; var ny: Float
                if (qx > 0f && qy > 0f) { val l = sqrt(qx * qx + qy * qy); nx = qx / l; ny = qy / l }
                else if (qx > qy) { nx = 1f; ny = 0f } else { nx = 0f; ny = 1f }
                if (pxl < 0) nx = -nx
                if (py < 0) ny = -ny
                val s = dIn / bezel
                // Canto iluminado: brilla más donde mira hacia la luz, y un reflejo débil en el lado opuesto.
                val facing = nx * lx + ny * ly
                val light = 0.35f + 0.65f * max(0f, facing) + 0.25f * max(0f, -facing)
                val rim = light * exp(-dIn / (1.2f * d)) * 0.85f
                val v = max(0.05f, rim)   // velo blanco muy ligero + canto, encima de todo
                if (s >= 1f) {
                    // Centro plano: transparente (se ve la foto real).
                    out[y * w + x] = argb(v, 255, 255, 255)
                    continue
                }
                val delta = lut[(s * (LUT_N - 1)).toInt()] * strength
                // La lente toma la imagen de un poco más hacia dentro (como una gota de agua).
                val c = sample(spec.left + x - nx * delta, spec.top + y - ny * delta)
                var rr = (c shr 16) and 255; var gg = (c shr 8) and 255; var bb = c and 255
                // Brillo suave a lo largo del bisel + canto fino.
                val sheen = 0.16f * (1f - s).pow(2) * light
                rr = (rr + (255 - rr) * sheen).toInt(); gg = (gg + (255 - gg) * sheen).toInt(); bb = (bb + (255 - bb) * sheen).toInt()
                // Hacia dentro del bisel se funde con la foto real (sin costura), y el velo encima:
                // resultado = (foto·(1−a) + lente·a)·(1−v) + blanco·v.
                val a = if (s < 0.72f) 1f else (1f - (s - 0.72f) / 0.28f).coerceIn(0f, 1f)
                val ao = 1f - (1f - a) * (1f - v)
                val k = a * (1f - v)
                fun mix(c: Int) = ((c * k + 255f * v) / ao).toInt().coerceIn(0, 255)
                out[y * w + x] = argb(ao, mix(rr), mix(gg), mix(bb))
            }
        }
        Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) { null }

    /** Desenfoque de caja (horizontal + vertical) del trozo de foto, en el sitio. */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val n = 2 * r + 1
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = px[y * w + i.coerceIn(0, w - 1)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (x in 0 until w) {
                tmp[y * w + x] = ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val a = px[y * w + (x - r).coerceIn(0, w - 1)]; val b = px[y * w + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((b shr 16) and 255) - ((a shr 16) and 255); sg += ((b shr 8) and 255) - ((a shr 8) and 255); sb += (b and 255) - (a and 255)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = tmp[i.coerceIn(0, h - 1) * w + x]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (y in 0 until h) {
                px[y * w + x] = ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val a = tmp[(y - r).coerceIn(0, h - 1) * w + x]; val b = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((b shr 16) and 255) - ((a shr 16) and 255); sg += ((b shr 8) and 255) - ((a shr 8) and 255); sb += (b and 255) - (a and 255)
            }
        }
    }

    private fun argb(a: Float, r: Int, g: Int, b: Int): Int =
        ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Desplazamiento de la refracción a lo largo del bisel (0 en el borde y en el centro plano,
     * máximo entre medias), normalizado a 1. Mismo modelo que quickLiquid (PHYSICS.md §1–3).
     */
    private fun refractionLut(bezel: Float, thick: Float): FloatArray {
        val lut = FloatArray(LUT_N)
        val eta = 1f / IOR
        var peak = 0f
        for (i in 0 until LUT_N) {
            val s = (i + 0.5f) / LUT_N
            val q = sqrt(s * (2f - s)).coerceAtLeast(1e-4f)
            val z = thick * q
            val slope = (thick / bezel) * (1f - s) / q
            val cosI = 1f / sqrt(1f + slope * slope)
            val sinI = slope * cosI
            val sinT = eta * sinI
            val cosT = sqrt((1f - sinT * sinT).coerceAtLeast(0f))
            val k = eta * cosI - cosT
            val txy = k * sinI
            val tz = -eta + k * cosI
            val delta = if (abs(tz) < 1e-6f) 0f else z * abs(txy / tz)
            lut[i] = delta
            if (delta > peak) peak = delta
        }
        if (peak > 0f) for (i in lut.indices) lut[i] /= peak
        return lut
    }
}
