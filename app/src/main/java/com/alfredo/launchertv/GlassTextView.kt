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
import android.graphics.Rect
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

    // FILTER: el cristal se guarda a 1/4 de resolución y la gráfica lo amplía suavizado.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** true cuando el cielo detrás del reloj es claro: entonces el cristal se oscurece para que se lea. */
    private var onBright = false
    private val bounds = Rect()

    /** Distancia desde el borde superior de la vista hasta el borde superior de las cifras. */
    fun digitTopOffset(): Float {
        val fm = fill.fontMetrics
        val y = measuredHeight / 2f - (fm.ascent + fm.descent) / 2f
        fill.getTextBounds(text.ifEmpty { "88:88" }, 0, text.ifEmpty { "88:88" }.length, bounds)
        return y + bounds.top
    }

    /** Modo claro/oscuro decidido fuera (plan precalculado). */
    fun setBright(bright: Boolean) { onBright = bright; invalidate() }

    private var glassBitmap: Bitmap? = null
    private var glassShader: BitmapShader? = null
    // Fondo anterior, que se funde con el nuevo durante FADE_MS (igual que la foto).
    private var oldBitmap: Bitmap? = null
    private var oldShader: BitmapShader? = null
    private var oldZoom = 1f
    private var fadeStart = 0L
    private val shaderMatrix = Matrix()
    private val screenPos = IntArray(2)

    init {
        // Se dibuja con la gráfica (aceleración por hardware): redibujar el reloj en cada
        // fotograma mientras se mueve es barato y el movimiento sale fluido. Las sombras de
        // texto están soportadas por hardware.
        fill.textAlign = Paint.Align.CENTER
        gloss.textAlign = Paint.Align.CENTER
        shadow.textAlign = Paint.Align.CENTER
        edge.textAlign = Paint.Align.CENTER
        shadow.color = Color.TRANSPARENT
        shadow.setShadowLayer(44f, 0f, 14f, 0x99000000.toInt())
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = 1.2f
        edge.color = 0x66FFFFFF
        textSizePx = textSizePx
        typeface = typeface
    }

    private var zoom = 1f
    private var screenW = 0
    private var screenH = 0

    /** Zoom de la foto de fondo (Ken Burns), centrado en la pantalla. Redibuja solo si cambia lo bastante. */
    fun setZoom(z: Float) {
        if (kotlin.math.abs(z - zoom) < 0.0015f) return
        zoom = z
        invalidate()
    }

    /**
     * Fondo nuevo para "ver a través" de las letras: [glass] es la foto a 1/4 de resolución
     * (preparada en segundo plano con [prepareGlass]). El anterior se funde con este en FADE_MS.
     */
    fun setBackdrop(glass: Bitmap?, bright: Boolean, screenW: Int, screenH: Int) {
        this.screenW = screenW; this.screenH = screenH
        oldBitmap?.recycle()
        oldBitmap = glassBitmap; oldShader = glassShader; oldZoom = zoom
        fadeStart = if (oldShader != null) android.os.SystemClock.uptimeMillis() else 0L
        zoom = 1f
        onBright = bright
        glassBitmap = glass
        glassShader = glass?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    /** Coloca el fondo [shader] de forma que cada píxel del cristal coincida con lo que hay detrás. */
    private fun align(shader: BitmapShader, bmp: Bitmap?, z: Float): BitmapShader {
        // El cristal está a 1/4 de resolución: primero se estira al tamaño de la pantalla…
        val sx = if (bmp != null && bmp.width > 0) screenW.toFloat() / bmp.width else 1f
        val sy = if (bmp != null && bmp.height > 0) screenH.toFloat() / bmp.height else 1f
        shaderMatrix.setScale(sx, sy)
        // …la foto se ve ampliada (Ken Burns) alrededor del centro de la pantalla: el cristal igual.
        shaderMatrix.postScale(z, z, screenW / 2f, screenH / 2f)
        // Pasar de coordenadas de pantalla a coordenadas de esta vista (donde esté ahora mismo).
        shaderMatrix.postTranslate(-screenPos[0].toFloat(), -screenPos[1].toFloat())
        // Refracción: el fondo se ve a través del cristal ligeramente ampliado y bajado.
        shaderMatrix.postScale(1.025f, 1.025f, width / 2f, height / 2f)
        shaderMatrix.postTranslate(0f, 8f)
        shader.setLocalMatrix(shaderMatrix)
        return shader
    }

    /** Ancho de celda para las cifras (todas iguales, como en el iPhone) y para los dos puntos. */
    private fun digitCell(): Float = (0..9).maxOf { fill.measureText(it.toString()) } * 1.04f
    private fun colonCell(): Float = fill.measureText(":") * 1.15f
    private fun totalWidth(): Float = text.sumOf { (if (it == ':') colonCell() else digitCell()).toDouble() }.toFloat()

    /** Dibuja el texto cifra a cifra en celdas fijas, centrado en cx. */
    private fun drawTabular(canvas: Canvas, paint: Paint, cx: Float, y: Float) {
        var x = cx - totalWidth() / 2f
        for (ch in text) {
            val w = if (ch == ':') colonCell() else digitCell()
            canvas.drawText(ch.toString(), x + w / 2f, y, paint)
            x += w
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (totalWidth() + textSizePx * 0.6f).toInt()
        val fm = fill.fontMetrics
        val h = (fm.bottom - fm.top + textSizePx * 0.45f).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val fm = fill.fontMetrics
        val x = width / 2f
        val y = height / 2f - (fm.ascent + fm.descent) / 2f

        // 1) Sombra suave.
        drawTabular(canvas, shadow, x, y)

        // 2) Relleno: fondo difuminado y aclarado, alineado con lo que hay detrás.
        val shader = glassShader
        if (shader != null) {
            getLocationOnScreen(screenPos)
            fill.colorFilter = LIGHTEN   // cristal esmerilado claro siempre, como el iPhone
            fill.alpha = 255
            fill.shader = align(shader, glassBitmap, zoom)
            drawTabular(canvas, fill, x, y)
            // Fundido con el fondo anterior, al mismo ritmo que cambia la foto.
            val old = oldShader
            if (old != null) {
                val t = (android.os.SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()
                if (t < 1f) {
                    fill.shader = align(old, oldBitmap, oldZoom)
                    // Misma curva que el fundido de la foto: el cristal cambia de color a la vez que ella.
                    fill.alpha = (255 * (1f - fadeCurve(t))).toInt()
                    drawTabular(canvas, fill, x, y)
                    postInvalidateOnAnimation()
                } else {
                    oldShader = null; oldBitmap?.recycle(); oldBitmap = null
                }
            }
            // Velo para que siempre se lea: blanco sobre fondos oscuros, oscuro sobre cielos claros.
            fill.shader = null
            fill.colorFilter = null
            fill.color = Color.WHITE
            fill.alpha = (255 * (1f - glassAmount)).toInt()
            drawTabular(canvas, fill, x, y)
        } else {
            fill.shader = null
            fill.colorFilter = null
            fill.color = Color.WHITE
            fill.alpha = 240
            drawTabular(canvas, fill, x, y)
        }

        // 3) Brillo superior tipo cristal.
        val top = y + fm.ascent
        gloss.shader = LinearGradient(0f, top, 0f, y + fm.descent,
            intArrayOf(0x8CFFFFFF.toInt(), 0x2EFFFFFF, 0x00FFFFFF, 0x22FFFFFF),
            floatArrayOf(0f, 0.40f, 0.52f, 1f), Shader.TileMode.CLAMP)
        drawTabular(canvas, gloss, x, y)

        // 4) Borde fino para que los trazos queden nítidos.
        // sobre cielos muy claros el borde se oscurece un poco para que las cifras no se fundan
        edge.color = if (onBright) 0x55303A48 else 0x66FFFFFF
        drawTabular(canvas, edge, x, y)
    }

    companion object {
        /** Igual que el fundido de la foto en Wallpapers. */
        const val FADE_MS = 1_600L

        /** Curva del fundido (= AccelerateDecelerateInterpolator, la que usa la foto). */
        fun fadeCurve(t: Float): Float = (Math.cos((t.coerceIn(0f, 1f) + 1) * Math.PI) / 2.0 + 0.5).toFloat()

        /**
         * Prepara el cristal de una foto (en cualquier hilo): la foto a 1/4 de resolución, que
         * la gráfica amplía suavizada (= el mismo difuminado de antes), y si el cielo de la
         * zona del reloj es claro.
         */
        fun prepareGlass(photo: Bitmap, screenW: Int, screenH: Int): Pair<Bitmap, Boolean> {
            val argb = photo.copy(Bitmap.Config.ARGB_8888, false)
            val small = Bitmap.createScaledBitmap(argb, (screenW / 4).coerceAtLeast(1), (screenH / 4).coerceAtLeast(1), true)
            if (small !== argb) argb.recycle()
            // Luminosidad media de la franja donde suele ir el reloj (centro, parte alta).
            var lum = 0L; var n = 0
            val sx = small.width / 48f; val sy = small.height / 27f
            for (y in 2 until 14) for (x in 12 until 36) {
                val c = small.getPixel((x * sx).toInt(), (y * sy).toInt())
                lum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000; n++
            }
            return small to (n > 0 && lum / n > 150)
        }
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
            // Color exacto del fondo; solo un pelín más luminoso para que parezca vidrio y no un agujero.
            postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, 14f,
                0f, 1f, 0f, 0f, 14f,
                0f, 0f, 1f, 0f, 14f,
                0f, 0f, 0f, 1f, 0f
            )))
        })
    }
}
