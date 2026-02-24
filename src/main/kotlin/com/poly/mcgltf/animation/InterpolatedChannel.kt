package com.poly.mcgltf.animation
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sin
sealed class InterpolatedChannel(protected val timesS: FloatArray) {
    abstract fun update(timeS: Float)
    abstract val listener: FloatArray
    fun getKeys(): FloatArray = timesS
    class Step(
        timesS: FloatArray,
        private val values: Array<FloatArray>,
        override val listener: FloatArray
    ) : InterpolatedChannel(timesS) {
        override fun update(timeS: Float) {
            val src = values[computeIndex(timeS, timesS)]
            System.arraycopy(src, 0, listener, 0, listener.size)
        }
    }
    class Linear(
        timesS: FloatArray,
        private val values: Array<FloatArray>,
        override val listener: FloatArray
    ) : InterpolatedChannel(timesS) {
        override fun update(timeS: Float) {
            if (timeS <= timesS[0]) {
                System.arraycopy(values[0], 0, listener, 0, listener.size)
                return
            }
            if (timeS >= timesS[timesS.size - 1]) {
                System.arraycopy(values[timesS.size - 1], 0, listener, 0, listener.size)
                return
            }
            val previousIndex = computeIndex(timeS, timesS)
            val nextIndex = previousIndex + 1
            val local = timeS - timesS[previousIndex]
            val delta = timesS[nextIndex] - timesS[previousIndex]
            val alpha = local / delta
            val previousPoint = values[previousIndex]
            val nextPoint = values[nextIndex]
            for (i in listener.indices) {
                val p = previousPoint[i]
                listener[i] = p + alpha * (nextPoint[i] - p)
            }
        }
    }
    class SphericalLinear(
        timesS: FloatArray,
        private val values: Array<FloatArray>,
        override val listener: FloatArray
    ) : InterpolatedChannel(timesS) {
        override fun update(timeS: Float) {
            if (timeS <= timesS[0]) {
                System.arraycopy(values[0], 0, listener, 0, listener.size)
                return
            }
            if (timeS >= timesS[timesS.size - 1]) {
                System.arraycopy(values[timesS.size - 1], 0, listener, 0, listener.size)
                return
            }
            val previousIndex = computeIndex(timeS, timesS)
            val nextIndex = previousIndex + 1
            val local = timeS - timesS[previousIndex]
            val delta = timesS[nextIndex] - timesS[previousIndex]
            val alpha = local / delta
            val prev = values[previousIndex]
            val next = values[nextIndex]
            var ax = prev[0]; var ay = prev[1]; var az = prev[2]; var aw = prev[3]
            var bx = next[0]; var by = next[1]; var bz = next[2]; var bw = next[3]
            var dot = ax * bx + ay * by + az * bz + aw * bw
            if (dot < 0f) {
                bx = -bx; by = -by; bz = -bz; bw = -bw; dot = -dot
            }
            val s0: Float
            val s1: Float
            if ((1.0f - dot) > 1e-6f) {
                val omega = acos(dot)
                val invSinOmega = 1.0f / sin(omega)
                s0 = sin((1.0f - alpha) * omega) * invSinOmega
                s1 = sin(alpha * omega) * invSinOmega
            } else {
                s0 = 1.0f - alpha
                s1 = alpha
            }
            listener[0] = s0 * ax + s1 * bx
            listener[1] = s0 * ay + s1 * by
            listener[2] = s0 * az + s1 * bz
            listener[3] = s0 * aw + s1 * bw
        }
    }
    class CubicSpline(
        timesS: FloatArray,
        private val values: Array<Array<FloatArray>>,
        override val listener: FloatArray
    ) : InterpolatedChannel(timesS) {
        override fun update(timeS: Float) {
            if (timeS <= timesS[0]) {
                System.arraycopy(values[0][1], 0, listener, 0, listener.size)
                return
            }
            if (timeS >= timesS[timesS.size - 1]) {
                System.arraycopy(values[timesS.size - 1][1], 0, listener, 0, listener.size)
                return
            }
            val previousIndex = computeIndex(timeS, timesS)
            val nextIndex = previousIndex + 1
            val local = timeS - timesS[previousIndex]
            val delta = timesS[nextIndex] - timesS[previousIndex]
            val alpha = local / delta
            val alpha2 = alpha * alpha
            val alpha3 = alpha2 * alpha
            val aa = 2f * alpha3 - 3f * alpha2 + 1f
            val ab = alpha3 - 2f * alpha2 + alpha
            val ac = -2f * alpha3 + 3f * alpha2
            val ad = alpha3 - alpha2
            val previous = values[previousIndex]
            val next = values[nextIndex]
            val previousPoint = previous[1]
            val nextPoint = next[1]
            val previousOutputTangent = previous[2]
            val nextInputTangent = next[0]
            for (i in listener.indices) {
                val p = previousPoint[i]
                val pt = previousOutputTangent[i] * delta
                val n = nextPoint[i]
                val nt = nextInputTangent[i] * delta
                listener[i] = aa * p + ab * pt + ac * n + ad * nt
            }
        }
    }
    companion object {
        @JvmStatic
        fun computeIndex(key: Float, keys: FloatArray): Int {
            val index = keys.binarySearch(key)
            return if (index >= 0) index else max(0, -index - 2)
        }
    }
}