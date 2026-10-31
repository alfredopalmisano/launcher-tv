package com.alfredo.launchertv

import android.content.Context

/** Orden de la fila "Conexiones y sistema" elegido con Mover. Se guarda en las preferencias. */
class SystemOrder(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /**
     * Ordena [tiles] según lo guardado. Las tarjetas nuevas (p. ej. un HDMI que antes no estaba)
     * se quedan en su posición de siempre.
     */
    fun apply(tiles: List<Tile>): List<Tile> {
        val saved = saved()
        if (saved.isEmpty()) return tiles
        val byId = tiles.associateBy { it.id }
        val result = saved.mapNotNull { byId[it] }.toMutableList()
        tiles.forEachIndexed { i, t ->
            if (t.id !in saved) result.add(i.coerceAtMost(result.size), t)
        }
        return result
    }

    /** Mueve [id] una posición (delta −1 izquierda, +1 derecha) dentro de [current]. */
    fun move(current: List<String>, id: String, delta: Int): Boolean {
        val list = current.toMutableList()
        val i = list.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return false
        list.removeAt(i)
        list.add(j, id)
        // Guardar también las que ahora no se ven (un HDMI apagado) para no perder su sitio.
        val hidden = saved().filter { it !in list }
        prefs.edit().putString(KEY, (list + hidden).joinToString("\n")).apply()
        return true
    }

    private fun saved(): List<String> = prefs.getString(KEY, "")!!.split('\n').filter { it.isNotBlank() }

    companion object {
        private const val KEY = "system_order"
    }
}
