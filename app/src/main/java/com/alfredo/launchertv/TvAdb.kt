package com.alfredo.launchertv

import android.content.Context
import android.util.Base64
import android.util.Log
import com.cgutman.adblib.AdbBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cliente ADB que habla con la PROPIA tele (127.0.0.1:5555), igual que hace el Mac.
 * La primera vez la tele pregunta "¿Permitir depuración USB?": marcar "Permitir siempre".
 * Con eso el launcher puede hacer cosas de sistema sin root: apagar (KEYCODE_SLEEP),
 * encender la pantalla, reiniciar, pulsar teclas del mando, abrir entradas…
 *
 * Usa AdbLib (Cameron Gutman, licencia BSD-3): com.cgutman.adblib
 */
object TvAdb {
    private const val TAG = "LauncherTV"
    private val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }

    private fun crypto(context: Context): AdbCrypto {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (priv.exists() && pub.exists()) {
            try { return AdbCrypto.loadAdbKeyPair(base64, priv, pub) } catch (e: Exception) { Log.w(TAG, "clave adb ilegible, se regenera", e) }
        }
        val c = AdbCrypto.generateAdbKeyPair(base64)
        c.saveAdbKeyPair(priv, pub)
        return c
    }

    /**
     * Ejecuta un comando de shell en la tele y devuelve su salida.
     * Bloqueante (hasta [timeoutMs]); llamar desde un hilo secundario.
     * Devuelve null si no se pudo conectar (depuración desactivada o permiso no aceptado aún).
     */
    fun shell(context: Context, command: String, timeoutMs: Int = 15_000): String? {
        var socket: Socket? = null
        var conn: AdbConnection? = null
        return try {
            socket = Socket().apply {
                soTimeout = timeoutMs
                connect(InetSocketAddress("127.0.0.1", 5555), 3_000)
            }
            conn = AdbConnection.create(socket, crypto(context))
            conn.connect()
            val stream = conn.open("shell:$command")
            val out = StringBuilder()
            while (!stream.isClosed) {
                val chunk = try { stream.read() } catch (e: Exception) { break } ?: break
                out.append(String(chunk, Charsets.UTF_8))
            }
            out.toString()
        } catch (e: Exception) {
            Log.w(TAG, "adb '$command' falló: $e")
            null
        } finally {
            try { conn?.close() } catch (e: Exception) { }
            try { socket?.close() } catch (e: Exception) { }
        }
    }

    /** ¿Está el ADB de la tele accesible y autorizado? */
    fun available(context: Context): Boolean = shell(context, "echo ok", 8_000)?.contains("ok") == true

    fun key(context: Context, keycode: String) = shell(context, "input keyevent $keycode")
    fun sleep(context: Context): Boolean = key(context, "KEYCODE_SLEEP") != null
    fun wake(context: Context): Boolean = key(context, "KEYCODE_WAKEUP") != null
    fun reboot(context: Context) = shell(context, "reboot", 5_000)

    /** Pide el permiso: basta con intentar conectar; la tele muestra el diálogo. */
    fun requestPermission(context: Context) { Thread { shell(context, "echo hola", 60_000) }.start() }
}
