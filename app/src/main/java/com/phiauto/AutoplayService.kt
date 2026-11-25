package com.phiauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Injects the planned touches with dispatchGesture() and shows a small floating
 * control bar (drag handle, -10ms, GO/STOP, +10ms, close).
 *
 * dispatchGesture() allows 10 strokes per gesture and a new gesture cancels the
 * running one, so the plan arrives pre-cut into windows (python/touch_plan.py).
 * Fingers that stay down across a window boundary are chained with
 * StrokeDescription.continueStroke().
 */
class AutoplayService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoplayService? = null
        private const val TAG = "PhiAuto"
        private const val MIN_STROKE_MS = 1L
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private lateinit var wh: Handler

    private var plan: Plan? = null
    private var delayMode = false
    private var delayMs = 1950L

    @Volatile private var running = false
    @Volatile private var cancelled = false
    @Volatile private var zero = 0L          // uptimeMillis that corresponds to chart time 0
    private val strokes = java.util.concurrent.ConcurrentHashMap<Int, StrokeDescription>()

    private var overlay: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var goButton: Button? = null
    private var infoView: TextView? = null
    private var handleView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val t = HandlerThread("phi-autoplay").also { it.start() }
        worker = t
        wh = Handler(t.looper)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        cancelled = true
    }

    override fun onDestroy() {
        cancelled = true
        removeOverlay()
        worker?.quitSafely()
        worker = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ control ---

    /** Called by MainActivity (any thread). */
    fun arm(p: Plan, delayMode: Boolean, delaySeconds: Double) {
        main.post {
            cancelled = true
            plan = p
            this.delayMode = delayMode
            this.delayMs = (delaySeconds * 1000).toLong()
            running = false
            showOverlay()
            setInfo(
                if (delayMode) "Ready. Pause the game, then press GO."
                else "Ready. Press GO when the first note is about to reach the line."
            )
        }
    }

    private fun start() {
        val p = plan ?: return
        if (running) return
        running = true
        cancelled = false
        strokes.clear()
        goButton?.text = "STOP"
        setInfo("Playing…")
        val now = SystemClock.uptimeMillis()
        if (delayMode) {
            zero = now + delayMs
            // tap the middle of the screen to resume the paused game, then run
            tapAt(p.screenW / 2f, p.screenH / 2f) { next(0) }
        } else {
            zero = now - p.first - 10
            next(0)
        }
    }

    private fun stop() {
        if (!running) return
        cancelled = true
        // A new gesture cancels the one in flight; use a harmless tap on our own drag handle.
        val h = handleView
        if (h != null) {
            val loc = IntArray(2)
            h.getLocationOnScreen(loc)
            tapAt(loc[0] + h.width / 2f, loc[1] + h.height / 2f, null)
        }
        finish("Stopped.")
    }

    private fun adjust(deltaMs: Long) {
        zero += deltaMs   // + = later, - = earlier (same as the original spinbox)
        setInfo("Timing nudge ${if (deltaMs > 0) "+" else ""}$deltaMs ms (applies to upcoming notes)")
    }

    private fun finish(msg: String) {
        running = false
        strokes.clear()
        main.post {
            goButton?.text = "GO"
            setInfo(msg)
        }
    }

    // ----------------------------------------------------------- dispatch ---

    private fun next(k: Int) {
        wh.post { dispatchWindow(k) }
    }

    private fun dispatchWindow(k: Int) {
        val p = plan
        if (cancelled || p == null) return
        if (k >= p.windows.size) {
            finish("Finished.")
            return
        }
        val win = p.windows[k]
        val nowRel = SystemClock.uptimeMillis() - zero

        val builder = GestureDescription.Builder()
        var added = 0
        for (s in win.strokes) {
            if (added >= GestureDescription.getMaxStrokeCount()) break
            try {
                val path = pathOf(s)
                val begin = if (s.cont) 0L else maxOf(0L, s.t0 - nowRel)
                val end = maxOf(s.t1 - nowRel, begin + MIN_STROKE_MS)
                val dur = end - begin
                val prev = strokes[s.id]
                val desc = if (s.cont && prev != null) {
                    prev.continueStroke(path, 0L, dur, s.will)
                } else {
                    StrokeDescription(path, begin, dur, s.will)
                }
                if (s.will) strokes[s.id] = desc else strokes.remove(s.id)
                builder.addStroke(desc)
                added++
            } catch (e: Exception) {
                Log.w(TAG, "stroke skipped: ${e.message}")
                strokes.remove(s.id)
            }
        }
        if (added == 0) {
            next(k + 1)
            return
        }

        val ok = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (!cancelled) next(k + 1)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "window $k cancelled")
                strokes.clear()          // chained strokes can no longer continue
                if (!cancelled) next(k + 1)
            }
        }, wh)
        if (!ok) {
            Log.w(TAG, "dispatchGesture refused window $k")
            strokes.clear()
            if (!cancelled) next(k + 1)
        }
    }

    private fun pathOf(s: StrokeSpec): Path {
        val path = Path()
        path.moveTo(s.xs[0], s.ys[0])
        var lx = s.xs[0]
        var ly = s.ys[0]
        for (i in 1 until s.xs.size) {
            if (s.xs[i] != lx || s.ys[i] != ly) {
                path.lineTo(s.xs[i], s.ys[i])
                lx = s.xs[i]
                ly = s.ys[i]
            }
        }
        return path
    }

    private fun tapAt(x: Float, y: Float, then: (() -> Unit)?) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(StrokeDescription(path, 0, 50)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                then?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                then?.invoke()
            }
        }, wh)
        if (!ok) then?.invoke()
    }

    // ------------------------------------------------------------ overlay ---

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC202020.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val screenW = resources.displayMetrics.widthPixels
            x = maxOf(0, screenW - dp(300))
            y = dp(6)
        }

        fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }

        val handle = TextView(this).apply {
            text = "☰"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(10), 0)
            gravity = Gravity.CENTER
            setOnTouchListener(object : View.OnTouchListener {
                var sx = 0
                var sy = 0
                var tx = 0f
                var ty = 0f
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = sx + (e.rawX - tx).toInt()
                            params.y = sy + (e.rawY - ty).toInt()
                            this@AutoplayService.overlay?.let { wm.updateViewLayout(it, params) }
                        }
                    }
                    return true
                }
            })
        }
        val minus = smallButton("-10") { adjust(-10) }
        val go = smallButton("GO") { if (running) stop() else start() }
        val plus = smallButton("+10") { adjust(10) }
        val close = smallButton("✕") { stop(); removeOverlay() }
        val info = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, dp(6), 0)
            gravity = Gravity.CENTER_VERTICAL
            maxWidth = dp(160)
        }

        row.addView(handle)
        row.addView(minus)
        row.addView(go)
        row.addView(plus)
        row.addView(close)
        row.addView(info)

        goButton = go
        infoView = info
        handleView = handle
        overlayParams = params
        overlay = row
        wm.addView(row, params)
    }

    private fun removeOverlay() {
        main.post {
            overlay?.let {
                try {
                    (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
                } catch (_: Exception) {
                }
            }
            overlay = null
            goButton = null
            infoView = null
            handleView = null
        }
    }

    private fun setInfo(s: String) {
        main.post { infoView?.text = s }
    }
}
