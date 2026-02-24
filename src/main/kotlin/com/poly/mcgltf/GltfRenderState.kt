package com.poly.mcgltf
import org.joml.Matrix4f
import org.joml.Vector3f
object GltfRenderState {
    @JvmField val projectionMatrix = Matrix4f()
    @JvmField val light0Direction = Vector3f()
    @JvmField val light1Direction = Vector3f()
    @JvmField var frameRendering = false
    private val OVERWORLD_LIGHT_0 = Vector3f(0.2f, 1.0f, -0.7f).normalize()
    private val OVERWORLD_LIGHT_1 = Vector3f(-0.2f, 1.0f, 0.7f).normalize()
    private val NETHER_LIGHT_0 = Vector3f(0.2f, 1.0f, -0.7f).normalize()
    private val NETHER_LIGHT_1 = Vector3f(-0.2f, -1.0f, 0.7f).normalize()
    init {
        light0Direction.set(OVERWORLD_LIGHT_0)
        light1Direction.set(OVERWORLD_LIGHT_1)
    }
    @JvmStatic
    fun captureProjectionMatrix(matrix: Matrix4f) {
        projectionMatrix.set(matrix)
    }
    @JvmStatic
    fun setFrameRendering(rendering: Boolean) {
        frameRendering = rendering
    }
    @JvmStatic
    fun setOverworldLighting() {
        light0Direction.set(OVERWORLD_LIGHT_0)
        light1Direction.set(OVERWORLD_LIGHT_1)
    }
    @JvmStatic
    fun setNetherLighting() {
        light0Direction.set(NETHER_LIGHT_0)
        light1Direction.set(NETHER_LIGHT_1)
    }
}
