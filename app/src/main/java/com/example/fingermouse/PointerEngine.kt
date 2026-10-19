package com.example.fingermouse

import android.content.SharedPreferences

object Defaults {
    const val MARGIN = 0.15f
    const val PINCH_ON = 0.28f
    const val PINCH_OFF = 0.38f
}

/**
 * 손 인식 결과 → 포인터 좌표 + 핀치(누름/드래그/놓음) 이벤트로 바꿔 주는 엔진 (전체 화면 모드용).
 * 메인 스레드에서만 호출하세요.
 */
class PointerEngine(
    private val prefs: SharedPreferences,
    private val out: Output
) {
    interface Output {
        /** 화면 전체 크기(px). 알 수 없으면 null */
        fun screenSize(): Pair<Float, Float>?
        fun onPointer(x: Float, y: Float, pinching: Boolean, visible: Boolean)
        fun onDown(x: Float, y: Float)
        fun onMove(x: Float, y: Float)
        fun onUp(x: Float, y: Float)
        /** 집은 채로 손이 사라진 경우: 엉뚱한 클릭이 나가지 않게 취소 */
        fun onCancel()
    }

    private class Sample(val t: Long, val x: Float, val y: Float)

    private val filterX = OneEuroFilter(1.5f, 0.02f)
    private val filterY = OneEuroFilter(1.5f, 0.02f)
    private val history = ArrayDeque<Sample>()

    private var pinching = false
    private var offX = 0f
    private var offY = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun handle(r: HandTracker.Result?, now: Long) {
        if (r == null) {
            if (pinching) {
                pinching = false
                offX = 0f
                offY = 0f
                out.onCancel()
            }
            out.onPointer(lastX, lastY, false, false)
            filterX.reset()
            filterY.reset()
            history.clear()
            return
        }

        val size = out.screenSize() ?: return
        val w = size.first
        val h = size.second
        if (w <= 0f || h <= 0f) return

        val m = prefs.getFloat("margin", Defaults.MARGIN)
        val on = prefs.getFloat("pinchOn", Defaults.PINCH_ON)
        val off = maxOf(prefs.getFloat("pinchOff", Defaults.PINCH_OFF), on + 0.05f)

        val nx = ((r.tipX - m) / (1f - 2f * m)).coerceIn(0f, 1f)
        val ny = ((r.tipY - m) / (1f - 2f * m)).coerceIn(0f, 1f)
        val sx = filterX.filter(nx * w, now)
        val sy = filterY.filter(ny * h, now)

        history.addLast(Sample(now, sx, sy))
        while (history.size > 1 && now - history.first().t > 300) history.removeFirst()

        if (!pinching && r.pinchRatio < on) {
            // 집는 동작 중 손끝이 흔들리므로, 약 150ms 전 위치를 누른 지점으로 사용
            val anchor = history.firstOrNull { now - it.t <= 150 } ?: history.last()
            offX = anchor.x - sx
            offY = anchor.y - sy
            pinching = true
            lastX = anchor.x
            lastY = anchor.y
            out.onDown(anchor.x, anchor.y)
        } else if (pinching && r.pinchRatio > off) {
            pinching = false
            offX = 0f
            offY = 0f
            out.onUp(lastX, lastY)
        }

        val px = (sx + offX).coerceIn(0f, w - 1f)
        val py = (sy + offY).coerceIn(0f, h - 1f)
        if (pinching && (px != lastX || py != lastY)) {
            out.onMove(px, py)
        }
        lastX = px
        lastY = py
        out.onPointer(px, py, pinching, true)
    }

    /** 추적을 끝낼 때 호출: 진행 중인 누름은 취소하고 포인터를 숨깁니다. */
    fun stop() {
        if (pinching) {
            pinching = false
            out.onCancel()
        }
        out.onPointer(0f, 0f, false, false)
        filterX.reset()
        filterY.reset()
        history.clear()
    }
}
