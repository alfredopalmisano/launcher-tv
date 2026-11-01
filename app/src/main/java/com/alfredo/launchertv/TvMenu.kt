package com.alfredo.launchertv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Menús del launcher, con el mismo estilo que el resto (sustituyen a los de Android):
 * Nunito, título 24 sp, opciones de 20 sp en filas de 56 dp y una barra blanca que se DESLIZA
 * hasta la opción elegida.
 *
 * Con [anchor] (mantener OK sobre una tarjeta) el menú sale creciendo desde la tarjeta y se queda
 * a su lado, dentro de la zona segura; sin él, aparece en el centro.
 */
class TvMenu(private val activity: Activity) {

    class Item(val text: String, val action: () -> Unit)

    private val d = activity.resources.displayMetrics.density
    private val curve = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)

    fun show(title: String?, items: List<Item>, anchor: View? = null, message: String? = null) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val small = anchor != null
        val rowH = ((if (small) 52 else 56) * d).toInt()
        val gap = (2 * d).toInt()

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(if (small) R.drawable.menu_bg_small else R.drawable.menu_bg)
            val pad = ((if (small) 8 else 12) * d).toInt()
            setPadding(pad, if (title != null) (20 * d).toInt() else pad, pad, pad)
            elevation = 24 * d
        }
        if (title != null) panel.addView(TextView(activity).apply {
            text = title
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Fonts.medium(activity)
            setPadding(px(16), 0, px(16), px(if (message != null) 8 else 12))
            maxLines = 2
        })
        if (message != null) panel.addView(TextView(activity).apply {
            text = message
            textSize = 18f
            setTextColor(0xD9FFFFFF.toInt())
            typeface = Fonts.regular(activity)
            setLineSpacing(0f, 1.15f)
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (16 * d).toInt())
        })

        // Lista con la barra de la opción elegida detrás (se desliza entre opciones).
        val listBox = FrameLayout(activity)
        val hl = View(activity).apply { background = activity.getDrawable(R.drawable.menu_hl) }
        listBox.addView(hl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        listBox.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val rows = ArrayList<TextView>()
        items.forEachIndexed { i, it ->
            val tv = TextView(activity).apply {
                text = it.text
                textSize = if (small) 19f else 20f
                typeface = Fonts.medium(activity)
                setTextColor(0xE0FFFFFF.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(if (small) 14 else 16), 0, px(if (small) 14 else 16), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { _ -> dialog.dismiss(); it.action() }
                setOnFocusChangeListener { v, has ->
                    (v as TextView).setTextColor(if (has) 0xFF141415.toInt() else 0xE0FFFFFF.toInt())
                    if (has) {
                        hl.animate().translationY(v.top.toFloat()).setDuration(220).setInterpolator(curve).start()
                        Sounds.play(Sounds.MOVE)
                    }
                }
            }
            list.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH).apply {
                if (i > 0) topMargin = gap
            })
            rows.add(tv)
        }
        panel.addView(listBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val width = ((if (small) 260 else 400) * d).toInt()
        dialog.setContentView(panel, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setWindowAnimations(0)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.45f)
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
        }

        // Posición: junto a la tarjeta (a la derecha si cabe), centrado en su altura, en la zona segura.
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        var originX = width / 2f; var originY = 0f
        if (anchor != null) {
            val loc = IntArray(2); anchor.getLocationOnScreen(loc)
            val aw = (anchor.width * anchor.scaleX).toInt(); val ah = (anchor.height * anchor.scaleY).toInt()
            val h = estimateHeight(items.size, rowH, gap, title != null)
            var x = loc[0] + aw + (16 * d).toInt()
            if (x + width > screenW - 48 * d) x = loc[0] - width - (16 * d).toInt()
            val y = (loc[1] + ah / 2 - h / 2).coerceIn((27 * d).toInt(), (screenH - 27 * d - h).toInt().coerceAtLeast((27 * d).toInt()))
            dialog.window?.let { w ->
                w.setGravity(Gravity.TOP or Gravity.START)
                w.attributes = w.attributes.apply { this.x = x; this.y = y }
            }
            originX = (loc[0] + aw / 2f - x)
            originY = (loc[1] + ah / 2f - y)
        }

        panel.alpha = 0f
        dialog.setOnShowListener {
            if (anchor != null) {
                panel.pivotX = originX; panel.pivotY = originY
                panel.scaleX = 0.35f; panel.scaleY = 0.35f
                panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(emphasized).start()
            } else {
                panel.pivotX = panel.width / 2f; panel.pivotY = panel.height / 2f
                panel.scaleX = 0.96f; panel.scaleY = 0.96f
                panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(emphasized).start()
            }
            rows.firstOrNull()?.requestFocus()
        }
        dialog.show()
    }

    private fun px(dp: Int): Int = (dp * d).toInt()

    private fun estimateHeight(n: Int, rowH: Int, gap: Int, hasTitle: Boolean): Int =
        n * rowH + (n - 1) * gap + (16 * d).toInt() + if (hasTitle) (60 * d).toInt() else 0
}
