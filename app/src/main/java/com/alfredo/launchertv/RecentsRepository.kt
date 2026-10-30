package com.alfredo.launchertv

import android.app.usage.UsageStatsManager
import android.content.Context

/** Últimas apps usadas (necesita el permiso de "acceso al uso", que da el paso 5 por ADB). */
object RecentsRepository {
    fun load(context: Context, installed: List<Tile.App>, exclude: Set<String>, max: Int = 10): List<Tile.App> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val now = System.currentTimeMillis()
        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 14L * 24 * 3600 * 1000, now)
        } catch (e: Exception) { null } ?: return emptyList()
        val last = HashMap<String, Long>()
        for (u in stats) {
            if (u.lastTimeUsed <= 0) continue
            val prev = last[u.packageName] ?: 0L
            if (u.lastTimeUsed > prev) last[u.packageName] = u.lastTimeUsed
        }
        val byPkg = installed.associateBy { it.packageName }
        return last.entries
            .filter { byPkg.containsKey(it.key) && it.key !in exclude }
            .sortedByDescending { it.value }
            .take(max)
            .mapNotNull { byPkg[it.key] }
    }
}
