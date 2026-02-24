package com.poly.mcgltf.iris
import com.poly.mcgltf.MCglTFSystem
import com.poly.mcgltf.RenderedGltfModel
import com.poly.mcgltf.RenderedGltfScene
import org.lwjgl.opengl.*
class IrisRenderedGltfScene : RenderedGltfScene() {
    override fun renderForVanilla() {
        if (skinningCommands.isNotEmpty()) {
            GL20.glUseProgram(MCglTFSystem.glProgramSkinning)
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD)
            try {
                skinningCommands.forEach(Runnable::run)
            } finally {
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0)
                GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0)
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)
            }
            val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
            GL20.glUseProgram(currentProgram)
        }
        vanillaRenderCommands.forEach(Runnable::run)
        RenderedGltfModel.NODE_GLOBAL_TRANSFORMATION_LOOKUP_CACHE.clear()
    }
    override fun renderForShaderMod() {
        val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        if (skinningCommands.isNotEmpty()) {
            GL20.glUseProgram(MCglTFSystem.glProgramSkinning)
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD)
            try {
                skinningCommands.forEach(Runnable::run)
            } finally {
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0)
                GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0)
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)
                GL20.glUseProgram(currentProgram)
            }
        }
        shaderModRenderCommands.forEach(Runnable::run)
        RenderedGltfModel.NODE_GLOBAL_TRANSFORMATION_LOOKUP_CACHE.clear()
    }
}