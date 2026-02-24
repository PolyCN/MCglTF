package com.poly.mcgltf
import org.joml.Matrix4f
import org.lwjgl.opengl.*
open class RenderedGltfScene {
    val skinningCommands = mutableListOf<Runnable>()
    val vanillaRenderCommands = mutableListOf<Runnable>()
    val shaderModRenderCommands = mutableListOf<Runnable>()
    open fun renderForVanilla() {
        val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        try {
            executeSkinning()
            val vanillaProgram = GltfVanillaProgram.create()
            GL20.glUseProgram(vanillaProgram)
            uploadVanillaUniforms()
            vanillaRenderCommands.forEach(Runnable::run)
        } finally {
            GL20.glUseProgram(currentProgram)
            RenderedGltfModel.NODE_GLOBAL_TRANSFORMATION_LOOKUP_CACHE.clear()
        }
    }
    open fun renderForShaderMod() {
        val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        try {
            if (skinningCommands.isNotEmpty()) {
                GL20.glUseProgram(MCglTFSystem.glProgramSkinning)
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD)
                skinningCommands.forEach(Runnable::run)
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0)
                GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0)
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)
                GL20.glUseProgram(currentProgram)
            }
            uploadShaderModUniforms(currentProgram)
            val currentTexture0 = saveTexture(GL13.GL_TEXTURE0)
            val currentTexture1 = saveTexture(GL13.GL_TEXTURE1)
            val currentTexture3 = saveTexture(GL13.GL_TEXTURE3)
            try {
                shaderModRenderCommands.forEach(Runnable::run)
            } finally {
                restoreTexture(GL13.GL_TEXTURE3, currentTexture3)
                restoreTexture(GL13.GL_TEXTURE1, currentTexture1)
                restoreTexture(GL13.GL_TEXTURE0, currentTexture0)
            }
        } finally {
            RenderedGltfModel.NODE_GLOBAL_TRANSFORMATION_LOOKUP_CACHE.clear()
        }
    }
    private fun executeSkinning() {
        if (skinningCommands.isEmpty()) return
        GL20.glUseProgram(MCglTFSystem.glProgramSkinning)
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD)
        try {
            skinningCommands.forEach(Runnable::run)
        } finally {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0)
            GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0)
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)
        }
    }
    private fun uploadVanillaUniforms() {
        val projMatrix = GltfRenderState.projectionMatrix
        projMatrix.get(RenderedGltfModel.BUF_FLOAT_16)
        GL20.glUniformMatrix4fv(GltfVanillaProgram.uProjMat, false, RenderedGltfModel.BUF_FLOAT_16)
        GL20.glUniform3f(
            GltfVanillaProgram.uLight0Direction,
            GltfRenderState.light0Direction.x,
            GltfRenderState.light0Direction.y,
            GltfRenderState.light0Direction.z
        )
        GL20.glUniform3f(
            GltfVanillaProgram.uLight1Direction,
            GltfRenderState.light1Direction.x,
            GltfRenderState.light1Direction.y,
            GltfRenderState.light1Direction.z
        )
        GL20.glUniform4f(GltfVanillaProgram.uColorModulator, 1.0f, 1.0f, 1.0f, 1.0f)
        GL20.glUniform1f(GltfVanillaProgram.uFogStart, Float.MAX_VALUE)
        GL20.glUniform1f(GltfVanillaProgram.uFogEnd, Float.MAX_VALUE)
        GL20.glUniform4f(GltfVanillaProgram.uFogColor, 0.0f, 0.0f, 0.0f, 0.0f)
        RenderedGltfModel.LIGHT0_DIRECTION.set(GltfRenderState.light0Direction)
        RenderedGltfModel.LIGHT1_DIRECTION.set(GltfRenderState.light1Direction)
    }
    private fun uploadShaderModUniforms(currentProgram: Int) {
        RenderedGltfModel.MODEL_VIEW_MATRIX = GL20.glGetUniformLocation(currentProgram, "modelViewMatrix")
        RenderedGltfModel.MODEL_VIEW_MATRIX_INVERSE = GL20.glGetUniformLocation(currentProgram, "modelViewMatrixInverse")
        RenderedGltfModel.NORMAL_MATRIX = GL20.glGetUniformLocation(currentProgram, "normalMatrix")
        val projMatrix = GltfRenderState.projectionMatrix
        projMatrix.get(RenderedGltfModel.BUF_FLOAT_16)
        GL20.glUniformMatrix4fv(
            GL20.glGetUniformLocation(currentProgram, "projectionMatrix"),
            false, RenderedGltfModel.BUF_FLOAT_16
        )
        Matrix4f(projMatrix).invert().get(RenderedGltfModel.BUF_FLOAT_16)
        GL20.glUniformMatrix4fv(
            GL20.glGetUniformLocation(currentProgram, "projectionMatrixInverse"),
            false, RenderedGltfModel.BUF_FLOAT_16
        )
    }
    companion object {
        private fun saveTexture(textureUnit: Int): Int {
            GL13.glActiveTexture(textureUnit)
            return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        }
        private fun restoreTexture(textureUnit: Int, textureId: Int) {
            GL13.glActiveTexture(textureUnit)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId)
        }
    }
}
