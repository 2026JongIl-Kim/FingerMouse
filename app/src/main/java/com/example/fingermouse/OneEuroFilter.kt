package com.example.fingermouse

import kotlin.math.abs
import kotlin.math.max

/**
 * One Euro Filter: 천천히 움직일 때는 떨림을 강하게 줄이고,
 * 빠르게 움직일 때는 지연 없이 따라가는 필터.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.5f,
    private val beta: Float = 0.02f,
    private val dCutoff: Float = 1.0f
) {
    private var initialized = false
    private var xPrev = 0f
    private var dxPrev = 0f
    private var tPrev = 0L

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(x: Float, tMs: Long): Float {
        if (!initialized) {
            initialized = true
            xPrev = x
            dxPrev = 0f
            tPrev = tMs
            return x
        }
        val dt = max((tMs - tPrev) / 1000f, 0.001f)
        tPrev = tMs
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1f - aD) * dxPrev
        dxPrev = dxHat
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1f - a) * xPrev
        xPrev = xHat
        return xHat
    }

    fun reset() {
        initialized = false
        dxPrev = 0f
    }
}
