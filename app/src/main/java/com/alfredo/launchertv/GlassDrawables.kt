package com.alfredo.launchertv

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Superficie con el canto iluminado (idea de quickLiquid): relleno liso y un borde fino que
 * brilla más donde le da la luz (arriba a la izquierda) y un poco en el lado opuesto.
 */
class RimDrawable(
    private val fill: Int,
    private val radius: Float,
    private val stroke: Float,
    private val rimTop: Float = 0.34f,
    private val rimMid: Float = 0.07f,
    private val rimBottom: Float = 0.14f
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val rect = RectF()
    private var alphaMul = 255

    override fun onBoundsChange(b: Rect) {
        rect.set(b.left + stroke / 2, b.top + stroke / 2, b.right - stroke / 2, b.bottom - stroke / 2)
        fun white(a: Float) = ((a * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        // Luz a −35°: el degradado va de arriba-izquierda a abajo-derecha.
        rimPaint.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.left + b.height() * 0.7f + b.width() * 0.3f, b.bottom.toFloat(),
            intArrayOf(white(rimTop), white(rimMid), white(rimBottom)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        fillPaint.alpha = (android.graphics.Color.alpha(fill) * alphaMul / 255)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        rimPaint.alpha = alphaMul
        canvas.drawRoundRect(rect, radius, radius, rimPaint)
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fillPaint.colorFilter = colorFilter; rimPaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Fondo del estante en modo "cristal": la imagen calculada por [LiquidShelf] (cambia con cada
 * foto, fundiéndose), y debajo el estante claro, que se ve mientras se calcula el primero y
 * cuando la página baja (el cristal está calculado para la posición del inicio).
 */
class ShelfGlassDrawable(private val fallback: Drawable) : Drawable() {
    private var cur: Bitmap? = null
    private var old: Bitmap? = null
    private var mix = 1f            // 0 = imagen anterior, 1 = actual
    private var glassAmount = 0f    // 0 = estante claro, 1 = cristal
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var mixAnim: ValueAnimator? = null
    private var amountAnim: ValueAnimator? = null
    private var hidden = false

    fun setGlass(bmp: Bitmap?, animate: Boolean) {
        mixAnim?.cancel()
        if (bmp == null) return
        if (!animate || cur == null) {
            old = null; cur = bmp; mix = 1f
            if (!hidden) fade(1f, animate)
            invalidateSelf(); return
        }
        old = cur; cur = bmp; mix = 0f
        mixAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200; interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { mix = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    /** Cristal visible (inicio) o estante claro (al bajar de fila). */
    fun showGlass(on: Boolean) {
        hidden = !on
        if (cur != null) fade(if (on) 1f else 0f, true)
    }

    private fun fade(to: Float, animate: Boolean) {
        amountAnim?.cancel()
        if (!animate) { glassAmount = to; invalidateSelf(); return }
        amountAnim = ValueAnimator.ofFloat(glassAmount, to).apply {
            duration = 450
            addUpdateListener { glassAmount = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    override fun onBoundsChange(b: Rect) { fallback.bounds = b; dst.set(b) }

    override fun draw(canvas: Canvas) {
        if (glassAmount < 1f) { fallback.alpha = (255 * (1f - glassAmount)).toInt(); fallback.draw(canvas) }
        if (glassAmount <= 0f) return
        val o = old
        if (o != null && mix < 1f) { paint.alpha = (255 * glassAmount * (1f - mix)).toInt(); canvas.drawBitmap(o, null, dst, paint) }
        cur?.let { paint.alpha = (255 * glassAmount * mix).toInt(); canvas.drawBitmap(it, null, dst, paint) }
    }

    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
