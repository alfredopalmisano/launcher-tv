package com.alfredo.launchertv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import org.json.JSONObject

/**
 * Posición del reloj y máscara de la montaña de cada fondo, calculadas al compilar
 * (scripts/analizar_fondos.py) y revisadas a ojo antes de publicar. La tele solo las aplica.
 */
class Layouts(private val context: Context) {

    class Entry(val digitTop: Int, val digitH: Int, val useFg: Boolean, val bright: Boolean)

    private val entries = HashMap<String, Entry>()

    init {
        try {
            val json = JSONObject(context.assets.open("wallpapers/layout.json").bufferedReader().readText())
            for (key in json.keys()) {
                val o = json.getJSONObject(key)
                entries[key] = Entry(o.getInt("digitTop"), o.getInt("digitH"), o.optBoolean("useFg", false), o.optBoolean("bright", false))
            }
        } catch (e: Exception) { }
    }

    operator fun get(file: String): Entry? = entries[file]

    /** Primer plano (montaña) = foto recortada con la máscara precalculada; null si no hay. */
    fun foreground(file: String, photo: Bitmap): Bitmap? {
        val maskName = "wallpapers/" + file.replace(".jpg", ".mask.png")
        val mask = try { context.assets.open(maskName).use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null } ?: return null
        return try {
            val fg = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(fg)
            c.drawBitmap(photo, 0f, 0f, null)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            c.drawBitmap(mask, Rect(0, 0, mask.width, mask.height), Rect(0, 0, photo.width, photo.height), p)
            mask.recycle()
            fg
        } catch (e: Throwable) { null }
    }
}
