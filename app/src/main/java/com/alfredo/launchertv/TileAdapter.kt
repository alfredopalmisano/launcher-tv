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
    private val onLongClick: (Tile) -> Boolean
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    private val items = ArrayList<Tile>()

    /** Tarjeta que se está recolocando (modo mover). */
    var movingId: String? = null
        set(value) { field = value; notifyDataSetChanged() }

    fun submit(list: List<Tile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }

    override fun getItemCount(): Int = items.size

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
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: FrameLayout = view.findViewById(R.id.card)
        private val banner: ImageView = view.findViewById(R.id.banner)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val label: TextView = view.findViewById(R.id.label)
        private val focusDrawable = view.context.getDrawable(R.drawable.tile_focus)
        private val movingDrawable = view.context.getDrawable(R.drawable.tile_moving)
        private val white = ColorStateList.valueOf(0xFFFFFFFF.toInt())

        init {
            card.clipToOutline = true
            label.typeface = Fonts.medium(view.context)
            view.setOnClickListener { items.getOrNull(bindingAdapterPosition)?.let(onClick) }
            view.setOnLongClickListener { items.getOrNull(bindingAdapterPosition)?.let(onLongClick) ?: false }
            view.setOnFocusChangeListener { _, focused ->
                val scale = if (focused) 1.10f else 1f
                card.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) 16f else 0f)
                    .setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                if (showLabels) label.animate().alpha(if (focused) 1f else 0f).translationY(if (focused) 0f else -6f).setDuration(180).start()
                val tile = items.getOrNull(bindingAdapterPosition)
                card.foreground = when {
                    tile != null && tile.id == movingId -> movingDrawable
                    focused -> focusDrawable
                    else -> null
                }
            }
        }

        fun bind(tile: Tile) {
            label.text = tile.label
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
}
