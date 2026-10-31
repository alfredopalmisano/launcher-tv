package com.alfredo.launchertv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Modo noche: mientras hay un temporizador de Dormir, el brillo de la tele baja a 10 y las
 * fotos se oscurecen. Al quitar el temporizador (o al volver a encender la tele después de que
 * se apagara sola) el brillo vuelve al valor que tenía y todo vuelve a la normalidad.
 *
 * El brillo de esta Xiaomi es el ajuste global "picture_backlight" (0–100); se cambia con el
 * ADB interno de la tele (TvAdb), que ya está autorizado.
 */
object Night {
    private const val KEY_PREV = "backlight_prev"
    private const val SETTING = "picture_backlight"
    const val NIGHT_BACKLIGHT = 10

    /** Avisa (en el hilo principal) cuando el modo cambia, para oscurecer o aclarar las fotos. */
    var onChange: ((Boolean) -> Unit)? = null

    fun isOn(context: Context): Boolean = prefs(context).contains(KEY_PREV)

    fun on(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_PREV)) {
            val prev = try { Settings.Global.getInt(context.contentResolver, SETTING) } catch (e: Exception) { 50 }
            p.edit().putInt(KEY_PREV, prev).apply()
        }
        setBacklight(context, NIGHT_BACKLIGHT)
        notify(true)
    }

    fun off(context: Context) {
        val p = prefs(context)
        val prev = p.getInt(KEY_PREV, -1)
        p.edit().remove(KEY_PREV).apply()
        if (prev >= 0) setBacklight(context, prev)
        notify(false)
    }

    /** Al encender la tele: si se apagó por el temporizador, volver a la normalidad. */
    fun restoreIfExpired(context: Context) {
        if (isOn(context) && Sleep.remainingMinutes(context) == null) off(context)
    }

    private fun setBacklight(context: Context, value: Int) {
        val app = context.applicationContext
        Thread { TvAdb.shell(app, "settings put global $SETTING $value") }.start()
    }

    private fun notify(on: Boolean) = Handler(Looper.getMainLooper()).post { onChange?.invoke(on) }
    private fun prefs(context: Context) = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
}
