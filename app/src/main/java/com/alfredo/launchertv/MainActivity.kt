package com.alfredo.launchertv

import android.app.Activity
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
    private lateinit var nightShade: View
    private lateinit var bootShade: View
    private lateinit var toastView: TextView
    private val menus by lazy { TvMenu(this) }
    /** Curva de la propuesta para entradas y desplazamientos (rápida al principio, llegada suave). */
    private val ease = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private var scrollAnim: android.animation.ObjectAnimator? = null
    private var deep = false
    private var isUp = false
    private var screenOnAt = 0L
    /** Foto de la tarjeta desde la que se abrió la última app, para volver encogiéndose hasta ella. */
    private var returnShot: Bitmap? = null
    private val returnLoc = IntArray(2)
    private var returnSize = Pair(0f, 0f)
    private var launchedAt = 0L
    private var returningHome = false
    private var knownInputs: Map<String, String>? = null
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
    private val recentsAdapter = TileAdapter(PROGRAM_TILE_DP, true, ::open, loopMin = PROGRAM_VISIBLE, posters = true) { false }
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
        nightShade = findViewById(R.id.nightShade)
        bootShade = findViewById(R.id.bootShade)
        toastView = findViewById(R.id.toast)
        toastView.typeface = Fonts.medium(this)
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
        findViewById<TextView>(R.id.credit).typeface = Fonts.regular(this)
        findViewById<TextView>(R.id.overlayTitle).typeface = Fonts.regular(this)

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

        // En el inicio solo se ve el dock, abajo (dentro de la zona segura); las demás filas están
        // ocultas hasta que bajas. Al bajar, la página sube lo justo para que la fila enfocada
        // (con su nombre) quede en la zona segura.
        rowsScroll.viewTreeObserver.addOnGlobalLayoutListener {
            val firstRow = rowsBox.getChildAt(1) ?: return@addOnGlobalLayoutListener
            val wanted = rowsScroll.height - firstRow.height - (DOCK_BOTTOM_DP * dm.density).toInt()
            if (wanted > 0 && topSpace.layoutParams.height != wanted) {
                topSpace.layoutParams = topSpace.layoutParams.apply { height = wanted }
                topSpace.requestLayout()
            }
            if (wanted > 0) dockTopPx = wanted.toFloat()
        }
        rowsScroll.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> scrollForFocus(now) }

        val gridWidthDp = dm.widthPixels / dm.density - 2 * 52
        grid.layoutManager = GridLayoutManager(this, (gridWidthDp / (GRID_TILE_DP + 24)).toInt().coerceAtLeast(3))
        grid.adapter = gridAdapter
        grid.itemAnimator = null

        hideSystemUi()
        setupSounds()
        Night.onChange = { applyNight(it, animate = true) }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
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
        returnHome()   // al volver de una app: la pantalla se encoge hasta la tarjeta
        applyDebug(emptySet())   // nunca arrancar con piezas apagadas
        Night.restoreIfExpired(this)
        applyNight(Night.isOn(this), animate = false)
        if (android.os.SystemClock.uptimeMillis() - screenOnAt < 4000) { screenOnAt = 0L; powerOn() }
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

    override fun onResume() {
        super.onResume()
        isUp = true
        // Seguro: al volver (aunque Android no pase por onStart, p. ej. tras apps que no tapan
        // del todo al launcher) nunca puede quedarse la capa negra de la animación encima.
        if (!returningHome) hideLaunchZoom(animate = true)
    }

    override fun onPause() {
        super.onPause()
        isUp = false
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) { }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { hideSystemUi(); if (!returningHome) hideLaunchZoom(animate = true) }
    }

    /** Al encender la tele: primero la hora, luego la foto sale de negro y por último las apps. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isUp) powerOn() else screenOnAt = android.os.SystemClock.uptimeMillis()
        }
    }

    private fun powerOn() {
        val d = resources.displayMetrics.density
        hideLaunchZoom(animate = false)
        bootShade.animate().cancel(); clockBox.animate().cancel(); rowsScroll.animate().cancel()
        bootShade.visibility = View.VISIBLE; bootShade.alpha = 1f
        clockBox.translationZ = 40 * d
        clockBox.alpha = 0f
        rowsScroll.alpha = 0f
        clockBox.animate().alpha(if (deep) 0.5f else 1f).setStartDelay(150).setDuration(500).start()
        bootShade.animate().alpha(0f).setStartDelay(530).setDuration(700).withEndAction {
            bootShade.visibility = View.GONE
            clockBox.translationZ = 0f
        }.start()
        handler.postDelayed({
            if (!ambient) { rowsScroll.alpha = 1f; rowsScroll.translationY = 0f; cascadeIn() }
        }, 1050)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hideLaunchZoom(animate = false)
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
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                // Al soltar la flecha tras recorrer la fila deprisa: efecto completo donde paras.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (TileAdapter.fast) {
                    TileAdapter.fast = false
                    settleFocused()
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> cancelSink()
            }
        }
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

        // Mantener OK sobre una tarjeta: se hunde poco a poco hasta que sale su menú.
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && event.repeatCount == 0) startSink()

        // Flecha mantenida (← →): recorrido rápido, sin inclinación ni brillo en cada tarjeta.
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) TileAdapter.fast = event.repeatCount > 0

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
        rowsScroll.animate().cancel()
        if (on) {
            // Recordar en qué app estaba el cursor para volver exactamente ahí.
            val focused = dock.focusedChild
            if (focused != null) dockFocusPos = dock.getChildAdapterPosition(focused)
            setDeep(false)
            // Se recogen: bajan 12 dp y se desvanecen.
            rowsScroll.animate().alpha(0f).translationY(12 * d).setDuration(300).setInterpolator(ease).withEndAction {
                scrollAnim?.cancel(); rowsScroll.scrollTo(0, 0)
            }.start()
        } else {
            scrollAnim?.cancel(); rowsScroll.scrollTo(0, 0)
            rowsScroll.alpha = 1f; rowsScroll.translationY = 0f
            dock.post {
                val v = if (dockFocusPos >= 0) dock.layoutManager?.findViewByPosition(dockFocusPos) else null
                if (v != null) v.requestFocus() else focusFirstVisible(dock)
                cascadeIn()
            }
        }
    }

    /**
     * Las tarjetas aparecen en cascada desde la que tiene el foco hacia los lados (40 ms entre
     * cada una), subiendo 12 dp: primero el estante y el dock, luego Conexiones y sistema.
     */
    private fun cascadeIn() {
        val d = resources.displayMetrics.density
        fun visible(rv: RecyclerView): List<View> = (0 until rv.childCount).map { rv.getChildAt(it) }
            .filter { it.right > rv.paddingLeft && it.left < rv.width - rv.paddingRight }.sortedBy { it.left }
        val dockItems = visible(dock)
        val fc = dockItems.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
        fun appear(v: View, delay: Long) {
            v.animate().cancel()
            v.alpha = 0f; v.translationY = 12 * d
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(450).setInterpolator(ease).start()
        }
        (dock.parent as? View)?.let { appear(it, 0) }   // el estante
        dockItems.forEachIndexed { i, v -> appear(v, kotlin.math.abs(i - fc) * 40L) }
    }

    /** Fila enfocada: el estante (dock) arriba del todo; las demás, subiendo lo justo. */
    private fun scrollForFocus(now: View?) {
        if (now == null || ambient || overlay.visibility == View.VISIBLE) return
        if (!isInside(now, rowsBox)) return
        val d = resources.displayMetrics.density
        val inDock = isInside(now, dock)
        var target = 0
        if (!inDock) {
            // La tarjeta (hija directa de su fila) y lo de debajo: nombre o información.
            var item: View = now
            while (item.parent !is RecyclerView) item = item.parent as? View ?: return
            val r = android.graphics.Rect(0, 0, item.width, item.height)
            rowsBox.offsetDescendantRectToMyCoords(item, r)
            val contentBottom = r.bottom - (12 * d).toInt()           // sin el relleno de la tarjeta
            val limit = rowsScroll.height - ((27 + 4) * d).toInt()     // zona segura: 27 dp abajo
            target = (contentBottom - limit).coerceAtLeast(0)
        }
        setDeep(!inDock)
        if (rowsScroll.scrollY == target) return
        scrollAnim?.cancel()
        scrollAnim = android.animation.ObjectAnimator.ofInt(rowsScroll, "scrollY", rowsScroll.scrollY, target).apply {
            duration = 450; interpolator = ease; start()
        }
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === parent) return true; p = p.parent as? View }
        return false
    }

    /** Profundidad al bajar de fila: la foto se oscurece un poco y el reloj se atenúa. */
    private fun setDeep(on: Boolean) {
        if (deep == on) return
        deep = on
        dim.animate().alpha(if (on) 0.3f else 0f).setDuration(450).setInterpolator(ease).start()
        clockBox.animate().alpha(if (on) 0.5f else 1f).setDuration(450).setInterpolator(ease).start()
        showLowerRows(on)
    }

    /** Las filas de abajo solo se ven al bajar a ellas (en el inicio, solo el dock). */
    private fun showLowerRows(on: Boolean) {
        for (i in 2 until rowsBox.childCount) {
            val r = rowsBox.getChildAt(i)
            r.animate().cancel()
            r.animate().alpha(if (on) 1f else 0f).setDuration(if (on) 300L else 220L).setInterpolator(ease).start()
        }
    }

    // ---- Mantener OK: la tarjeta se hunde poco a poco hasta que sale su menú ----
    private var sinking: View? = null

    private fun focusedBox(): View? {
        val f = currentFocus ?: return null
        if (f.parent !is RecyclerView) return null
        return f.findViewById(R.id.box)
    }

    private fun focusScaleOf(box: View): Float {
        val card = box.findViewById<View>(R.id.card) ?: return 1.12f
        return 1f + TileAdapter.GROW_DP * resources.displayMetrics.density * 2f / card.width.coerceAtLeast(1)
    }

    private fun startSink() {
        if (ambient || movingId != null) return
        val box = focusedBox() ?: return
        sinking = box
        box.animate().scaleX(1f).scaleY(1f).setStartDelay(0)
            .setDuration(android.view.ViewConfiguration.getLongPressTimeout().toLong())
            .setInterpolator(android.view.animation.PathInterpolator(0.3f, 0f, 0.2f, 1f)).start()
    }

    private fun cancelSink() {
        val box = sinking ?: return
        sinking = null
        val s = focusScaleOf(box)
        box.animate().scaleX(s).scaleY(s).setDuration(160).setInterpolator(ease).start()
    }

    /** Tras el menú (pulsación larga): la tarjeta vuelve a su tamaño con un pequeño rebote. */
    private fun popBack() {
        val box = sinking ?: focusedBox() ?: return
        sinking = null
        val s = focusScaleOf(box)
        box.animate().scaleX(s).scaleY(s).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
    }

    /** Efecto completo en la tarjeta donde se paró el recorrido rápido. */
    private fun settleFocused() {
        val f = currentFocus ?: return
        val rv = f.parent as? RecyclerView ?: return
        (rv.findContainingViewHolder(f) as? TileAdapter.Holder)?.settle()
    }

    private fun startMoving(tile: Tile, inSystem: Boolean) {
        movingId = tile.id
        movingInSystem = inSystem
        (if (inSystem) systemAdapter else dockAdapter).movingId = tile.id
        toast(getString(R.string.move_hint), long = true)
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
            val before = positionsOf(systemRow, systemAdapter)
            if (systemOrder.move(systemAdapter.ids(), id, delta)) {
                refreshSystem()
                focusTile(systemRow, systemAdapter, id)
                slideFrom(systemRow, systemAdapter, before)
            }
        } else {
            val before = positionsOf(dock, dockAdapter)
            if (favorites.move(id.removePrefix("app:"), delta)) {
                refreshDock(focus = id)
                slideFrom(dock, dockAdapter, before)
            }
        }
    }

    /** Posición en pantalla de cada tarjeta visible de la fila (por su id). */
    private fun positionsOf(row: RecyclerView, adapter: TileAdapter): Map<String, Float> {
        val out = HashMap<String, Float>()
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            val id = adapter.itemAt(row.getChildAdapterPosition(v))?.id ?: continue
            if (id !in out) out[id] = v.left.toFloat()
        }
        return out
    }

    /**
     * Tras recolocar una fila, cada tarjeta se desliza desde donde estaba (en vez de saltar).
     * Las que no estaban (una consola recién conectada) aparecen subiendo y fundiéndose.
     */
    private fun slideFrom(row: RecyclerView, adapter: TileAdapter, before: Map<String, Float>, fadeNew: Boolean = false) {
        val d = resources.displayMetrics.density
        row.post {
            row.post {
                for (i in 0 until row.childCount) {
                    val v = row.getChildAt(i)
                    val id = adapter.itemAt(row.getChildAdapterPosition(v))?.id ?: continue
                    val old = before[id]
                    if (old != null) {
                        val dx = old - v.left
                        if (kotlin.math.abs(dx) > 1f && kotlin.math.abs(dx) < row.width) {
                            v.translationX = dx
                            v.animate().translationX(0f).setDuration(280).setInterpolator(ease).start()
                        }
                    } else if (fadeNew) {
                        v.alpha = 0f; v.translationY = 12 * d
                        v.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(450).setInterpolator(ease).start()
                    }
                }
            }
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
        // Títulos de fila a 20 sp (se leen a 3 m): 24 dp por encima, pegados a su fila.
        val title = TextView(this).apply {
            text = getString(titleRes)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 20f
            typeface = Fonts.medium(this@MainActivity)
            setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
            setPadding(((ROW_EDGE_DP + 12) * d).toInt(), ((if (rowsBox.childCount == 2) 20 else 28) * d).toInt(), 0, 0)
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
            // Lo que queda fuera de las columnas no asoma por los lados, pero la tarjeta enfocada
            // (más grande e inclinada) tiene 10 dp de margen para no cortarse en los extremos:
            // las vecinas empiezan 12 dp más allá, así que siguen sin verse.
            clipToPadding = false
            clipChildren = false
            addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val ext = (10 * d).toInt()
                val h = b - t
                v.clipBounds = android.graphics.Rect(v.paddingLeft - ext, -h, (r - l) - v.paddingRight + ext, 2 * h)
            }
            // Las tarjetas de todas las filas empiezan en la misma columna que las del dock (52 dp).
            setPadding((ROW_EDGE_DP * d).toInt(), 0, (ROW_EDGE_DP * d).toInt(), 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            if (!first) addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (-4 * d).toInt()
            })
            if (first) {
                // Estante de cristal detrás del dock (estilo Apple TV).
                val shelf = android.widget.FrameLayout(this@MainActivity).apply {
                    background = getDrawable(if (shelfLight()) R.drawable.dock_shelf_light else R.drawable.dock_shelf)
                    clipChildren = false
                    clipToPadding = false
                    setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
                    addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                // Caben exactamente DOCK_VISIBLE tarjetas en el estante: al mover el foco, la fila se
                // desplaza de tarjeta en tarjeta y nunca queda una cortada en el borde.
                // 960 dp de pantalla − 2×24 margen − 2×8 relleno − 2×DOCK_PAD = DOCK_VISIBLE × (tarjeta + 24).
                list.setPadding((DOCK_PAD_DP * d).toInt(), 0, (DOCK_PAD_DP * d).toInt(), 0)
                // Las vecinas no pueden pintarse fuera del estante (antes asomaban trocitos por los lados).
                // El foco agranda la tarjeta 9 dp por lado y cabe en sus 12 dp de relleno: no se recorta.
                addView(shelf, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (24 * d).toInt(); marginEnd = (24 * d).toInt()
                })
            } else {
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        if (rowsBox.childCount >= 2) row.alpha = 0f   // filas de abajo: ocultas hasta bajar
        rowsBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (first) applyShelf(animate = false)
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
        val inputs = InputsRepository.loadConnected(this)
        sys.addAll(inputs)
        // Consola o Chromecast recién conectado (o desconectado): aviso, y su tarjeta entra deslizándose.
        val now = inputs.associate { it.id to it.label }
        val known = knownInputs
        knownInputs = now
        val changed = known != null && known.keys != now.keys
        if (known != null) {
            now.filterKeys { it !in known }.values.forEach { toast(getString(R.string.input_connected, it)) }
            known.filterKeys { it !in now }.values.forEach { toast(getString(R.string.input_disconnected, it)) }
        }
        val before = if (changed) positionsOf(systemRow, systemAdapter) else null
        sys.add(Tile.AirPlay)
        sys.add(Tile.Action("wifi", getString(R.string.wifi), R.drawable.ic_wifi))
        sys.add(Tile.Action("bt", getString(R.string.bluetooth), R.drawable.ic_bluetooth))
        sys.add(Tile.SleepTile)
        sys.add(Tile.Action("reboot", getString(R.string.reboot), R.drawable.ic_restart))
        sys.add(Tile.Action("remote", getString(R.string.remote), R.drawable.ic_phone))
        sys.add(Tile.Store)
        // Cambiar el estante del dock entre oscuro y claro (muestra el que pondrá).
        sys.add(Tile.Action("shelf", getString(if (shelfLight()) R.string.shelf_dark else R.string.shelf_light), R.drawable.ic_contrast))
        sys.add(Tile.SettingsTile)
        submitRow(systemRow, systemAdapter, systemOrder.apply(sys))
        if (before != null) slideFrom(systemRow, systemAdapter, before, fadeNew = true)
    }

    private fun refreshDock(focus: String? = null) {
        val hadFocus = dock.hasFocus()
        val tiles = ArrayList<Tile>()
        tiles.add(Tile.AllApps)              // "Apps" va primero en el dock (gusto de Alfredo)
        tiles.addAll(favorites.get(apps))
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
                "shelf" -> {
                    prefs().edit().putBoolean(KEY_SHELF_LIGHT, !shelfLight()).apply()
                    applyShelf(animate = true)
                    toast(getString(if (shelfLight()) R.string.shelf_light else R.string.shelf_dark))
                    val id = tile.id
                    refreshSystem()
                    focusTile(systemRow, systemAdapter, id)
                }
                "reboot" -> menus.show(getString(R.string.reboot_title), listOf(
                    TvMenu.Item(getString(R.string.reboot)) {
                        Thread { if (TvAdb.reboot(this) == null) runOnUiThread { toast(getString(R.string.sleep_no_admin)) } }.start()
                    },
                    TvMenu.Item(getString(R.string.menu_cancel)) { }))
            }
            Tile.Store -> {
                val store = packageManager.getLeanbackLaunchIntentForPackage("com.android.vending")
                    ?: packageManager.getLaunchIntentForPackage("com.android.vending")
                if (store == null || !tryStart(store)) toast(getString(R.string.cannot_open, getString(R.string.store)))
            }
        }
    }

    private fun tryStart(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val from = TileAdapter.lastClicked?.takeIf { it.isAttachedToWindow && it.width > 0 }
        TileAdapter.lastClicked = null
        if (from == null || packageManager.resolveActivity(intent, 0) == null) {
            return try { startActivity(intent); true } catch (e: Exception) { false }
        }
        // Animación al abrir: el icono crece hasta llenar la pantalla y se funde a negro; entonces
        // la app aparece con un fundido. (Grabado en la tele: la animación del sistema hacía crecer
        // un rectángulo NEGRO, la ventana vacía de la app, seguido de más de 1 s en negro.)
        Sounds.play(Sounds.OPEN)
        zoomFrom(from) {
            try {
                startActivity(intent, android.app.ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, R.anim.launcher_hold).toBundle())
                launchedAt = android.os.SystemClock.uptimeMillis()
                handler.postDelayed({ if (!returningHome) hideLaunchZoom(animate = false) }, 1500)
            } catch (e: Exception) {
                returnShot = null
                hideLaunchZoom(animate = true)
            }
        }
        return true
    }

    private fun zoomFrom(card: View, then: () -> Unit) {
        val zoom = findViewById<ImageView>(R.id.launchZoom)
        val black = findViewById<View>(R.id.launchBlack)
        val root = zoom.parent as View
        val w = card.width; val h = card.height
        val d = resources.displayMetrics.density
        // Foto de la tarjeta (sin el marco de foco ni el brillo), con las esquinas redondeadas.
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val gl = card.findViewById<View>(R.id.gloss)
        val glA = gl?.alpha ?: 0f
        gl?.alpha = 0f
        card.draw(android.graphics.Canvas(raw))
        gl?.alpha = glA
        val img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(img).drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 16 * d, 16 * d,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                shader = android.graphics.BitmapShader(raw, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            })
        val loc = IntArray(2)
        card.getLocationOnScreen(loc)
        val rw = root.width.toFloat().coerceAtLeast(1f); val rh = root.height.toFloat().coerceAtLeast(1f)
        // Para volver luego encogiéndose hasta esta misma tarjeta.
        returnShot = img
        returnLoc[0] = loc[0]; returnLoc[1] = loc[1]
        val sc = (card.parent as? View)?.scaleX ?: 1f
        returnSize = Pair(w * sc, h * sc)
        zoom.animate().cancel()
        zoom.setImageBitmap(img)
        zoom.pivotX = 0f; zoom.pivotY = 0f
        zoom.scaleX = w * sc / rw; zoom.scaleY = h * sc / rh
        zoom.translationX = loc[0].toFloat(); zoom.translationY = loc[1].toFloat()
        zoom.alpha = 1f
        zoom.visibility = View.VISIBLE
        black.alpha = 0f
        black.visibility = View.VISIBLE
        // Curva "enfatizada" de Material: arranca con decisión y llega suave. El negro entra en el
        // último tercio, cuando la tarjeta ya casi llena la pantalla.
        val curve = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        zoom.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(LAUNCH_ZOOM_MS).setInterpolator(curve)
            .withEndAction(then)
            .start()
        android.animation.ObjectAnimator.ofFloat(zoom, View.ALPHA, 1f, 0f).apply {
            startDelay = LAUNCH_ZOOM_MS * 62 / 100; duration = LAUNCH_ZOOM_MS * 38 / 100; start()
        }
        android.animation.ObjectAnimator.ofFloat(black, View.ALPHA, 0f, 1f).apply {
            startDelay = LAUNCH_ZOOM_MS * 50 / 100; duration = LAUNCH_ZOOM_MS * 45 / 100; start()
        }
    }

    /**
     * Al volver de una app abierta desde una tarjeta: la imagen de la tarjeta, a pantalla
     * completa, se encoge hasta su sitio (como en Apple TV). Si no hay de dónde, solo se quita el negro.
     */
    private fun returnHome() {
        val shot = returnShot
        val elapsed = android.os.SystemClock.uptimeMillis() - launchedAt
        returnShot = null
        if (shot == null || launchedAt == 0L || elapsed < 1200 || ambient) { hideLaunchZoom(animate = true); return }
        val zoom = findViewById<ImageView>(R.id.launchZoom)
        val black = findViewById<View>(R.id.launchBlack)
        val root = zoom.parent as View
        val rw = root.width.toFloat().coerceAtLeast(1f); val rh = root.height.toFloat().coerceAtLeast(1f)
        returningHome = true
        zoom.animate().cancel(); black.animate().cancel()
        zoom.setImageBitmap(shot)
        zoom.pivotX = 0f; zoom.pivotY = 0f
        zoom.scaleX = 1f; zoom.scaleY = 1f; zoom.translationX = 0f; zoom.translationY = 0f
        zoom.alpha = 1f
        zoom.visibility = View.VISIBLE
        black.visibility = View.GONE
        Sounds.play(Sounds.BACK)
        zoom.animate().scaleX(returnSize.first / rw).scaleY(returnSize.second / rh)
            .translationX(returnLoc[0].toFloat()).translationY(returnLoc[1].toFloat())
            .setDuration(420).setInterpolator(ease)
            .withEndAction {
                zoom.animate().alpha(0f).setDuration(140).withEndAction {
                    returningHome = false
                    hideLaunchZoom(animate = false)
                }.start()
            }.start()
        // Seguro: pase lo que pase, en 1,2 s no queda nada encima.
        handler.postDelayed({ if (returningHome) { returningHome = false; hideLaunchZoom(animate = false) } }, 1200)
    }

    /** Al volver de una app: quitar el negro con un fundido (o al momento). */
    private fun hideLaunchZoom(animate: Boolean) {
        val zoom = findViewById<ImageView>(R.id.launchZoom)
        val black = findViewById<View>(R.id.launchBlack)
        zoom.animate().cancel()
        zoom.visibility = View.GONE
        zoom.setImageDrawable(null)
        if (black.visibility != View.VISIBLE) return
        if (!animate) { black.visibility = View.GONE; return }
        black.animate().alpha(0f).setDuration(260).withEndAction { black.visibility = View.GONE }.start()
    }

    private var lastNavKey = 0L

    private fun setupSounds() {
        Sounds.init(this)
        // Solo cuando el foco se mueve por una flecha del mando (no al volver de una app, etc.).
        // Un sonido distinto al moverse, al ir deprisa y al cambiar de fila.
        TileAdapter.onFocusSound = { kind ->
            if (android.os.SystemClock.uptimeMillis() - lastNavKey < 400) Sounds.play(when (kind) {
                TileAdapter.Sound.LIGHT -> Sounds.LIGHT
                TileAdapter.Sound.ROW -> Sounds.ROW
                else -> Sounds.MOVE
            })
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

    /**
     * Modo noche (temporizador de Dormir activo): foto, montaña y reloj se oscurecen poco a poco
     * (2 s) con un velo un pelín cálido; al quitarlo vuelven igual de suave.
     */
    private fun applyNight(on: Boolean, animate: Boolean) {
        nightShade.animate().cancel()
        val to = if (on) NIGHT_SHADE else 0f
        if (!animate) { nightShade.alpha = to; return }
        nightShade.animate().alpha(to).setDuration(if (on) 2000 else 1500)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start()
    }

    /** Mando desde el móvil: muestra la dirección a abrir en el iPhone. */
    private fun showRemoteInfo() {
        RemoteService.start(this)
        val ip = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
        } catch (e: Exception) { null }
        val url = if (ip != null) "http://$ip:${RemoteService.PORT}" else getString(R.string.remote_no_wifi)
        menus.show(getString(R.string.remote_title), listOf(TvMenu.Item(getString(R.string.ok_button)) { }),
            message = getString(R.string.remote_message, url, RemoteService.pin(this)))
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
        val items = ArrayList<TvMenu.Item>()
        if (remaining != null) items.add(TvMenu.Item(getString(R.string.sleep_cancel_timer, remaining)) {
            Sleep.cancel(this); toast(getString(R.string.sleep_cancelled))
        })
        items.add(TvMenu.Item(getString(R.string.sleep_now)) {
            Thread { if (!Sleep.sleepNow(this)) runOnUiThread { toast(getString(R.string.sleep_no_admin)) } }.start()
        })
        for (m in options) items.add(TvMenu.Item(getString(R.string.sleep_minutes, m)) {
            Sleep.schedule(this, m); toast(getString(R.string.sleep_set, m))
        })
        menus.show(getString(R.string.sleep_title), items)
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

        val items = ArrayList<TvMenu.Item>()
        fun item(text: Int, action: () -> Unit) { items.add(TvMenu.Item(getString(text), action)) }

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
        // El menú sale creciendo desde la tarjeta y se queda a su lado (Atrás lo cierra).
        val anchor = currentFocus?.findViewById<View>(R.id.card)
        popBack()
        menus.show(if (anchor == null) tile.label else null, items, anchor)
        return true
    }

    /** Menú al mantener pulsado OK en Conexiones y sistema: recolocar la tarjeta. */
    private fun showSystemMenu(tile: Tile): Boolean {
        val anchor = currentFocus?.findViewById<View>(R.id.card)
        popBack()
        menus.show(if (anchor == null) tile.label else null, listOf(
            TvMenu.Item(getString(R.string.menu_move)) { startMoving(tile, inSystem = true) },
            TvMenu.Item(getString(R.string.menu_cancel)) { }), anchor)
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
        private const val LAUNCH_ZOOM_MS = 450L
        private const val NIGHT_SHADE = 0.38f
        /** El dock, en el inicio, queda a esta distancia del borde de abajo (zona segura: 27 dp). */
        private const val DOCK_BOTTOM_DP = 32
        private const val KEY_SHELF_LIGHT = "shelf_light"   // = las fotos al 62 % de brillo, como antes
        private const val TAG = "LauncherTV"
        private val AIRPLAY_RECEIVERS = listOf(
            "io.github.jqssun.airplay",        // AirPlay Server (código abierto, UxPlay) — paso6_airplay.sh
            "com.ionitech.airscreen",          // AirScreen
            "com.sand.airdroidcast.tv",        // AirDroid Cast
            "com.airbeamtv.receiver"           // AirBeamTV
        )
    }

    private fun prefs() = getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private fun shelfLight() = prefs().getBoolean(KEY_SHELF_LIGHT, false)

    /** Estante del dock oscuro (humo) o claro; al cambiarlo, se funde de uno a otro. */
    private fun applyShelf(animate: Boolean) {
        val shelf = (if (::dock.isInitialized) dock.parent as? View else null) ?: return
        val to = getDrawable(if (shelfLight()) R.drawable.dock_shelf_light else R.drawable.dock_shelf) ?: return
        val from = shelf.background
        if (!animate || from == null) { shelf.background = to; return }
        val t = android.graphics.drawable.TransitionDrawable(arrayOf(from, to))
        t.isCrossFadeEnabled = true
        shelf.background = t
        t.startTransition(400)
    }

    /** Aviso propio: baja desde arriba a la derecha y se va solo. */
    private val hideToast = Runnable {
        toastView.animate().alpha(0f).translationY(-12 * resources.displayMetrics.density).setDuration(250)
            .withEndAction { toastView.visibility = View.GONE }.start()
    }

    private fun toast(text: String, long: Boolean = false) {
        val d = resources.displayMetrics.density
        handler.removeCallbacks(hideToast)
        toastView.animate().cancel()
        toastView.text = text
        if (toastView.visibility != View.VISIBLE || toastView.alpha < 0.05f) {
            toastView.visibility = View.VISIBLE
            toastView.alpha = 0f
            toastView.translationY = -12 * d
        }
        toastView.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(ease).start()
        handler.postDelayed(hideToast, if (long) 4500 else 2600)
    }
}
