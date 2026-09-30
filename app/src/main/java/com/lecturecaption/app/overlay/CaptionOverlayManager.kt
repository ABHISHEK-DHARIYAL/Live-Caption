package com.lecturecaption.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

data class OverlayPrefs(
    val fontSizeSp: Float = 18f,
    val darkBackground: Boolean = true,
    val alphaPercent: Int = 85, // 0-100
    val maxLines: Int = 3
)

/**
 * Manages two floating windows (WindowManager + TYPE_APPLICATION_OVERLAY, needs
 * SYSTEM_ALERT_WINDOW):
 *  1. The live caption box.
 *  2. A small always-visible "REC" indicator in the top-right corner (pulsing red dot + timer).
 *     Tapping it calls [onIndicatorTap] (the service uses this to open the "Is it done?" dialog).
 *
 * IMPORTANT: every method here must be called on the main thread.
 */
class CaptionOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var textView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var minimized = false

    private var indicatorView: View? = null
    private var indicatorDot: View? = null
    private var indicatorTimer: TextView? = null
    private var pulseAnimator: ValueAnimator? = null

    var onIndicatorTap: (() -> Unit)? = null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- caption box

    fun show(prefs: OverlayPrefs = OverlayPrefs()) {
        if (overlayView != null) return
        if (!canDrawOverlays()) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            background = buildBackgroundDrawable(prefs)
        }

        val caption = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = prefs.fontSizeSp
            maxLines = prefs.maxLines
            text = "Listening…"
            gravity = Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        container.addView(caption)
        textView = caption

        val params = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(96)
        }
        layoutParams = params

        attachDragBehavior(container, params, onTap = null)

        try {
            windowManager.addView(container, params)
            overlayView = container
        } catch (e: Exception) {
            overlayView = null
            textView = null
        }
    }

    private fun buildBackgroundDrawable(prefs: OverlayPrefs): GradientDrawable {
        val baseColor = if (prefs.darkBackground) Color.BLACK else Color.WHITE
        val alpha = (prefs.alphaPercent * 255 / 100).coerceIn(0, 255)
        return GradientDrawable().apply {
            setColor(Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)))
            cornerRadius = 20f
        }
    }

    fun updateText(text: String) {
        if (minimized) return
        textView?.let { tv ->
            val lines = text.split("\n").takeLast(tv.maxLines)
            tv.text = lines.joinToString("\n")
        }
    }

    fun applyPrefs(prefs: OverlayPrefs) {
        textView?.apply {
            textSize = prefs.fontSizeSp
            maxLines = prefs.maxLines
            (overlayView as? LinearLayout)?.background = buildBackgroundDrawable(prefs)
        }
    }

    fun setMinimized(value: Boolean) {
        minimized = value
        overlayView?.visibility = if (value) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- corner indicator

    fun showIndicator() {
        if (indicatorView != null) return
        if (!canDrawOverlays()) return

        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3B30"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(6) }
        }
        val timer = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "REC 00:00"
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(210, 20, 20, 20))
                cornerRadius = dp(20).toFloat()
            }
            addView(dot)
            addView(timer)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(64)
        }

        attachDragBehavior(pill, params) { onIndicatorTap?.invoke() }

        try {
            windowManager.addView(pill, params)
            indicatorView = pill
            indicatorDot = dot
            indicatorTimer = timer
            pulseAnimator = ValueAnimator.ofFloat(1f, 0.25f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { dot.alpha = it.animatedValue as Float }
                start()
            }
        } catch (e: Exception) {
            indicatorView = null
        }
    }

    fun updateIndicatorTime(text: String) {
        indicatorTimer?.text = "REC $text"
    }

    // ---------------------------------------------------------------- shared

    /** Drag to move; a short touch with almost no movement counts as a tap. */
    @Suppress("ClickableViewAccessibility")
    private fun attachDragBehavior(view: View, params: WindowManager.LayoutParams, onTap: (() -> Unit)?) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val slop = dp(8)
        val horizontalSign = if ((params.gravity and Gravity.END) == Gravity.END) -1 else 1
        val verticalSign = if ((params.gravity and Gravity.BOTTOM) == Gravity.BOTTOM) -1 else 1

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                    if (moved) {
                        // Keep the window fully on screen (it used to be draggable half off the left edge,
                        // which cut off the start of every caption line).
                        val dm = context.resources.displayMetrics
                        val centered = (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
                        val maxX = if (centered) ((dm.widthPixels - v.width) / 2).coerceAtLeast(0)
                                   else (dm.widthPixels - v.width).coerceAtLeast(0)
                        val minX = if (centered) -maxX else 0
                        val maxY = (dm.heightPixels - v.height).coerceAtLeast(0)
                        params.x = (initialX + dx * horizontalSign).coerceIn(minX, maxX)
                        params.y = (initialY + dy * verticalSign).coerceIn(0, maxY)
                        try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    fun resize(widthDp: Int) {
        val params = layoutParams ?: return
        params.width = dp(widthDp)
        overlayView?.let { windowManager.updateViewLayout(it, params) }
    }

    fun hide() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        indicatorView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
        textView = null
        layoutParams = null
        indicatorView = null
        indicatorDot = null
        indicatorTimer = null
    }
}
