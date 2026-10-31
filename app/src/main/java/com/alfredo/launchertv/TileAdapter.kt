package com.alfredo.launchertv

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * @param tileWidthDp ancho de cada tarjeta (alto = 9/16). El dock usa tarjetas grandes; la cuadrícula, más pequeñas.
 */
class TileAdapter(
    private val tileWidthDp: Int,
    private val showLabels: Boolean,
    private val onClick: (Tile) -> Unit,
    /** Carrusel infinito: tras la última tarjeta vuelve la primera (solo si hay más de [loopMin]). */
    private val loopMin: Int = Int.MAX_VALUE,
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
        if (!showLabels) label.visibility = View.GONE
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[realIndex(position)])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: FrameLayout = view.findViewById(R.id.card)
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val label: TextView = view.findViewById(R.id.label)
        private val badge: ImageView = view.findViewById(R.id.badge)
        private val shine: View = view.findViewById(R.id.shine)
        private val focusDrawable = view.context.getDrawable(R.drawable.tile_focus)
        private val movingDrawable = view.context.getDrawable(R.drawable.tile_moving)
        private val white = ColorStateList.valueOf(0xFFFFFFFF.toInt())

        init {
            card.clipToOutline = true
            label.typeface = Fonts.medium(view.context)
            view.setOnClickListener { itemAt(bindingAdapterPosition)?.let(onClick) }
            view.setOnLongClickListener { itemAt(bindingAdapterPosition)?.let(onLongClick) ?: false }
            view.setOnFocusChangeListener { _, focused ->
                val scale = if (focused) 1.12f else 1f
                card.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) 18f else 0f)
                    .setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                // Luz que barre el cristal al enfocar y se queda como reflejo suave.
                shine.animate().cancel()
                if (focused && (sweep == Sweep.UP || sweep == Sweep.DOWN)) {
                    // Al cambiar de fila no hay barrido: solo aparece el reflejo glossy.
                    shine.translationX = 0f; shine.translationY = 0f
                    shine.alpha = 0f
                    shine.animate().alpha(0.35f).setDuration(220).start()
                } else if (focused) {
                    // El brillo barre en la dirección en la que te mueves (← →).
                    val w = card.width.toFloat()
                    val fromX = if (sweep == Sweep.LEFT) w else -w
                    shine.translationX = fromX
                    shine.translationY = 0f
                    shine.alpha = 0.95f
                    shine.animate().translationX(-fromX).translationY(0f).setDuration(520)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction {
                            shine.translationX = 0f; shine.alpha = 0f
                            shine.animate().alpha(0.35f).setDuration(250).start()
                        }
                        .start()
                } else {
                    shine.animate().alpha(0f).setDuration(150).start()
                }
                if (showLabels) label.animate().alpha(if (focused) 1f else 0f).translationY(if (focused) 0f else -6f).setDuration(180).start()
                val tile = itemAt(bindingAdapterPosition)
                card.foreground = when {
                    tile != null && tile.id == movingId -> movingDrawable
                    focused -> focusDrawable
                    else -> null
                }
            }
        }

        fun bind(tile: Tile) {
            label.text = tile.label
            badge.visibility = View.GONE
            val moving = tile.id == movingId
            card.foreground = if (moving) movingDrawable else if (itemView.hasFocus()) focusDrawable else null
            card.rotation = if (moving) -2f else 0f
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
                        icon.visibility = View.VISIBLE
                    }
                }
                is Tile.Program -> {
                    icon.visibility = View.GONE
                    banner.visibility = View.VISIBLE
                    banner.setImageDrawable(null)
                    if (tile.posterUri != null) Posters.load(itemView.context, tile.posterUri, banner, card.layoutParams.width)
                    // Logo de la app de origen en la esquina.
                    val appIcon = try { itemView.context.packageManager.getApplicationIcon(tile.packageName) } catch (e: Exception) { null }
                    if (appIcon != null) { badge.setImageDrawable(appIcon); badge.visibility = View.VISIBLE }
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
            icon.visibility = View.VISIBLE
        }
    }

    /** Dirección del último movimiento con el mando: hacia dónde barre el brillo. */
    enum class Sweep { LEFT, RIGHT, UP, DOWN }

    companion object {
        var sweep = Sweep.RIGHT
        /** Vueltas del carrusel: de sobra para no llegar nunca al final (y barato: solo es un número). */
        const val LOOP_TURNS = 400
    }
}
