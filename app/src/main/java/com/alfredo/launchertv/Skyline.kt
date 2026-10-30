package com.alfredo.launchertv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Separa el cielo del paisaje para que el reloj pueda quedar DETRÁS de las montañas
 * (efecto profundidad, como la pantalla de bloqueo del iPhone).
 *
 * Método: en una copia pequeña de la foto, por cada columna se baja desde arriba mientras el
 * color se parezca al del cielo (que se va adaptando para degradados); donde deja de parecerse
 * durante varios píxeles seguidos empieza el paisaje. Esa línea (la "skyline") se suaviza y con
 * ella se recorta la foto: lo que queda es el primer plano que se dibuja encima del reloj.
 */
object Skyline {

    class Result(
        /** Foto recortada: transparente en el cielo, opaca en el paisaje. */
        val foreground: Bitmap,
        /** Fila (en píxeles de la foto) del punto más alto del paisaje. */
        val topY: Int,
        /** Fila media de la línea del horizonte. */
        val meanY: Int,
        /** Qué parte de la pantalla es cielo (0..1). Si es poca, no merece la pena el efecto. */
        val skyFraction: Float,
        /** Línea del horizonte: fila (px de la foto) por cada una de las COLUMNS columnas. */
        val line: IntArray
    ) {
        /** Fracción (0..1) de un rectángulo de pantalla que queda tapada por el paisaje. */
        fun coverage(left: Float, top: Float, right: Float, bottom: Float, screenW: Int, screenH: Int, photoW: Int, photoH: Int): Float {
            var covered = 0f; var n = 0
            val c0 = (left / screenW * COLUMNS).toInt().coerceIn(0, COLUMNS - 1)
            val c1 = (right / screenW * COLUMNS).toInt().coerceIn(0, COLUMNS - 1)
            for (c in c0..c1) {
                val yScreen = line[c].toFloat() * screenH / photoH
                covered += ((bottom - yScreen) / (bottom - top)).coerceIn(0f, 1f)
                n++
            }
            return if (n == 0) 0f else covered / n
        }
    }

    const val COLUMNS = 192

    private const val W = COLUMNS
    private const val H = 108

    fun compute(photo: Bitmap): Result? {
        val small = Bitmap.createScaledBitmap(photo, W, H, true)
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        small.recycle()

        // Color de referencia del cielo: media de las 4 filas superiores.
        var sr = 0; var sg = 0; var sb = 0; var n = 0
        for (y in 0 until 4) for (x in 0 until W) {
            val c = px[y * W + x]; sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++
        }
        sr /= n; sg /= n; sb /= n
        if ((sr + sg + sb) / 3 < 70) return null   // arriba no hay cielo claro (noche, bosque…): sin efecto

        val line = IntArray(W)
        for (x in 0 until W) {
            var r = sr; var g = sg; var b = sb
            var y = 0
            var run = 0
            var found = H
            while (y < H) {
                val c = px[y * W + x]
                val cr = Color.red(c); val cg = Color.green(c); val cb = Color.blue(c)
                val dist = abs(cr - r) + abs(cg - g) + abs(cb - b)
                val bright = (cr + cg + cb) / 3
                val sat = max(cr, max(cg, cb)) - min(cr, min(cg, cb))
                val isSky = dist < 70 || (bright > 215 && sat < 40)   // cielo o nube
                if (isSky) {
                    run = 0
                    // el cielo cambia de tono poco a poco: adaptar la referencia
                    r = (r * 7 + cr) / 8; g = (g * 7 + cg) / 8; b = (b * 7 + cb) / 8
                } else {
                    run++
                    if (run >= 3) { found = y - run + 1; break }
                }
                y++
            }
            line[x] = found
        }

        // Suavizado (mediana de 5) para quitar picos sueltos.
        val smooth = IntArray(W)
        for (x in 0 until W) {
            val v = IntArray(5) { i -> line[(x + i - 2).coerceIn(0, W - 1)] }
            v.sort(); smooth[x] = v[2]
        }

        val topSmall = smooth.minOrNull() ?: H
        val meanSmall = smooth.average().toInt()
        val skyFraction = meanSmall.toFloat() / H
        if (skyFraction < 0.12f || skyFraction > 0.92f) return null   // sin cielo claro: no aplicar

        // Máscara pequeña (borde suave al escalarla) y recorte de la foto a tamaño completo.
        val mask = Bitmap.createBitmap(W, H, Bitmap.Config.ALPHA_8)
        val maskPx = ByteArray(W * H)
        for (x in 0 until W) for (y in 0 until H) {
            maskPx[y * W + x] = if (y >= smooth[x]) 0xFF.toByte() else 0
        }
        mask.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(maskPx))

        val fw = photo.width; val fh = photo.height
        val fg = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fg)
        canvas.drawBitmap(photo, 0f, 0f, null)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask, Rect(0, 0, W, H), Rect(0, 0, fw, fh), p)
        mask.recycle()

        return Result(fg, topSmall * fh / H, meanSmall * fh / H, skyFraction, IntArray(W) { smooth[it] * fh / H })
    }
}
