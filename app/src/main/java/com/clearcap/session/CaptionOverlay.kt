package com.clearcap.session

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Floating caption bar over other apps. Drag it up or down; the position is remembered via [onMoved].
 * It is not focusable and does not read or change the app underneath. Needs the user's "display over other apps" permission.
 */
class CaptionOverlay(ctx: Context, textSp: Int, bottomPx: Int, private val onMoved: (Int) -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var shown = false

    private val tv = TextView(ctx).apply {
        setTextColor(Color.parseColor("#FAFAFA"))
        textSize = textSp.toFloat()
        gravity = Gravity.CENTER
        maxLines = 4
        setPadding(36, 22, 36, 22)
        background = GradientDrawable().apply { setColor(Color.parseColor("#E6111113")); cornerRadius = 28f }
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // the app screen is the accessible copy; avoid announcing every update twice
    }

    private val lp = WindowManager.LayoutParams(
        (ctx.resources.displayMetrics.widthPixels * 0.94f).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = bottomPx
    }

    init {
        var startY = 0f
        var startPos = 0
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startPos = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.y = (startPos - (e.rawY - startY)).toInt().coerceAtLeast(0)
                    if (shown) wm.updateViewLayout(tv, lp)
                }
                MotionEvent.ACTION_UP -> onMoved(lp.y)
            }
            true
        }
    }

    fun show() {
        if (shown) return
        runCatching { wm.addView(tv, lp); shown = true }
    }

    fun hide() {
        if (!shown) return
        runCatching { wm.removeView(tv) }
        shown = false
    }

    fun set(text: String, warning: String?) {
        val t = listOfNotNull(warning, text.takeIf { it.isNotBlank() }).joinToString("\n")
        tv.text = t
        tv.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
    }
}
