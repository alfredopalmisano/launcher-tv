package com.alfredo.launchertv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.tv.TvContract
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Arranca el mando web al encender la tele. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RemoteService.start(context)
    }
}

/**
 * Mando a distancia desde el móvil: pequeño servidor web en la tele (puerto 8080).
 * Abre http://IP-de-la-tele:8080 en el iPhone. Solo funciona dentro de tu Wi-Fi.
 */
class RemoteService : Service() {

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mando web", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Mando web activo")
            .setContentText("http://${localIp()}:$PORT")
            .setSmallIcon(R.drawable.ic_app)
            .build()
        startForeground(1, n)
        TvAdb.startInjector(this)
        // Al encender la tele (después de que el temporizador la apagara): brillo normal otra vez.
        registerReceiver(screenOn, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))
        Night.restoreIfExpired(this)   // dejar listo el inyector: la primera tecla ya va rápida
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { Night.restoreIfExpired(context) }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOn) } catch (e: Exception) { }
        running = false
        try { server?.close() } catch (e: Exception) { }
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startServer() {
        if (running) return
        running = true
        Thread {
            try {
                server = ServerSocket(PORT)
                Log.i(TAG, "mando web en http://${localIp()}:$PORT")
                while (running) {
                    val client = try { server!!.accept() } catch (e: Exception) { break }
                    pool.execute { try { handle(client) } catch (e: Exception) { Log.w(TAG, "web: $e") } finally { try { client.close() } catch (e: Exception) { } } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo abrir el puerto $PORT: $e")
            }
        }.start()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 8000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine() ?: return
        var line = reader.readLine()
        while (!line.isNullOrEmpty()) line = reader.readLine()   // cabeceras (no las necesitamos)
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val url = parts[1]
        val path = url.substringBefore('?')
        val query = url.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val out = client.getOutputStream()

        // Solo desde la propia red local (Wi-Fi de casa); nunca desde fuera.
        val remote = client.inetAddress
        if (!(remote.isSiteLocalAddress || remote.isLoopbackAddress || remote.isLinkLocalAddress)) { text(out, "403", 403); return }
        // Toda orden necesita el PIN que muestra la tele en el tile "Mando".
        if (path != "/" && query["pin"] != pin(this)) { json(out, JSONObject().put("ok", false).put("error", "pin")); return }

        when (path) {
            "/" -> html(out, PAGE)
            "/api/state" -> json(out, JSONObject().put("name", "Tele").put("ip", localIp()).put("adb", TvAdb.available(this)))
            "/api/apps" -> {
                val arr = JSONArray()
                for (a in AppsRepository.load(this)) arr.put(JSONObject().put("pkg", a.packageName).put("label", a.label))
                json(out, arr)
            }
            "/api/inputs" -> {
                val arr = JSONArray()
                for (i in InputsRepository.loadConnected(this)) arr.put(JSONObject().put("id", i.inputId).put("label", i.label))
                json(out, arr)
            }
            "/icon" -> icon(out, query["pkg"] ?: "")
            "/api/open" -> ok(out, openApp(query["pkg"] ?: ""))
            "/api/input" -> ok(out, openInput(query["id"] ?: ""))
            "/api/home" -> { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)); ok(out, true) }
            "/api/key" -> { val k = sanitizeKey(query["code"] ?: ""); ok(out, k != null && TvAdb.key(this, k) != null) }
            "/api/text" -> ok(out, typeText(query["t"] ?: ""))
            "/api/wake" -> ok(out, wake())
            "/api/sleep" -> ok(out, Sleep.sleepNow(this))
            "/api/sleep_in" -> { val m = query["min"]?.toIntOrNull() ?: 30; Sleep.schedule(this, m); ok(out, true) }
            "/api/reboot" -> ok(out, TvAdb.reboot(this) != null)
            else -> text(out, "404 Not Found", 404)
        }
    }

    // ---------- acciones ----------

    private fun openApp(pkg: String): Boolean {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg) ?: return false
        wake()
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun openInput(id: String): Boolean = try {
        wake()
        startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) { false }

    private fun wake(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "launchertv:wake")
            wl.acquire(5000)
            if (!pm.isInteractive) TvAdb.wake(this)
            true
        } catch (e: Exception) { false }
    }

    /**
     * Teclea texto en la tele (buscar, contraseñas de apps…). Solo letras, números y
     * puntuación corriente: nada que pueda significar algo para la shell.
     */
    private fun typeText(t: String): Boolean {
        val clean = t.take(200).filter { it.isLetterOrDigit() || it in " @._-:,?!¿¡+" }
        if (clean.isEmpty()) return false
        return TvAdb.shell(this, "input text '" + clean.replace(" ", "%s") + "'") != null
    }

    /** Solo las teclas de un mando normal; cualquier otra cosa se ignora. */
    private fun sanitizeKey(code: String): String? {
        val k = code.uppercase().removePrefix("KEYCODE_")
        return if (k in ALLOWED_KEYS) "KEYCODE_$k" else null
    }

    // ---------- respuestas ----------

    private fun ok(out: OutputStream, success: Boolean) = json(out, JSONObject().put("ok", success))

    private fun json(out: OutputStream, body: Any) = text(out, body.toString(), 200, "application/json; charset=utf-8")

    private fun html(out: OutputStream, body: String) = text(out, body, 200, "text/html; charset=utf-8")

    private fun text(out: OutputStream, body: String, code: Int = 200, type: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "200 OK" else "$code Error"
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun icon(out: OutputStream, pkg: String) {
        try {
            val d = packageManager.getApplicationBanner(pkg) ?: packageManager.getApplicationIcon(pkg)
            val w = if (d is BitmapDrawable) minOf(d.bitmap.width, 320) else 192
            val h = if (d is BitmapDrawable) w * d.bitmap.height / d.bitmap.width else 192
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); d.setBounds(0, 0, w, h); d.draw(c)
            val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
            val bytes = bos.toByteArray()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nCache-Control: max-age=3600\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes); out.flush()
        } catch (e: Exception) { text(out, "no icon", 404) }
    }

    private fun localIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress ?: "?"
    } catch (e: Exception) { "?" }

    companion object {
        const val PORT = 8080
        private val ALLOWED_KEYS = setOf(
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER", "ENTER", "BACK", "HOME", "MENU",
            "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE", "MEDIA_PLAY_PAUSE", "MEDIA_PLAY", "MEDIA_PAUSE", "MEDIA_STOP",
            "MEDIA_NEXT", "MEDIA_PREVIOUS", "MEDIA_FAST_FORWARD", "MEDIA_REWIND", "CHANNEL_UP", "CHANNEL_DOWN",
            "DEL", "SPACE", "TAB", "SEARCH", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"
        )

        /** PIN de 6 cifras propio de esta tele; se genera una vez y se enseña en el tile "Mando". */
        fun pin(context: Context): String {
            val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
            prefs.getString("pin", null)?.let { return it }
            val p = "%06d".format(java.security.SecureRandom().nextInt(1_000_000))
            prefs.edit().putString("pin", p).apply()
            return p
        }
        private const val TAG = "LauncherTV"
        private const val CHANNEL = "remote"

        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, RemoteService::class.java)) } catch (e: Exception) { Log.w(TAG, "no se pudo iniciar el mando web: $e") }
        }

        /** Página del mando (móvil). Todo en un archivo: sin dependencias. */
        val PAGE = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes"><meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<title>Tele</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent;user-select:none}
body{margin:0;background:radial-gradient(120% 80% at 50% 0%,#1d2a3a 0%,#0b0f16 60%,#06080c 100%);color:#fff;font-family:-apple-system,system-ui,Inter,sans-serif;min-height:100vh;padding:env(safe-area-inset-top) 16px calc(24px + env(safe-area-inset-bottom))}
h1{font-weight:600;font-size:22px;margin:18px 4px 6px;letter-spacing:.2px}
.sub{color:#8fa0b5;font-size:13px;margin:0 4px 16px}
.glass{background:linear-gradient(180deg,rgba(255,255,255,.14),rgba(255,255,255,.06));border:1px solid rgba(255,255,255,.18);border-radius:22px;backdrop-filter:blur(20px);box-shadow:0 10px 30px rgba(0,0,0,.35),inset 0 1px 0 rgba(255,255,255,.25)}
.row{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:14px}
button{appearance:none;border:0;color:#fff;font:inherit;cursor:pointer}
.btn{padding:16px 6px;border-radius:18px;font-size:15px;font-weight:600;text-align:center;transition:transform .08s}
.btn:active{transform:scale(.95);filter:brightness(1.25)}
.pad{position:relative;height:270px;margin:0 auto 14px;max-width:360px}
.pad .k{position:absolute;width:33%;height:33%;border-radius:20px;font-size:26px}
.pad .u{left:33.5%;top:0}.pad .d{left:33.5%;bottom:0}.pad .l{left:0;top:33.5%}.pad .r{right:0;top:33.5%}
.pad .ok{left:33.5%;top:33.5%;border-radius:999px;background:linear-gradient(180deg,rgba(255,255,255,.28),rgba(255,255,255,.1));font-size:17px;font-weight:700}
.apps{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
.app{padding:0;overflow:hidden;aspect-ratio:16/9;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.08)}
.app img{width:100%;height:100%;object-fit:cover}
.app span{font-size:12px;padding:6px;color:#dfe7f2}
.small{font-size:13px;padding:12px 6px}
.sleep{background:linear-gradient(180deg,rgba(120,130,255,.35),rgba(120,130,255,.12))}
.power{background:linear-gradient(180deg,rgba(255,90,90,.35),rgba(255,90,90,.12))}
input{width:100%;padding:14px;border-radius:16px;border:1px solid rgba(255,255,255,.18);background:rgba(255,255,255,.08);color:#fff;font:inherit;font-size:16px;margin-bottom:10px}
.toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:rgba(20,26,34,.95);padding:10px 16px;border-radius:999px;font-size:14px;opacity:0;transition:opacity .2s;pointer-events:none}
</style></head><body>
<h1>Tele</h1><p class="sub" id="sub">Conectando…</p>
<div class="row">
 <button class="btn glass power" onclick="api('/api/wake')">Encender</button>
 <button class="btn glass sleep" onclick="api('/api/sleep')">Dormir</button>
 <button class="btn glass" onclick="api('/api/home')">Inicio</button>
 <button class="btn glass" onclick="key('BACK')">Atrás</button>
</div>
<div class="pad">
 <button class="k glass u" onclick="key('DPAD_UP')">▲</button>
 <button class="k glass l" onclick="key('DPAD_LEFT')">◀</button>
 <button class="k glass ok" onclick="key('DPAD_CENTER')">OK</button>
 <button class="k glass r" onclick="key('DPAD_RIGHT')">▶</button>
 <button class="k glass d" onclick="key('DPAD_DOWN')">▼</button>
</div>
<div class="row">
 <button class="btn glass small" onclick="key('VOLUME_DOWN')">Vol −</button>
 <button class="btn glass small" onclick="key('VOLUME_MUTE')">Silencio</button>
 <button class="btn glass small" onclick="key('VOLUME_UP')">Vol +</button>
 <button class="btn glass small" onclick="key('MEDIA_PLAY_PAUSE')">⏯</button>
</div>
<form onsubmit="return send()"><input id="t" placeholder="Escribir en la tele (buscar, contraseñas…)" autocomplete="off"></form>
<div class="row" id="inputs"></div>
<div class="apps" id="apps"></div>
<div class="row" style="margin-top:14px">
 <button class="btn glass small" onclick="api('/api/sleep_in?min=30')">Dormir 30 min</button>
 <button class="btn glass small" onclick="api('/api/sleep_in?min=60')">Dormir 60 min</button>
 <button class="btn glass small" onclick="api('/api/sleep_in?min=90')">Dormir 90 min</button>
 <button class="btn glass small" onclick="if(confirm('¿Reiniciar la tele?'))api('/api/reboot')">Reiniciar</button>
</div>
<div class="toast" id="toast"></div>
<script>
const q=s=>document.querySelector(s);
function toast(m){const t=q('#toast');t.textContent=m;t.style.opacity=1;clearTimeout(t._h);t._h=setTimeout(()=>t.style.opacity=0,1400)}
let pin=new URLSearchParams(location.search).get('p')||localStorage.getItem('pin')||'';
if(pin)localStorage.setItem('pin',pin);
async function api(u){ if(!pin){pin=prompt('PIN que muestra la tele en "Mando"')||'';localStorage.setItem('pin',pin)}
 try{const r=await fetch(u+(u.includes('?')?'&':'?')+'pin='+encodeURIComponent(pin));const j=await r.json();
 if(j.error==='pin'){localStorage.removeItem('pin');pin='';toast('PIN incorrecto');return}
 if(j.ok===false)toast('La tele no aceptó la orden');return j}catch(e){toast('Sin conexión con la tele')}}
function key(k){if(navigator.vibrate)navigator.vibrate(8);api('/api/key?code='+k)}
function send(){const v=q('#t').value;if(v){api('/api/text?t='+encodeURIComponent(v));q('#t').value=''}return false}
(async()=>{
 const s=await api('/api/state'); if(s) q('#sub').textContent='http://'+s.ip+':8080 · '+(s.adb?'control total':'acepta "Permitir siempre" en la tele para control total');
 const ins=await api('/api/inputs'); if(ins) q('#inputs').innerHTML=ins.map(i=>`<button class="btn glass small" onclick="api('/api/input?id=${'$'}{encodeURIComponent(i.id)}')">${'$'}{i.label}</button>`).join('');
 const apps=await api('/api/apps'); if(apps) q('#apps').innerHTML=apps.map(a=>`<button class="app glass" onclick="api('/api/open?pkg=${'$'}{a.pkg}')"><img src="/icon?pin=${'$'}{encodeURIComponent(pin)}&pkg=${'$'}{a.pkg}" alt="" onerror="this.outerHTML='<span>${'$'}{a.label}</span>'"></button>`).join('');
})();
</script></body></html>"""
    }
}
