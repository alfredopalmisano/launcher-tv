package com.alfredo.launchertv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView

/**
 * Fondos que rotan con fundido y zoom muy lento (Ken Burns).
 * Cada fondo tiene dos capas: la foto (debajo del reloj) y el primer plano recortado
 * (montañas, encima del reloj) para el efecto de profundidad.
 */
class Wallpapers(
    private val context: Context,
    photoA: ImageView, fgA: ImageView,
    photoB: ImageView, fgB: ImageView,
    private val credit: TextView
) {
    class Layer(val photo: ImageView, val fg: ImageView) {
        fun views() = listOf(photo, fg)
    }

    /** Todo lo que necesita una foto para mostrarse, preparado de antemano en segundo plano. */
    class Prepared(
        val index: Int,
        val photo: Bitmap,
        val plan: Layouts.Entry?,
        /** Máscara de la montaña: el reloj se recorta con ella (efecto de profundidad). */
        val mask: Bitmap?,
        /** Foto a 1/4 de resolución para el cristal del reloj. */
        val glass: Bitmap,
        /** ¿Cielo claro en la zona del reloj? */
        val bright: Boolean,
        /** Miniatura muy difuminada para el fondo de la pantalla Apps. */
        val blurred: Bitmap,
        /** Montaña recortada (foto con la máscara), para el relieve durante el zoom lento. */
        val fg: Bitmap?
    )

    /** Se llama con cada foto nueva, ya preparada, justo cuando empieza su fundido. */
    var onPhotoChanged: ((Prepared) -> Unit)? = null
    /** Montaña en relieve durante el zoom lento (se puede apagar para medir). */
    var relief = true
    /** Zoom actual de la foto (1…1,07) para que el reloj de cristal siga alineado con ella. */
    var onZoom: ((Float) -> Unit)? = null
    private val layouts = Layouts(context)

    // Capa de abajo (la foto que se va) y capa de arriba (la que entra). Siempre se funde la de
    // arriba sobre la de abajo opaca: así no hay bajón de brillo a mitad del cambio.
    private val bottom = Layer(photoA, fgA)
    private val top = Layer(photoB, fgB)
    private val handler = Handler(Looper.getMainLooper())
    private val files: List<String>
    private val credits: Map<String, String>
    private var index = -1
    private var running = false
    private var zoom: ValueAnimator? = null
    // Preparación en segundo plano de la foto siguiente: al cambiar no se decodifica nada en
    // el hilo de pantalla, así el fundido y el movimiento del reloj arrancan sin tirones.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var ready: Prepared? = null
    private var preparing = -1

    private val next = object : Runnable {
        override fun run() {
            advance()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    init {
        val all = try { context.assets.list("wallpapers")?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        files = all.filter { it.endsWith(".jpg", true) }.sorted()
        credits = try {
            context.assets.open("wallpapers/creditos.txt").bufferedReader().readLines()
                .mapNotNull { line ->
                    val p = line.split('|')
                    if (p.size >= 2) p[0].trim() to p[1].trim() else null
                }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    /** Cambia de foto ya (para las medidas) y reinicia la cuenta de 40 s. */
    fun nextNow() {
        if (!running) return
        handler.removeCallbacks(next)
        advance()
        handler.postDelayed(next, INTERVAL_MS)
    }

    fun start() {
        if (running) return
        running = true
        if (index < 0) advance() else { handler.removeCallbacks(kbTick); kbTick.run() }
        handler.postDelayed(next, INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(next)
        handler.removeCallbacks(kbTick)
    }

    private fun advance() {
        if (files.isEmpty()) return
        val nextIndex = (index + 1) % files.size
        // Lo normal: ya está preparada. La primera vez (o si no dio tiempo) se prepara aquí.
        val p = ready?.takeIf { it.index == nextIndex } ?: prepare(nextIndex) ?: return
        ready = null
        index = nextIndex
        val bmp = p.photo
        val plan = p.plan
        android.util.Log.i("LauncherTV", "fondo ${files[index]} plan=${plan != null} mascara=${p.mask != null} top=${plan?.digitTop}")

        // 1) Lo que se ve ahora pasa, tal cual (mismo zoom), a la capa de abajo: no cambia nada en pantalla.
        zoom?.cancel()
        fadeAnims.forEach { it.cancel() }; fadeAnims.clear()
        val s = top.photo.scaleX
        bottom.photo.setImageDrawable(top.photo.drawable)
        bottom.photo.scaleX = s; bottom.photo.scaleY = s
        bottom.photo.alpha = 1f
        // 2) La foto nueva entra arriba desde transparente. (La montaña ya no es una capa aparte:
        //    el reloj se recorta con su máscara, así el fundido de las fotos es exacto y la
        //    gráfica pinta dos pantallas completas menos en cada fotograma.)
        top.photo.setImageBitmap(bmp)
        top.photo.scaleX = 1f; top.photo.scaleY = 1f; top.photo.alpha = 0f
        // Relieve: la montaña de la foto anterior desaparece justo cuando coincide con la foto
        // (su adelanto ya volvió a cero), y la nueva aparece cuando ya no hay fundido.
        bottom.fg.alpha = 0f; bottom.fg.setImageDrawable(null)
        top.fg.alpha = 0f; top.fg.scaleX = 1f; top.fg.scaleY = 1f
        top.fg.setImageBitmap(p.fg)
        fade(top.photo, 1f) { bottom.photo.setImageDrawable(null) }
        kenBurns(top)
        onPhotoChanged?.invoke(p)
        val author = credits[files[index]]
        credit.text = if (author != null) context.getString(R.string.credit, author) else ""
        prepareNextLater()
    }

    /** Prepara la foto siguiente en segundo plano (unos segundos después, cuando el fundido ya acabó). */
    private fun prepareNextLater() {
        val n = (index + 1) % files.size
        if (preparing == n || ready?.index == n) return
        preparing = n
        handler.postDelayed({
            worker.execute {
                val p = prepare(n)
                handler.post {
                    preparing = -1
                    if (p != null) {
                        // Subir ya las imágenes a la gráfica, para que el primer fotograma no tarde.
                        p.photo.prepareToDraw()
                        ready = p
                    }
                }
            }
        }, FADE_MS + 2_000L)
    }

    /** Decodifica la foto, recorta la montaña y calcula el cristal. Seguro en cualquier hilo. */
    private fun prepare(i: Int): Prepared? {
        val name = files[i]
        val bmp = decode(name) ?: return null
        val plan = layouts[name]
        val mask = if (plan?.useFg == true) layouts.mask(name) else null
        val dm = context.resources.displayMetrics
        val (glass, bright) = GlassTextView.prepareGlass(bmp, dm.widthPixels, dm.heightPixels)
        val tiny = Bitmap.createScaledBitmap(bmp, 32, 18, true)
        val blurred = Bitmap.createScaledBitmap(tiny, 320, 180, true)
        tiny.recycle()
        val fg = if (plan?.useFg == true) layouts.foreground(name, bmp) else null
        return Prepared(i, bmp, plan, mask, glass, plan?.bright ?: bright, blurred, fg)
    }

    private val fadeAnims = ArrayList<ObjectAnimator>()

    private fun fade(view: View, to: Float, end: (() -> Unit)?) {
        ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, to).apply {
            duration = FADE_MS
            // La misma curva que usa el cristal del reloj (GlassTextView.fadeCurve).
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            fadeAnims.add(this)
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { end() }
            })
            start()
        }
    }

    /**
     * Zoom lento (Ken Burns): de 1 a 1,07 en lo que dura la foto. Es tan lento (menos de 2 px
     * por segundo en los bordes) que basta con actualizarlo ~5 veces por segundo: se ve igual
     * de suave y la tele trabaja unas 12 veces menos que a 60 fps (medido: a 60 no daba abasto).
     */
    private var kbLayer: Layer? = null
    private var kbStart = 0L
    private val kbTick = object : Runnable {
        override fun run() {
            val l = kbLayer ?: return
            val t = ((android.os.SystemClock.uptimeMillis() - kbStart).toFloat() / (INTERVAL_MS + FADE_MS)).coerceIn(0f, 1f)
            val sc = 1f + KB_ZOOM * t
            l.photo.scaleX = sc; l.photo.scaleY = sc
            // Montaña en relieve: se acerca un poco más que el cielo (hasta un 3 % a mitad de la
            // foto) y vuelve a coincidir antes del cambio. Fuera de ese tramo la capa no se pinta.
            val el = (android.os.SystemClock.uptimeMillis() - kbStart).toFloat()
            val u = ((el - FADE_MS) / (INTERVAL_MS - 2 * FADE_MS)).coerceIn(0f, 1f)
            val extra = if (relief && l.fg.drawable != null) RELIEF * kotlin.math.sin(u * Math.PI).toFloat() else 0f
            if (extra > 0.0004f) {
                val fs = sc * (1f + extra)
                l.fg.scaleX = fs; l.fg.scaleY = fs
                if (l.fg.alpha != 1f) l.fg.alpha = 1f
            } else if (l.fg.alpha != 0f) {
                l.fg.alpha = 0f
            }
            onZoom?.invoke(sc)
            if (t < 1f && running) handler.postDelayed(this, KB_STEP_MS)
        }
    }

    private fun kenBurns(layer: Layer) {
        handler.removeCallbacks(kbTick)
        kbLayer = layer
        kbStart = android.os.SystemClock.uptimeMillis()
        kbTick.run()
    }

    private fun decode(name: String): Bitmap? = try {
        context.assets.open("wallpapers/$name").use { stream ->
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = 1
            }
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (e: Throwable) {
        null
    }

    companion object {
        private const val INTERVAL_MS = 40_000L
        private const val FADE_MS = 1_600L
        private const val KB_ZOOM = 0.07f
        private const val KB_STEP_MS = 200L
        private const val RELIEF = 0.03f
    }
}
