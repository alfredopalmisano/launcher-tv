package com.alfredo.launchertv

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.KeyEvent
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var clock: GlassTextView
    private lateinit var dock: RecyclerView
    private lateinit var recentsRow: RecyclerView
    private lateinit var systemRow: RecyclerView
    private lateinit var rowsScroll: ScrollView
    private lateinit var rowsBox: LinearLayout
    private lateinit var topSpace: Space
    private lateinit var dim: View
    private lateinit var clockBox: View
    private var dockTopPx = 0f
    private var ambient = false
    private var dockFocusPos = 0
    private var movingPkg: String? = null
    private val idleToAmbient = Runnable { if (!ambient && overlay.visibility != View.VISIBLE) setAmbient(true) }
    private lateinit var overlay: View
    private lateinit var grid: RecyclerView
    private lateinit var wallpapers: Wallpapers
    private lateinit var favorites: Favorites

    private val dockAdapter = TileAdapter(DOCK_TILE_DP, false, ::open) { showAppMenu(it, fromDock = true) }
    private val recentsAdapter = TileAdapter(PROGRAM_TILE_DP, true, ::open) { false }
    private val systemAdapter = TileAdapter(ROW_TILE_DP, true, ::open) { false }
    private val gridAdapter = TileAdapter(GRID_TILE_DP, true, ::open) { showAppMenu(it, fromDock = false) }

    private var apps: List<Tile.App> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = timeFormat.format(now)
            handler.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L) + 20L)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { reload() }
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) { handler.post { refreshDock() } }
        override fun onInputAdded(inputId: String) { handler.post { refreshDock() } }
        override fun onInputRemoved(inputId: String) { handler.post { refreshDock() } }
        override fun onInputUpdated(inputId: String) { handler.post { refreshDock() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Sin salvapantallas mientras se ve el launcher.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        clock = findViewById(R.id.clock)
        overlay = findViewById(R.id.overlay)
        rowsScroll = findViewById(R.id.rowsScroll)
        rowsBox = findViewById(R.id.rows)
        topSpace = findViewById(R.id.topSpace)
        dim = findViewById(R.id.dim)
        clockBox = findViewById(R.id.clockBox)
        RemoteService.start(this)
        dock = addRow(R.string.row_favorites, dockAdapter)
        systemRow = addRow(R.string.row_system, systemAdapter)
        recentsRow = addRow(R.string.row_recents, recentsAdapter)
        grid = findViewById(R.id.grid)
        favorites = Favorites(this)

        val dm = resources.displayMetrics
        clock.typeface = Fonts.clock(this)
        clock.textSizePx = 180f * dm.scaledDensity
        clock.glassAmount = 0.92f
        findViewById<TextView>(R.id.credit).typeface = Fonts.light(this)
        findViewById<TextView>(R.id.overlayTitle).typeface = Fonts.light(this)

        wallpapers = Wallpapers(this,
            findViewById(R.id.wallA), findViewById(R.id.fgA),
            findViewById(R.id.wallB), findViewById(R.id.fgB),
            findViewById(R.id.credit))
        val overlayBg = findViewById<ImageView>(R.id.overlayBg)
        wallpapers.onPhotoChanged = { photo, plan ->
            clock.setBackdrop(photo, dm.widthPixels, dm.heightPixels)
            if (plan != null) clock.setBright(plan.bright)
            placeClock(clockBox, plan, dm.heightPixels, photo.height)
            // Fondo de la pantalla de apps: la misma foto muy difuminada.
            val tiny = Bitmap.createScaledBitmap(photo, 32, 18, true)
            overlayBg.setImageBitmap(Bitmap.createScaledBitmap(tiny, 320, 180, true))
            tiny.recycle()
        }

        // La primera fila queda abajo; las demás, fuera de pantalla hasta que bajes.
        rowsScroll.viewTreeObserver.addOnGlobalLayoutListener {
            val firstRow = rowsBox.getChildAt(1) ?: return@addOnGlobalLayoutListener
            val wanted = rowsScroll.height - firstRow.height - (16 * dm.density).toInt()
            if (wanted > 0 && topSpace.layoutParams.height != wanted) {
                topSpace.layoutParams = topSpace.layoutParams.apply { height = wanted }
                topSpace.requestLayout()
            }
            dockTopPx = (wanted + (36 * dm.density)).toFloat()
        }
        rowsScroll.viewTreeObserver.addOnScrollChangedListener {
            // Al bajar a las otras filas se oscurece el fondo y se atenúa el reloj.
            val p = (rowsScroll.scrollY / (260 * dm.density)).coerceIn(0f, 1f)
            dim.alpha = 0.65f * p
            clockBox.alpha = 1f - p
            // Las filas de abajo no se ven hasta que bajas.
            for (i in 2 until rowsBox.childCount) rowsBox.getChildAt(i).alpha = (p * 2f).coerceIn(0f, 1f)
        }

        val gridWidthDp = dm.widthPixels / dm.density - 2 * 52
        grid.layoutManager = GridLayoutManager(this, (gridWidthDp / (GRID_TILE_DP + 24)).toInt().coerceAtLeast(3))
        grid.adapter = gridAdapter
        grid.itemAnimator = null

        hideSystemUi()
        reload()
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
        resetIdle()
        if (ambient) setAmbient(false)
        wallpapers.start()
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        InputsRepository.manager(this)?.registerCallback(inputCallback, handler)
        refreshDock()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(idleToAmbient)
        wallpapers.stop()
        try { unregisterReceiver(packageReceiver) } catch (e: Exception) { }
        try { InputsRepository.manager(this)?.unregisterCallback(inputCallback) } catch (e: Exception) { }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Botón Inicio estando ya en el launcher: cerrar la cuadrícula y volver al dock.
        if (overlay.visibility == View.VISIBLE) hideAllApps()
    }

    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE) hideAllApps()
        // En la pantalla principal "atrás" no hace nada: somos el inicio.
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetIdle()
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode

        // Modo ambiente (solo fondo y reloj): cualquier tecla vuelve a mostrar las apps.
        if (ambient) {
            if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_HOME) { setAmbient(false); return true }
        }

        // Modo mover: ← → recolocan la app; OK suelta; Atrás cancela.
        val pkg = movingPkg
        if (pkg != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (favorites.move(pkg, -1)) refreshDock(focus = "app:$pkg"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (favorites.move(pkg, +1)) refreshDock(focus = "app:$pkg"); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { stopMoving(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
        }

        // Arriba desde la primera fila: esconder las apps y dejar solo el fondo con el reloj.
        if (code == KeyEvent.KEYCODE_DPAD_UP && overlay.visibility != View.VISIBLE && dock.hasFocus() && rowsScroll.scrollY == 0) {
            setAmbient(true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun resetIdle() {
        handler.removeCallbacks(idleToAmbient)
        handler.postDelayed(idleToAmbient, IDLE_MS)
    }

    private fun setAmbient(on: Boolean) {
        ambient = on
        val d = resources.displayMetrics.density
        if (on) {
            // Recordar en qué app estaba el cursor para volver exactamente ahí.
            val focused = dock.focusedChild
            if (focused != null) dockFocusPos = dock.getChildAdapterPosition(focused).coerceAtLeast(0)
            rowsScroll.scrollTo(0, 0)
        }
        rowsScroll.animate().alpha(if (on) 0f else 1f).translationY(if (on) 40 * d else 0f).setDuration(350).withEndAction {
            if (!on) dock.post {
                (dock.layoutManager?.findViewByPosition(dockFocusPos) ?: dock.getChildAt(0))?.requestFocus()
            }
        }.start()
    }

    private fun startMoving(tile: Tile.App) {
        movingPkg = tile.packageName
        dockAdapter.movingId = tile.id
        toast(getString(R.string.move_hint))
        refreshDock(focus = tile.id)
    }

    private fun stopMoving() {
        val id = movingPkg?.let { "app:$it" }
        movingPkg = null
        dockAdapter.movingId = null
        if (id != null) refreshDock(focus = id)
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    /** Añade una fila horizontal (título + tarjetas) al contenedor de filas. */
    private fun addRow(titleRes: Int, adapter: TileAdapter): RecyclerView {
        val d = resources.displayMetrics.density
        val first = rowsBox.childCount < 2
        val title = TextView(this).apply {
            text = getString(titleRes)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 15f
            typeface = Fonts.medium(this@MainActivity)
            setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
            setPadding((64 * d).toInt(), (10 * d).toInt(), 0, 0)
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding((52 * d).toInt(), 0, (52 * d).toInt(), 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            if (!first) addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (first) {
                // Estante de cristal detrás del dock (estilo Apple TV).
                val shelf = android.widget.FrameLayout(this@MainActivity).apply {
                    background = getDrawable(R.drawable.dock_shelf)
                    clipChildren = false
                    clipToPadding = false
                    setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                    addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                list.setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                list.isHorizontalFadingEdgeEnabled = true
                list.setFadingEdgeLength((56 * d).toInt())
                addView(shelf, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (24 * d).toInt(); marginEnd = (24 * d).toInt()
                })
            } else {
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        if (rowsBox.childCount >= 2) row.alpha = 0f   // filas de abajo: ocultas hasta bajar
        rowsBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return list
    }

    private fun reload() {
        apps = AppsRepository.load(this)
        gridAdapter.submit(apps)
        refreshDock()
    }

    private fun refreshRows() {
        val programs = WatchNextRepository.load(this)
        recentsAdapter.submit(programs)
        (recentsRow.parent as View).visibility = if (programs.isEmpty()) View.GONE else View.VISIBLE

        val sys = ArrayList<Tile>()
        sys.addAll(InputsRepository.loadConnected(this))
        sys.add(Tile.AirPlay)
        sys.add(Tile.Action("wifi", getString(R.string.wifi), R.drawable.ic_wifi))
        sys.add(Tile.Action("bt", getString(R.string.bluetooth), R.drawable.ic_bluetooth))
        sys.add(Tile.SleepTile)
        sys.add(Tile.Action("reboot", getString(R.string.reboot), R.drawable.ic_restart))
        sys.add(Tile.Action("remote", getString(R.string.remote), R.drawable.ic_phone))
        sys.add(Tile.Store)
        sys.add(Tile.SettingsTile)
        systemAdapter.submit(sys)
    }

    private fun refreshDock(focus: String? = null) {
        val hadFocus = dock.hasFocus()
        val tiles = ArrayList<Tile>()
        tiles.addAll(favorites.get(apps))
        tiles.add(Tile.AllApps)
        dockAdapter.submit(tiles)
        refreshRows()
        if (focus != null) {
            val pos = dockAdapter.indexOf(focus)
            if (pos >= 0) dock.post { dock.layoutManager?.findViewByPosition(pos)?.requestFocus() }
        } else if (!hadFocus && overlay.visibility != View.VISIBLE) {
            dock.post { dock.getChildAt(0)?.requestFocus() }
        }
    }

    private fun open(tile: Tile) {
        when (tile) {
            is Tile.App -> {
                val intent = AppsRepository.launchIntent(this, tile)
                if (intent == null || !tryStart(intent)) toast(getString(R.string.cannot_open, tile.label))
            }
            is Tile.Input -> {
                if (!tryStart(InputsRepository.launchIntent(tile))) {
                    // Plan B: abrir la app de TV en directo de la tele.
                    val tv = packageManager.getLeanbackLaunchIntentForPackage("com.mediatek.wwtv.tvcenter")
                    if (tv == null || !tryStart(tv)) toast(getString(R.string.cannot_open, tile.label))
                }
            }
            Tile.AllApps -> showAllApps()
            Tile.SettingsTile -> {
                if (!tryStart(Intent(Settings.ACTION_SETTINGS))) toast(getString(R.string.cannot_open, "Ajustes"))
            }
            Tile.AirPlay -> openAirPlay()
            Tile.SleepTile -> showSleepMenu()
            is Tile.Program -> {
                val i = try { tile.intentUri?.let { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) } } catch (e: Exception) { null }
                if (i == null || !tryStart(i)) {
                    val app = packageManager.getLeanbackLaunchIntentForPackage(tile.packageName)
                    if (app == null || !tryStart(app)) toast(getString(R.string.cannot_open, tile.label))
                }
            }
            is Tile.Action -> when (tile.id) {
                "wifi" -> if (!tryStart(Intent(Settings.ACTION_WIFI_SETTINGS))) tryStart(Intent(Settings.ACTION_SETTINGS))
                "bt" -> if (!tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) tryStart(Intent(Settings.ACTION_SETTINGS))
                "remote" -> showRemoteInfo()
                "reboot" -> AlertDialog.Builder(this, R.style.Theme_Launcher_Dialog)
                    .setTitle(R.string.reboot_title)
                    .setItems(arrayOf(getString(R.string.reboot), getString(R.string.menu_cancel))) { _, which ->
                        if (which == 0) Thread { if (TvAdb.reboot(this) == null) runOnUiThread { toast(getString(R.string.sleep_no_admin)) } }.start()
                    }.show()
            }
            Tile.Store -> {
                val store = packageManager.getLeanbackLaunchIntentForPackage("com.android.vending")
                    ?: packageManager.getLaunchIntentForPackage("com.android.vending")
                if (store == null || !tryStart(store)) toast(getString(R.string.cannot_open, getString(R.string.store)))
            }
        }
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Coloca el reloj donde dice el plan precalculado de la foto (borde superior de las cifras,
     * en píxeles de pantalla). Sin plan: arriba, a 48 dp. Nunca en la zona de las apps.
     */
    private fun placeClock(box: View, plan: Layouts.Entry?, screenH: Int, photoH: Int) {
        if (clock.height == 0) { box.post { placeClock(box, plan, screenH, photoH) }; return }
        val d = resources.displayMetrics.density
        val digitH = clock.textSizePx * 0.88f
        val dockTop = if (dockTopPx > 0f) dockTopPx else screenH * 0.78f
        val maxDigitTop = dockTop - digitH - 24 * d
        val digitTop = (plan?.digitTop?.toFloat()?.times(screenH.toFloat() / photoH) ?: (48 * d)).coerceIn(24 * d, maxDigitTop)
        val viewTop = digitTop - clock.digitTopOffset()
        android.util.Log.i(TAG, "reloj: digitTop=%.0f viewTop=%.0f fg=%b".format(digitTop, viewTop, plan?.useFg == true))
        box.animate().translationY(viewTop - 72 * d).setDuration(1200).start()
    }

    /** Mando desde el móvil: muestra la dirección a abrir en el iPhone. */
    private fun showRemoteInfo() {
        RemoteService.start(this)
        val ip = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
        } catch (e: Exception) { null }
        val url = if (ip != null) "http://$ip:${RemoteService.PORT}" else getString(R.string.remote_no_wifi)
        AlertDialog.Builder(this, R.style.Theme_Launcher_Dialog)
            .setTitle(R.string.remote_title)
            .setMessage(getString(R.string.remote_message, url, RemoteService.pin(this)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Temporizador de apagado (tile Dormir). */
    private fun showSleepMenu() {
        // Comprobar en segundo plano si la tele acepta órdenes (ADB interno); si no, pedir el permiso.
        Thread {
            val ok = TvAdb.available(this)
            android.util.Log.i(TAG, "Dormir: adb interno=$ok, admin=${Sleep.isAdmin(this)}")
            if (!ok && !Sleep.isAdmin(this)) runOnUiThread { toast(getString(R.string.sleep_no_admin)); TvAdb.requestPermission(this) }
        }.start()
        val remaining = Sleep.remainingMinutes(this)
        val options = listOf(15, 30, 45, 60, 90, 120)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (remaining != null) {
            labels.add(getString(R.string.sleep_cancel_timer, remaining))
            actions.add { Sleep.cancel(this); toast(getString(R.string.sleep_cancelled)) }
        }
        labels.add(getString(R.string.sleep_now)); actions.add {
            Thread { if (!Sleep.sleepNow(this)) runOnUiThread { toast(getString(R.string.sleep_no_admin)) } }.start()
        }
        for (m in options) {
            labels.add(getString(R.string.sleep_minutes, m))
            actions.add { Sleep.schedule(this, m); toast(getString(R.string.sleep_set, m)) }
        }
        labels.add(getString(R.string.menu_cancel)); actions.add { }
        AlertDialog.Builder(this, R.style.Theme_Launcher_Dialog)
            .setTitle(R.string.sleep_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /**
     * AirPlay: abre el receptor instalado (AirScreen); si no está, lleva a instalarlo.
     * (Fase 2: receptor AirPlay propio dentro del launcher.)
     */
    private fun openAirPlay() {
        for (pkg in AIRPLAY_RECEIVERS) {
            val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            if (i != null && tryStart(i)) return
        }
        toast(getString(R.string.airplay_install))
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + AIRPLAY_RECEIVERS[0]))
        if (!tryStart(market)) {
            val store = packageManager.getLeanbackLaunchIntentForPackage("com.android.vending")
            if (store != null) tryStart(store)
        }
    }

    /** Menú al mantener pulsado OK sobre una app. */
    private fun showAppMenu(tile: Tile, fromDock: Boolean): Boolean {
        if (tile !is Tile.App) return false
        val inDock = favorites.contains(tile.packageName)
        val isSystem = try {
            packageManager.getApplicationInfo(tile.packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        } catch (e: Exception) { true }

        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(text: Int, action: () -> Unit) { labels.add(getString(text)); actions.add(action) }

        if (inDock) {
            item(R.string.menu_remove_dock) {
                favorites.toggle(tile.packageName)
                toast(getString(R.string.removed_from_dock, tile.label)); refreshDock()
            }
            if (fromDock) {
                item(R.string.menu_move) { startMoving(tile) }
            }
        } else {
            item(R.string.menu_add_dock) {
                favorites.toggle(tile.packageName)
                toast(getString(R.string.added_to_dock, tile.label)); refreshDock()
            }
        }
        if (!isSystem) {
            item(R.string.menu_uninstall) {
                tryStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + tile.packageName)))
            }
        }
        item(R.string.menu_app_info) {
            tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + tile.packageName)))
        }
        item(R.string.menu_cancel) { }

        AlertDialog.Builder(this, R.style.Theme_Launcher_Dialog)
            .setTitle(tile.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
        return true
    }

    private fun showAllApps() {
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(200).start()
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    private fun hideAllApps() {
        overlay.animate().alpha(0f).setDuration(160).withEndAction {
            overlay.visibility = View.GONE
            dock.post { dock.getChildAt(0)?.requestFocus() }
        }.start()
    }

    companion object {
        private const val DOCK_TILE_DP = 168
        private const val GRID_TILE_DP = 144
        private const val ROW_TILE_DP = 150
        private const val PROGRAM_TILE_DP = 200
        private const val IDLE_MS = 5 * 60_000L
        private const val TAG = "LauncherTV"
        private val AIRPLAY_RECEIVERS = listOf(
            "io.github.jqssun.airplay",        // AirPlay Server (código abierto, UxPlay) — paso6_airplay.sh
            "com.ionitech.airscreen",          // AirScreen
            "com.sand.airdroidcast.tv",        // AirDroid Cast
            "com.airbeamtv.receiver"           // AirBeamTV
        )
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
