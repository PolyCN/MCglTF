package com.poly.mcgltf.iris
import com.poly.mcgltf.GltfRenderState
import com.poly.mcgltf.RenderedGltfModel
import net.irisshaders.iris.Iris
import net.irisshaders.iris.api.v0.IrisApi
import net.irisshaders.iris.pipeline.WorldRenderingPhase
import net.irisshaders.iris.pipeline.WorldRenderingPipeline
import org.lwjgl.opengl.*
import java.util.*
object IrisRenderingHook {
    private val phaseCommands = EnumMap<WorldRenderingPhase, LinkedHashMap<Any, MutableList<Runnable>>>(WorldRenderingPhase::class.java)
    @JvmField var currentRenderType: Any? = null
    @JvmStatic
    fun submitCommand(phase: WorldRenderingPhase, renderKey: Any, command: Runnable) {
        phaseCommands.getOrPut(phase) { LinkedHashMap() }
            .getOrPut(renderKey) { mutableListOf() }
            .add(command)
    }
    @JvmStatic
    fun submitCommandByPhaseName(phaseName: String, renderKey: Any, command: Runnable) {
        try { submitCommand(WorldRenderingPhase.valueOf(phaseName), renderKey, command) }
        catch (_: IllegalArgumentException) {}
    }
    @JvmStatic
    fun afterRenderTypeDraw(renderType: Any) {
        currentRenderType = renderType
        val phase = currentPhase() ?: return
        val shardMap = phaseCommands[phase] ?: return
        val commands = shardMap.remove(renderType) ?: return
        val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        if (currentProgram != 0) applyShaderUniforms(currentProgram)
        setupAndRender(phase, commands)
    }
    private fun currentPhase(): WorldRenderingPhase? {
        if (!IrisApi.getInstance().isShaderPackInUse) return null
        val pipeline: WorldRenderingPipeline = Iris.getPipelineManager().getPipelineNullable() ?: return null
        return pipeline.phase
    }
    private fun setupAndRender(phase: WorldRenderingPhase, commands: List<Runnable>) {
        val savedVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val savedArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        val savedElementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING)
        val savedCullFace = GL11.glGetBoolean(GL11.GL_CULL_FACE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        val savedTexColor = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + 2)
        val savedTexNormal = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + 1)
        val savedTexSpecular = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        try {
            val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
            if (phase != WorldRenderingPhase.NONE && currentProgram != 0) {
                RenderedGltfModel.MODEL_VIEW_MATRIX = GL20.glGetUniformLocation(currentProgram, "iris_ModelViewMat")
                RenderedGltfModel.NORMAL_MATRIX = GL20.glGetUniformLocation(currentProgram, "iris_NormalMat")
            } else {
                RenderedGltfModel.LIGHT0_DIRECTION.set(GltfRenderState.light0Direction)
                RenderedGltfModel.LIGHT1_DIRECTION.set(GltfRenderState.light1Direction)
            }
            commands.forEach(Runnable::run)
        } finally {
            GL20.glDisableVertexAttribArray(RenderedGltfModel.at_tangent)
            restoreTextures(savedTexColor, savedTexNormal, savedTexSpecular)
            if (savedCullFace) GL11.glEnable(GL11.GL_CULL_FACE) else GL11.glDisable(GL11.GL_CULL_FACE)
            GL30.glBindVertexArray(savedVAO)
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedArrayBuffer)
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, savedElementBuffer)
        }
    }
    private fun restoreTextures(color: Int, normal: Int, specular: Int) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, color)
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + 2)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, normal)
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + 1)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, specular)
    }
    private fun applyShaderUniforms(program: Int) {
        for (i in 0 until 12) {
            val loc = GL20.glGetUniformLocation(program, "Sampler$i")
            if (loc != -1) GL20.glUniform1i(loc, i)
        }
    }
}