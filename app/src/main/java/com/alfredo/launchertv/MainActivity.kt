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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var clock: GlassTextView
    private lateinit var dock: RecyclerView
    private lateinit var overlay: View
    private lateinit var grid: RecyclerView
    private lateinit var wallpapers: Wallpapers
    private lateinit var favorites: Favorites

    private val dockAdapter = TileAdapter(DOCK_TILE_DP, ::open) { showAppMenu(it, fromDock = true) }
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

        clock = findViewById(R.id.clock)
        dock = findViewById(R.id.dock)
        overlay = findViewById(R.id.overlay)
        grid = findViewById(R.id.grid)
        favorites = Favorites(this)

        val dm = resources.displayMetrics
        clock.typeface = Fonts.clock(this)
        clock.textSizePx = 190f * dm.scaledDensity
        clock.glassAmount = 0.62f
        findViewById<TextView>(R.id.credit).typeface = Fonts.light(this)
        findViewById<TextView>(R.id.overlayTitle).typeface = Fonts.light(this)

        wallpapers = Wallpapers(this, findViewById(R.id.wallA), findViewById(R.id.wallB), findViewById(R.id.credit))
        val overlayBg = findViewById<ImageView>(R.id.overlayBg)
        wallpapers.onPhotoChanged = { photo ->
            clock.setBackdrop(photo, dm.widthPixels, dm.heightPixels)
            // Fondo de la pantalla de apps: la misma foto muy difuminada.
            val tiny = Bitmap.createScaledBitmap(photo, 32, 18, true)
            overlayBg.setImageBitmap(Bitmap.createScaledBitmap(tiny, 320, 180, true))
            tiny.recycle()
        }

        dock.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        dock.adapter = dockAdapter
        dock.itemAnimator = null

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

    private fun reload() {
        apps = AppsRepository.load(this)
        gridAdapter.submit(apps)
        refreshDock()
    }

    private fun refreshDock(focus: String? = null) {
        val hadFocus = dock.hasFocus()
        val tiles = ArrayList<Tile>()
        tiles.addAll(favorites.get(apps))
        tiles.addAll(InputsRepository.loadConnected(this))
        tiles.add(Tile.Store)
        tiles.add(Tile.AllApps)
        dockAdapter.submit(tiles)
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
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
