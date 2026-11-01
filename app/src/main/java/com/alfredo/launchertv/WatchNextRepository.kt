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
            "package_name", "last_engagement_time_utc_millis", "watch_next_type", "browsable",
            "season_display_number", "episode_display_number", "last_playback_position_millis", "duration_millis")
        val cursor = try {
            context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
                "last_engagement_time_utc_millis DESC")
        } catch (e: Exception) { null } ?: return out
        cursor.use { c ->
            val iId = c.getColumnIndex("_id"); val iTitle = c.getColumnIndex("title")
            val iEp = c.getColumnIndex("episode_title"); val iPoster = c.getColumnIndex("poster_art_uri")
            val iIntent = c.getColumnIndex("intent_uri"); val iPkg = c.getColumnIndex("package_name")
            val iBrowsable = c.getColumnIndex("browsable")
            val iSeason = c.getColumnIndex("season_display_number"); val iEpNum = c.getColumnIndex("episode_display_number")
            val iPos = c.getColumnIndex("last_playback_position_millis"); val iDur = c.getColumnIndex("duration_millis")
            while (c.moveToNext() && out.size < max) {
                if (iBrowsable >= 0 && !c.isNull(iBrowsable) && c.getInt(iBrowsable) == 0) continue
                val title = c.getString(iTitle) ?: continue
                val ep = if (iEp >= 0) c.getString(iEp) else null
                val pkg = c.getString(iPkg) ?: ""
                val appName = try {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) { "" }
                val base = if (!ep.isNullOrBlank()) "$title · $ep" else title
                val label = if (appName.isNotBlank()) "$appName — $base" else base
                // Progreso y lo que queda (si la app lo publica).
                val pos = if (iPos >= 0 && !c.isNull(iPos)) c.getLong(iPos) else 0L
                val dur = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0L
                val progress = if (dur > 0 && pos > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null
                val season = if (iSeason >= 0) c.getString(iSeason) else null
                val epNum = if (iEpNum >= 0) c.getString(iEpNum) else null
                val parts = ArrayList<String>()
                if (!season.isNullOrBlank() && !epNum.isNullOrBlank()) parts.add("T$season · E$epNum")
                else if (!ep.isNullOrBlank()) parts.add(ep)
                if (dur > 0 && pos > 0) {
                    val left = ((dur - pos) / 60_000L).coerceAtLeast(1)
                    parts.add(if (left >= 60) "quedan ${left / 60} h ${left % 60} min" else "quedan $left min")
                } else if (appName.isNotBlank()) parts.add(appName)
                val sub = parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
                out.add(Tile.Program(
                    id = "wn:" + c.getLong(iId),
                    label = label,
                    posterUri = c.getString(iPoster),
                    intentUri = c.getString(iIntent),
                    packageName = pkg,
                    title = title,
                    subtitle = sub,
                    progress = progress
                ))
            }
        }
        return out
    }
}
