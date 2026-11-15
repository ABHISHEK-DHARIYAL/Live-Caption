package com.clearcap.session

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import com.clearcap.text.ScreenTerms
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional. Lets Clearcap learn names and technical terms from the screen you are looking at (slide titles, code, web pages)
 * so the captions spell them correctly. It does nothing unless the user turned it on in Settings AND a recording is running.
 *
 * What it reads: visible text of the foreground app. What it skips: password fields, any text box you can type in, keyboards,
 * system UI and Clearcap itself. What it keeps: a short list of unusual words, in memory, for the current recording only.
 * It never stores or sends screen text, and it cannot click, type or change anything (it requests no such abilities).
 */
class ContextAccessibilityService : AccessibilityService() {
    private val thread = HandlerThread("clearcap-screen-terms").apply { start() }
    private val bg = Handler(thread.looper)
    private val busy = AtomicBoolean(false)
    @Volatile private var lastRead = 0L
    private var imePackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        imePackages = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !ScreenContext.active) return
        val pkg = event.packageName?.toString() ?: return
        if (skipPackage(pkg)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRead < MIN_INTERVAL_MS || !busy.compareAndSet(false, true)) return
        lastRead = now
        // Walking the tree costs one IPC per node, so it runs off the main thread.
        bg.post {
            try {
                if (!ScreenContext.active) return@post
                val root = rootInActiveWindow ?: return@post
                try {
                    if (skipPackage(root.packageName?.toString().orEmpty())) return@post
                    val out = StringBuilder()
                    collect(root, out, intArrayOf(0), 0)
                    if (out.isNotEmpty()) ScreenContext.vocab.add(ScreenTerms.extract(out.toString()))
                } finally {
                    recycleCompat(root)
                }
            } catch (_: Exception) {
                // The window can disappear mid-read; the next event simply tries again.
            } finally {
                busy.set(false)
            }
        }
    }

    private fun skipPackage(pkg: String) =
        pkg.isEmpty() || pkg == packageName || pkg == "com.android.systemui" || pkg in imePackages

    private fun collect(node: AccessibilityNodeInfo, out: StringBuilder, count: IntArray, depth: Int) {
        if (count[0] >= MAX_NODES || out.length >= MAX_CHARS || depth > MAX_DEPTH) return
        count[0]++
        if (!node.isVisibleToUser) return
        // Never read what the user types, and never read passwords.
        if (node.isPassword || node.isEditable) return
        node.text?.let { t -> if (t.isNotBlank() && t.length <= MAX_NODE_CHARS) out.append(t).append('\n') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { collect(child, out, count, depth + 1) } finally { recycleCompat(child) }
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleCompat(n: AccessibilityNodeInfo) { if (Build.VERSION.SDK_INT < 33) runCatching { n.recycle() } }

    override fun onInterrupt() {}

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private companion object {
        const val MIN_INTERVAL_MS = 2_500L
        const val MAX_NODES = 700
        const val MAX_CHARS = 30_000
        const val MAX_NODE_CHARS = 600
        const val MAX_DEPTH = 40
    }
}
