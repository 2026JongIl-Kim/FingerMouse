package com.example.fingermouse

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.sqrt

/** MediaPipe HandLandmarker 래퍼. 검지 끝 좌표와 '핀치 비율'을 돌려줍니다. */
class HandTracker(context: Context) {

    class Result(
        /** 검지 끝(랜드마크 8)의 정규화 좌표 (0~1, 좌우 반전 적용된 화면 기준) */
        val tipX: Float,
        val tipY: Float,
        /** 엄지 끝~검지 끝 거리 ÷ 손바닥 길이. 작을수록 집은 상태 */
        val pinchRatio: Float,
        /** 21개 랜드마크 (x0,y0,x1,y1,...) */
        val points: FloatArray
    )

    private val landmarker: HandLandmarker
    private var lastTs = 0L

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap): Result? {
        val ts = maxOf(SystemClock.uptimeMillis(), lastTs + 1)
        lastTs = ts

        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(mpImage, ts)
        val hands = result.landmarks()
        if (hands.isEmpty()) return null

        val l = hands[0]
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        fun dist(a: Int, b: Int): Float {
            val dx = (l[a].x() - l[b].x()) * w
            val dy = (l[a].y() - l[b].y()) * h
            return sqrt(dx * dx + dy * dy)
        }

        val palm = dist(0, 9).coerceAtLeast(1f)
        val pts = FloatArray(42)
        for (i in 0 until 21) {
            pts[i * 2] = l[i].x()
            pts[i * 2 + 1] = l[i].y()
        }
        return Result(l[8].x(), l[8].y(), dist(4, 8) / palm, pts)
    }

    fun close() {
        landmarker.close()
    }
}
