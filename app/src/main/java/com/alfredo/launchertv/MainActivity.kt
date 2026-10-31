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
    private var movingId: String? = null        // tarjeta que se está recolocando
    private var movingInSystem = false          // …en Conexiones y sistema (si no, en el dock)
    private val idleToAmbient = Runnable { if (!ambient && overlay.visibility != View.VISIBLE) setAmbient(true) }
    private lateinit var overlay: View
    private lateinit var grid: RecyclerView
    private lateinit var wallpapers: Wallpapers
    private lateinit var favorites: Favorites
    private lateinit var systemOrder: SystemOrder

    // El dock es un carrusel: tras la última tarjeta vuelve la primera (si hay más de las que caben).
    private val dockAdapter = TileAdapter(DOCK_TILE_DP, false, ::open,
        onLongClick = { showAppMenu(it, fromDock = true) }, loopMin = DOCK_VISIBLE)
    private val recentsAdapter = TileAdapter(PROGRAM_TILE_DP, true, ::open, loopMin = PROGRAM_VISIBLE) { false }
    private val systemAdapter = TileAdapter(ROW_TILE_DP, true, ::open, loopMin = DOCK_VISIBLE) { showSystemMenu(it) }
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

    /**
     * Interruptores de medida, solo desde el Mac (adb):
     *   am broadcast -a com.alfredo.launchertv.DEBUG --es off "scrim,filas,reloj,mascara,sombra,tarjetas,raiz" --ez next true
     * "off" apaga esas piezas (vacío = todo encendido); next=true cambia de foto al momento.
     * Solo lo puede enviar quien tenga el permiso DUMP (la shell de adb), no otras apps.
     */
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val off = (intent.getStringExtra("off") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            applyDebug(off)
            // Seguridad: si no llega otra orden en 20 s, todo vuelve a encenderse solo.
            handler.removeCallbacks(debugReset)
            if (off.isNotEmpty()) handler.postDelayed(debugReset, 20_000)
            android.util.Log.i(TAG, "debug: off=$off next=${intent.getBooleanExtra("next", false)}")
            if (intent.getBooleanExtra("next", false)) handler.postDelayed({ wallpapers.nextNow() }, 400)
        }
    }

    private val debugReset = Runnable { applyDebug(emptySet()) }

    /** Apaga (o vuelve a encender) piezas para medir su coste. Vacío = todo normal. */
    private fun applyDebug(off: Set<String>) {
        run {
            findViewById<View>(R.id.scrim).visibility = if ("scrim" in off) View.INVISIBLE else View.VISIBLE
            rowsScroll.visibility = if ("filas" in off) View.INVISIBLE else View.VISIBLE
            clockBox.visibility = if ("reloj" in off) View.INVISIBLE else View.VISIBLE
            clock.debugNoMask = "mascara" in off
            clock.debugNoShadow = "sombra" in off
            clock.invalidate()
            if (TileAdapter.noElevation != ("tarjetas" in off)) {
                TileAdapter.noElevation = "tarjetas" in off
                dockAdapter.notifyDataSetChanged(); systemAdapter.notifyDataSetChanged(); recentsAdapter.notifyDataSetChanged()
            }
            (findViewById<ViewGroup>(android.R.id.content).getChildAt(0)).setBackgroundColor(
                if ("raiz" in off) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK)
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
        // La foto de fondo tapa toda la pantalla: el fondo negro de la ventana sobra (cada capa
        // de pantalla completa cuesta en una gráfica modesta).
        window.setBackgroundDrawable(null)

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
        systemOrder = SystemOrder(this)

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
        wallpapers.onZoom = { z -> clock.setZoom(z) }
        wallpapers.onPhotoChanged = { p ->
            // Todo llega ya preparado: aquí solo se asigna y arrancan las animaciones.
            clock.setBackdrop(p.glass, p.mask, p.bright, dm.widthPixels, dm.heightPixels)
            placeClock(clockBox, p.plan, dm.heightPixels, p.photo.height)
            // Fondo de la pantalla de apps: la misma foto muy difuminada.
            overlayBg.setImageBitmap(p.blurred)
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
        setupSounds()
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
        applyDebug(emptySet())   // nunca arrancar con piezas apagadas
        registerReceiver(debugReceiver, IntentFilter("com.alfredo.launchertv.DEBUG"), android.Manifest.permission.DUMP, null)
        refreshDock()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(idleToAmbient)
        wallpapers.stop()
        try { unregisterReceiver(packageReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(debugReceiver) } catch (e: Exception) { }
        handler.removeCallbacks(debugReset)
        applyDebug(emptySet())
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
        when {
            overlay.visibility == View.VISIBLE -> hideAllApps()
            ambient -> { }   // ya estás en las imágenes: somos el inicio, no hay más atrás
            // Desde Conexiones o Continuar viendo: subir al dock, a la app donde estabas.
            !dock.hasFocus() -> {
                TileAdapter.sweep = TileAdapter.Sweep.UP
                rowsScroll.smoothScrollTo(0, 0)
                dock.post {
                    val v = if (dockFocusPos >= 0) dock.layoutManager?.findViewByPosition(dockFocusPos) else null
                    if (v != null) v.requestFocus() else focusFirstVisible(dock)
                }
            }
            // Desde el dock: solo las imágenes con el reloj.
            else -> setAmbient(true)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetIdle()
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) lastNavKey = android.os.SystemClock.uptimeMillis()

        // Modo ambiente (solo fondo y reloj): SOLO la flecha abajo vuelve a las apps; el resto no hace nada.
        if (ambient) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) setAmbient(false)
            if (code != KeyEvent.KEYCODE_HOME && code != KeyEvent.KEYCODE_POWER && code != KeyEvent.KEYCODE_SLEEP &&
                code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_MUTE) return true
        }

        // Modo mover: ← → recolocan la tarjeta; OK suelta; Atrás cancela.
        val mid = movingId
        if (mid != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { moveBy(mid, -1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { moveBy(mid, +1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { stopMoving(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
        }

        // El brillo de la tarjeta barre en la dirección del movimiento.
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> TileAdapter.sweep = TileAdapter.Sweep.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> TileAdapter.sweep = TileAdapter.Sweep.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> TileAdapter.sweep = TileAdapter.Sweep.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> TileAdapter.sweep = TileAdapter.Sweep.DOWN
        }

        // ← → dentro de una fila los llevamos nosotros: así, pulsando rápido, el foco nunca
        // salta a otra tarjeta lejana ni a otra fila (pasaba al final del carrusel).
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && overlay.visibility != View.VISIBLE) {
            val row = listOf(dock, systemRow, recentsRow).firstOrNull { it.hasFocus() }
            if (row != null && stepRow(row, if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)) return true
        }

        // Al bajar desde el dock, recordar la app para volver a ella con Atrás.
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && dock.hasFocus()) {
            dock.focusedChild?.let { dockFocusPos = dock.getChildAdapterPosition(it) }
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
            if (focused != null) dockFocusPos = dock.getChildAdapterPosition(focused)
            rowsScroll.scrollTo(0, 0)
        }
        rowsScroll.animate().alpha(if (on) 0f else 1f).translationY(if (on) 40 * d else 0f).setDuration(350).withEndAction {
            if (!on) dock.post {
                val v = if (dockFocusPos >= 0) dock.layoutManager?.findViewByPosition(dockFocusPos) else null
                if (v != null) v.requestFocus() else focusFirstVisible(dock)
            }
        }.start()
    }

    private fun startMoving(tile: Tile, inSystem: Boolean) {
        movingId = tile.id
        movingInSystem = inSystem
        (if (inSystem) systemAdapter else dockAdapter).movingId = tile.id
        toast(getString(R.string.move_hint))
        if (inSystem) focusTile(systemRow, systemAdapter, tile.id) else refreshDock(focus = tile.id)
    }

    private fun stopMoving() {
        val id = movingId ?: return
        val inSystem = movingInSystem
        movingId = null
        dockAdapter.movingId = null
        systemAdapter.movingId = null
        if (inSystem) focusTile(systemRow, systemAdapter, id) else refreshDock(focus = id)
    }

    /** Recoloca la tarjeta que se está moviendo una posición a la izquierda (−1) o derecha (+1). */
    private fun moveBy(id: String, delta: Int) {
        if (movingInSystem) {
            if (systemOrder.move(systemAdapter.ids(), id, delta)) {
                refreshSystem()
                focusTile(systemRow, systemAdapter, id)
            }
        } else {
            if (favorites.move(id.removePrefix("app:"), delta)) refreshDock(focus = id)
        }
    }

    /** Pone el foco en la tarjeta [id] de [row] (la copia más cercana, si es un carrusel). */
    private fun focusTile(row: RecyclerView, adapter: TileAdapter, id: String) {
        val lm = row.layoutManager as LinearLayoutManager
        val near = row.focusedChild?.let { row.getChildAdapterPosition(it) }?.takeIf { it >= 0 }
            ?: lm.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: adapter.loopStart()
        val pos = adapter.positionNear(id, near)
        if (pos < 0) return
        row.post {
            val v = lm.findViewByPosition(pos)
            if (v != null) v.requestFocus()
            else { lm.scrollToPosition(pos); row.post { lm.findViewByPosition(pos)?.requestFocus() } }
        }
    }

    /**
     * Mueve el foco una tarjeta a la izquierda (−1) o derecha (+1) dentro de [row].
     * Si la tarjeta aún no está dibujada (pulsando muy rápido), la trae a la vista y la enfoca
     * al terminar. En los extremos de una fila sin carrusel no hace nada.
     */
    private fun stepRow(row: RecyclerView, delta: Int): Boolean {
        val adapter = row.adapter as? TileAdapter ?: return false
        val lm = row.layoutManager as? LinearLayoutManager ?: return false
        val focused = row.focusedChild ?: return false
        val base = pendingFocus?.takeIf { it.first === row }?.second ?: row.getChildAdapterPosition(focused)
        if (base < 0) return false
        val target = base + delta
        if (target < 0 || target >= adapter.itemCount) return true   // final de una fila sin carrusel
        val v = lm.findViewByPosition(target)
        if (v != null && pendingFocus == null) {
            v.requestFocus()
            return true
        }
        // Todavía no está dibujada: colocarla (alineada al borde) y enfocarla en cuanto lo esté.
        pendingFocus = row to target
        lm.scrollToPosition(target)
        row.post {
            val pf = pendingFocus
            if (pf != null && pf.first === row) {
                pendingFocus = null
                lm.findViewByPosition(pf.second)?.requestFocus()
            }
        }
        return true
    }

    /** Foco pendiente (fila, posición) mientras se dibuja la tarjeta destino. */
    private var pendingFocus: Pair<RecyclerView, Int>? = null

    /** Foco en la primera tarjeta que se ve entera de la fila. */
    private fun focusFirstVisible(row: RecyclerView) {
        val lm = row.layoutManager as LinearLayoutManager
        val pos = lm.findFirstCompletelyVisibleItemPosition()
        (if (pos >= 0) lm.findViewByPosition(pos) else row.getChildAt(0))?.requestFocus()
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
            setPadding(((ROW_EDGE_DP + 12) * d).toInt(), (10 * d).toInt(), 0, 0)
        }
        val list = RecyclerView(this).apply {
            layoutManager = object : LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false) {
                // Dejar dibujada una tarjeta más a cada lado (fuera de la vista): al pulsar rápido
                // siempre hay a dónde ir y el desplazamiento sigue siendo suave.
                override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
                    val e = (EXTRA_LAYOUT_DP * d).toInt()
                    extraLayoutSpace[0] = e
                    extraLayoutSpace[1] = e
                }
            }
            this.adapter = adapter
            itemAnimator = null
            // Como el dock: lo que queda fuera de las columnas no asoma por los lados.
            clipToPadding = true
            clipChildren = true
            // Las tarjetas de todas las filas empiezan en la misma columna que las del dock (52 dp).
            setPadding((ROW_EDGE_DP * d).toInt(), 0, (ROW_EDGE_DP * d).toInt(), 0)
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
                // Caben exactamente DOCK_VISIBLE tarjetas en el estante: al mover el foco, la fila se
                // desplaza de tarjeta en tarjeta y nunca queda una cortada en el borde.
                // 960 dp de pantalla − 2×24 margen − 2×8 relleno − 2×DOCK_PAD = DOCK_VISIBLE × (tarjeta + 24).
                list.setPadding((DOCK_PAD_DP * d).toInt(), 0, (DOCK_PAD_DP * d).toInt(), 0)
                // Las vecinas no pueden pintarse fuera del estante (antes asomaban trocitos por los lados).
                // El foco agranda la tarjeta 9 dp por lado y cabe en sus 12 dp de relleno: no se recorta.
                list.clipToPadding = true
                list.clipChildren = true
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
        submitRow(recentsRow, recentsAdapter, programs)
        (recentsRow.parent as View).visibility = if (programs.isEmpty()) View.GONE else View.VISIBLE

        refreshSystem()
    }

    /** Carga una fila; si cambia el número de tarjetas, coloca el carrusel en su vuelta del medio. */
    private fun submitRow(row: RecyclerView, adapter: TileAdapter, tiles: List<Tile>) {
        val sizeBefore = adapter.size
        adapter.submit(tiles)
        if (sizeBefore != tiles.size) (row.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(adapter.loopStart(), 0)
    }

    private fun refreshSystem() {
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
        submitRow(systemRow, systemAdapter, systemOrder.apply(sys))
    }

    private fun refreshDock(focus: String? = null) {
        val hadFocus = dock.hasFocus()
        val tiles = ArrayList<Tile>()
        tiles.addAll(favorites.get(apps))
        tiles.add(Tile.AllApps)
        submitRow(dock, dockAdapter, tiles)
        refreshRows()
        if (focus != null) {
            focusTile(dock, dockAdapter, focus)
        } else if (!hadFocus && overlay.visibility != View.VISIBLE) {
            dock.post { focusFirstVisible(dock) }
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
        // Animación al abrir: la app crece desde la tarjeta pulsada (si la hay y se ve).
        val from = TileAdapter.lastClicked?.takeIf { it.isAttachedToWindow && it.width > 0 }
        TileAdapter.lastClicked = null
        val opts = from?.let { android.app.ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
        true
    } catch (e: Exception) {
        false
    }

    // Sonido de clic al moverse entre tarjetas (sintetizado, res/raw/click.wav).
    private var sounds: android.media.SoundPool? = null
    private var clickId = 0
    private var lastNavKey = 0L

    private fun setupSounds() {
        val sp = android.media.SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        clickId = sp.load(this, R.raw.click, 1)
        sounds = sp
        // Solo cuando el foco se mueve por una flecha del mando (no al volver de una app, etc.).
        TileAdapter.onFocusSound = {
            if (android.os.SystemClock.uptimeMillis() - lastNavKey < 400) sounds?.play(clickId, CLICK_VOLUME, CLICK_VOLUME, 1, 0, 1f)
        }
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
        // El reloj es una capa cacheada: hay que redibujarlo mientras se mueve para que el cristal
        // muestre lo que hay detrás en su posición actual, no en la anterior.
        box.animate().translationY(viewTop - 72 * d).setDuration(GlassTextView.FADE_MS)
            // Arranque y llegada suaves (curva "ease-in-out" tipo Apple), sin acelerones.
            .setInterpolator(android.view.animation.PathInterpolator(0.42f, 0f, 0.20f, 1f))
            .setUpdateListener { clock.invalidate() }
            .withEndAction { clock.invalidate() }
            .start()
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
                item(R.string.menu_move) { startMoving(tile, inSystem = false) }
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

    /** Menú al mantener pulsado OK en Conexiones y sistema: recolocar la tarjeta. */
    private fun showSystemMenu(tile: Tile): Boolean {
        val labels = arrayOf(getString(R.string.menu_move), getString(R.string.menu_cancel))
        AlertDialog.Builder(this, R.style.Theme_Launcher_Dialog)
            .setTitle(tile.label)
            .setItems(labels) { _, which -> if (which == 0) startMoving(tile, inSystem = true) }
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
            dock.post { focusFirstVisible(dock) }
        }.start()
    }

    companion object {
        private const val DOCK_TILE_DP = 152      // 5 × (152 + 24) = 880 = 960 − 48 − 16 − 16
        private const val DOCK_PAD_DP = 8
        private const val DOCK_VISIBLE = 5      // tarjetas que caben en el estante
        private const val PROGRAM_VISIBLE = 3   // carátulas de Continuar viendo que caben
        private const val EXTRA_LAYOUT_DP = 240 // algo más que una tarjeta (176 dp en dock y sistema, 224 en programas)
        private const val GRID_TILE_DP = 144
        private const val ROW_TILE_DP = 152      // igual que el dock: mismas columnas
        /** Relleno de las filas: 40 + 12 de la tarjeta = 52 dp, donde empiezan las tarjetas del dock (24+8+8+12). */
        private const val ROW_EDGE_DP = 40
        private const val PROGRAM_TILE_DP = 200
        private const val IDLE_MS = 5 * 60_000L
        private const val CLICK_VOLUME = 0.15f   // clic de navegación (0–1); 0,35 era alto
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
