package com.alfredo.launchertv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView

/** Fondos de naturaleza que rotan con fundido y un zoom muy lento (efecto Ken Burns). */
class Wallpapers(
    private val context: Context,
    private val viewA: ImageView,
    private val viewB: ImageView,
    private val credit: TextView
) {
    /** Se llama con cada foto nueva (para el reloj de cristal). */
    var onPhotoChanged: ((Bitmap) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val files: List<String>
    private val credits: Map<String, String>
    private var index = -1
    private var showingA = true
    private var running = false

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
        if (index < 0) advance() else kenBurns(if (showingA) viewA else viewB)
        handler.postDelayed(next, INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(next)
        viewA.animate().cancel()
        viewB.animate().cancel()
    }

    private fun advance() {
        if (files.isEmpty()) return
        index = (index + 1) % files.size
        val bmp = decode(files[index]) ?: return

        val incoming = if (showingA) viewB else viewA
        val outgoing = if (showingA) viewA else viewB
        showingA = !showingA

        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.setImageBitmap(bmp)
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.alpha = 0f

        // Fundido con ObjectAnimator (independiente del zoom, que usa animate()).
        ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).apply {
            duration = FADE_MS
            start()
        }
        ObjectAnimator.ofFloat(outgoing, View.ALPHA, outgoing.alpha, 0f).apply {
            duration = FADE_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (outgoing !== (if (showingA) viewA else viewB)) outgoing.setImageDrawable(null)
                }
            })
            start()
        }
        kenBurns(incoming)
        onPhotoChanged?.invoke(bmp)

        val author = credits[files[index]]
        credit.text = if (author != null) context.getString(R.string.credit, author) else ""
    }

    private fun kenBurns(view: ImageView) {
        view.animate().scaleX(1.07f).scaleY(1.07f)
            .setDuration(INTERVAL_MS + FADE_MS)
            .setInterpolator(LinearInterpolator())
            .start()
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
