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
    // Máscara de la montaña de la foto actual y de la anterior: el reloj se borra donde hay montaña.
    private var maskBitmap: Bitmap? = null
    private var maskShader: BitmapShader? = null
    private var oldMaskBitmap: Bitmap? = null
    private var oldMaskShader: BitmapShader? = null
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
    }
    private val mixPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ADD = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
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

    /** Interruptores para medir cuánto cuesta cada parte (solo desde adb, ver MainActivity.debug). */
    var debugNoShadow = false
    /** Modo noche: las fotos se ven más oscuras, así que el cristal también. */
    var night = false
        set(value) { field = value; invalidate() }
    var debugNoMask = false

    private var zoom = 1f
    private var screenW = 0
    private var screenH = 0

    /** Zoom de la foto de fondo (Ken Burns), centrado en la pantalla. Redibuja solo si cambia lo bastante. */
    fun setZoom(z: Float) {
        if (kotlin.math.abs(z - zoom) < 0.0001f) return   // cada paso del zoom (~5 por segundo)
        zoom = z
        invalidate()
    }

    /**
     * Fondo nuevo para "ver a través" de las letras: [glass] es la foto a 1/4 de resolución
     * (preparada en segundo plano con [prepareGlass]). El anterior se funde con este en FADE_MS.
     */
    fun setBackdrop(glass: Bitmap?, mask: Bitmap?, bright: Boolean, screenW: Int, screenH: Int) {
        this.screenW = screenW; this.screenH = screenH
        oldBitmap = glassBitmap; oldShader = glassShader; oldZoom = zoom
        oldMaskBitmap = maskBitmap; oldMaskShader = maskShader
        maskBitmap = mask
        maskShader = mask?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
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
        // Sin "refracción": cada píxel del cristal muestra exactamente lo que tiene detrás.
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

    // Imágenes del reloj para la hora actual (se rehacen al cambiar la hora, el tamaño o el modo).
    private var cacheText: Bitmap? = null      // forma de las cifras (ALPHA_8)
    private var cacheShadow: Bitmap? = null    // sombra difuminada
    private var cacheOverlay: Bitmap? = null   // brillo superior + borde
    private var cacheKey = ""

    private fun ensureCache(x: Float, y: Float) {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val key = "$text|$w|$h|${fill.textSize}|${System.identityHashCode(fill.typeface)}|$onBright"
        if (key == cacheKey && cacheText != null) return
        cacheKey = key
        // (Sin recycle(): la gráfica puede estar aún pintando el fotograma anterior con ellas.)
        val fm = fill.fontMetrics

        // Forma de las cifras: blanco opaco sobre transparente, en un mapa de solo alfa.
        val t = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val tp = Paint(fill).apply { shader = null; colorFilter = null; color = Color.WHITE; alpha = 255 }
        drawTabular(Canvas(t), tp, x, y)
        cacheText = t

        // Sombra (por software: difuminado de calidad, una sola vez).
        val sh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawTabular(Canvas(sh), shadow, x, y)
        cacheShadow = sh

        // Brillo superior + borde fino.
        val ov = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val oc = Canvas(ov)
        val top = y + fm.ascent
        gloss.shader = LinearGradient(0f, top, 0f, y + fm.descent,
            intArrayOf(0x8CFFFFFF.toInt(), 0x2EFFFFFF, 0x00FFFFFF, 0x22FFFFFF),
            floatArrayOf(0f, 0.40f, 0.52f, 1f), Shader.TileMode.CLAMP)
        drawTabular(oc, gloss, x, y)
        // sobre cielos muy claros el borde se oscurece un poco para que las cifras no se fundan
        edge.color = if (onBright) 0x55303A48 else 0x66FFFFFF
        drawTabular(oc, edge, x, y)
        cacheOverlay = ov
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
        getLocationOnScreen(screenPos)
        // Progreso del cambio de foto (misma curva que el fundido de la foto); 1 = sin cambio.
        val e = if (fadeStart > 0L) fadeCurve((android.os.SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()) else 1f
        // Con montaña delante, el reloj se dibuja en una capa aparte para poder borrarle esa zona.
        val masked = !debugNoMask && (maskShader != null || (oldMaskShader != null && e < 1f))
        val layer = if (masked) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) else -1

        // Las partes que no dependen del fondo (forma de las cifras, sombra, brillo y borde) se
        // dibujan UNA vez por minuto en imágenes; en cada fotograma la gráfica solo pega imágenes.
        // (Medido en la tele: dibujar las letras gigantes con sombra en cada fotograma era lo
        // que bajaba el cambio de foto a 20–30 fps.)
        ensureCache(x, y)
        val textMask = cacheText ?: return

        // 1) Sombra suave.
        if (!debugNoShadow) cacheShadow?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // 2) Relleno: el fondo de detrás, alineado píxel a píxel (la imagen de las cifras es
        //    ALPHA_8: la gráfica la colorea con el fondo).
        val shader = glassShader
        if (shader != null) {
            fill.colorFilter = if (night) LIGHTEN_NIGHT else LIGHTEN   // igual de oscuro que las fotos en modo noche
            fill.alpha = 255
            fill.shader = align(shader, glassBitmap, zoom)
            canvas.drawBitmap(textMask, 0f, 0f, fill)
            // Fundido con el fondo anterior, al mismo ritmo que cambia la foto.
            val old = oldShader
            if (old != null) {
                val t = (android.os.SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()
                if (t < 1f) {
                    fill.shader = align(old, oldBitmap, oldZoom)
                    fill.alpha = (255 * (1f - fadeCurve(t))).toInt()
                    canvas.drawBitmap(textMask, 0f, 0f, fill)
                    postInvalidateOnAnimation()
                } else {
                    oldShader = null; oldBitmap = null
                }
            }
            // Velo blanco muy suave para que siempre se lea.
            fill.shader = null
            fill.colorFilter = null
            fill.color = Color.WHITE
            fill.alpha = (255 * (1f - glassAmount)).toInt()
            canvas.drawBitmap(textMask, 0f, 0f, fill)
        } else {
            fill.shader = null
            fill.colorFilter = null
            fill.color = Color.WHITE
            fill.alpha = 240
            canvas.drawBitmap(textMask, 0f, 0f, fill)
        }

        // 3–4) Brillo superior y borde fino (ya pintados en su imagen).
        cacheOverlay?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // 5) Profundidad: borrar el reloj (y su sombra) donde la montaña queda por delante.
        //    Es lo mismo que pintar la montaña encima (es la propia foto), pero sin capas extra.
        if (masked) {
            val w = width.toFloat(); val h = height.toFloat()
            val om = oldMaskShader?.takeIf { e < 1f }
            val m = maskShader
            if (om != null && m != null) {
                // Cambio de foto: máscara mezclada = nueva·e + vieja·(1−e) (suma exacta con ADD),
                // y se borra una sola vez. Así el reloj no se transparenta donde hay montaña en ambas.
                val ml = canvas.saveLayer(0f, 0f, w, h, maskPaint)
                mixPaint.xfermode = null
                mixPaint.shader = align(m, maskBitmap, zoom); mixPaint.alpha = (255 * e).toInt()
                canvas.drawRect(0f, 0f, w, h, mixPaint)
                mixPaint.xfermode = ADD
                mixPaint.shader = align(om, oldMaskBitmap, oldZoom); mixPaint.alpha = (255 * (1f - e)).toInt()
                canvas.drawRect(0f, 0f, w, h, mixPaint)
                canvas.restoreToCount(ml)
            } else if (m != null) {
                maskPaint.shader = align(m, maskBitmap, zoom)
                maskPaint.alpha = (255 * e).toInt()
                canvas.drawRect(0f, 0f, w, h, maskPaint)
                maskPaint.shader = null; maskPaint.alpha = 255
            } else if (om != null) {
                maskPaint.shader = align(om, oldMaskBitmap, oldZoom)
                maskPaint.alpha = (255 * (1f - e)).toInt()
                canvas.drawRect(0f, 0f, w, h, maskPaint)
                maskPaint.shader = null; maskPaint.alpha = 255
            }
            canvas.restoreToCount(layer)
        }
        if (e >= 1f && oldMaskShader != null) { oldMaskShader = null; oldMaskBitmap = null }
        if (e < 1f) postInvalidateOnAnimation()
    }

    companion object {
        /** Igual que el fundido de la foto en Wallpapers. */
        const val FADE_MS = 1_600L

        /** Curva del fundido (= AccelerateDecelerateInterpolator, la que usa la foto). */
        fun fadeCurve(t: Float): Float = (Math.cos((t.coerceIn(0f, 1f) + 1) * Math.PI) / 2.0 + 0.5).toFloat()

        /**
         * Prepara el cristal de una foto (en cualquier hilo): la foto a 1/2 de resolución, que
         * la gráfica amplía suavizada (detalle casi exacto, apenas suavizado como un cristal),
         * y si el cielo de la zona del reloj es claro.
         */
        fun prepareGlass(photo: Bitmap, screenW: Int, screenH: Int): Pair<Bitmap, Boolean> {
            val argb = photo.copy(Bitmap.Config.ARGB_8888, false)
            val small = Bitmap.createScaledBitmap(argb, (screenW / 2).coerceAtLeast(1), (screenH / 2).coerceAtLeast(1), true)
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

        /** Factor de oscurecido de las fotos en modo noche (lo usan también las fotos). */
        const val NIGHT_FACTOR = 0.62f

        fun nightFilter() = ColorMatrixColorFilter(ColorMatrix().apply { setScale(NIGHT_FACTOR, NIGHT_FACTOR, NIGHT_FACTOR, 1f) })

        private val LIGHTEN_NIGHT = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            NIGHT_FACTOR, 0f, 0f, 0f, 14f * NIGHT_FACTOR,
            0f, NIGHT_FACTOR, 0f, 0f, 14f * NIGHT_FACTOR,
            0f, 0f, NIGHT_FACTOR, 0f, 14f * NIGHT_FACTOR,
            0f, 0f, 0f, 1f, 0f)))

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
