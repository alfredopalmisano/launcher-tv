package com.alfredo.launchertv

import android.content.Context
import android.graphics.Typeface

/**
 * Tipografía: Nunito (libre, OFL), redondeada como la del reloj de bloqueo del iPhone.
 * Es una fuente variable: el grosor se elige con el eje "wght". Si no está, se usa la del sistema.
 */
object Fonts {
    private const val NUNITO = "fonts/Nunito.ttf"
    private val cache = HashMap<Int, Typeface>()

    fun clock(context: Context): Typeface = weight(context, 230) ?: Typeface.create("sans-serif-thin", Typeface.NORMAL)
    fun light(context: Context): Typeface = weight(context, 320) ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
    fun medium(context: Context): Typeface = weight(context, 560) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun weight(context: Context, wght: Int): Typeface? = cache[wght] ?: try {
        Typeface.Builder(context.assets, NUNITO)
            .setFontVariationSettings("'wght' $wght")
            .build()
            ?.also { cache[wght] = it }
    } catch (e: Exception) {
        null
    }
}
