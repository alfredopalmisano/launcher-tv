package com.alfredo.launchertv

import android.content.Context
import android.graphics.Typeface

/**
 * Tipografía: Nunito (libre, OFL), redondeada como la del reloj de bloqueo del iPhone.
 * Es una fuente variable: el grosor se elige con el eje "wght". Si no está, se usa la del sistema.
 */
object Fonts {
    private const val NUNITO = "fonts/Nunito.ttf"
    private const val ANTONIO = "fonts/Antonio.ttf"   // condensada y alta, como el reloj de bloqueo del iPhone
    private val cache = HashMap<String, Typeface>()

    fun clock(context: Context): Typeface = variable(context, ANTONIO, 200)
        ?: variable(context, NUNITO, 230)
        ?: Typeface.create("sans-serif-condensed-light", Typeface.NORMAL)
    fun light(context: Context): Typeface = variable(context, NUNITO, 320) ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
    fun medium(context: Context): Typeface = variable(context, NUNITO, 560) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun variable(context: Context, path: String, wght: Int): Typeface? {
        val key = "$path@$wght"
        cache[key]?.let { return it }
        return try {
            Typeface.Builder(context.assets, path)
                .setFontVariationSettings("'wght' $wght")
                .build()
                ?.also { cache[key] = it }
        } catch (e: Exception) {
            null
        }
    }
}
