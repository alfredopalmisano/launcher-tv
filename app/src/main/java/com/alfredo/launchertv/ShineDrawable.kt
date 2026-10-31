package com.alfredo.launchertv

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Reflejo de luz al enfocar una tarjeta: una franja diagonal MUY difuminada que cruza la tarjeta.
 * Antes era un rectángulo que se desplazaba y se le veía el borde (grabado en la tele: parecía
 * "un rectángulo que persigue al icono"). Aquí la franja se desvanece a cero por los dos lados
 * y fuera de ella todo es transparente, así que no hay ningún borde que ver.
 *
 * [progress] va de 0 (franja fuera, a la izquierda) a 1 (fuera, a la derecha).
 */
class ShineDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var shader: LinearGradient? = null
    private var band = 0f

    var progress: Float = -1f
        set(value) { field = value; invalidateSelf() }

    /** Intensidad del brillo (0–1). */
    var strength: Float = 1f
        set(value) { field = value; invalidateSelf() }

    override fun onBoundsChange(bounds: Rect) {
        // Franja de ~55 % del ancho, con 5 escalones de opacidad para que sea muy suave.
        band = bounds.width() * 0.55f
        shader = LinearGradient(0f, 0f, band, 0f,
            intArrayOf(0x00FFFFFF, 0x1AFFFFFF, 0x52FFFFFF, 0x1AFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val s = shader ?: return
        if (progress < 0f || progress > 1f) return
        val b = bounds
        // Recorre de "totalmente fuera por la izquierda" a "totalmente fuera por la derecha",
        // inclinada 20° (la diagonal se compensa con el alto de la tarjeta).
        val travel = b.width() + band + b.height() * 0.36f
        val x = -band - b.height() * 0.18f + travel * progress
        m.setRotate(-20f)
        m.postTranslate(b.left + x, b.top.toFloat())
        s.setLocalMatrix(m)
        paint.shader = s
        paint.alpha = (255 * strength).toInt().coerceIn(0, 255)
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) { strength = alpha / 255f }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
