package com.alfredo.launchertv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/** Lee las apps instaladas que tienen icono en la tele. */
object AppsRepository {

    fun load(context: Context): List<Tile.App> {
        val pm = context.packageManager
        val self = context.packageName
        val result = LinkedHashMap<String, Tile.App>()

        fun query(category: String, withBanner: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val list: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == self || result.containsKey(pkg)) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                val banner = if (withBanner) safe { ai.loadBanner(pm) } ?: safe { ri.activityInfo.applicationInfo.loadBanner(pm) } else null
                val icon = safe { ri.loadIcon(pm) }
                result[pkg] = Tile.App(pkg, ai.name, label, banner, icon)
            }
        }

        query(Intent.CATEGORY_LEANBACK_LAUNCHER, withBanner = true)
        query(Intent.CATEGORY_LAUNCHER, withBanner = false)

        return result.values.sortedBy { it.label.lowercase() }
    }

    fun launchIntent(context: Context, app: Tile.App): Intent? {
        val pm: PackageManager = context.packageManager
        return pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: pm.getLaunchIntentForPackage(app.packageName)
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (e: Exception) { null }
}
