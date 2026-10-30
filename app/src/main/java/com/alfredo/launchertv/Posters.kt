package com.alfredo.launchertv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Carga de carátulas (http o content://) en segundo plano, con caché en memoria. */
object Posters {
    private val cache = LruCache<String, Bitmap>(24)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, uri: String, into: ImageView, maxW: Int) {
        into.tag = uri
        cache.get(uri)?.let { into.setImageBitmap(it); return }
        val app = context.applicationContext
        pool.execute {
            val bmp = fetch(app, uri, maxW)
            if (bmp != null) {
                cache.put(uri, bmp)
                main.post { if (into.tag == uri) into.setImageBitmap(bmp) }
            }
        }
    }

    private fun fetch(context: Context, uri: String, maxW: Int): Bitmap? = try {
        val bytes: ByteArray = if (uri.startsWith("http")) {
            val conn = URL(uri).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 8000
            conn.inputStream.use { it.readBytes() }
        } else {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (e: Throwable) { null }
}
