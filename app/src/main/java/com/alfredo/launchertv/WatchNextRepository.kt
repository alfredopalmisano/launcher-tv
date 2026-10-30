package com.alfredo.launchertv

import android.content.Context
import android.media.tv.TvContract

/**
 * Fila "Continuar viendo": lo que las apps publican en el canal Watch Next de Android TV
 * (Netflix, Prime Video, Disney+, Max, YouTube…). Necesita el permiso READ_TV_LISTINGS,
 * que se concede una vez por ADB (paso 5).
 */
object WatchNextRepository {

    fun load(context: Context, max: Int = 12): List<Tile.Program> {
        val out = ArrayList<Tile.Program>()
        val cols = arrayOf("_id", "title", "episode_title", "poster_art_uri", "intent_uri",
            "package_name", "last_engagement_time_utc_millis", "watch_next_type", "browsable")
        val cursor = try {
            context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
                "last_engagement_time_utc_millis DESC")
        } catch (e: Exception) { null } ?: return out
        cursor.use { c ->
            val iId = c.getColumnIndex("_id"); val iTitle = c.getColumnIndex("title")
            val iEp = c.getColumnIndex("episode_title"); val iPoster = c.getColumnIndex("poster_art_uri")
            val iIntent = c.getColumnIndex("intent_uri"); val iPkg = c.getColumnIndex("package_name")
            val iBrowsable = c.getColumnIndex("browsable")
            while (c.moveToNext() && out.size < max) {
                if (iBrowsable >= 0 && !c.isNull(iBrowsable) && c.getInt(iBrowsable) == 0) continue
                val title = c.getString(iTitle) ?: continue
                val ep = if (iEp >= 0) c.getString(iEp) else null
                val label = if (!ep.isNullOrBlank()) "$title · $ep" else title
                out.add(Tile.Program(
                    id = "wn:" + c.getLong(iId),
                    label = label,
                    posterUri = c.getString(iPoster),
                    intentUri = c.getString(iIntent),
                    packageName = c.getString(iPkg) ?: ""
                ))
            }
        }
        return out
    }
}
