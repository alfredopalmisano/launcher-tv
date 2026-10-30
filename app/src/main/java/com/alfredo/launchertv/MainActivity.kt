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
    private lateinit var overlay: View
    private lateinit var grid: RecyclerView
    private lateinit var wallpapers: Wallpapers
    private lateinit var favorites: Favorites

    private val dockAdapter = TileAdapter(DOCK_TILE_DP, ::open) { showAppMenu(it, fromDock = true) }
    private val recentsAdapter = TileAdapter(ROW_TILE_DP, ::open) { showAppMenu(it, fromDock = false) }
    private val systemAdapter = TileAdapter(ROW_TILE_DP, ::open) { false }
    private val gridAdapter = TileAdapter(GRID_TILE_DP, ::open) { showAppMenu(it, fromDock = false) }

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
        dock = addRow(R.string.row_favorites, dockAdapter)
        recentsRow = addRow(R.string.row_recents, recentsAdapter)
        systemRow = addRow(R.string.row_system, systemAdapter)
        grid = findViewById(R.id.grid)
        favorites = Favorites(this)

        val dm = resources.displayMetrics
        clock.typeface = Fonts.clock(this)
        clock.textSizePx = 215f * dm.scaledDensity
        clock.glassAmount = 0.62f
        findViewById<TextView>(R.id.credit).typeface = Fonts.light(this)
        findViewById<TextView>(R.id.overlayTitle).typeface = Fonts.light(this)

        wallpapers = Wallpapers(this,
            findViewById(R.id.wallA), findViewById(R.id.fgA),
            findViewById(R.id.wallB), findViewById(R.id.fgB),
            findViewById(R.id.credit))
        val overlayBg = findViewById<ImageView>(R.id.overlayBg)
        wallpapers.onPhotoChanged = { photo, sky ->
            clock.setBackdrop(photo, dm.widthPixels, dm.heightPixels)
            val useFg = placeClock(clockBox, sky, dm.widthPixels, dm.heightPixels, photo.width, photo.height)
            // Fondo de la pantalla de apps: la misma foto muy difuminada.
            val tiny = Bitmap.createScaledBitmap(photo, 32, 18, true)
            overlayBg.setImageBitmap(Bitmap.createScaledBitmap(tiny, 320, 180, true))
            tiny.recycle()
            useFg
        }

        // La primera fila queda abajo; las demás, fuera de pantalla hasta que bajes.
        rowsScroll.viewTreeObserver.addOnGlobalLayoutListener {
            val firstRow = rowsBox.getChildAt(1) ?: return@addOnGlobalLayoutListener
            val wanted = rowsScroll.height - firstRow.height - (24 * dm.density).toInt()
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

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    /** Añade una fila horizontal (título + tarjetas) al contenedor de filas. */
    private fun addRow(titleRes: Int, adapter: TileAdapter): RecyclerView {
        val d = resources.displayMetrics.density
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
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        rowsBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return list
    }

    private fun reload() {
        apps = AppsRepository.load(this)
        gridAdapter.submit(apps)
        refreshDock()
    }

    private fun refreshRows() {
        val favs = favorites.get(apps).map { it.packageName }.toSet()
        val recents = RecentsRepository.load(this, apps, favs)
        recentsAdapter.submit(recents)
        (recentsRow.parent as View).visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE

        val sys = ArrayList<Tile>()
        sys.addAll(InputsRepository.loadConnected(this))
        sys.add(Tile.AirPlay)
        sys.add(Tile.Action("wifi", getString(R.string.wifi), R.drawable.ic_wifi))
        sys.add(Tile.Action("bt", getString(R.string.bluetooth), R.drawable.ic_bluetooth))
        sys.add(Tile.SleepTile)
        sys.add(Tile.Store)
        sys.add(Tile.SettingsTile)
        systemAdapter.submit(sys)
    }

    private fun refreshDock(focus: String? = null) {
        val hadFocus = dock.hasFocus()
        val tiles = ArrayList<Tile>()
        tiles.addAll(favorites.get(apps))
        tiles.addAll(InputsRepository.loadConnected(this))
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
            is Tile.Action -> when (tile.id) {
                "wifi" -> if (!tryStart(Intent(Settings.ACTION_WIFI_SETTINGS))) tryStart(Intent(Settings.ACTION_SETTINGS))
                "bt" -> if (!tryStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) tryStart(Intent(Settings.ACTION_SETTINGS))
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
     * Coloca el reloj según el horizonte de la foto: las cifras quedan un poco por detrás
     * de las cumbres (como el iPhone) y apoyadas sobre el dock. Devuelve si merece la pena
     * usar el primer plano (si taparía demasiado el reloj, no).
     */
    private fun placeClock(box: View, sky: Skyline.Result?, screenW: Int, screenH: Int, photoW: Int, photoH: Int): Boolean {
        val d = resources.displayMetrics.density
        val clockH = clock.textSizePx * 0.92f
        val dockTop = if (dockTopPx > 0f) dockTopPx else screenH * 0.72f
        val maxTop = dockTop - clockH * 0.82f              // como mucho un 18 % del reloj detrás del dock
        val minTop = 24 * d
        var top = (dockTop - clockH * 0.90f).coerceIn(minTop, maxTop)   // por defecto: apoyado sobre el dock
        var useFg = false
        if (sky != null) {
            val peakY = sky.topY.toFloat() * screenH / photoH
            val wanted = (peakY - clockH * 0.55f).coerceIn(minTop, maxTop)
            val w = clock.measuredWidth.toFloat().takeIf { it > 0f } ?: (screenW * 0.5f)
            val left = (screenW - w) / 2f
            val cov = sky.coverage(left, wanted, left + w, wanted + clockH, screenW, screenH, photoW, photoH)
            if (cov in 0.08f..0.6f) { top = wanted; useFg = true }
        }
        box.animate().translationY(top - 72 * d).setDuration(1200).start()
        return useFg
    }

    /** Temporizador de apagado (tile Dormir). */
    private fun showSleepMenu() {
        if (!Sleep.isAdmin(this)) { toast(getString(R.string.sleep_no_admin)) }
        val remaining = Sleep.remainingMinutes(this)
        val options = listOf(15, 30, 45, 60, 90, 120)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (remaining != null) {
            labels.add(getString(R.string.sleep_cancel_timer, remaining))
            actions.add { Sleep.cancel(this); toast(getString(R.string.sleep_cancelled)) }
        }
        labels.add(getString(R.string.sleep_now)); actions.add { if (!Sleep.sleepNow(this)) toast(getString(R.string.sleep_no_admin)) }
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
                item(R.string.menu_move_left) { if (favorites.move(tile.packageName, -1)) refreshDock(focus = tile.id) }
                item(R.string.menu_move_right) { if (favorites.move(tile.packageName, +1)) refreshDock(focus = tile.id) }
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
        private const val DOCK_TILE_DP = 176
        private const val GRID_TILE_DP = 144
        private const val ROW_TILE_DP = 150
        private val AIRPLAY_RECEIVERS = listOf(
            "com.ionitech.airscreen",          // AirScreen
            "com.sand.airdroidcast.tv",        // AirDroid Cast
            "com.airbeamtv.receiver"           // AirBeamTV
        )
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
