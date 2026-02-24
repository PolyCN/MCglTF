package com.poly.mcgltf.instancing
import com.poly.mcgltf.GLProfile
import com.poly.mcgltf.MCglTFSystem
import com.poly.mcgltf.RenderedGltfModel
import com.poly.mcgltf.RenderedGltfScene
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import java.nio.FloatBuffer
class GPUInstancingSystem {
    companion object {
        const val ATTRIB_BASE = 14
        const val MAT4_FLOATS = 16
        const val MAT3_FLOATS = 9
        const val MAT4_COLS = 4
        const val MAT3_COLS = 3
        const val ATTRIB_SLOTS = MAT4_COLS + MAT3_COLS
        const val FLOATS_PER_INSTANCE = MAT4_FLOATS + MAT3_FLOATS
        const val BYTES_PER_FLOAT = 4
        const val STRIDE = FLOATS_PER_INSTANCE * BYTES_PER_FLOAT
        const val INITIAL_CAPACITY = 64
    }
    private var vbo: Int = 0
    private var gpuCapacity: Int = 0
    private var instanceCount: Int = 0
    private var cpuBuffer: FloatBuffer = BufferUtils.createFloatBuffer(INITIAL_CAPACITY * FLOATS_PER_INSTANCE)
    val count: Int get() = instanceCount
    val supportsInstancing: Boolean get() = MCglTFSystem.glProfile !is GLProfile.GL30
    fun addInstance(modelMatrix: Matrix4f, normalMatrix: Matrix3f) {
        growCpuBuffer()
        val base = instanceCount * FLOATS_PER_INSTANCE
        modelMatrix.get(base, cpuBuffer)
        normalMatrix.get(base + MAT4_FLOATS, cpuBuffer)
        instanceCount++
    }
    fun reset() { instanceCount = 0 }
    fun upload() {
        if (instanceCount == 0) return
        ensureGpuCapacity()
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        cpuBuffer.position(0).limit(instanceCount * FLOATS_PER_INSTANCE)
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, cpuBuffer)
    }
    fun bindAttribs() {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        for (col in 0 until MAT4_COLS) {
            val loc = ATTRIB_BASE + col
            GL20.glEnableVertexAttribArray(loc)
            GL20.glVertexAttribPointer(loc, 4, GL11.GL_FLOAT, false, STRIDE, (col * 4 * BYTES_PER_FLOAT).toLong())
            GL33.glVertexAttribDivisor(loc, 1)
        }
        for (col in 0 until MAT3_COLS) {
            val loc = ATTRIB_BASE + MAT4_COLS + col
            GL20.glEnableVertexAttribArray(loc)
            GL20.glVertexAttribPointer(loc, 3, GL11.GL_FLOAT, false, STRIDE, ((MAT4_FLOATS + col * 3) * BYTES_PER_FLOAT).toLong())
            GL33.glVertexAttribDivisor(loc, 1)
        }
    }
    fun unbindAttribs() {
        for (i in 0 until ATTRIB_SLOTS) {
            val loc = ATTRIB_BASE + i
            GL33.glVertexAttribDivisor(loc, 0)
            GL20.glDisableVertexAttribArray(loc)
        }
    }
    fun drawElementsInstanced(mode: Int, count: Int, type: Int, offset: Long) {
        GL31.glDrawElementsInstanced(mode, count, type, offset, instanceCount)
    }
    fun drawArraysInstanced(mode: Int, first: Int, count: Int) {
        GL31.glDrawArraysInstanced(mode, first, count, instanceCount)
    }
    fun renderInstanced(scene: RenderedGltfScene) {
        if (instanceCount == 0) return
        if (!supportsInstancing) {
            renderFallback(scene)
            return
        }
        upload()
        val savedVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val savedBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        try {
            scene.skinningCommands.forEach(Runnable::run)
            scene.vanillaRenderCommands.forEach(Runnable::run)
        } finally {
            unbindAttribs()
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedBuf)
            GL30.glBindVertexArray(savedVAO)
        }
    }
    fun cleanup() {
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo)
            vbo = 0
        }
        gpuCapacity = 0
        instanceCount = 0
    }
    private fun renderFallback(scene: RenderedGltfScene) {
        for (i in 0 until instanceCount) {
            val base = i * FLOATS_PER_INSTANCE
            RenderedGltfModel.CURRENT_POSE.set(base, cpuBuffer)
            RenderedGltfModel.CURRENT_NORMAL.set(base + MAT4_FLOATS, cpuBuffer)
            scene.renderForVanilla()
        }
    }
    private fun ensureGpuCapacity() {
        if (vbo == 0) vbo = GL15.glGenBuffers()
        if (instanceCount <= gpuCapacity) return
        var cap = if (gpuCapacity == 0) INITIAL_CAPACITY else gpuCapacity
        while (cap < instanceCount) cap = cap shl 1
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (cap * STRIDE).toLong(), GL15.GL_DYNAMIC_DRAW)
        gpuCapacity = cap
    }
    private fun growCpuBuffer() {
        val required = (instanceCount + 1) * FLOATS_PER_INSTANCE
        if (required <= cpuBuffer.capacity()) return
        val newCap = maxOf(cpuBuffer.capacity() shl 1, required)
        val newBuf = BufferUtils.createFloatBuffer(newCap)
        cpuBuffer.position(0).limit(instanceCount * FLOATS_PER_INSTANCE)
        newBuf.put(cpuBuffer)
        cpuBuffer = newBuf
    }
}