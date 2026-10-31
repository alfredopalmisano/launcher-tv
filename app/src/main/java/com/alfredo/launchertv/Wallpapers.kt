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
        val blurred: Bitmap
    )

    /** Se llama con cada foto nueva, ya preparada, justo cuando empieza su fundido. */
    var onPhotoChanged: ((Prepared) -> Unit)? = null
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
        if (index < 0) advance() else kenBurns(top)
        handler.postDelayed(next, INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(next)
        zoom?.cancel()
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
        // Sin foto debajo (solo al arrancar), negro; con foto, nada: la foto ya tapa la pantalla.
        if (bottom.photo.drawable == null) bottom.photo.setBackgroundColor(android.graphics.Color.BLACK)
        else bottom.photo.background = null
        bottom.photo.scaleX = s; bottom.photo.scaleY = s
        bottom.photo.alpha = 1f
        // 2) La foto nueva entra arriba desde transparente. (La montaña ya no es una capa aparte:
        //    el reloj se recorta con su máscara, así el fundido de las fotos es exacto y la
        //    gráfica pinta dos pantallas completas menos en cada fotograma.)
        top.photo.setImageBitmap(bmp)
        top.photo.scaleX = 1f; top.photo.scaleY = 1f; top.photo.alpha = 0f
        fade(top.photo, 1f) { bottom.photo.setImageDrawable(null); bottom.photo.background = null }
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
        // El degradado oscuro de abajo (detrás del dock) se pinta dentro de la foto una vez,
        // en vez de ser una capa a pantalla completa en cada fotograma. Se ve igual.
        try {
            context.getDrawable(R.drawable.scrim)?.mutate()?.let { d ->
                d.setBounds(0, 0, bmp.width, bmp.height)
                d.draw(android.graphics.Canvas(bmp))
            }
        } catch (e: Exception) { }
        val plan = layouts[name]
        val mask = if (plan?.useFg == true) layouts.mask(name) else null
        val dm = context.resources.displayMetrics
        val (glass, bright) = GlassTextView.prepareGlass(bmp, dm.widthPixels, dm.heightPixels)
        val tiny = Bitmap.createScaledBitmap(bmp, 32, 18, true)
        val blurred = Bitmap.createScaledBitmap(tiny, 320, 180, true)
        tiny.recycle()
        return Prepared(i, bmp, plan, mask, glass, plan?.bright ?: bright, blurred)
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
     * Antes había un zoom lento continuo (Ken Burns). Se quitó: obligaba a redibujar la
     * pantalla entera 60 veces por segundo y la tele no llegaba (medido: ~38 fps en reposo).
     * Ahora, sin cambio de foto, la tele no tiene que redibujar nada.
     */
    private fun kenBurns(layer: Layer) {
        zoom?.cancel()
        layer.photo.scaleX = 1f; layer.photo.scaleY = 1f
        onZoom?.invoke(1f)
    }

    private fun decode(name: String): Bitmap? = try {
        context.assets.open("wallpapers/$name").use { stream ->
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true   // para pintar el degradado dentro
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
    }
}
