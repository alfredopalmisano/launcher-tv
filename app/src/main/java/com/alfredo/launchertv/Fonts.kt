package com.alfredo.launchertv

import android.content.Context
import android.graphics.Typeface

/** Tipografías: Inter (libre, muy parecida a la de Apple) si está en los assets; si no, la del sistema. */
object Fonts {
    fun thin(context: Context): Typeface = load(context, "fonts/Inter-ExtraLight.ttf")
        ?: load(context, "fonts/Inter-Light.ttf")
        ?: Typeface.create("sans-serif-thin", Typeface.NORMAL)

    fun light(context: Context): Typeface = load(context, "fonts/Inter-Light.ttf")
        ?: Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun medium(context: Context): Typeface = load(context, "fonts/Inter-Medium.ttf")
        ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun load(context: Context, path: String): Typeface? = try {
        Typeface.createFromAsset(context.assets, path)
    } catch (e: Exception) {
        null
    }
}
