package com.poly.mcgltf.iris
import com.poly.mcgltf.MCglTFSystem
import com.poly.mcgltf.RenderedGltfModel
import com.poly.mcgltf.RenderedGltfScene
import de.javagl.jgltf.model.*
import de.javagl.jgltf.model.v2.MaterialModelV2
import org.lwjgl.opengl.*
class IrisRenderedGltfModel : RenderedGltfModel {
    override val shaderModTransform = ::applyTransformShaderModIris
    constructor(gltfModel: GltfModel) : super(gltfModel)
    constructor(gltfRenderData: MutableList<Runnable>, gltfModel: GltfModel) : super(gltfModel) {
        processSceneModels(gltfRenderData, gltfModel.sceneModels)
    }
    override fun processSceneModels(gltfRenderData: MutableList<Runnable>, sceneModels: List<SceneModel>) {
        for (sceneModel in sceneModels) {
            val renderedGltfScene = IrisRenderedGltfScene()
            renderedGltfScenes.add(renderedGltfScene)
            for (nodeModel in sceneModel.nodeModels) {
                val commands = rootNodeModelToCommands.getOrPut(nodeModel) {
                    val skinning = mutableListOf<Runnable>()
                    val vanilla = mutableListOf<Runnable>()
                    val shaderMod = mutableListOf<Runnable>()
                    processNodeModel(gltfRenderData, nodeModel, skinning, vanilla, shaderMod)
                    Triple(skinning, vanilla, shaderMod)
                }
                renderedGltfScene.skinningCommands.addAll(commands.first)
                renderedGltfScene.vanillaRenderCommands.addAll(commands.second)
                renderedGltfScene.shaderModRenderCommands.addAll(commands.third)
            }
        }
    }
    override fun obtainMaterial(gltfRenderData: MutableList<Runnable>, materialModel: MaterialModel): Material {
        return materialModelToRenderedMaterial.getOrPut(materialModel) {
            val material = Material()
            if (materialModel is MaterialModelV2) {
                val colorMap = resolveTexture(gltfRenderData, materialModel.baseColorTexture, MCglTFSystem.defaultColorMap)
                val normalMap = resolveTexture(gltfRenderData, materialModel.normalTexture, MCglTFSystem.defaultNormalMap)
                val specularMap = resolveTexture(gltfRenderData, materialModel.metallicRoughnessTexture, MCglTFSystem.defaultSpecularMap)
                val baseColorFactor = materialModel.baseColorFactor
                val isDoubleSided = materialModel.isDoubleSided
                material.normalTexture = materialModel.normalTexture
                material.vanillaMaterialCommand = Runnable {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorMap)
                    GL20.glVertexAttrib4f(vaColor, baseColorFactor[0], baseColorFactor[1], baseColorFactor[2], baseColorFactor[3])
                    if (baseColorFactor[3] < 1.0f) {
                        GL11.glEnable(GL11.GL_BLEND)
                        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
                    } else GL11.glDisable(GL11.GL_BLEND)
                    if (isDoubleSided) GL11.glDisable(GL11.GL_CULL_FACE) else GL11.glEnable(GL11.GL_CULL_FACE)
                }
                material.shaderModMaterialCommand = Runnable {
                    bindTripleTextures(colorMap, normalMap, specularMap)
                    GL20.glVertexAttrib4f(vaColor, baseColorFactor[0], baseColorFactor[1], baseColorFactor[2], baseColorFactor[3])
                    setEntityColorUniform()
                    if (baseColorFactor[3] < 1.0f) {
                        GL11.glEnable(GL11.GL_BLEND)
                        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
                    } else GL11.glDisable(GL11.GL_BLEND)
                    if (isDoubleSided) GL11.glDisable(GL11.GL_CULL_FACE) else GL11.glEnable(GL11.GL_CULL_FACE)
                }
            }
            material
        }
    }
    private fun resolveTexture(gltfRenderData: MutableList<Runnable>, textureModel: TextureModel?, fallback: Int): Int =
        if (textureModel != null) obtainGlTexture(gltfRenderData, textureModel) else fallback
    companion object {
        private fun bindTripleTextures(colorMap: Int, normalMap: Int, specularMap: Int) {
            GL13.glActiveTexture(COLOR_MAP_INDEX)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorMap)
            GL13.glActiveTexture(NORMAL_MAP_INDEX)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, normalMap)
            GL13.glActiveTexture(SPECULAR_MAP_INDEX)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, specularMap)
        }
        private fun setEntityColorUniform() {
            val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
            val loc = GL20.glGetUniformLocation(currentProgram, "entityColor")
            if (loc != -1) GL20.glUniform4f(loc, 1.0f, 1.0f, 1.0f, 0.0f)
        }
    }
}