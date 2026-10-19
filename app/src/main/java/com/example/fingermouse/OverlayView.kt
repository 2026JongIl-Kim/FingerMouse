package com.example.fingermouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** 화면 위에 포인터(원)와, 오른쪽 위에 손 인식 미니맵을 그리는 투명 뷰. 터치는 통과시킵니다. */
class OverlayView(context: Context) : View(context) {

    var showPointer = false
    var pointerX = 0f
    var pointerY = 0f
    var pinching = false
    var points: FloatArray? = null
    var margin = 0.15f

    /** false면 미니맵을 그리지 않음 (전체 화면 모드) */
    var showMap = true

    /** true면 pointerX/Y를 '화면 절대 좌표'로 보고 이 뷰의 화면상 위치만큼 보정 */
    var screenCoords = false
    private val loc = IntArray(2)

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = Color.WHITE
    }
    private val mapBg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val region = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = Color.YELLOW
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN }
    private val tipDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showMap) drawMap(canvas)
        drawPointer(canvas)
    }

    private fun drawMap(canvas: Canvas) {
        // 미니맵: 카메라가 보는 손 위치. 노란 사각형 = 화면 전체로 매핑되는 영역
        val mw = 96f * d
        val mh = 128f * d
        val left = width - mw - 8f * d
        val top = 8f * d
        canvas.drawRect(left, top, left + mw, top + mh, mapBg)
        canvas.drawRect(
            left + mw * margin, top + mh * margin,
            left + mw * (1f - margin), top + mh * (1f - margin), region
        )
        points?.let { p ->
            for (i in 0 until 21) {
                val paint = if (i == 8) tipDot else dot
                val r = (if (i == 8) 4f else 2.5f) * d
                canvas.drawCircle(left + p[i * 2] * mw, top + p[i * 2 + 1] * mh, r, paint)
            }
        }

    }

    private fun drawPointer(canvas: Canvas) {
        if (!showPointer) return
        var ox = 0f
        var oy = 0f
        if (screenCoords) {
            getLocationOnScreen(loc)
            ox = loc[0].toFloat()
            oy = loc[1].toFloat()
        }
        fill.color = if (pinching) Color.argb(220, 255, 60, 60) else Color.argb(200, 60, 220, 120)
        canvas.drawCircle(pointerX - ox, pointerY - oy, 16f * d, fill)
        canvas.drawCircle(pointerX - ox, pointerY - oy, 16f * d, ring)
    }
}
