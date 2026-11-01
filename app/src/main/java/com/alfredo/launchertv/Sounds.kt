package com.alfredo.launchertv

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Sonidos de navegación (sintetizados, en res/raw), todos a volumen bajo:
 * moverse, moverse deprisa (más suave), cambiar de fila (más grave), abrir y volver de una app.
 */
object Sounds {
    const val MOVE = 0
    const val LIGHT = 1
    const val ROW = 2
    const val OPEN = 3
    const val BACK = 4
    private const val VOLUME = 0.15f
    private var pool: SoundPool? = null
    private val ids = IntArray(5)

    fun init(context: Context) {
        if (pool != null) return
        val sp = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        ids[MOVE] = sp.load(context, R.raw.click, 1)
        ids[LIGHT] = sp.load(context, R.raw.click_light, 1)
        ids[ROW] = sp.load(context, R.raw.click_row, 1)
        ids[OPEN] = sp.load(context, R.raw.open, 1)
        ids[BACK] = sp.load(context, R.raw.back, 1)
        pool = sp
    }

    fun play(which: Int) {
        val sp = pool ?: return
        val v = if (which == LIGHT) VOLUME * 0.6f else VOLUME
        sp.play(ids[which], v, v, 1, 0, 1f)
    }
}
