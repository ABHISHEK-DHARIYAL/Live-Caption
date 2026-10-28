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

/** Floating caption bar over other apps (VoiceFlow-style dark pill). Drag it up or down to move it. */
class CaptionOverlay(ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var shown = false

    private val tv = TextView(ctx).apply {
        setTextColor(Color.parseColor("#FAFAFA"))
        textSize = 18f
        gravity = Gravity.CENTER
        maxLines = 3
        setPadding(36, 22, 36, 22)
        background = GradientDrawable().apply { setColor(Color.parseColor("#E6111113")); cornerRadius = 28f }
        visibility = View.GONE
    }

    private val lp = WindowManager.LayoutParams(
        (ctx.resources.displayMetrics.widthPixels * 0.94f).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = 180
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

    fun set(text: String, warn: Boolean) {
        val t = if (warn) "\u26A0 Falling behind\n$text".trim() else text
        tv.text = t
        tv.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
    }
}
