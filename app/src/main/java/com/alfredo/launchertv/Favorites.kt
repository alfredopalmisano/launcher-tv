package com.alfredo.launchertv

import android.content.Context

/** Apps del dock, en orden. Se guardan en las preferencias de la app. */
class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Orden preferido para el dock la primera vez (si la app está instalada). */
    private val defaultOrder = listOf(
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.wbd.stream",
        "com.skyshowtime.skyshowtime.google",
        "com.android.mgandroid",
        "com.televen_stream.android",
        "com.vis.ligafutve",
        "com.mediatek.wwtv.tvcenter"
    )

    fun get(installed: List<Tile.App>): List<Tile.App> {
        val byPkg = installed.associateBy { it.packageName }
        val saved = prefs.getString(KEY, null)
        val order = if (saved == null) {
            val first = defaultOrder.filter { byPkg.containsKey(it) }
            set(first)
            first
        } else {
            saved.split('\n').filter { it.isNotBlank() }
        }
        return order.mapNotNull { byPkg[it] }
    }

    fun contains(pkg: String): Boolean = current().contains(pkg)

    fun toggle(pkg: String): Boolean {
        val list = current().toMutableList()
        val added = if (list.remove(pkg)) false else { list.add(pkg); true }
        set(list)
        return added
    }

    /** Mueve una app del dock una posición (delta -1 izquierda, +1 derecha). */
    fun move(pkg: String, delta: Int): Boolean {
        val list = current().toMutableList()
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return false
        list.removeAt(i)
        list.add(j, pkg)
        set(list)
        return true
    }

    private fun current(): List<String> =
        prefs.getString(KEY, "")!!.split('\n').filter { it.isNotBlank() }

    private fun set(list: List<String>) {
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    companion object {
        private const val KEY = "favorites"
    }
}
