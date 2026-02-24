package com.poly.mcgltf.shadow
import com.poly.mcgltf.MCglTFSystem
import com.poly.mcgltf.RenderedGltfModel
import com.poly.mcgltf.RenderedGltfScene
import com.poly.mcgltf.config.ConfigSystem
import com.poly.mcgltf.lod.LODSystem
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.lwjgl.opengl.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
object ShadowProjectionSystem {
    private val shadowFlags = ConcurrentHashMap<Identifier, ShadowFlags>()
    private val pendingCommands = Collections.synchronizedList(mutableListOf<ShadowCommand>())
    private val savedMatrix = Matrix4f()
    data class ShadowFlags(val castShadow: Boolean = true, val receiveShadow: Boolean = true)
    private class ShadowCommand(val scene: RenderedGltfScene, val modelMatrix: Matrix4f, val useIris: Boolean)
    fun setShadowFlags(location: Identifier, flags: ShadowFlags) { shadowFlags[location] = flags }
    fun removeShadowFlags(location: Identifier) { shadowFlags.remove(location) }
    fun canCastShadow(location: Identifier): Boolean = shadowFlags[location]?.castShadow ?: true
    fun canReceiveShadow(location: Identifier): Boolean = shadowFlags[location]?.receiveShadow ?: true
    fun submitShadowGeometry(
        location: Identifier,
        model: RenderedGltfModel,
        modelMatrix: Matrix4f,
        sceneIndex: Int = 0
    ) {
        if (!isEnabled()) return
        if (!canCastShadow(location)) return
        val scene = selectShadowScene(location, model, sceneIndex) ?: return
        val useIris = MCglTFSystem.isIrisLoaded() && isIrisShadowPass()
        pendingCommands.add(ShadowCommand(scene, Matrix4f(modelMatrix), useIris))
    }
    fun renderPendingShadows() {
        if (!isEnabled()) return
        if (pendingCommands.isEmpty()) return
        val commands: List<ShadowCommand>
        synchronized(pendingCommands) {
            commands = ArrayList(pendingCommands)
            pendingCommands.clear()
        }
        val savedDepthTest = GL11.glGetBoolean(GL11.GL_DEPTH_TEST)
        val savedCullFace = GL11.glGetBoolean(GL11.GL_CULL_FACE)
        val savedPolygonOffsetFill = GL11.glGetBoolean(GL11.GL_POLYGON_OFFSET_FILL)
        val savedVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        try {
            GL11.glEnable(GL11.GL_DEPTH_TEST)
            GL11.glDepthFunc(GL11.GL_LEQUAL)
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL)
            GL11.glPolygonOffset(1.1f, 4.0f)
            for (cmd in commands) {
                RenderedGltfModel.CURRENT_POSE.set(cmd.modelMatrix)
                RenderedGltfModel.CURRENT_NORMAL.set(cmd.modelMatrix).invert().transpose()
                if (cmd.useIris) renderForIrisShadow(cmd.scene)
                else renderForVanillaShadow(cmd.scene)
            }
        } finally {
            GL30.glBindVertexArray(savedVAO)
            GL20.glUseProgram(savedProgram)
            if (!savedDepthTest) GL11.glDisable(GL11.GL_DEPTH_TEST)
            if (savedCullFace) GL11.glEnable(GL11.GL_CULL_FACE) else GL11.glDisable(GL11.GL_CULL_FACE)
            if (!savedPolygonOffsetFill) GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL)
            GL11.glPolygonOffset(0.0f, 0.0f)
            RenderedGltfModel.NODE_GLOBAL_TRANSFORMATION_LOOKUP_CACHE.clear()
        }
    }
    fun clear() {
        pendingCommands.clear()
        shadowFlags.clear()
    }
    fun isEnabled(): Boolean = ConfigSystem.isSubsystemEnabled("shadowProjection")
    private fun selectShadowScene(
        location: Identifier,
        model: RenderedGltfModel,
        sceneIndex: Int
    ): RenderedGltfScene? {
        if (LODSystem.isEnabled() && LODSystem.hasLOD(location)) {
            val levels = LODSystem.getLevelCount(location)
            if (levels > 0) {
                val lowestLOD = LODSystem.selectModel(location, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
                lowestLOD?.renderedGltfScenes?.getOrNull(sceneIndex)?.let { return it }
            }
        }
        return model.renderedGltfScenes.getOrNull(sceneIndex)
    }
    private fun renderForVanillaShadow(scene: RenderedGltfScene) {
        executeSkinning(scene)
        scene.vanillaRenderCommands.forEach(Runnable::run)
    }
    private fun renderForIrisShadow(scene: RenderedGltfScene) {
        executeSkinning(scene)
        val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        if (currentProgram != 0) {
            setupIrisShadowUniforms(currentProgram)
        }
        scene.shaderModRenderCommands.forEach(Runnable::run)
    }
    private fun executeSkinning(scene: RenderedGltfScene) {
        if (scene.skinningCommands.isEmpty()) return
        GL20.glUseProgram(MCglTFSystem.glProgramSkinning)
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD)
        try {
            scene.skinningCommands.forEach(Runnable::run)
        } finally {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0)
            GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0)
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)
        }
    }
    private fun setupIrisShadowUniforms(program: Int) {
        RenderedGltfModel.MODEL_VIEW_MATRIX = GL20.glGetUniformLocation(program, "iris_ModelViewMat")
        RenderedGltfModel.NORMAL_MATRIX = GL20.glGetUniformLocation(program, "iris_NormalMat")
    }
    private fun isIrisShadowPass(): Boolean {
        return try {
            net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered()
        } catch (_: Throwable) { false }
    }
}