package com.alfredo.launchertv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TileAdapter(
    private val onClick: (Tile) -> Unit,
    private val onLongClick: (Tile) -> Boolean
) : RecyclerView.Adapter<TileAdapter.Holder>() {

    private val items = ArrayList<Tile>()

    fun submit(list: List<Tile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
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

        init {
            card.clipToOutline = true
            label.typeface = Fonts.medium(view.context)
            view.setOnClickListener { items.getOrNull(bindingAdapterPosition)?.let(onClick) }
            view.setOnLongClickListener { items.getOrNull(bindingAdapterPosition)?.let(onLongClick) ?: false }
            view.setOnFocusChangeListener { _, focused ->
                val scale = if (focused) 1.12f else 1f
                card.animate().scaleX(scale).scaleY(scale).setDuration(170).start()
                card.animate().translationZ(if (focused) 14f else 0f).setDuration(170).start()
                label.animate().alpha(if (focused) 1f else 0f).setDuration(170).start()
                card.foreground = if (focused) focusDrawable else null
            }
        }

        fun bind(tile: Tile) {
            label.text = tile.label
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
                is Tile.Input -> {
                    banner.setImageDrawable(null)
                    banner.visibility = View.GONE
                    icon.setImageResource(tile.kind.iconRes)
                    icon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    icon.visibility = View.VISIBLE
                }
                Tile.AllApps -> {
                    banner.setImageDrawable(null)
                    banner.visibility = View.GONE
                    icon.setImageResource(R.drawable.ic_apps)
                    icon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    icon.visibility = View.VISIBLE
                }
            }
        }
    }
}
