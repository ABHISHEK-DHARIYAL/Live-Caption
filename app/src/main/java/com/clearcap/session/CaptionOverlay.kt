package com.clearcap.session

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating caption bar over other apps. Drag it up or down; the position is remembered via [onMoved].
 * It has two small buttons: "–" minimises it to a small "CC" pill (tap the pill to expand again) and
 * "✕" closes it for the rest of the session ([onClose]); the next session shows it again if enabled in Settings.
 * It is not focusable and does not read or change the app underneath. Needs the user's "display over other apps" permission.
 */
class CaptionOverlay(
    private val ctx: Context,
    textSp: Int,
    bottomPx: Int,
    private val onMoved: (Int) -> Unit,
    private val onClose: () -> Unit = {},
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var shown = false
    private var minimized = false
    private var hasText = false

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun bg(color: String, radius: Float) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = radius
    }

    private fun button(label: String, desc: String, onTap: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(Color.parseColor("#FAFAFA"))
        textSize = 16f
        gravity = Gravity.CENTER
        minWidth = dp(40)
        minHeight = dp(32)
        contentDescription = desc
        setOnClickListener { onTap() }
    }

    private val tv = TextView(ctx).apply {
        setTextColor(Color.parseColor("#FAFAFA"))
        textSize = textSp.toFloat()
        gravity = Gravity.CENTER
        maxLines = 4
        setPadding(36, 4, 36, 22)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // the app screen is the accessible copy; avoid announcing every update twice
    }

    private val minimizeBtn = button("–", "Minimise captions") { setMinimized(true) }
    private val closeBtn = button("✕", "Close captions") { onClose() }

    // Header row: spacer pushes the buttons to the right; the spacer also acts as a drag handle.
    private val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(12, 6, 12, 0)
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(minimizeBtn)
        addView(closeBtn)
    }

    private val expanded = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = bg("#E6111113", 28f)
        visibility = View.GONE
        addView(header)
        addView(tv)
    }

    private val pill = TextView(ctx).apply {
        text = "CC"
        setTextColor(Color.parseColor("#FAFAFA"))
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = bg("#E6111113", 100f)
        contentDescription = "Show captions"
        visibility = View.GONE
        setOnClickListener { setMinimized(false) }
    }

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(expanded, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        // Drag by the text area, the header background or the pill. Taps on the buttons / pill still click.
        var startY = 0f
        var startPos = 0
        var dragged = false
        val drag = View.OnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startPos = lp.y; dragged = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawY - startY) > dp(6)) dragged = true
                    if (dragged) {
                        lp.y = (startPos - (e.rawY - startY)).toInt().coerceAtLeast(0)
                        if (shown) wm.updateViewLayout(root, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) onMoved(lp.y) else if (v === pill) v.performClick()
                }
            }
            true
        }
        tv.setOnTouchListener(drag)
        header.setOnTouchListener(drag)
        pill.setOnTouchListener(drag)
    }

    private fun setMinimized(m: Boolean) {
        minimized = m
        refresh()
        lp.width = if (m) WindowManager.LayoutParams.WRAP_CONTENT else (ctx.resources.displayMetrics.widthPixels * 0.94f).toInt()
        if (shown) runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun refresh() {
        expanded.visibility = if (!minimized && hasText) View.VISIBLE else View.GONE
        pill.visibility = if (minimized) View.VISIBLE else View.GONE
    }

    fun show() {
        if (shown) return
        runCatching { wm.addView(root, lp); shown = true }
    }

    fun hide() {
        if (!shown) return
        runCatching { wm.removeView(root) }
        shown = false
    }

    fun set(text: String, warning: String?) {
        val t = listOfNotNull(warning, text.takeIf { it.isNotBlank() }).joinToString("\n")
        tv.text = t
        hasText = t.isNotBlank()
        refresh()
    }
}
