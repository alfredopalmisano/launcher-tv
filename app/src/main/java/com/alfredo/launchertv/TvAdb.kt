package com.alfredo.launchertv

import android.content.Context
import android.util.Base64
import android.util.Log
import com.cgutman.adblib.AdbBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import com.cgutman.adblib.AdbStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Cliente ADB que habla con la PROPIA tele (127.0.0.1:5555), igual que hace el Mac.
 * La primera vez la tele pregunta "¿Permitir depuración USB?": marcar "Permitir siempre".
 * Con eso el launcher puede hacer cosas de sistema sin root: apagar (KEYCODE_SLEEP),
 * encender la pantalla, reiniciar, pulsar teclas del mando, abrir entradas…
 *
 * Rápido: la conexión se abre una vez y se reutiliza, y las teclas las pulsa un proceso
 * [KeyInjector] que se queda arrancado (como hace scrcpy), en vez de lanzar `input` cada vez.
 *
 * Usa AdbLib (Cameron Gutman, licencia BSD-3): com.cgutman.adblib
 */
object TvAdb {
    private const val TAG = "LauncherTV"
    private val base64 = AdbBase64 { data -> Base64.encodeToString(data, Base64.NO_WRAP) }

    /** Protege la conexión: abrir streams y escribir no es seguro desde varios hilos a la vez. */
    private val lock = Any()
    private var socket: Socket? = null
    private var conn: AdbConnection? = null
    private var injector: AdbStream? = null
    @Volatile private var injectorStarting = false

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

    /** Conexión abierta (la crea si hace falta). Llamar con [lock]. */
    private fun connection(context: Context, timeoutMs: Int): AdbConnection {
        conn?.let { return it }
        val s = Socket().apply { connect(InetSocketAddress("127.0.0.1", 5555), 3_000) }
        // Solo el saludo tiene límite de tiempo (si la tele no da permiso, se corta);
        // después la conexión se queda abierta sin límite para reutilizarla.
        val guard = deadline(timeoutMs) { try { s.close() } catch (x: Exception) { } }
        try {
            val c = AdbConnection.create(s, crypto(context))
            c.connect()
            guard.interrupt()
            socket = s; conn = c
            return c
        } catch (e: Exception) {
            guard.interrupt()
            try { s.close() } catch (x: Exception) { }
            throw e
        }
    }

    /** Ejecuta [onTimeout] si pasan [ms] sin que se interrumpa el hilo devuelto. */
    private fun deadline(ms: Int, onTimeout: () -> Unit): Thread = Thread {
        try { Thread.sleep(ms.toLong()) } catch (e: InterruptedException) { return@Thread }
        onTimeout()
    }.apply { isDaemon = true; start() }

    /** Cierra la conexión (se volverá a abrir en la siguiente orden). Llamar con [lock]. */
    private fun reset() {
        try { injector?.close() } catch (e: Exception) { }
        try { conn?.close() } catch (e: Exception) { }
        try { socket?.close() } catch (e: Exception) { }
        injector = null; conn = null; socket = null
    }

    /** Abre un stream, reintentando una vez con conexión nueva si la anterior se había caído. */
    private fun open(context: Context, destination: String, timeoutMs: Int): AdbStream = synchronized(lock) {
        try {
            connection(context, timeoutMs).open(destination)
        } catch (e: Exception) {
            reset()
            connection(context, timeoutMs).open(destination)
        }
    }

    /**
     * Ejecuta un comando de shell en la tele y devuelve su salida.
     * Bloqueante (hasta [timeoutMs]); llamar desde un hilo secundario.
     * Devuelve null si no se pudo conectar (depuración desactivada o permiso no aceptado aún).
     */
    fun shell(context: Context, command: String, timeoutMs: Int = 15_000): String? {
        return try {
            val stream = open(context, "shell:$command", timeoutMs)
            // Si el comando no termina a tiempo, se cierra su stream (la conexión sigue viva).
            val guard = deadline(timeoutMs) { try { stream.close() } catch (x: Exception) { } }
            val out = StringBuilder()
            while (!stream.isClosed) {
                val chunk = try { stream.read() } catch (e: Exception) { break } ?: break
                out.append(String(chunk, Charsets.UTF_8))
            }
            guard.interrupt()
            try { stream.close() } catch (e: Exception) { }
            out.toString()
        } catch (e: Exception) {
            Log.w(TAG, "adb '$command' falló: $e")
            synchronized(lock) { reset() }
            null
        }
    }

    /** ¿Está el ADB de la tele accesible y autorizado? */
    fun available(context: Context): Boolean = shell(context, "echo ok", 8_000)?.contains("ok") == true

    /**
     * Pulsa una tecla. Vía rápida: el inyector ya arrancado (unos milisegundos).
     * Si aún no está listo, la pulsa con `input keyevent` (lento, ~1 s) y arranca el inyector para las siguientes.
     */
    fun key(context: Context, keycode: String): String? {
        val inj = injector
        if (inj != null && !inj.isClosed) {
            try {
                synchronized(lock) { inj.write((keycode + "\n").toByteArray()) }
                return ""
            } catch (e: Exception) {
                Log.w(TAG, "inyector caído: $e")
                synchronized(lock) { if (injector === inj) injector = null }
            }
        }
        startInjector(context)
        return shell(context, "input keyevent $keycode")
    }

    /** Arranca (en segundo plano) el proceso que inyecta teclas, si no está ya. */
    fun startInjector(context: Context) {
        if (injectorStarting || injector?.isClosed == false) return
        injectorStarting = true
        val apk = context.applicationInfo.sourceDir
        Thread {
            try {
                val cmd = "shell:CLASSPATH=$apk exec app_process /system/bin com.alfredo.launchertv.KeyInjector"
                val s = open(context, cmd, 8_000)
                // Leer y tirar lo que escriba (el eco de la terminal), para que no se llene el búfer.
                Thread {
                    while (!s.isClosed) { try { s.read() ?: break } catch (e: Exception) { break } }
                    Log.i(TAG, "inyector terminado")
                }.start()
                synchronized(lock) { injector = s }
                Log.i(TAG, "inyector de teclas listo")
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo arrancar el inyector: $e")
            } finally {
                injectorStarting = false
            }
        }.start()
    }

    fun sleep(context: Context): Boolean = key(context, "KEYCODE_SLEEP") != null
    fun wake(context: Context): Boolean = key(context, "KEYCODE_WAKEUP") != null
    fun reboot(context: Context) = shell(context, "reboot", 5_000)

    /** Pide el permiso: basta con intentar conectar; la tele muestra el diálogo. */
    fun requestPermission(context: Context) { Thread { shell(context, "echo hola", 60_000) }.start() }
}
