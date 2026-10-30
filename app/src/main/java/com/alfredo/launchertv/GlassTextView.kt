package com.alfredo.launchertv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Texto "de cristal": las letras se rellenan con una versión difuminada y aclarada del
 * fondo que tienen detrás, con un brillo superior y una sombra suave. Así el reloj
 * cambia con cada foto en vez de ser un bloque blanco encima.
 */
class GlassTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var text: String = ""
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    var textSizePx: Float = 200f
        set(value) { field = value; fill.textSize = value; gloss.textSize = value; shadow.textSize = value; edge.textSize = value; requestLayout(); invalidate() }

    var typeface: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        set(value) { field = value; fill.typeface = value; gloss.typeface = value; shadow.typeface = value; edge.typeface = value; requestLayout(); invalidate() }

    /** Cuánto se ve el fondo a través de las letras (0 = blanco sólido, 1 = solo fondo). */
    var glassAmount: Float = 0.55f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** true cuando el cielo detrás del reloj es claro: entonces el cristal se oscurece para que se lea. */
    private var onBright = false

    private var glassBitmap: Bitmap? = null
    private var glassShader: BitmapShader? = null
    private val shaderMatrix = Matrix()
    private val screenPos = IntArray(2)

    init {
        // Capa por software: permite la sombra difuminada del texto.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        fill.textAlign = Paint.Align.CENTER
        gloss.textAlign = Paint.Align.CENTER
        shadow.textAlign = Paint.Align.CENTER
        edge.textAlign = Paint.Align.CENTER
        shadow.color = Color.TRANSPARENT
        shadow.setShadowLayer(40f, 0f, 12f, 0x8C000000.toInt())
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = 1.2f
        edge.color = 0x66FFFFFF
        textSizePx = textSizePx
        typeface = typeface
    }

    /** Fondo actual (la foto completa, en coordenadas de pantalla) para "ver a través" de las letras. */
    fun setBackdrop(photo: Bitmap?, screenW: Int, screenH: Int) {
        glassBitmap?.recycle()
        glassBitmap = null
        glassShader = null
        if (photo != null && screenW > 0 && screenH > 0) {
            // Versión pequeña (difuminado barato) y aclarada de la foto, estirada a la pantalla.
            val small = Bitmap.createScaledBitmap(photo, 48, 27, true)
            // Luminosidad media de la franja donde suele ir el reloj (centro, parte alta).
            var lum = 0L; var n = 0
            for (y in 2 until 14) for (x in 12 until 36) {
                val c = small.getPixel(x, y)
                lum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000; n++
            }
            onBright = n > 0 && lum / n > 150
            val blurred = Bitmap.createScaledBitmap(small, screenW, screenH, true)
            small.recycle()
            glassBitmap = blurred
            glassShader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (fill.measureText(text) + textSizePx * 0.2f).toInt()
        val fm = fill.fontMetrics
        val h = (fm.bottom - fm.top + textSizePx * 0.25f).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val fm = fill.fontMetrics
        val x = width / 2f
        val y = height / 2f - (fm.ascent + fm.descent) / 2f

        // 1) Sombra suave.
        canvas.drawText(text, x, y, shadow)

        // 2) Relleno: fondo difuminado y aclarado, alineado con lo que hay detrás.
        val shader = glassShader
        if (shader != null) {
            getLocationOnScreen(screenPos)
            shaderMatrix.reset()
            shaderMatrix.postTranslate(-screenPos[0].toFloat(), -screenPos[1].toFloat())
            shader.setLocalMatrix(shaderMatrix)
            fill.shader = shader
            fill.colorFilter = if (onBright) DARKEN else LIGHTEN
            fill.alpha = 255
            canvas.drawText(text, x, y, fill)
            // Velo para que siempre se lea: blanco sobre fondos oscuros, oscuro sobre cielos claros.
            fill.shader = null
            fill.colorFilter = null
            fill.color = if (onBright) 0xFF1A2430.toInt() else Color.WHITE
            fill.alpha = (255 * (1f - glassAmount)).toInt()
            canvas.drawText(text, x, y, fill)
        } else {
            fill.shader = null
            fill.colorFilter = null
            fill.color = Color.WHITE
            fill.alpha = 240
            canvas.drawText(text, x, y, fill)
        }

        // 3) Brillo superior tipo cristal.
        val top = y + fm.ascent
        gloss.shader = LinearGradient(0f, top, 0f, y + fm.descent,
            intArrayOf(0x8CFFFFFF.toInt(), 0x26FFFFFF, 0x00FFFFFF, 0x1AFFFFFF),
            floatArrayOf(0f, 0.42f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawText(text, x, y, gloss)

        // 4) Borde fino para que los trazos queden nítidos.
        edge.color = if (onBright) 0x66FFFFFF else 0x66FFFFFF
        canvas.drawText(text, x, y, edge)
    }

    companion object {
        /** Oscurece el fondo (para cielos claros): cristal ahumado. */
        private val DARKEN = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0.9f)
            postConcat(ColorMatrix(floatArrayOf(
                0.55f, 0f, 0f, 0f, -25f,
                0f, 0.55f, 0f, 0f, -25f,
                0f, 0f, 0.55f, 0f, -25f,
                0f, 0f, 0f, 1f, 0f
            )))
        })

        /** Aclara y desatura un poco el fondo para que parezca cristal esmerilado. */
        private val LIGHTEN = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0.8f)
            postConcat(ColorMatrix(floatArrayOf(
                0.75f, 0f, 0f, 0f, 70f,
                0f, 0.75f, 0f, 0f, 70f,
                0f, 0f, 0.75f, 0f, 70f,
                0f, 0f, 0f, 1f, 0f
            )))
        })
    }
}
