package com.poly.mcgltf.animation
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sin
class AnimationBlendingSystem {
    enum class BlendMode { OVERRIDE, ADDITIVE }
    data class AnimationLayer(
        val channels: List<InterpolatedChannel>,
        var weight: Float = 1.0f,
        var priority: Int = 0,
        val blendMode: BlendMode = BlendMode.OVERRIDE
    )
    private val layers = mutableListOf<AnimationLayer>()
    private var sourceLayer: AnimationLayer? = null
    private var targetLayer: AnimationLayer? = null
    private var transitionDuration: Float = 0.25f
    private var transitionProgress: Float = 0.0f
    private var transitioning: Boolean = false
    fun addLayer(layer: AnimationLayer) { layers.add(layer) }
    fun removeLayer(layer: AnimationLayer) { layers.remove(layer) }
    fun transition(target: AnimationLayer, duration: Float = 0.25f) {
        if (duration <= 0f) {
            sourceLayer = null
            targetLayer = target
            transitioning = false
            return
        }
        sourceLayer = targetLayer
        targetLayer = target
        transitionDuration = duration
        transitionProgress = 0.0f
        transitioning = true
    }
    fun update(deltaTime: Float) {
        if (transitioning) {
            transitionProgress = min(transitionProgress + deltaTime, transitionDuration)
            val alpha = transitionProgress / transitionDuration
            sourceLayer?.let { src ->
                for (ch in src.channels) ch.update(deltaTime)
            }
            targetLayer?.let { tgt ->
                snapshotListeners(tgt)
                for (ch in tgt.channels) ch.update(deltaTime)
                sourceLayer?.let { src -> blendListeners(src, tgt, alpha) }
            }
            if (transitionProgress >= transitionDuration) {
                sourceLayer = null
                transitioning = false
            }
        } else {
            targetLayer?.let { tgt ->
                for (ch in tgt.channels) ch.update(deltaTime)
            }
        }
        applyLayers()
    }
    private val snapshotMap = HashMap<FloatArray, FloatArray>()
    private fun snapshotListeners(layer: AnimationLayer) {
        for (ch in layer.channels) {
            val listener = ch.listener
            val snap = snapshotMap.getOrPut(listener) { FloatArray(listener.size) }
            System.arraycopy(listener, 0, snap, 0, listener.size)
        }
    }
    private fun blendListeners(src: AnimationLayer, tgt: AnimationLayer, alpha: Float) {
        for (ch in tgt.channels) {
            val listener = ch.listener
            val snap = snapshotMap[listener] ?: continue
            if (ch is InterpolatedChannel.SphericalLinear && listener.size == 4) {
                slerp(snap, listener, alpha, listener)
            } else {
                for (i in listener.indices) {
                    listener[i] = snap[i] + alpha * (listener[i] - snap[i])
                }
            }
        }
    }
    private fun applyLayers() {
        if (layers.isEmpty()) return
        layers.sortBy { it.priority }
        for (layer in layers) {
            if (layer.weight <= 0f) continue
            when (layer.blendMode) {
                BlendMode.OVERRIDE -> {
                    for (ch in layer.channels) {
                        val listener = ch.listener
                        val w = layer.weight
                        if (w >= 1f) continue
                        val snap = snapshotMap.getOrPut(listener) { FloatArray(listener.size) }
                        if (ch is InterpolatedChannel.SphericalLinear && listener.size == 4) {
                            slerp(snap, listener, w, listener)
                        } else {
                            for (i in listener.indices) {
                                listener[i] = snap[i] + w * (listener[i] - snap[i])
                            }
                        }
                    }
                }
                BlendMode.ADDITIVE -> {
                    for (ch in layer.channels) {
                        val listener = ch.listener
                        val w = layer.weight
                        val snap = snapshotMap[listener] ?: continue
                        for (i in listener.indices) {
                            listener[i] += (listener[i] - snap[i]) * w
                        }
                    }
                }
            }
        }
    }
    companion object {
        @JvmStatic
        fun slerp(a: FloatArray, b: FloatArray, t: Float, out: FloatArray) {
            var bx = b[0]; var by = b[1]; var bz = b[2]; var bw = b[3]
            var dot = a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw
            if (dot < 0f) { bx = -bx; by = -by; bz = -bz; bw = -bw; dot = -dot }
            val s0: Float; val s1: Float
            if ((1.0f - dot) > 1e-6f) {
                val omega = acos(dot)
                val invSin = 1.0f / sin(omega)
                s0 = sin((1.0f - t) * omega) * invSin
                s1 = sin(t * omega) * invSin
            } else {
                s0 = 1.0f - t
                s1 = t
            }
            out[0] = s0 * a[0] + s1 * bx
            out[1] = s0 * a[1] + s1 * by
            out[2] = s0 * a[2] + s1 * bz
            out[3] = s0 * a[3] + s1 * bw
        }
    }
}