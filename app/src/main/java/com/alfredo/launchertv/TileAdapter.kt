package com.alfredo.launchertv

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * @param tileWidthDp ancho de cada tarjeta (alto = 9/16). El dock usa tarjetas grandes; la cuadrícula, más pequeñas.
 * @param posters fila "Continuar viendo": el póster enfocado se ensancha y enseña título y progreso.
 */
class TileAdapter(
    private val tileWidthDp: Int,
    private val showLabels: Boolean,
    private val onClick: (Tile) -> Unit,
    /** Carrusel infinito: tras la última tarjeta vuelve la primera (solo si hay más de [loopMin]). */
    private val loopMin: Int = Int.MAX_VALUE,
    private val posters: Boolean = false,
    private val onLongClick: (Tile) -> Boolean
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    private val items = ArrayList<Tile>()

    /** ¿Está en modo carrusel ahora mismo? */
    val looping: Boolean get() = items.size > loopMin

    /** Posición "real" (0…n-1) de una posición del adaptador. */
    fun realIndex(position: Int): Int = if (items.isEmpty()) -1 else position.mod(items.size)

    fun itemAt(position: Int): Tile? = if (position < 0 || items.isEmpty()) null else items[realIndex(position)]

    /** Posición del carrusel donde empieza la vuelta del medio (la tarjeta 0 de esa vuelta). */
    fun loopStart(): Int = if (looping) items.size * (LOOP_TURNS / 2) else 0

    /**
     * Posición del adaptador de la tarjeta [id] más cercana a [near] (en el carrusel hay muchas copias).
     * -1 si no está.
     */
    fun positionNear(id: String, near: Int): Int {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return -1
        if (!looping) return i
        val n = items.size
        val base = near - near.mod(n)
        return listOf(base - n + i, base + i, base + n + i).filter { it in 0 until itemCount }
            .minByOrNull { kotlin.math.abs(it - near) } ?: i
    }

    /** Tarjeta que se está recolocando (modo mover). */
    var movingId: String? = null
        set(value) { field = value; notifyDataSetChanged() }

    fun submit(list: List<Tile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun ids(): List<String> = items.map { it.id }

    /** Número de tarjetas distintas (sin contar las copias del carrusel). */
    val size: Int get() = items.size

    fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }

    override fun getItemCount(): Int = if (looping) items.size * LOOP_TURNS else items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
        val d = parent.resources.displayMetrics.density
        val w = (tileWidthDp * d).toInt()
        val card = v.findViewById<FrameLayout>(R.id.card)
        card.layoutParams = card.layoutParams.apply { width = w; height = w * 9 / 16 }
        val label = v.findViewById<TextView>(R.id.label)
        label.layoutParams = label.layoutParams.apply { width = w }
        if (!showLabels || posters) label.visibility = View.GONE
        if (posters) {
            val info = v.findViewById<View>(R.id.info)
            info.visibility = View.VISIBLE
            info.layoutParams = info.layoutParams.apply { width = w }
            v.findViewById<View>(R.id.shade).visibility = View.VISIBLE
            v.findViewById<View>(R.id.prog).visibility = View.VISIBLE
        }
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.itemView.findViewById<View>(R.id.card).elevation = if (noElevation) 0f else holder.itemView.resources.displayMetrics.density * 6
        holder.bind(items[realIndex(position)])
    }

    override fun onViewRecycled(holder: Holder) {
        holder.reset()
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val box: FrameLayout = view.findViewById(R.id.box)
        private val ring: View = view.findViewById(R.id.ring)
        private val card: FrameLayout = view.findViewById(R.id.card)
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val label: TextView = view.findViewById(R.id.label)
        private val badge: ImageView = view.findViewById(R.id.badge)
        private val shine: View = view.findViewById(R.id.shine)
        private val gloss: View = view.findViewById(R.id.gloss)
        private val info: View = view.findViewById(R.id.info)
        private val infoTitle: TextView = view.findViewById(R.id.infoTitle)
        private val infoSub: TextView = view.findViewById(R.id.infoSub)
        private val progFill: View = view.findViewById(R.id.progFill)
        private val focusDrawable = view.context.getDrawable(R.drawable.tile_focus)
        private val movingDrawable = view.context.getDrawable(R.drawable.tile_moving)
        private val shineDrawable = ShineDrawable()
        private var shineAnim: ValueAnimator? = null
        private var widthAnim: ValueAnimator? = null
        private val white = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        private val d = view.resources.displayMetrics.density
        private val baseW = (tileWidthDp * d).toInt()
        private val wideW = (POSTER_WIDE_DP * d).toInt()

        init {
            card.clipToOutline = true
            shine.background = shineDrawable
            shine.alpha = 1f
            // Cámara lejos: la inclinación 3D se ve sutil, sin deformar la tarjeta.
            box.cameraDistance = 9000f * d
            label.typeface = Fonts.medium(view.context)
            infoTitle.typeface = Fonts.medium(view.context)
            infoSub.typeface = Fonts.regular(view.context)
            view.setOnClickListener {
                // Al pulsar: la tarjeta se hunde un instante (respuesta inmediata, como en Apple TV)
                // y la app se abre creciendo desde ella (ver MainActivity.tryStart).
                lastClicked = card
                // Pulsación "líquida" (idea de quickLiquid): se aplasta un pelín (más de alto que de
                // ancho) y vuelve con un rebote de muelle.
                val s = focusScale()
                box.animate().scaleX(s * 0.955f).scaleY(s * 0.925f).setDuration(90).setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        box.animate().scaleX(s).scaleY(s).setDuration(360).setInterpolator(OvershootInterpolator(2.2f)).start()
                    }
                    .start()
                itemAt(bindingAdapterPosition)?.let(onClick)
                lastClicked = null   // solo vale para la app que se abre con esta pulsación
            }
            view.setOnLongClickListener { itemAt(bindingAdapterPosition)?.let(onLongClick) ?: false }
            view.setOnFocusChangeListener { _, focused -> onFocus(focused) }
        }

        /**
         * Todas las tarjetas crecen lo mismo, unos 9 dp por lado, sea cual sea su tamaño
         * (1,12 en las de 152 dp; menos en los pósters, más anchos).
         */
        private fun focusScale(): Float {
            val w = if (posters && itemView.hasFocus()) wideW else card.layoutParams.width.coerceAtLeast(1)
            return 1f + GROW_DP * d * 2f / w
        }

        private fun onFocus(focused: Boolean) {
            val light = focused && fast
            if (posters) widen(focused)
            val scale = if (focused) focusScale() else 1f
            if (focused && !light) {
                // Efecto 3D: llega ladeada hacia donde te mueves y se endereza con un pequeño rebote.
                box.rotationY = when (sweep) { Sweep.RIGHT -> -TILT; Sweep.LEFT -> TILT; else -> 0f }
                box.rotationX = when (sweep) { Sweep.DOWN -> TILT * 0.7f; Sweep.UP -> -TILT * 0.7f; else -> 0f }
            }
            if (focused) onFocusSound?.invoke(if (light) Sound.LIGHT else if (sweep == Sweep.UP || sweep == Sweep.DOWN) Sound.ROW else Sound.MOVE)
            box.animate().scaleX(scale).scaleY(scale).rotationX(0f).rotationY(0f)
                .setDuration(if (light) 110 else if (focused) FOCUS_MS else 200)
                .setInterpolator(if (light) LinearInterpolator() else if (focused) OvershootInterpolator(1.0f) else DecelerateInterpolator())
                .start()
            card.animate().translationZ(if (focused) 14f * d else 0f).setDuration(if (focused) 260 else 200).start()
            // El marco y el brillo solo en la tarjeta elegida.
            ring.animate().alpha(if (focused) 1f else 0f).setDuration(if (focused) 120 else 160).start()
            gloss.animate().alpha(if (focused) 0.6f else 0f).setDuration(200).start()
            // Reflejo de luz que cruza la tarjeta en la dirección del movimiento (← →), no al pasar rápido.
            shineAnim?.cancel()
            if (focused && !light && (sweep == Sweep.LEFT || sweep == Sweep.RIGHT)) {
                val fromLeft = sweep == Sweep.RIGHT
                shineAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 700
                    startDelay = 60
                    interpolator = PathInterpolator(0.35f, 0f, 0.25f, 1f)
                    addUpdateListener { a ->
                        val p = a.animatedValue as Float
                        shineDrawable.progress = if (fromLeft) p else 1f - p
                        shineDrawable.strength = kotlin.math.sin(p * Math.PI).toFloat()
                    }
                    start()
                }
            } else {
                shineDrawable.progress = -1f
            }
            if (showLabels && !posters) label.animate().alpha(if (focused) 1f else 0f).translationY(if (focused) 0f else -6f * d).setDuration(180).start()
            if (posters) info.animate().alpha(if (focused) 1f else 0f).translationY(if (focused) 0f else -6f * d)
                .setStartDelay(if (focused) 120 else 0).setDuration(if (focused) 220 else 150).start()
            paintRing()
        }

        /** Tras recorrer la fila deprisa: la tarjeta donde paras recibe el efecto completo. */
        fun settle() {
            if (!itemView.hasFocus()) return
            onFocus(true)
        }

        /** "Continuar viendo": el póster enfocado se ensancha (200 → 280 dp) y las vecinas se apartan. */
        private fun widen(open: Boolean) {
            widthAnim?.cancel()
            val from = card.layoutParams.width
            val to = if (open) wideW else baseW
            if (from == to) return
            widthAnim = ValueAnimator.ofInt(from, to).apply {
                duration = 320
                interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
                addUpdateListener { a -> setCardWidth(a.animatedValue as Int) }
                start()
            }
        }

        private fun setCardWidth(w: Int) {
            card.layoutParams = card.layoutParams.apply { width = w }
            info.layoutParams = info.layoutParams.apply { width = w }
            card.requestLayout()
        }

        private fun paintRing() {
            val tile = itemAt(bindingAdapterPosition)
            val moving = tile != null && tile.id == movingId
            ring.background = if (moving) movingDrawable else focusDrawable
            if (moving) { ring.animate().cancel(); ring.alpha = 1f }
            box.rotation = if (moving) -2f else 0f
        }

        /** Al reciclar la vista: sin restos de foco ni de ancho. */
        fun reset() {
            widthAnim?.cancel(); shineAnim?.cancel()
            box.animate().cancel(); box.scaleX = 1f; box.scaleY = 1f; box.rotationX = 0f; box.rotationY = 0f
            card.animate().cancel(); card.translationZ = 0f
            ring.animate().cancel(); ring.alpha = 0f
            gloss.animate().cancel(); gloss.alpha = 0f
            if (posters && card.layoutParams.width != baseW) setCardWidth(baseW)
            info.animate().cancel(); info.alpha = 0f
            label.animate().cancel(); label.alpha = 0f
        }

        fun bind(tile: Tile) {
            label.text = tile.label
            badge.visibility = View.GONE
            paintRing()
            if (tile.id == movingId) ring.alpha = 1f
            else if (!itemView.hasFocus()) ring.alpha = 0f
            when (tile) {
                is Tile.App -> {
                    if (tile.banner != null) {
                        banner.setImageDrawable(tile.banner)
                        banner.visibility = View.VISIBLE
                        icon.visibility = View.GONE
                    } else {
                        banner.setImageDrawable(null)
                        banner.visibility = View.GONE
                        icon.setImageDrawable(tile.icon)
                        icon.imageTintList = null
                        // Apps sin banner: su icono a buen tamaño (no el de 40 dp de los iconos del sistema).
                        icon.layoutParams = icon.layoutParams.apply { width = (56 * d).toInt(); height = (56 * d).toInt() }
                        icon.visibility = View.VISIBLE
                    }
                }
                is Tile.Program -> {
                    icon.visibility = View.GONE
                    banner.visibility = View.VISIBLE
                    banner.setImageDrawable(null)
                    if (tile.posterUri != null) Posters.load(itemView.context, tile.posterUri, banner, wideW)
                    infoTitle.text = tile.title
                    infoSub.text = tile.subtitle
                    progFill.scaleX = (tile.progress ?: 0f).coerceIn(0f, 1f)
                    (progFill.parent as View).visibility = if (tile.progress != null) View.VISIBLE else View.GONE
                    // Logo de la app de origen en la esquina.
                    val appIcon = try { itemView.context.packageManager.getApplicationIcon(tile.packageName) } catch (e: Exception) { null }
                    if (appIcon != null) { badge.setImageDrawable(appIcon); badge.visibility = View.VISIBLE }
                    if (tile.progress != null) (badge.layoutParams as FrameLayout.LayoutParams).bottomMargin = (20 * d).toInt()
                }
                is Tile.Input -> showIcon(tile.kind.iconRes)
                Tile.AllApps -> showIcon(R.drawable.ic_apps)
                Tile.Store -> showIcon(R.drawable.ic_store)
                Tile.SettingsTile -> showIcon(R.drawable.ic_settings)
                Tile.AirPlay -> showIcon(R.drawable.ic_airplay)
                Tile.SleepTile -> showIcon(R.drawable.ic_moon)
                is Tile.Action -> showIcon(tile.iconRes)
            }
        }

        private fun showIcon(res: Int) {
            banner.setImageDrawable(null)
            banner.visibility = View.GONE
            icon.setImageResource(res)
            icon.imageTintList = white
            icon.layoutParams = icon.layoutParams.apply { width = (40 * d).toInt(); height = (40 * d).toInt() }
            icon.visibility = View.VISIBLE
        }
    }

    /** Dirección del último movimiento con el mando: hacia dónde barre el brillo. */
    enum class Sweep { LEFT, RIGHT, UP, DOWN }

    /** Sonidos de navegación: moverse, moverse deprisa, cambiar de fila. */
    enum class Sound { MOVE, LIGHT, ROW }

    companion object {
        var sweep = Sweep.RIGHT
        /** Grados de inclinación al llegar el foco (efecto 3D). */
        const val TILT = 7f
        /** Llegada del foco: 300 ms con un rebote corto (antes 420 ms y más rebote). */
        const val FOCUS_MS = 300L
        /** Cuánto crece cada tarjeta enfocada, por lado. */
        const val GROW_DP = 9f
        const val POSTER_WIDE_DP = 280
        /** Recorriendo la fila con la flecha mantenida: sin inclinación, brillo ni clic en cada tarjeta. */
        var fast = false
        /** Última tarjeta pulsada: la app se abre creciendo desde ella. */
        var lastClicked: View? = null
        /** Se llama al enfocar una tarjeta (sonido). */
        var onFocusSound: ((Sound) -> Unit)? = null
        /** Prueba de rendimiento: tarjetas sin sombra de elevación. */
        var noElevation = false
        /** Vueltas del carrusel: de sobra para no llegar nunca al final (y barato: solo es un número). */
        const val LOOP_TURNS = 400
    }
}
