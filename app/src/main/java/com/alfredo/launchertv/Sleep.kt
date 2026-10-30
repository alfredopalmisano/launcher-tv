package com.alfredo.launchertv

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Temporizador de apagado. Para poner la tele en reposo la app necesita ser "administrador
 * de dispositivo" (se activa una vez por ADB: paso5). Entonces lockNow() apaga la pantalla
 * y la tele entra en reposo, aunque el launcher esté en segundo plano.
 */
class SleepAdminReceiver : DeviceAdminReceiver()

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Sleep.sleepNow(context)
    }
}

object Sleep {
    private const val PREF = "sleep_at"

    fun admin(context: Context) = ComponentName(context, SleepAdminReceiver::class.java)

    fun isAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(admin(context))
    }

    fun sleepNow(context: Context): Boolean {
        clear(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return try {
            if (!dpm.isAdminActive(admin(context))) return false
            dpm.lockNow()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Programa el reposo dentro de [minutes] minutos. */
    fun schedule(context: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        prefs(context).edit().putLong(PREF, at).apply()
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context))
        clear(context)
    }

    /** Minutos que faltan, o null si no hay temporizador. */
    fun remainingMinutes(context: Context): Int? {
        val at = prefs(context).getLong(PREF, 0L)
        if (at <= System.currentTimeMillis()) return null
        return ((at - System.currentTimeMillis()) / 60_000L).toInt() + 1
    }

    private fun clear(context: Context) = prefs(context).edit().remove(PREF).apply()
    private fun prefs(context: Context) = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 7, Intent(context, SleepReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
