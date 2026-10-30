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

    /** Se llama con cada foto nueva: la foto, y la línea del horizonte (o null si no hay cielo). */
    var onPhotoChanged: ((Bitmap, Skyline.Result?) -> Unit)? = null

    private val layers = listOf(Layer(photoA, fgA), Layer(photoB, fgB))
    private var current = 0
    private val handler = Handler(Looper.getMainLooper())
    private val files: List<String>
    private val credits: Map<String, String>
    private var index = -1
    private var running = false
    private var zoom: ValueAnimator? = null

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

    fun start() {
        if (running) return
        running = true
        if (index < 0) advance() else kenBurns(layers[current])
        handler.postDelayed(next, INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(next)
        zoom?.cancel()
    }

    private fun advance() {
        if (files.isEmpty()) return
        index = (index + 1) % files.size
        val bmp = decode(files[index]) ?: return
        val sky = try { Skyline.compute(bmp) } catch (e: Throwable) { null }

        val outgoing = layers[current]
        current = 1 - current
        val incoming = layers[current]

        incoming.photo.setImageBitmap(bmp)
        incoming.fg.setImageBitmap(sky?.foreground)
        for (v in incoming.views()) { v.scaleX = 1f; v.scaleY = 1f; v.alpha = 0f }

        fade(incoming.photo, 1f, null)
        fade(incoming.fg, 1f, null)
        fade(outgoing.photo, 0f) { outgoing.photo.setImageDrawable(null) }
        fade(outgoing.fg, 0f) { outgoing.fg.setImageDrawable(null) }
        kenBurns(incoming)

        onPhotoChanged?.invoke(bmp, sky)
        val author = credits[files[index]]
        credit.text = if (author != null) context.getString(R.string.credit, author) else ""
    }

    private fun fade(view: View, to: Float, end: (() -> Unit)?) {
        ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, to).apply {
            duration = FADE_MS
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { end() }
            })
            start()
        }
    }

    /** Zoom lento aplicado a la vez a la foto y al primer plano, para que sigan alineados. */
    private fun kenBurns(layer: Layer) {
        zoom?.cancel()
        zoom = ValueAnimator.ofFloat(1f, 1.07f).apply {
            duration = INTERVAL_MS + FADE_MS
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val s = a.animatedValue as Float
                for (v in layer.views()) { v.scaleX = s; v.scaleY = s }
            }
            start()
        }
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
    }
}
