package com.alfredo.launchertv

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Tiempos de apagado acordados: SIN salvapantallas, y la tele se apaga sola tras 60 minutos sin
 * tocar el mando (para cuando te quedas dormido; para antes está el temporizador de Dormir).
 *
 * Al desactivar el salvapantallas, Android apaga la pantalla al llegar a "screen_off_timeout"
 * (aquí estaba en ~5 min): en el menú de una app (SkyShowtime…) la tele se apagaba a los 5 minutos.
 * Este arreglo lo sube a 60 minutos si está por debajo. Usa el ADB interno de la tele (TvAdb).
 */
object Power {
    private const val TAG = "LauncherTV"
    const val IDLE_OFF_MS = 3_600_000   // 60 minutos

    /** Llamar en segundo plano (bloquea mientras habla con el ADB interno). */
    fun ensureTimeouts(context: Context) {
        try {
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
            if (cur in 0 until IDLE_OFF_MS) {
                val r = TvAdb.shell(context, "settings put system screen_off_timeout $IDLE_OFF_MS")
                Log.i(TAG, "apagado por inactividad: ${cur / 1000}s -> ${IDLE_OFF_MS / 1000}s (${if (r != null) "ok" else "sin adb"})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo revisar el apagado por inactividad: $e")
        }
    }
}
