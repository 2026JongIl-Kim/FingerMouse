package com.example.fingermouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/**
 * 전체 화면 모드의 '손' 역할:
 *  - 모든 화면 위에 포인터(원)와 뒤로/홈/최근/정지 버튼 줄을 띄우고
 *  - 엄지+검지 핀치를 실제 터치(탭 / 길게 누르기 / 드래그)로 바꿔 보냅니다.
 */
class PointerAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PointerAccessibilityService? = null
    }

    private lateinit var wm: WindowManager
    private var overlay: OverlayView? = null
    private var bar: LinearLayout? = null

    private val pts = ArrayList<PointF>()
    private var downAt = 0L
    private var pressing = false

    private val density: Float by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeViews()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeViews()
        instance = null
        super.onDestroy()
    }

    // ───────────────────────── 화면에 띄우기 ─────────────────────────

    /** 추적이 시작되면 포인터와 버튼 줄을 보여줍니다. */
    fun show() {
        if (!::wm.isInitialized) return
        addOverlay()
        addBar()
    }

    /** 추적이 끝나면 모두 치웁니다. */
    fun hide() {
        removeViews()
    }

    private fun addOverlay() {
        if (overlay != null) return
        val v = OverlayView(this).apply {
            showMap = false
            screenCoords = true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, lp)
            overlay = v
        } catch (e: Exception) {
            overlay = null
        }
    }

    private fun addBar() {
        if (bar != null) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }

        fun item(label: String, action: () -> Unit) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { action() }
            }
            row.addView(tv)
        }

        item("◀ 뒤로") { performGlobalAction(GLOBAL_ACTION_BACK) }
        item("● 홈") { performGlobalAction(GLOBAL_ACTION_HOME) }
        item("▣ 최근") { performGlobalAction(GLOBAL_ACTION_RECENTS) }
        item("■ 정지") { TrackingService.stop() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = dp(70)
        try {
            wm.addView(row, lp)
            bar = row
        } catch (e: Exception) {
            bar = null
        }
    }

    private fun removeViews() {
        if (!::wm.isInitialized) return
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
        bar?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        bar = null
        pts.clear()
        pressing = false
    }

    // ───────────────────────── 포인터 갱신 ─────────────────────────

    @Suppress("DEPRECATION")
    fun screenSize(): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return Pair(b.width().toFloat(), b.height().toFloat())
        }
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
    }

    fun updatePointer(x: Float, y: Float, pinching: Boolean, visible: Boolean) {
        val o = overlay ?: return
        o.showPointer = visible
        o.pointerX = x
        o.pointerY = y
        o.pinching = pinching
        o.invalidate()
    }

    // ───────────────────────── 핀치 → 터치 ─────────────────────────

    fun pinchDown(x: Float, y: Float) {
        pts.clear()
        pts.add(PointF(x, y))
        downAt = SystemClock.uptimeMillis()
        pressing = true
    }

    fun pinchMove(x: Float, y: Float) {
        if (!pressing) return
        val last = pts.lastOrNull() ?: return
        if (hypot(x - last.x, y - last.y) >= 8f) pts.add(PointF(x, y))
    }

    fun pinchCancel() {
        pts.clear()
        pressing = false
    }

    /**
     * 놓는 순간 지금까지의 움직임을 한 번의 제스처로 보냅니다.
     *  - 거의 안 움직임 + 짧음  → 탭
     *  - 거의 안 움직임 + 길게  → 길게 누르기
     *  - 움직임이 큼           → 같은 경로/속도로 드래그(스크롤, 스와이프)
     */
    fun pinchUp(x: Float, y: Float) {
        if (!pressing || pts.isEmpty()) {
            pinchCancel()
            return
        }
        val last = pts.last()
        if (hypot(x - last.x, y - last.y) >= 1f) pts.add(PointF(x, y))

        val elapsed = SystemClock.uptimeMillis() - downAt
        val first = pts.first()
        var length = 0f
        for (i in 1 until pts.size) {
            length += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        val slop = 24f * density

        val path = Path()
        path.moveTo(first.x, first.y)
        val duration: Long
        if (length < slop) {
            duration = if (elapsed < 400L) 50L else elapsed.coerceAtMost(3000L)
        } else {
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            duration = elapsed.coerceIn(80L, 3000L)
        }
        pinchCancel()

        try {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
        }
    }
}
