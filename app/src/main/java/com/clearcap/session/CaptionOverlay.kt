package com.clearcap.session

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** What the floating bubble's small dot shows while the captions are minimised. */
enum class OverlayStatus { LISTENING, PAUSED, BEHIND }

/**
 * Floating captions over other apps, in two forms that swap in place:
 *
 *  - the caption bar: drag it up or down; "–" minimises it into the bubble, "✕" closes it for the rest of the session;
 *  - the bubble: a small round "CC" icon you can drag anywhere. When you let go it settles against the nearest screen edge.
 *    Tap it to bring the caption bar back. Its dot is green while listening and amber when paused or falling behind.
 *
 * Positions and the minimised state are remembered through the callbacks. Neither view takes focus, and neither reads or changes
 * the app underneath. Needs the user's "display over other apps" permission.
 */
class CaptionOverlay(
    private val ctx: Context,
    textSp: Int,
    private val bottomPx: Int,
    bubbleOnLeft: Boolean,
    bubbleYPx: Int,
    startMinimized: Boolean,
    private val onBarMoved: (Int) -> Unit,
    private val onBubbleMoved: (onLeft: Boolean, yPx: Int) -> Unit,
    private val onMinimizedChanged: (Boolean) -> Unit,
    private val onClose: () -> Unit = {},
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var shown = false
    private var minimized = startMinimized
    private var hasText = false
    private var barAttached = false
    private var bubbleAttached = false
    private var snap: ValueAnimator? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun screenW() = ctx.resources.displayMetrics.widthPixels
    private fun screenH() = ctx.resources.displayMetrics.heightPixels

    private fun shape(color: String, radius: Float) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = radius
    }

    // ---------------------------------------------------------------- caption bar

    private fun button(label: String, desc: String, onTap: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(Color.parseColor("#FAFAFA"))
        textSize = 16f
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(36)
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

    // Header row: the empty space pushes the buttons to the right and doubles as a drag handle.
    private val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(12, 6, 12, 0)
        addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        addView(button("–", "Minimise captions to a bubble") { setMinimized(true) })
        addView(button("✕", "Close captions") { onClose() })
    }

    private val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = shape("#E6111113", 28f)
        visibility = View.GONE
        addView(header)
        addView(tv)
    }

    private val barRoot = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private val barLp = WindowManager.LayoutParams(
        (screenW() * 0.94f).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = bottomPx
    }

    // ---------------------------------------------------------------- bubble

    private val bubbleSize = dp(52)

    private val dot = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(GREEN)) } }

    private val bubble = FrameLayout(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#F0111113"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }
        elevation = dp(6).toFloat()
        contentDescription = "Show captions. Drag to move."
        addView(TextView(ctx).apply {
            text = "CC"
            setTextColor(Color.parseColor("#FAFAFA"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(dot, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) })
        setOnClickListener { setMinimized(false) }
    }

    private val bubbleLp = WindowManager.LayoutParams(
        bubbleSize, bubbleSize,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = if (bubbleOnLeft) 0 else (screenW() - bubbleSize).coerceAtLeast(0)
        y = (if (bubbleYPx >= 0) bubbleYPx else (screenH() * 0.35f).toInt()).coerceIn(0, (screenH() - bubbleSize).coerceAtLeast(0))
    }

    init {
        // Caption bar: vertical drag only. A tap on the buttons still clicks.
        var startY = 0f
        var startPos = 0
        var barDragged = false
        val barDrag = View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startPos = barLp.y; barDragged = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawY - startY) > dp(6)) barDragged = true
                    if (barDragged) {
                        barLp.y = (startPos - (e.rawY - startY)).toInt().coerceAtLeast(0)
                        if (barAttached) wm.updateViewLayout(barRoot, barLp)
                    }
                }
                MotionEvent.ACTION_UP -> if (barDragged) onBarMoved(barLp.y)
            }
            true
        }
        tv.setOnTouchListener(barDrag)
        header.setOnTouchListener(barDrag)

        // Bubble: free drag, then settle on the nearest edge. A tap (no drag) expands the caption bar.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startYb = 0
        var bubbleDragged = false
        bubble.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    snap?.cancel()
                    downX = e.rawX; downY = e.rawY; startX = bubbleLp.x; startYb = bubbleLp.y; bubbleDragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!bubbleDragged && (Math.abs(dx) > slop || Math.abs(dy) > slop)) bubbleDragged = true
                    if (bubbleDragged) {
                        bubbleLp.x = (startX + dx).toInt().coerceIn(0, (screenW() - bubbleSize).coerceAtLeast(0))
                        bubbleLp.y = (startYb + dy).toInt().coerceIn(0, (screenH() - bubbleSize).coerceAtLeast(0))
                        if (bubbleAttached) wm.updateViewLayout(bubble, bubbleLp)
                    }
                }
                MotionEvent.ACTION_UP -> if (bubbleDragged) settleOnEdge() else v.performClick()
                MotionEvent.ACTION_CANCEL -> if (bubbleDragged) settleOnEdge()
            }
            true
        }
    }

    private fun settleOnEdge() {
        val maxX = (screenW() - bubbleSize).coerceAtLeast(0)
        val toLeft = bubbleLp.x + bubbleSize / 2 < screenW() / 2
        val target = if (toLeft) 0 else maxX
        snap?.cancel()
        snap = ValueAnimator.ofInt(bubbleLp.x, target).apply {
            duration = 180
            addUpdateListener {
                bubbleLp.x = it.animatedValue as Int
                if (bubbleAttached) runCatching { wm.updateViewLayout(bubble, bubbleLp) }
            }
            start()
        }
        onBubbleMoved(toLeft, bubbleLp.y)
    }

    // ---------------------------------------------------------------- showing and hiding

    private fun attachBar() {
        if (barAttached) return
        barLp.width = (screenW() * 0.94f).toInt()
        runCatching { wm.addView(barRoot, barLp); barAttached = true }
    }

    private fun detachBar() {
        if (!barAttached) return
        runCatching { wm.removeView(barRoot) }
        barAttached = false
    }

    private fun attachBubble() {
        if (bubbleAttached) return
        bubbleLp.x = bubbleLp.x.coerceIn(0, (screenW() - bubbleSize).coerceAtLeast(0))
        bubbleLp.y = bubbleLp.y.coerceIn(0, (screenH() - bubbleSize).coerceAtLeast(0))
        runCatching { wm.addView(bubble, bubbleLp); bubbleAttached = true }
    }

    private fun detachBubble() {
        snap?.cancel()
        if (!bubbleAttached) return
        runCatching { wm.removeView(bubble) }
        bubbleAttached = false
    }

    private fun apply() {
        if (!shown) return
        if (minimized) { detachBar(); attachBubble() } else { detachBubble(); attachBar() }
    }

    private fun setMinimized(m: Boolean) {
        if (minimized == m) return
        minimized = m
        apply()
        onMinimizedChanged(m)
    }

    private fun refresh() {
        bar.visibility = if (hasText) View.VISIBLE else View.GONE
    }

    fun show() {
        if (shown) return
        shown = true
        apply()
    }

    fun hide() {
        if (!shown) return
        shown = false
        detachBar()
        detachBubble()
    }

    fun set(text: String, warning: String?) {
        val t = listOfNotNull(warning, text.takeIf { it.isNotBlank() }).joinToString("\n")
        tv.text = t
        hasText = t.isNotBlank()
        refresh()
    }

    /** Colours the bubble's dot. Called on every state change; cheap when nothing changed. */
    fun setStatus(status: OverlayStatus) {
        val color = when (status) { OverlayStatus.LISTENING -> GREEN; OverlayStatus.PAUSED, OverlayStatus.BEHIND -> AMBER }
        (dot.background as? GradientDrawable)?.setColor(Color.parseColor(color))
        bubble.contentDescription = when (status) {
            OverlayStatus.LISTENING -> "Show captions. Listening. Drag to move."
            OverlayStatus.PAUSED -> "Show captions. Paused. Drag to move."
            OverlayStatus.BEHIND -> "Show captions. Falling behind. Drag to move."
        }
    }

    private companion object {
        const val GREEN = "#34D399"
        const val AMBER = "#FBBF24"
    }
}
