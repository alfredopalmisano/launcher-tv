package com.alfredo.launchertv

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * El desplazamiento vertical de las filas lo decide MainActivity (para que la fila enfocada,
 * con su nombre, quede siempre dentro de la zona segura de la tele): aquí se anula el que
 * Android hace por su cuenta al mover el foco.
 */
class RowsScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0
}
