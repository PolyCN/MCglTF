package com.poly.mcgltf
import com.poly.mcgltf.mikktspace.MikkTSpaceContext
import com.poly.mcgltf.mikktspace.MikktspaceTangentGenerator
import de.javagl.jgltf.model.*
import de.javagl.jgltf.model.image.PixelDatas
import de.javagl.jgltf.model.impl.DefaultAccessorModel
import de.javagl.jgltf.model.impl.DefaultBufferViewModel
import de.javagl.jgltf.model.impl.DefaultNodeModel
import de.javagl.jgltf.model.v2.MaterialModelV2
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*

class RenderedGltfModel(val gltfModel: GltfModel, val renderedGltfScenes: MutableList<RenderedGltfScene> = mutableListOf()) {
    companion object {
        const val mc_midTexCoord = 12
        const val at_tangent = 13
        const val COLOR_MAP_INDEX = GL13.GL_TEXTURE0
        @JvmField var NORMAL_MAP_INDEX = GL13.GL_TEXTURE2
        @JvmField var SPECULAR_MAP_INDEX = GL13.GL_TEXTURE1
        @JvmField var MODEL_VIEW_MATRIX = 0
        @JvmField var MODEL_VIEW_MATRIX_INVERSE = 0
        @JvmField var NORMAL_MATRIX = 0
        const val vaPosition = 0
        const val vaColor = 1
        const val vaUV0 = 2
        const val vaUV1 = 3
        const val vaUV2 = 4
        const val vaNormal = 10
        const val skinning_joint = 0
        const val skinning_weight = 1
        const val skinning_position = 2
        const val skinning_normal = 3
        const val skinning_tangent = 4
        const val skinning_out_position = 0
        const val skinning_out_normal = 1
        const val skinning_out_tangent = 2
        @JvmField val BUF_FLOAT_9: FloatBuffer = BufferUtils.createFloatBuffer(9)
        @JvmField val BUF_FLOAT_16: FloatBuffer = BufferUtils.createFloatBuffer(16)
        @JvmField val NODE_GLOBAL_TRANSFORMATION_LOOKUP_CACHE = IdentityHashMap<NodeModel, FloatArray>()
        @JvmField var CURRENT_POSE: Matrix4f = Matrix4f()
        @JvmField var CURRENT_NORMAL: Matrix3f = Matrix3f()
        @JvmField var LIGHT0_DIRECTION: Vector3f = Vector3f()
        @JvmField var LIGHT1_DIRECTION: Vector3f = Vector3f()
        @JvmField var uniformFloatBuffer: FloatBuffer? = null
        @JvmField val vanillaDefaultMaterialCommand = Runnable {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTFSystem.defaultColorMap)
            GL20.glVertexAttrib4f(vaColor, 1.0f, 1.0f, 1.0f, 1.0f)
            GL11.glEnable(GL11.GL_CULL_FACE)
        }
        @JvmField val shaderModDefaultMaterialCommand = Runnable {
            GL13.glActiveTexture(COLOR_MAP_INDEX)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTFSystem.defaultColorMap)
            GL13.glActiveTexture(NORMAL_MAP_INDEX)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTFSystem.defaultNormalMap)
            GL13.glActiveTexture(SPECULAR_MAP_INDEX)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTFSystem.defaultSpecularMap)
            GL20.glVertexAttrib4f(vaColor, 1.0f, 1.0f, 1.0f, 1.0f)
            val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
            val entityColorLocation = GL20.glGetUniformLocation(currentProgram, "entityColor")
            if (entityColorLocation != -1) GL20.glUniform4f(entityColorLocation, 1.0f, 1.0f, 1.0f, 0.0f)
            GL11.glDisable(GL11.GL_BLEND)
            GL11.glEnable(GL11.GL_CULL_FACE)
        }
        @JvmStatic
        fun findGlobalTransform(nodeModel: NodeModel): FloatArray {
            NODE_GLOBAL_TRANSFORMATION_LOOKUP_CACHE[nodeModel]?.let { return it }
            val transform = FloatArray(16)
            val localTransform = FloatArray(16)
            (nodeModel as DefaultNodeModel).computeLocalTransform(localTransform)
            val parent = nodeModel.parent
            if (parent is NodeModel) {
                MathUtils.mul4x4(findGlobalTransform(parent), localTransform, transform)
            } else {
                System.arraycopy(localTransform, 0, transform, 0, 16)
            }
            NODE_GLOBAL_TRANSFORMATION_LOOKUP_CACHE[nodeModel] = transform
            return transform
        }
        @JvmStatic
        fun putFloatBuffer(value: FloatArray): FloatBuffer {
            var buf = uniformFloatBuffer
            if (buf == null || buf.capacity() < value.size) {
                buf = BufferUtils.createFloatBuffer(value.size)
                uniformFloatBuffer = buf
            }
            buf.clear()
            buf.put(value)
            buf.flip()
            return buf
        }
        @JvmStatic
        fun applyTransformVanilla(nodeModel: NodeModel) {
            val globalTransform = findGlobalTransform(nodeModel)
            CURRENT_POSE.set(globalTransform)
            CURRENT_NORMAL.set(CURRENT_POSE).invert().transpose()
        }
        @JvmStatic
        fun applyTransformShaderMod(nodeModel: NodeModel) {
            val globalTransform = findGlobalTransform(nodeModel)
            val modelView = Matrix4f().set(globalTransform)
            modelView.get(BUF_FLOAT_16)
            GL20.glUniformMatrix4fv(MODEL_VIEW_MATRIX, false, BUF_FLOAT_16)
            val modelViewInverse = Matrix4f(modelView).invert()
            modelViewInverse.get(BUF_FLOAT_16)
            GL20.glUniformMatrix4fv(MODEL_VIEW_MATRIX_INVERSE, false, BUF_FLOAT_16)
            val normalMatrix = Matrix3f().set(modelView).invert().transpose()
            normalMatrix.get(BUF_FLOAT_9)
            GL20.glUniformMatrix3fv(NORMAL_MATRIX, false, BUF_FLOAT_9)
        }
        @JvmStatic
        fun createAccessorModel(componentType: Int, count: Int, elementType: ElementType, label: String): DefaultAccessorModel {
            val model = DefaultAccessorModel(componentType, count, elementType)
            val byteLength = count * model.paddedElementSizeInBytes
            val bufferView = DefaultBufferViewModel(null)
            val buffer = ByteBuffer.allocateDirect(byteLength).order(ByteOrder.nativeOrder())
            bufferView.setByteLength(byteLength)
            bufferView.setBufferModel(object : BufferModel {
                override fun getUri(): String = label
                override fun getByteLength(): Int = byteLength
                override fun getBufferData(): ByteBuffer = buffer
                override fun getName(): String = label
                override fun getExtensions(): MutableMap<String, Any> = mutableMapOf()
                override fun getExtras(): Any? = null
            })
            model.bufferViewModel = bufferView
            model.accessorData = AccessorDatas.create(model)
            return model
        }
    }
    data class Material(
        var vanillaMaterialCommand: Runnable = vanillaDefaultMaterialCommand,
        var shaderModMaterialCommand: Runnable = shaderModDefaultMaterialCommand,
        var normalTexture: TextureModel? = null
    )
    private val rootNodeModelToCommands = IdentityHashMap<NodeModel, Triple<MutableList<Runnable>, MutableList<Runnable>, MutableList<Runnable>>>()
    private val positionsAccessorModelToNormalsAccessorModel = IdentityHashMap<AccessorModel, AccessorModel>()
    private val normalsAccessorModelToTangentsAccessorModel = IdentityHashMap<AccessorModel, AccessorModel>()
    private val colorsAccessorModelVec3ToVec4 = IdentityHashMap<AccessorModel, AccessorModel>()
    private val jointsAccessorModelUnsignedLookup = IdentityHashMap<AccessorModel, AccessorModel>()
    private val weightsAccessorModelDequantizedLookup = IdentityHashMap<AccessorModel, AccessorModel>()
    private val colorsMorphTargetAccessorModelToAccessorData = IdentityHashMap<AccessorModel, AccessorFloatData>()
    private val texcoordsMorphTargetAccessorModelToAccessorData = IdentityHashMap<AccessorModel, AccessorFloatData>()
    private val meshPrimitiveModelToTangentsAccessorModel = IdentityHashMap<MeshPrimitiveModel, AccessorModel>()
    private val meshPrimitiveModelToUnindexed = IdentityHashMap<MeshPrimitiveModel, Pair<Map<String, AccessorModel>, List<Map<String, AccessorModel>>>>()
    private val bufferViewModelToGlBufferView = IdentityHashMap<BufferViewModel, Int>()
    private val textureModelToGlTexture = IdentityHashMap<TextureModel, Int>()
    private val materialModelToRenderedMaterial = IdentityHashMap<MaterialModel, Material>()
    constructor(gltfRenderData: MutableList<Runnable>, gltfModel: GltfModel) : this(gltfModel) {
        processSceneModels(gltfRenderData, gltfModel.sceneModels)
    }
    fun processSceneModels(gltfRenderData: MutableList<Runnable>, sceneModels: List<SceneModel>) {
        for (sceneModel in sceneModels) {
            val renderedGltfScene = RenderedGltfScene()
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
    fun processNodeModel(
        gltfRenderData: MutableList<Runnable>,
        nodeModel: NodeModel,
        skinningCommands: MutableList<Runnable>,
        vanillaRenderCommands: MutableList<Runnable>,
        shaderModRenderCommands: MutableList<Runnable>
    ) {
        val nodeSkinningCommands = mutableListOf<Runnable>()
        val vanillaNodeRenderCommands = mutableListOf<Runnable>()
        val shaderModNodeRenderCommands = mutableListOf<Runnable>()
        val skinModel = nodeModel.skinModel
        if (skinModel != null) {
            processSkinModel(gltfRenderData, nodeModel, skinModel, nodeSkinningCommands, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
        } else if (nodeModel.meshModels.isNotEmpty()) {
            for (meshModel in nodeModel.meshModels) {
                for (meshPrimitiveModel in meshModel.meshPrimitiveModels) {
                    processMeshPrimitiveModel(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
                }
            }
        }
        nodeModel.children.forEach { childNode ->
            processNodeModel(gltfRenderData, childNode, nodeSkinningCommands, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
        }
        if (nodeSkinningCommands.isNotEmpty()) {
            skinningCommands.add(Runnable {
                val scale = nodeModel.scale
                if (scale == null || scale[0] != 0.0f || scale[1] != 0.0f || scale[2] != 0.0f) {
                    nodeSkinningCommands.forEach(Runnable::run)
                }
            })
        }
        if (vanillaNodeRenderCommands.isNotEmpty()) {
            vanillaRenderCommands.add(Runnable {
                val scale = nodeModel.scale
                if (scale == null || scale[0] != 0.0f || scale[1] != 0.0f || scale[2] != 0.0f) {
                    applyTransformVanilla(nodeModel)
                    vanillaNodeRenderCommands.forEach(Runnable::run)
                }
            })
            shaderModRenderCommands.add(Runnable {
                val scale = nodeModel.scale
                if (scale == null || scale[0] != 0.0f || scale[1] != 0.0f || scale[2] != 0.0f) {
                    applyTransformShaderMod(nodeModel)
                    shaderModNodeRenderCommands.forEach(Runnable::run)
                }
            })
        }
    }
    private fun processSkinModel(
        gltfRenderData: MutableList<Runnable>,
        nodeModel: NodeModel,
        skinModel: SkinModel,
        nodeSkinningCommands: MutableList<Runnable>,
        vanillaNodeRenderCommands: MutableList<Runnable>,
        shaderModNodeRenderCommands: MutableList<Runnable>
    ) {
        val jointCount = skinModel.joints.size
        val transforms = Array(jointCount) { FloatArray(16) }
        val invertNodeTransform = FloatArray(16)
        val bindShapeMatrix = FloatArray(16)
        val canHaveHardwareSkinning = nodeModel.meshModels.any { meshModel ->
            meshModel.meshPrimitiveModels.any { !it.attributes.containsKey("JOINTS_1") }
        }
        val jointMatricesTransformCommands = (0 until jointCount).map { i ->
            val inverseBindMatrix = FloatArray(16)
            Runnable {
                MathUtils.mul4x4(invertNodeTransform, transforms[i], transforms[i])
                skinModel.getInverseBindMatrix(i, inverseBindMatrix)
                MathUtils.mul4x4(transforms[i], inverseBindMatrix, transforms[i])
                MathUtils.mul4x4(transforms[i], bindShapeMatrix, transforms[i])
            }
        }
        val computeJointMatrices = Runnable {
            for (i in transforms.indices) {
                System.arraycopy(findGlobalTransform(skinModel.joints[i]), 0, transforms[i], 0, 16)
            }
            MathUtils.invert4x4(findGlobalTransform(nodeModel), invertNodeTransform)
            skinModel.getBindShapeMatrix(bindShapeMatrix)
            jointMatricesTransformCommands.parallelStream().forEach(Runnable::run)
        }
        if (canHaveHardwareSkinning) {
            when (MCglTFSystem.glProfile) {
                is GLProfile.GL43 -> setupSkinningGL43(gltfRenderData, nodeModel, skinModel, transforms, jointCount, computeJointMatrices, nodeSkinningCommands, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
                is GLProfile.GL40, is GLProfile.GL33 -> setupSkinningTBO(gltfRenderData, nodeModel, skinModel, transforms, jointCount, computeJointMatrices, nodeSkinningCommands, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
                is GLProfile.GL30 -> setupSkinningCPU(gltfRenderData, nodeModel, skinModel, transforms, computeJointMatrices, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
            }
        } else {
            vanillaNodeRenderCommands.add(computeJointMatrices)
            shaderModNodeRenderCommands.add(computeJointMatrices)
            for (meshModel in nodeModel.meshModels) {
                for (meshPrimitiveModel in meshModel.meshPrimitiveModels) {
                    processMeshPrimitiveModelSkinned(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, transforms, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
                }
            }
        }
    }
    private fun setupSkinningGL43(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, skinModel: SkinModel,
        transforms: Array<FloatArray>, jointCount: Int, computeJointMatrices: Runnable,
        nodeSkinningCommands: MutableList<Runnable>, vanillaNodeRenderCommands: MutableList<Runnable>, shaderModNodeRenderCommands: MutableList<Runnable>
    ) {
        val jointMatrixSize = jointCount * 16
        val jointMatrices = FloatArray(jointMatrixSize)
        val jointMatrixBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(jointMatrixBuffer) })
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, jointMatrixBuffer)
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (jointMatrixSize * 4).toLong(), GL15.GL_STATIC_DRAW)
        val copyToJointMatrices = (0 until jointCount).map { i ->
            Runnable { System.arraycopy(transforms[i], 0, jointMatrices, i * 16, 16) }
        }
        nodeSkinningCommands.add(Runnable {
            computeJointMatrices.run()
            copyToJointMatrices.parallelStream().forEach(Runnable::run)
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, jointMatrixBuffer)
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, putFloatBuffer(jointMatrices))
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, jointMatrixBuffer)
        })
        for (meshModel in nodeModel.meshModels) {
            for (meshPrimitiveModel in meshModel.meshPrimitiveModels) {
                processMeshPrimitiveModelSkinned(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, transforms, nodeSkinningCommands, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
            }
        }
    }
    private fun setupSkinningTBO(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, skinModel: SkinModel,
        transforms: Array<FloatArray>, jointCount: Int, computeJointMatrices: Runnable,
        nodeSkinningCommands: MutableList<Runnable>, vanillaNodeRenderCommands: MutableList<Runnable>, shaderModNodeRenderCommands: MutableList<Runnable>
    ) {
        val jointMatrixSize = jointCount * 16
        val jointMatrices = FloatArray(jointMatrixSize)
        val jointMatrixBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(jointMatrixBuffer) })
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, jointMatrixBuffer)
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (jointMatrixSize * 4).toLong(), GL15.GL_STATIC_DRAW)
        val glTexture = GL11.glGenTextures()
        gltfRenderData.add(Runnable { GL11.glDeleteTextures(glTexture) })
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, glTexture)
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, jointMatrixBuffer)
        val copyToJointMatrices = (0 until jointCount).map { i ->
            Runnable { System.arraycopy(transforms[i], 0, jointMatrices, i * 16, 16) }
        }
        nodeSkinningCommands.add(Runnable {
            computeJointMatrices.run()
            copyToJointMatrices.parallelStream().forEach(Runnable::run)
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, jointMatrixBuffer)
            GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0, putFloatBuffer(jointMatrices))
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, glTexture)
        })
        for (meshModel in nodeModel.meshModels) {
            for (meshPrimitiveModel in meshModel.meshPrimitiveModels) {
                processMeshPrimitiveModelSkinned(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, transforms, nodeSkinningCommands, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
            }
        }
    }
    private fun setupSkinningCPU(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, skinModel: SkinModel,
        transforms: Array<FloatArray>, computeJointMatrices: Runnable,
        vanillaNodeRenderCommands: MutableList<Runnable>, shaderModNodeRenderCommands: MutableList<Runnable>
    ) {
        vanillaNodeRenderCommands.add(computeJointMatrices)
        shaderModNodeRenderCommands.add(computeJointMatrices)
        for (meshModel in nodeModel.meshModels) {
            for (meshPrimitiveModel in meshModel.meshPrimitiveModels) {
                processMeshPrimitiveModelSkinned(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, transforms, vanillaNodeRenderCommands, shaderModNodeRenderCommands)
            }
        }
    }
    private fun processMeshPrimitiveModelSkinned(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, transforms: Array<FloatArray>,
        skinningCommands: MutableList<Runnable>, vanillaRenderCommands: MutableList<Runnable>, shaderModRenderCommands: MutableList<Runnable>
    ) {
        val attributes = meshPrimitiveModel.attributes
        val positionsAccessorModel = attributes["POSITION"] ?: return
        val renderCommand = mutableListOf<Runnable>()
        if (attributes.containsKey("JOINTS_1")) {
            processMeshPrimitiveModelSkinned(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, transforms, vanillaRenderCommands, shaderModRenderCommands)
            return
        }
        val normalsAccessorModel = attributes["NORMAL"]
        if (normalsAccessorModel != null) {
            val tangentsAccessorModel = attributes["TANGENT"]
            if (tangentsAccessorModel != null) {
                processSkinnedIncludedTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, skinningCommands, attributes, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel)
                addMaterialCommands(gltfRenderData, meshPrimitiveModel, vanillaRenderCommands, shaderModRenderCommands)
            } else {
                val materialModel = meshPrimitiveModel.materialModel
                if (materialModel != null) {
                    val renderedMaterial = obtainMaterial(gltfRenderData, materialModel)
                    vanillaRenderCommands.add(renderedMaterial.vanillaMaterialCommand)
                    shaderModRenderCommands.add(renderedMaterial.shaderModMaterialCommand)
                    if (renderedMaterial.normalTexture != null) processSkinnedMikkTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, skinningCommands)
                    else processSkinnedSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, skinningCommands, attributes, positionsAccessorModel, normalsAccessorModel)
                } else {
                    vanillaRenderCommands.add(vanillaDefaultMaterialCommand)
                    shaderModRenderCommands.add(shaderModDefaultMaterialCommand)
                    processSkinnedSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, skinningCommands, attributes, positionsAccessorModel, normalsAccessorModel)
                }
            }
        } else {
            val materialModel = meshPrimitiveModel.materialModel
            if (materialModel != null) {
                val renderedMaterial = obtainMaterial(gltfRenderData, materialModel)
                vanillaRenderCommands.add(renderedMaterial.vanillaMaterialCommand)
                shaderModRenderCommands.add(renderedMaterial.shaderModMaterialCommand)
                if (renderedMaterial.normalTexture != null) processSkinnedFlatNormalMikkTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, skinningCommands)
                else processSkinnedFlatNormalSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, skinningCommands)
            } else {
                vanillaRenderCommands.add(vanillaDefaultMaterialCommand)
                shaderModRenderCommands.add(shaderModDefaultMaterialCommand)
                processSkinnedFlatNormalSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, skinningCommands)
            }
        }
        vanillaRenderCommands.addAll(renderCommand)
        shaderModRenderCommands.addAll(renderCommand)
    }
    private fun processMeshPrimitiveModelSkinned(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, transforms: Array<FloatArray>,
        vanillaRenderCommands: MutableList<Runnable>, shaderModRenderCommands: MutableList<Runnable>
    ) {
        val attributes = meshPrimitiveModel.attributes
        val positionsAccessorModel = attributes["POSITION"] ?: return
        val renderCommand = mutableListOf<Runnable>()
        val normalsAccessorModel = attributes["NORMAL"]
        if (normalsAccessorModel != null) {
            val tangentsAccessorModel = attributes["TANGENT"]
            if (tangentsAccessorModel != null) {
                processCpuSkinnedIncludedTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, transforms, attributes, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel)
                addMaterialCommands(gltfRenderData, meshPrimitiveModel, vanillaRenderCommands, shaderModRenderCommands)
            } else {
                val materialModel = meshPrimitiveModel.materialModel
                if (materialModel != null) {
                    val renderedMaterial = obtainMaterial(gltfRenderData, materialModel)
                    vanillaRenderCommands.add(renderedMaterial.vanillaMaterialCommand)
                    shaderModRenderCommands.add(renderedMaterial.shaderModMaterialCommand)
                    if (renderedMaterial.normalTexture != null) processCpuSkinnedMikkTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, transforms)
                    else processCpuSkinnedSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, transforms, attributes, positionsAccessorModel, normalsAccessorModel)
                } else {
                    vanillaRenderCommands.add(vanillaDefaultMaterialCommand)
                    shaderModRenderCommands.add(shaderModDefaultMaterialCommand)
                    processCpuSkinnedSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, transforms, attributes, positionsAccessorModel, normalsAccessorModel)
                }
            }
        } else {
            val materialModel = meshPrimitiveModel.materialModel
            if (materialModel != null) {
                val renderedMaterial = obtainMaterial(gltfRenderData, materialModel)
                vanillaRenderCommands.add(renderedMaterial.vanillaMaterialCommand)
                shaderModRenderCommands.add(renderedMaterial.shaderModMaterialCommand)
                if (renderedMaterial.normalTexture != null) processCpuSkinnedFlatNormalMikkTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, transforms)
                else processCpuSkinnedFlatNormalSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, transforms)
            } else {
                vanillaRenderCommands.add(vanillaDefaultMaterialCommand)
                shaderModRenderCommands.add(shaderModDefaultMaterialCommand)
                processCpuSkinnedFlatNormalSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, transforms)
            }
        }
        vanillaRenderCommands.addAll(renderCommand)
        shaderModRenderCommands.addAll(renderCommand)
    }
    private fun setupSkinningAttribs(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        morphTargets: List<Map<String, AccessorModel>>, skinningCommand: MutableList<Runnable>,
        attributes: Map<String, AccessorModel>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel, tangentsAccessorModel: AccessorModel
    ) {
        val jointsAccessorModel = attributes["JOINTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, jointsAccessorModel.bufferViewModel)
        setupVertexAttrib(jointsAccessorModel, skinning_joint)
        val weightsAccessorModel = attributes["WEIGHTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, weightsAccessorModel.bufferViewModel)
        setupVertexAttrib(weightsAccessorModel, skinning_weight)
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, skinning_position)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "NORMAL")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, normalsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(normalsAccessorModel, skinning_normal)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "TANGENT")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, tangentsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(tangentsAccessorModel, skinning_tangent)
    }
    private fun createTFBuffersAndCommand(
        gltfRenderData: MutableList<Runnable>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel, tangentsAccessorModel: AccessorModel,
        glVertexArraySkinning: Int, skinningCommand: MutableList<Runnable>,
        glTransformFeedback: Int
    ): Triple<Int, Int, Int> {
        val positionBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(positionBuffer) })
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, positionBuffer)
        GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, positionsAccessorModel.bufferViewModel.byteLength.toLong(), GL15.GL_STATIC_DRAW)
        val normalBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(normalBuffer) })
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, normalBuffer)
        GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, normalsAccessorModel.bufferViewModel.byteLength.toLong(), GL15.GL_STATIC_DRAW)
        val tangentBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(tangentBuffer) })
        GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, tangentBuffer)
        GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, tangentsAccessorModel.bufferViewModel.byteLength.toLong(), GL15.GL_STATIC_DRAW)
        val pointCount = positionsAccessorModel.count
        when (MCglTFSystem.glProfile) {
            is GLProfile.GL43, is GLProfile.GL40 -> {
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, skinning_out_position, positionBuffer)
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, skinning_out_normal, normalBuffer)
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, skinning_out_tangent, tangentBuffer)
                skinningCommand.add(Runnable {
                    GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, glTransformFeedback)
                    GL30.glBeginTransformFeedback(GL11.GL_POINTS)
                    GL30.glBindVertexArray(glVertexArraySkinning)
                    GL11.glDrawArrays(GL11.GL_POINTS, 0, pointCount)
                    GL30.glEndTransformFeedback()
                })
            }
            is GLProfile.GL33 -> {
                skinningCommand.add(Runnable {
                    GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, skinning_out_position, positionBuffer)
                    GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, skinning_out_normal, normalBuffer)
                    GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, skinning_out_tangent, tangentBuffer)
                    GL30.glBeginTransformFeedback(GL11.GL_POINTS)
                    GL30.glBindVertexArray(glVertexArraySkinning)
                    GL11.glDrawArrays(GL11.GL_POINTS, 0, pointCount)
                    GL30.glEndTransformFeedback()
                })
            }
            is GLProfile.GL30 -> {}
        }
        return Triple(positionBuffer, normalBuffer, tangentBuffer)
    }
    private fun setupRenderVAOFromTF(
        gltfRenderData: MutableList<Runnable>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel, tangentsAccessorModel: AccessorModel,
        positionBuffer: Int, normalBuffer: Int, tangentBuffer: Int
    ): Int {
        val glVertexArray = GL30.glGenVertexArrays()
        gltfRenderData.add(Runnable { GL30.glDeleteVertexArrays(glVertexArray) })
        GL30.glBindVertexArray(glVertexArray)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionBuffer)
        GL20.glVertexAttribPointer(vaPosition, positionsAccessorModel.elementType.numComponents, positionsAccessorModel.componentType, false, 0, 0)
        GL20.glEnableVertexAttribArray(vaPosition)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, normalBuffer)
        GL20.glVertexAttribPointer(vaNormal, normalsAccessorModel.elementType.numComponents, normalsAccessorModel.componentType, false, 0, 0)
        GL20.glEnableVertexAttribArray(vaNormal)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, tangentBuffer)
        GL20.glVertexAttribPointer(at_tangent, tangentsAccessorModel.elementType.numComponents, tangentsAccessorModel.componentType, false, 0, 0)
        GL20.glEnableVertexAttribArray(at_tangent)
        return glVertexArray
    }
    private fun addSkinnedDrawCommand(
        gltfRenderData: MutableList<Runnable>, meshPrimitiveModel: MeshPrimitiveModel,
        pointCount: Int, glVertexArray: Int, glTransformFeedback: Int,
        renderCommand: MutableList<Runnable>
    ) {
        val mode = meshPrimitiveModel.mode
        val indices = meshPrimitiveModel.indices
        if (indices != null) {
            val glIndicesBufferView = obtainElementArrayBuffer(gltfRenderData, indices.bufferViewModel)
            val count = indices.count
            val type = indices.componentType
            val offset = indices.byteOffset
            renderCommand.add(Runnable {
                GL30.glBindVertexArray(glVertexArray)
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, glIndicesBufferView)
                GL11.glDrawElements(mode, count, type, offset.toLong())
            })
        } else {
            when (MCglTFSystem.glProfile) {
                is GLProfile.GL43, is GLProfile.GL40 -> renderCommand.add(Runnable {
                    GL30.glBindVertexArray(glVertexArray)
                    GL40.glDrawTransformFeedback(mode, glTransformFeedback)
                })
                is GLProfile.GL33 -> renderCommand.add(Runnable {
                    GL30.glBindVertexArray(glVertexArray)
                    GL11.glDrawArrays(mode, 0, pointCount)
                })
                is GLProfile.GL30 -> {}
            }
        }
    }
    private fun beginSkinnedPrimitive(gltfRenderData: MutableList<Runnable>): Pair<Int, Int> {
        val glTransformFeedback: Int
        when (MCglTFSystem.glProfile) {
            is GLProfile.GL43, is GLProfile.GL40 -> {
                glTransformFeedback = GL40.glGenTransformFeedbacks()
                gltfRenderData.add(Runnable { GL40.glDeleteTransformFeedbacks(glTransformFeedback) })
                GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, glTransformFeedback)
            }
            else -> glTransformFeedback = -1
        }
        val glVertexArraySkinning = GL30.glGenVertexArrays()
        gltfRenderData.add(Runnable { GL30.glDeleteVertexArrays(glVertexArraySkinning) })
        GL30.glBindVertexArray(glVertexArraySkinning)
        return Pair(glTransformFeedback, glVertexArraySkinning)
    }
    private fun processSkinnedIncludedTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        skinningCommand: MutableList<Runnable>, attributes: Map<String, AccessorModel>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel, tangentsAccessorModel: AccessorModel
    ) {
        val (glTransformFeedback, glVertexArraySkinning) = beginSkinnedPrimitive(gltfRenderData)
        val morphTargets = meshPrimitiveModel.targets
        setupSkinningAttribs(gltfRenderData, nodeModel, meshModel, morphTargets, skinningCommand, attributes, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel)
        val (positionBuffer, normalBuffer, tangentBuffer) = createTFBuffersAndCommand(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, glVertexArraySkinning, skinningCommand, glTransformFeedback)
        val glVertexArray = setupRenderVAOFromTF(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, positionBuffer, normalBuffer, tangentBuffer)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addSkinnedDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel.count, glVertexArray, glTransformFeedback, renderCommand)
    }
    private fun processSkinnedSimpleTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        skinningCommand: MutableList<Runnable>, attributes: Map<String, AccessorModel>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel
    ) {
        val (glTransformFeedback, glVertexArraySkinning) = beginSkinnedPrimitive(gltfRenderData)
        val morphTargets = meshPrimitiveModel.targets
        val jointsAccessorModel = attributes["JOINTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, jointsAccessorModel.bufferViewModel)
        setupVertexAttrib(jointsAccessorModel, skinning_joint)
        val weightsAccessorModel = attributes["WEIGHTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, weightsAccessorModel.bufferViewModel)
        setupVertexAttrib(weightsAccessorModel, skinning_weight)
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, skinning_position)
        val tangentsAccessorModel = obtainTangentsAccessorModel(normalsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        val tangentTargetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createNormalTangentMorphTarget(morphTargets, normalsAccessorModel, tangentsAccessorModel, targetAccessorDatas, tangentTargetAccessorDatas)) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, normalsAccessorModel, targetAccessorDatas)
            setupVertexAttrib(normalsAccessorModel, skinning_normal)
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, tangentsAccessorModel, tangentTargetAccessorDatas)
            setupVertexAttrib(tangentsAccessorModel, skinning_tangent)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
            setupVertexAttrib(normalsAccessorModel, skinning_normal)
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
            setupVertexAttrib(tangentsAccessorModel, skinning_tangent)
        }
        val (positionBuffer, normalBuffer, tangentBuffer) = createTFBuffersAndCommand(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, glVertexArraySkinning, skinningCommand, glTransformFeedback)
        val glVertexArray = setupRenderVAOFromTF(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, positionBuffer, normalBuffer, tangentBuffer)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addSkinnedDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel.count, glVertexArray, glTransformFeedback, renderCommand)
    }
    private fun processSkinnedMikkTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        skinningCommand: MutableList<Runnable>
    ) {
        val (glTransformFeedback, glVertexArraySkinning) = beginSkinnedPrimitive(gltfRenderData)
        val unindexed = obtainUnindexed(meshPrimitiveModel)
        val attributes = unindexed.first
        val morphTargets = unindexed.second
        val jointsAccessorModel = attributes["JOINTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, jointsAccessorModel.bufferViewModel)
        setupVertexAttrib(jointsAccessorModel, skinning_joint)
        val weightsAccessorModel = attributes["WEIGHTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, weightsAccessorModel.bufferViewModel)
        setupVertexAttrib(weightsAccessorModel, skinning_weight)
        val positionsAccessorModel = attributes["POSITION"]!!
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, skinning_position)
        val normalsAccessorModel = attributes["NORMAL"]!!
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "NORMAL")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, normalsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(normalsAccessorModel, skinning_normal)
        val texcoordsAccessorModel = attributes["TEXCOORD_0"]
        val tangentsAccessorModel = obtainTangentsAccessorModel(meshPrimitiveModel, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (texcoordsAccessorModel != null && createTangentMorphTarget(morphTargets, targetAccessorDatas, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel, "TEXCOORD_0", tangentsAccessorModel)) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, tangentsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(tangentsAccessorModel, skinning_tangent)
        val (positionBuffer, normalBuffer, tangentBuffer) = createTFBuffersAndCommand(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, glVertexArraySkinning, skinningCommand, glTransformFeedback)
        val glVertexArray = setupRenderVAOFromTF(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, positionBuffer, normalBuffer, tangentBuffer)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addSkinnedDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel.count, glVertexArray, glTransformFeedback, renderCommand)
    }
    private fun processSkinnedFlatNormalSimpleTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        skinningCommand: MutableList<Runnable>
    ) {
        val (glTransformFeedback, glVertexArraySkinning) = beginSkinnedPrimitive(gltfRenderData)
        val unindexed = obtainUnindexed(meshPrimitiveModel)
        val attributes = unindexed.first
        val morphTargets = unindexed.second
        val jointsAccessorModel = attributes["JOINTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, jointsAccessorModel.bufferViewModel)
        setupVertexAttrib(jointsAccessorModel, skinning_joint)
        val weightsAccessorModel = attributes["WEIGHTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, weightsAccessorModel.bufferViewModel)
        setupVertexAttrib(weightsAccessorModel, skinning_weight)
        val positionsAccessorModel = attributes["POSITION"]!!
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, skinning_position)
        val normalsAccessorModel = obtainNormalsAccessorModel(positionsAccessorModel)
        val tangentsAccessorModel = obtainTangentsAccessorModel(normalsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        val tangentTargetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createNormalTangentMorphTarget(morphTargets, normalsAccessorModel, tangentsAccessorModel, targetAccessorDatas, tangentTargetAccessorDatas)) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, normalsAccessorModel, targetAccessorDatas)
            setupVertexAttrib(normalsAccessorModel, skinning_normal)
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, tangentsAccessorModel, tangentTargetAccessorDatas)
            setupVertexAttrib(tangentsAccessorModel, skinning_tangent)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
            setupVertexAttrib(normalsAccessorModel, skinning_normal)
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
            setupVertexAttrib(tangentsAccessorModel, skinning_tangent)
        }
        val (positionBuffer, normalBuffer, tangentBuffer) = createTFBuffersAndCommand(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, glVertexArraySkinning, skinningCommand, glTransformFeedback)
        val glVertexArray = setupRenderVAOFromTF(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, positionBuffer, normalBuffer, tangentBuffer)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addSkinnedDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel.count, glVertexArray, glTransformFeedback, renderCommand)
    }
    private fun processSkinnedFlatNormalMikkTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        skinningCommand: MutableList<Runnable>
    ) {
        val (glTransformFeedback, glVertexArraySkinning) = beginSkinnedPrimitive(gltfRenderData)
        val unindexed = obtainUnindexed(meshPrimitiveModel)
        val attributes = unindexed.first
        val morphTargets = unindexed.second
        val jointsAccessorModel = attributes["JOINTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, jointsAccessorModel.bufferViewModel)
        setupVertexAttrib(jointsAccessorModel, skinning_joint)
        val weightsAccessorModel = attributes["WEIGHTS_0"]!!
        bindArrayBufferViewModel(gltfRenderData, weightsAccessorModel.bufferViewModel)
        setupVertexAttrib(weightsAccessorModel, skinning_weight)
        val positionsAccessorModel = attributes["POSITION"]!!
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, skinning_position)
        val normalsAccessorModel = obtainNormalsAccessorModel(positionsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "NORMAL")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, normalsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(normalsAccessorModel, skinning_normal)
        val texcoordsAccessorModel = attributes["TEXCOORD_0"]
        val tangentsAccessorModel = obtainTangentsAccessorModel(meshPrimitiveModel, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (texcoordsAccessorModel != null && createTangentMorphTarget(morphTargets, targetAccessorDatas, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel, "TEXCOORD_0", tangentsAccessorModel)) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, skinningCommand, tangentsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(tangentsAccessorModel, skinning_tangent)
        val (positionBuffer, normalBuffer, tangentBuffer) = createTFBuffersAndCommand(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, glVertexArraySkinning, skinningCommand, glTransformFeedback)
        val glVertexArray = setupRenderVAOFromTF(gltfRenderData, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, positionBuffer, normalBuffer, tangentBuffer)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addSkinnedDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel.count, glVertexArray, glTransformFeedback, renderCommand)
    }
    fun processMeshPrimitiveModel(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel,
        vanillaRenderCommands: MutableList<Runnable>, shaderModRenderCommands: MutableList<Runnable>
    ) {
        val attributes = meshPrimitiveModel.attributes
        val positionsAccessorModel = attributes["POSITION"] ?: return
        val renderCommand = mutableListOf<Runnable>()
        val normalsAccessorModel = attributes["NORMAL"]
        if (normalsAccessorModel != null) {
            val tangentsAccessorModel = attributes["TANGENT"]
            if (tangentsAccessorModel != null) {
                processMeshPrimitiveModelIncludedTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel)
                addMaterialCommands(gltfRenderData, meshPrimitiveModel, vanillaRenderCommands, shaderModRenderCommands)
            } else {
                val materialModel = meshPrimitiveModel.materialModel
                if (materialModel != null) {
                    val renderedMaterial = obtainMaterial(gltfRenderData, materialModel)
                    vanillaRenderCommands.add(renderedMaterial.vanillaMaterialCommand)
                    shaderModRenderCommands.add(renderedMaterial.shaderModMaterialCommand)
                    if (renderedMaterial.normalTexture != null) {
                        processMeshPrimitiveModelMikkTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand)
                    } else {
                        processMeshPrimitiveModelSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, positionsAccessorModel, normalsAccessorModel)
                    }
                } else {
                    vanillaRenderCommands.add(vanillaDefaultMaterialCommand)
                    shaderModRenderCommands.add(shaderModDefaultMaterialCommand)
                    processMeshPrimitiveModelSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, positionsAccessorModel, normalsAccessorModel)
                }
            }
        } else {
            val materialModel = meshPrimitiveModel.materialModel
            if (materialModel != null) {
                val renderedMaterial = obtainMaterial(gltfRenderData, materialModel)
                vanillaRenderCommands.add(renderedMaterial.vanillaMaterialCommand)
                shaderModRenderCommands.add(renderedMaterial.shaderModMaterialCommand)
                if (renderedMaterial.normalTexture != null) {
                    processMeshPrimitiveModelFlatNormalMikkTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand)
                } else {
                    processMeshPrimitiveModelFlatNormalSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand)
                }
            } else {
                vanillaRenderCommands.add(vanillaDefaultMaterialCommand)
                shaderModRenderCommands.add(shaderModDefaultMaterialCommand)
                processMeshPrimitiveModelFlatNormalSimpleTangent(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand)
            }
        }
        vanillaRenderCommands.addAll(renderCommand)
        shaderModRenderCommands.addAll(renderCommand)
    }
    private fun addMaterialCommands(
        gltfRenderData: MutableList<Runnable>, meshPrimitiveModel: MeshPrimitiveModel,
        vanillaRenderCommands: MutableList<Runnable>, shaderModRenderCommands: MutableList<Runnable>
    ) {
        val materialModel = meshPrimitiveModel.materialModel
        if (materialModel != null) {
            val renderedMaterial = obtainMaterial(gltfRenderData, materialModel)
            vanillaRenderCommands.add(renderedMaterial.vanillaMaterialCommand)
            shaderModRenderCommands.add(renderedMaterial.shaderModMaterialCommand)
        } else {
            vanillaRenderCommands.add(vanillaDefaultMaterialCommand)
            shaderModRenderCommands.add(shaderModDefaultMaterialCommand)
        }
    }
    private fun processMeshPrimitiveModelIncludedTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        attributes: Map<String, AccessorModel>, positionsAccessorModel: AccessorModel,
        normalsAccessorModel: AccessorModel, tangentsAccessorModel: AccessorModel
    ) {
        val glVertexArray = GL30.glGenVertexArrays()
        gltfRenderData.add(Runnable { GL30.glDeleteVertexArrays(glVertexArray) })
        GL30.glBindVertexArray(glVertexArray)
        val morphTargets = meshPrimitiveModel.targets
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, vaPosition)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "NORMAL")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, normalsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(normalsAccessorModel, vaNormal)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "TANGENT")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, tangentsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(tangentsAccessorModel, at_tangent)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel, glVertexArray, renderCommand, true)
    }
    private fun processMeshPrimitiveModelSimpleTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        attributes: Map<String, AccessorModel>, positionsAccessorModel: AccessorModel,
        normalsAccessorModel: AccessorModel
    ) {
        val glVertexArray = GL30.glGenVertexArrays()
        gltfRenderData.add(Runnable { GL30.glDeleteVertexArrays(glVertexArray) })
        GL30.glBindVertexArray(glVertexArray)
        val morphTargets = meshPrimitiveModel.targets
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, vaPosition)
        val tangentsAccessorModel = obtainTangentsAccessorModel(normalsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        val tangentTargetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createNormalTangentMorphTarget(morphTargets, normalsAccessorModel, tangentsAccessorModel, targetAccessorDatas, tangentTargetAccessorDatas)) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, normalsAccessorModel, targetAccessorDatas)
            setupVertexAttrib(normalsAccessorModel, vaNormal)
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, tangentsAccessorModel, tangentTargetAccessorDatas)
            setupVertexAttrib(tangentsAccessorModel, at_tangent)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
            setupVertexAttrib(normalsAccessorModel, vaNormal)
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
            setupVertexAttrib(tangentsAccessorModel, at_tangent)
        }
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel, glVertexArray, renderCommand, true)
    }
    private fun processMeshPrimitiveModelMikkTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>
    ) {
        val glVertexArray = GL30.glGenVertexArrays()
        gltfRenderData.add(Runnable { GL30.glDeleteVertexArrays(glVertexArray) })
        GL30.glBindVertexArray(glVertexArray)
        val unindexed = obtainUnindexed(meshPrimitiveModel)
        val attributes = unindexed.first
        val morphTargets = unindexed.second
        val positionsAccessorModel = attributes["POSITION"]!!
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, vaPosition)
        val normalsAccessorModel = attributes["NORMAL"]!!
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "NORMAL")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, normalsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(normalsAccessorModel, vaNormal)
        val texcoordsAccessorModel = attributes["TEXCOORD_0"]
        val tangentsAccessorModel = obtainTangentsAccessorModel(meshPrimitiveModel, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (texcoordsAccessorModel != null && createTangentMorphTarget(morphTargets, targetAccessorDatas, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel, "TEXCOORD_0", tangentsAccessorModel)) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, tangentsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(tangentsAccessorModel, at_tangent)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel, glVertexArray, renderCommand, true, unindexed = true)
    }
    private fun processMeshPrimitiveModelFlatNormalSimpleTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>
    ) {
        val glVertexArray = GL30.glGenVertexArrays()
        gltfRenderData.add(Runnable { GL30.glDeleteVertexArrays(glVertexArray) })
        GL30.glBindVertexArray(glVertexArray)
        val unindexed = obtainUnindexed(meshPrimitiveModel)
        val attributes = unindexed.first
        val morphTargets = unindexed.second
        val positionsAccessorModel = attributes["POSITION"]!!
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, vaPosition)
        val normalsAccessorModel = obtainNormalsAccessorModel(positionsAccessorModel)
        val tangentsAccessorModel = obtainTangentsAccessorModel(normalsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        val tangentTargetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createNormalTangentMorphTarget(morphTargets, normalsAccessorModel, tangentsAccessorModel, targetAccessorDatas, tangentTargetAccessorDatas)) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, normalsAccessorModel, targetAccessorDatas)
            setupVertexAttrib(normalsAccessorModel, vaNormal)
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, tangentsAccessorModel, tangentTargetAccessorDatas)
            setupVertexAttrib(tangentsAccessorModel, at_tangent)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
            setupVertexAttrib(normalsAccessorModel, vaNormal)
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
            setupVertexAttrib(tangentsAccessorModel, at_tangent)
        }
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel, glVertexArray, renderCommand, true, unindexed = true)
    }
    private fun processMeshPrimitiveModelFlatNormalMikkTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>
    ) {
        val glVertexArray = GL30.glGenVertexArrays()
        gltfRenderData.add(Runnable { GL30.glDeleteVertexArrays(glVertexArray) })
        GL30.glBindVertexArray(glVertexArray)
        val unindexed = obtainUnindexed(meshPrimitiveModel)
        val attributes = unindexed.first
        val morphTargets = unindexed.second
        val positionsAccessorModel = attributes["POSITION"]!!
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, positionsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(positionsAccessorModel, vaPosition)
        val normalsAccessorModel = obtainNormalsAccessorModel(positionsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (createMorphTarget(morphTargets, targetAccessorDatas, "NORMAL")) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, normalsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, normalsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(normalsAccessorModel, vaNormal)
        val texcoordsAccessorModel = attributes["TEXCOORD_0"]
        val tangentsAccessorModel = obtainTangentsAccessorModel(meshPrimitiveModel, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        if (texcoordsAccessorModel != null && createTangentMorphTarget(morphTargets, targetAccessorDatas, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel, "TEXCOORD_0", tangentsAccessorModel)) {
            bindVec3FloatMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, tangentsAccessorModel, targetAccessorDatas)
        } else {
            bindArrayBufferViewModel(gltfRenderData, tangentsAccessorModel.bufferViewModel)
        }
        setupVertexAttrib(tangentsAccessorModel, at_tangent)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addDrawCommand(gltfRenderData, meshPrimitiveModel, positionsAccessorModel, glVertexArray, renderCommand, true, unindexed = true)
    }
    private fun setupVertexAttrib(accessorModel: AccessorModel, attribIndex: Int) {
        GL20.glVertexAttribPointer(
            attribIndex,
            accessorModel.elementType.numComponents,
            accessorModel.componentType,
            false,
            accessorModel.byteStride,
            accessorModel.byteOffset.toLong()
        )
        GL20.glEnableVertexAttribArray(attribIndex)
    }
    private fun bindColorAndTexcoord(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        attributes: Map<String, AccessorModel>, morphTargets: List<Map<String, AccessorModel>>
    ) {
        var colorsAccessorModel = attributes["COLOR_0"]
        if (colorsAccessorModel != null) {
            colorsAccessorModel = obtainVec4ColorsAccessorModel(colorsAccessorModel)
            var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
            if (createColorMorphTarget(morphTargets, targetAccessorDatas, "COLOR_0")) {
                colorsAccessorModel = bindColorMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, colorsAccessorModel, targetAccessorDatas)
            } else {
                bindArrayBufferViewModel(gltfRenderData, colorsAccessorModel.bufferViewModel)
            }
            setupVertexAttrib(colorsAccessorModel, vaColor)
        }
        var texcoordsAccessorModel = attributes["TEXCOORD_0"]
        if (texcoordsAccessorModel != null) {
            var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
            if (createTexcoordMorphTarget(morphTargets, targetAccessorDatas, "TEXCOORD_0")) {
                texcoordsAccessorModel = bindTexcoordMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, texcoordsAccessorModel, targetAccessorDatas)
            } else {
                bindArrayBufferViewModel(gltfRenderData, texcoordsAccessorModel.bufferViewModel)
            }
            setupVertexAttrib(texcoordsAccessorModel, vaUV0)
            var midTexcoordModel = attributes["TEXCOORD_1"]
            if (midTexcoordModel != null) {
                targetAccessorDatas = ArrayList(morphTargets.size)
                if (createTexcoordMorphTarget(morphTargets, targetAccessorDatas, "TEXCOORD_1")) {
                    midTexcoordModel = bindTexcoordMorphed(gltfRenderData, nodeModel, meshModel, renderCommand, midTexcoordModel, targetAccessorDatas)
                } else {
                    bindArrayBufferViewModel(gltfRenderData, midTexcoordModel.bufferViewModel)
                }
                texcoordsAccessorModel = midTexcoordModel
            }
            setupVertexAttrib(texcoordsAccessorModel, mc_midTexCoord)
        }
    }
    private fun addDrawCommand(
        gltfRenderData: MutableList<Runnable>, meshPrimitiveModel: MeshPrimitiveModel,
        positionsAccessorModel: AccessorModel, glVertexArray: Int,
        renderCommand: MutableList<Runnable>, hasTangent: Boolean, unindexed: Boolean = false
    ) {
        val mode = meshPrimitiveModel.mode
        val indices = if (unindexed) null else meshPrimitiveModel.indices
        if (indices != null) {
            val glIndicesBufferView = obtainElementArrayBuffer(gltfRenderData, indices.bufferViewModel)
            val count = indices.count
            val type = indices.componentType
            val offset = indices.byteOffset
            renderCommand.add(Runnable {
                try {
                    GL30.glBindVertexArray(glVertexArray)
                    GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, glIndicesBufferView)
                    GL11.glDrawElements(mode, count, type, offset.toLong())
                } finally {
                    if (hasTangent) GL20.glDisableVertexAttribArray(at_tangent)
                }
            })
        } else {
            val count = positionsAccessorModel.count
            renderCommand.add(Runnable {
                try {
                    GL30.glBindVertexArray(glVertexArray)
                    GL11.glDrawArrays(mode, 0, count)
                } finally {
                    if (hasTangent) GL20.glDisableVertexAttribArray(at_tangent)
                }
            })
        }
    }
    fun bindArrayBufferViewModel(gltfRenderData: MutableList<Runnable>, bufferViewModel: BufferViewModel) {
        val glBufferView = bufferViewModelToGlBufferView.getOrPut(bufferViewModel) {
            val glBuffer = GL15.glGenBuffers()
            gltfRenderData.add(Runnable { GL15.glDeleteBuffers(glBuffer) })
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, bufferViewModel.bufferViewData, GL15.GL_STATIC_DRAW)
            glBuffer
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBufferView)
    }
    fun obtainElementArrayBuffer(gltfRenderData: MutableList<Runnable>, bufferViewModel: BufferViewModel): Int {
        return bufferViewModelToGlBufferView.getOrPut(bufferViewModel) {
            val glBuffer = GL15.glGenBuffers()
            gltfRenderData.add(Runnable { GL15.glDeleteBuffers(glBuffer) })
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, glBuffer)
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, bufferViewModel.bufferViewData, GL15.GL_STATIC_DRAW)
            glBuffer
        }
    }
    fun obtainGlTexture(gltfRenderData: MutableList<Runnable>, textureModel: TextureModel): Int {
        return textureModelToGlTexture.getOrPut(textureModel) {
            val pixelData = PixelDatas.create(textureModel.imageModel.imageData)
            val glTexture = GL11.glGenTextures()
            gltfRenderData.add(Runnable { GL11.glDeleteTextures(glTexture) })
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                pixelData.width, pixelData.height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                pixelData.pixelsRGBA
            )
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT)
            val magFilter = textureModel.magFilter ?: GL11.GL_LINEAR
            val minFilter = textureModel.minFilter ?: GL11.GL_LINEAR_MIPMAP_LINEAR
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter)
            glTexture
        }
    }
    fun obtainMaterial(gltfRenderData: MutableList<Runnable>, materialModel: MaterialModel): Material {
        return materialModelToRenderedMaterial.getOrPut(materialModel) {
            val material = Material()
            if (materialModel is MaterialModelV2) {
                val baseColorTexture = materialModel.baseColorTexture
                val baseColorFactor = materialModel.baseColorFactor
                val isDoubleSided = materialModel.isDoubleSided
                val normalTextureModel = materialModel.normalTexture
                material.normalTexture = normalTextureModel
                material.vanillaMaterialCommand = Runnable {
                    if (baseColorTexture != null) {
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, obtainGlTexture(gltfRenderData, baseColorTexture))
                    } else {
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTFSystem.defaultColorMap)
                    }
                    GL20.glVertexAttrib4f(vaColor, baseColorFactor[0], baseColorFactor[1], baseColorFactor[2], baseColorFactor[3])
                    if (isDoubleSided) GL11.glDisable(GL11.GL_CULL_FACE) else GL11.glEnable(GL11.GL_CULL_FACE)
                }
                material.shaderModMaterialCommand = Runnable {
                    GL13.glActiveTexture(COLOR_MAP_INDEX)
                    if (baseColorTexture != null) {
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, obtainGlTexture(gltfRenderData, baseColorTexture))
                    } else {
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTFSystem.defaultColorMap)
                    }
                    GL13.glActiveTexture(NORMAL_MAP_INDEX)
                    if (normalTextureModel != null) {
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, obtainGlTexture(gltfRenderData, normalTextureModel))
                    } else {
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTFSystem.defaultNormalMap)
                    }
                    GL13.glActiveTexture(SPECULAR_MAP_INDEX)
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTFSystem.defaultSpecularMap)
                    GL20.glVertexAttrib4f(vaColor, baseColorFactor[0], baseColorFactor[1], baseColorFactor[2], baseColorFactor[3])
                    val currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
                    val entityColorLocation = GL20.glGetUniformLocation(currentProgram, "entityColor")
                    if (entityColorLocation != -1) GL20.glUniform4f(entityColorLocation, 1.0f, 1.0f, 1.0f, 0.0f)
                    if (materialModel.alphaMode == MaterialModelV2.AlphaMode.BLEND) GL11.glEnable(GL11.GL_BLEND) else GL11.glDisable(GL11.GL_BLEND)
                    if (isDoubleSided) GL11.glDisable(GL11.GL_CULL_FACE) else GL11.glEnable(GL11.GL_CULL_FACE)
                }
            }
            material
        }
    }
    fun obtainNormalsAccessorModel(positionsAccessorModel: AccessorModel): AccessorModel {
        return positionsAccessorModelToNormalsAccessorModel.getOrPut(positionsAccessorModel) {
            val count = positionsAccessorModel.count
            val normalsModel = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC3, "normals")
            val positionsData = AccessorDatas.create(positionsAccessorModel) as AccessorFloatData
            val normalsData = normalsModel.accessorData as AccessorFloatData
            val triangleCount = count / 3
            for (i in 0 until triangleCount) {
                val i0 = i * 3; val i1 = i0 + 1; val i2 = i0 + 2
                val p0x = positionsData.get(i0, 0); val p0y = positionsData.get(i0, 1); val p0z = positionsData.get(i0, 2)
                val p1x = positionsData.get(i1, 0); val p1y = positionsData.get(i1, 1); val p1z = positionsData.get(i1, 2)
                val p2x = positionsData.get(i2, 0); val p2y = positionsData.get(i2, 1); val p2z = positionsData.get(i2, 2)
                val e1x = p1x - p0x; val e1y = p1y - p0y; val e1z = p1z - p0z
                val e2x = p2x - p0x; val e2y = p2y - p0y; val e2z = p2z - p0z
                var nx = e1y * e2z - e1z * e2y
                var ny = e1z * e2x - e1x * e2z
                var nz = e1x * e2y - e1y * e2x
                val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
                if (len > 0f) { nx /= len; ny /= len; nz /= len }
                for (v in i0..i2) {
                    normalsData.set(v, 0, nx); normalsData.set(v, 1, ny); normalsData.set(v, 2, nz)
                }
            }
            normalsModel
        }
    }
    fun obtainTangentsAccessorModel(normalsAccessorModel: AccessorModel): AccessorModel {
        return normalsAccessorModelToTangentsAccessorModel.getOrPut(normalsAccessorModel) {
            val count = normalsAccessorModel.count
            val tangentsModel = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC4, "tangents")
            val normalsData = AccessorDatas.create(normalsAccessorModel) as AccessorFloatData
            val tangentsData = tangentsModel.accessorData as AccessorFloatData
            for (i in 0 until count) {
                val nx = normalsData.get(i, 0); val ny = normalsData.get(i, 1); val nz = normalsData.get(i, 2)
                var tx: Float; var ty: Float; var tz: Float
                if (Math.abs(nx) < 0.9f) {
                    tx = 0f; ty = -nz; tz = ny
                } else {
                    tx = nz; ty = 0f; tz = -nx
                }
                val len = Math.sqrt((tx * tx + ty * ty + tz * tz).toDouble()).toFloat()
                if (len > 0f) { tx /= len; ty /= len; tz /= len }
                tangentsData.set(i, 0, tx); tangentsData.set(i, 1, ty); tangentsData.set(i, 2, tz); tangentsData.set(i, 3, 1.0f)
            }
            tangentsModel
        }
    }
    fun obtainTangentsAccessorModel(
        meshPrimitiveModel: MeshPrimitiveModel,
        positionsAccessorModel: AccessorModel,
        normalsAccessorModel: AccessorModel,
        texcoordsAccessorModel: AccessorModel?
    ): AccessorModel {
        return meshPrimitiveModelToTangentsAccessorModel.getOrPut(meshPrimitiveModel) {
            if (texcoordsAccessorModel == null) return@getOrPut obtainTangentsAccessorModel(normalsAccessorModel)
            val count = positionsAccessorModel.count
            val tangentsModel = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC4, "mikkTangents")
            val positionsData = AccessorDatas.create(positionsAccessorModel) as AccessorFloatData
            val normalsData = AccessorDatas.create(normalsAccessorModel) as AccessorFloatData
            val texcoordsData = AccessorDatas.create(texcoordsAccessorModel) as AccessorFloatData
            val tangentsData = tangentsModel.accessorData as AccessorFloatData
            val numFaces = count / 3
            val ctx = object : MikkTSpaceContext {
                override fun getNumFaces(): Int = numFaces
                override fun getNumVerticesOfFace(face: Int): Int = 3
                override fun getPosition(posOut: FloatArray, face: Int, vert: Int) {
                    val idx = face * 3 + vert
                    posOut[0] = positionsData.get(idx, 0); posOut[1] = positionsData.get(idx, 1); posOut[2] = positionsData.get(idx, 2)
                }
                override fun getNormal(normOut: FloatArray, face: Int, vert: Int) {
                    val idx = face * 3 + vert
                    normOut[0] = normalsData.get(idx, 0); normOut[1] = normalsData.get(idx, 1); normOut[2] = normalsData.get(idx, 2)
                }
                override fun getTexCoord(texOut: FloatArray, face: Int, vert: Int) {
                    val idx = face * 3 + vert
                    texOut[0] = texcoordsData.get(idx, 0); texOut[1] = texcoordsData.get(idx, 1)
                }
                override fun setTSpaceBasic(tangent: FloatArray, sign: Float, face: Int, vert: Int) {
                    val idx = face * 3 + vert
                    tangentsData.set(idx, 0, tangent[0]); tangentsData.set(idx, 1, tangent[1]); tangentsData.set(idx, 2, tangent[2]); tangentsData.set(idx, 3, sign)
                }
                override fun setTSpace(tangent: FloatArray, biTangent: FloatArray, magS: Float, magT: Float, isOrientationPreserving: Boolean, face: Int, vert: Int) {}
            }
            MikktspaceTangentGenerator.genTangSpaceDefault(ctx)
            tangentsModel
        }
    }
    fun obtainVec4ColorsAccessorModel(colorsAccessorModel: AccessorModel): AccessorModel {
        if (colorsAccessorModel.elementType == ElementType.VEC4) return colorsAccessorModel
        return colorsAccessorModelVec3ToVec4.getOrPut(colorsAccessorModel) {
            val count = colorsAccessorModel.count
            val vec4Model = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC4, "colorsVec4")
            val srcData = AccessorDatas.create(colorsAccessorModel) as AccessorFloatData
            val dstData = vec4Model.accessorData as AccessorFloatData
            for (i in 0 until count) {
                dstData.set(i, 0, srcData.get(i, 0))
                dstData.set(i, 1, srcData.get(i, 1))
                dstData.set(i, 2, srcData.get(i, 2))
                dstData.set(i, 3, 1.0f)
            }
            vec4Model
        }
    }
    fun obtainUnindexed(meshPrimitiveModel: MeshPrimitiveModel): Pair<Map<String, AccessorModel>, List<Map<String, AccessorModel>>> {
        return meshPrimitiveModelToUnindexed.getOrPut(meshPrimitiveModel) {
            val indices = meshPrimitiveModel.indices
            val srcAttributes = meshPrimitiveModel.attributes
            val srcMorphTargets = meshPrimitiveModel.targets
            if (indices == null) return@getOrPut Pair(srcAttributes, srcMorphTargets)
            val indexData = AccessorDataUtils.readInts(AccessorDatas.create(indices))
            val count = indexData.size
            val newAttributes = LinkedHashMap<String, AccessorModel>()
            for ((name, srcAccessor) in srcAttributes) {
                val elementType = srcAccessor.elementType
                val newAccessor = createAccessorModel(GL11.GL_FLOAT, count, elementType, "unindexed_$name")
                val srcData = AccessorDatas.create(srcAccessor) as AccessorFloatData
                val dstData = newAccessor.accessorData as AccessorFloatData
                val numComponents = elementType.numComponents
                for (i in indexData.indices) {
                    val srcIdx = indexData[i]
                    for (c in 0 until numComponents) {
                        dstData.set(i, c, srcData.get(srcIdx, c))
                    }
                }
                newAttributes[name] = newAccessor
            }
            val newMorphTargets = srcMorphTargets.map { morphTarget ->
                val newMorphTarget = LinkedHashMap<String, AccessorModel>()
                for ((name, srcAccessor) in morphTarget) {
                    val elementType = srcAccessor.elementType
                    val newAccessor = createAccessorModel(GL11.GL_FLOAT, count, elementType, "unindexed_morph_$name")
                    val srcData = AccessorDatas.create(srcAccessor) as AccessorFloatData
                    val dstData = newAccessor.accessorData as AccessorFloatData
                    val numComponents = elementType.numComponents
                    for (i in indexData.indices) {
                        val srcIdx = indexData[i]
                        for (c in 0 until numComponents) {
                            dstData.set(i, c, srcData.get(srcIdx, c))
                        }
                    }
                    newMorphTarget[name] = newAccessor
                }
                newMorphTarget as Map<String, AccessorModel>
            }
            Pair(newAttributes as Map<String, AccessorModel>, newMorphTargets)
        }
    }
    fun createMorphTarget(morphTargets: List<Map<String, AccessorModel>>, targetAccessorDatas: MutableList<AccessorFloatData>, attribute: String): Boolean {
        var found = false
        for (morphTarget in morphTargets) {
            val accessor = morphTarget[attribute]
            if (accessor != null) {
                targetAccessorDatas.add(AccessorDatas.create(accessor) as AccessorFloatData)
                found = true
            }
        }
        return found
    }
    fun createColorMorphTarget(morphTargets: List<Map<String, AccessorModel>>, targetAccessorDatas: MutableList<AccessorFloatData>, attribute: String): Boolean {
        var found = false
        for (morphTarget in morphTargets) {
            val accessor = morphTarget[attribute]
            if (accessor != null) {
                val data = colorsMorphTargetAccessorModelToAccessorData.getOrPut(accessor) {
                    AccessorDatas.create(accessor) as AccessorFloatData
                }
                targetAccessorDatas.add(data)
                found = true
            }
        }
        return found
    }
    fun createTexcoordMorphTarget(morphTargets: List<Map<String, AccessorModel>>, targetAccessorDatas: MutableList<AccessorFloatData>, attribute: String): Boolean {
        var found = false
        for (morphTarget in morphTargets) {
            val accessor = morphTarget[attribute]
            if (accessor != null) {
                val data = texcoordsMorphTargetAccessorModelToAccessorData.getOrPut(accessor) {
                    AccessorDatas.create(accessor) as AccessorFloatData
                }
                targetAccessorDatas.add(data)
                found = true
            }
        }
        return found
    }
    fun createNormalTangentMorphTarget(
        morphTargets: List<Map<String, AccessorModel>>,
        normalsAccessorModel: AccessorModel, tangentsAccessorModel: AccessorModel,
        normalTargetDatas: MutableList<AccessorFloatData>, tangentTargetDatas: MutableList<AccessorFloatData>
    ): Boolean {
        var found = false
        for (morphTarget in morphTargets) {
            val normalAccessor = morphTarget["NORMAL"]
            if (normalAccessor != null) {
                normalTargetDatas.add(AccessorDatas.create(normalAccessor) as AccessorFloatData)
                val tangentData = createAccessorModel(GL11.GL_FLOAT, normalAccessor.count, ElementType.VEC4, "morphTangent")
                val normData = AccessorDatas.create(normalAccessor) as AccessorFloatData
                val tangData = tangentData.accessorData as AccessorFloatData
                for (i in 0 until normalAccessor.count) {
                    val nx = normData.get(i, 0); val ny = normData.get(i, 1); val nz = normData.get(i, 2)
                    var tx: Float; var ty: Float; var tz: Float
                    if (Math.abs(nx) < 0.9f) { tx = 0f; ty = -nz; tz = ny } else { tx = nz; ty = 0f; tz = -nx }
                    val len = Math.sqrt((tx * tx + ty * ty + tz * tz).toDouble()).toFloat()
                    if (len > 0f) { tx /= len; ty /= len; tz /= len }
                    tangData.set(i, 0, tx); tangData.set(i, 1, ty); tangData.set(i, 2, tz); tangData.set(i, 3, 0f)
                }
                tangentTargetDatas.add(tangData)
                found = true
            }
        }
        return found
    }
    fun createTangentMorphTarget(
        morphTargets: List<Map<String, AccessorModel>>,
        tangentTargetDatas: MutableList<AccessorFloatData>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel,
        texcoordsAccessorModel: AccessorModel, texcoordAttribute: String,
        tangentsAccessorModel: AccessorModel
    ): Boolean {
        var found = false
        for (morphTarget in morphTargets) {
            val posAccessor = morphTarget["POSITION"]
            val normAccessor = morphTarget["NORMAL"]
            if (posAccessor != null || normAccessor != null) {
                val count = positionsAccessorModel.count
                val tangentData = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC4, "morphMikkTangent")
                tangentTargetDatas.add(tangentData.accessorData as AccessorFloatData)
                found = true
            }
        }
        return found
    }
    fun bindVec3FloatMorphed(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        renderCommand: MutableList<Runnable>, baseAccessorModel: AccessorModel,
        targetAccessorDatas: List<AccessorFloatData>
    ) {
        val count = baseAccessorModel.count
        val numComponents = baseAccessorModel.elementType.numComponents
        val baseData = AccessorDatas.create(baseAccessorModel) as AccessorFloatData
        val morphedModel = createAccessorModel(GL11.GL_FLOAT, count, baseAccessorModel.elementType, "morphed")
        val morphedData = morphedModel.accessorData as AccessorFloatData
        val glBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(glBuffer) })
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, morphedModel.bufferViewModel.byteLength.toLong(), GL15.GL_STATIC_DRAW)
        renderCommand.add(Runnable {
            val weights = Optionals.of(meshModel.weights, nodeModel.weights)
            for (i in 0 until count) {
                for (c in 0 until numComponents) {
                    var value = baseData.get(i, c)
                    for (t in targetAccessorDatas.indices) {
                        if (t < weights.size) {
                            value += weights[t] * targetAccessorDatas[t].get(i, c)
                        }
                    }
                    morphedData.set(i, c, value)
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, morphedModel.bufferViewModel.bufferViewData)
        })
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
    }
    fun bindColorMorphed(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        renderCommand: MutableList<Runnable>, baseAccessorModel: AccessorModel,
        targetAccessorDatas: List<AccessorFloatData>
    ): AccessorModel {
        val count = baseAccessorModel.count
        val baseData = AccessorDatas.create(baseAccessorModel) as AccessorFloatData
        val morphedModel = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC4, "colorMorphed")
        val morphedData = morphedModel.accessorData as AccessorFloatData
        val glBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(glBuffer) })
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, morphedModel.bufferViewModel.byteLength.toLong(), GL15.GL_STATIC_DRAW)
        renderCommand.add(Runnable {
            val weights = Optionals.of(meshModel.weights, nodeModel.weights)
            val srcComponents = baseData.numComponentsPerElement
            for (i in 0 until count) {
                for (c in 0 until 4) {
                    var value = if (c < srcComponents) baseData.get(i, c) else 1.0f
                    for (t in targetAccessorDatas.indices) {
                        if (t < weights.size && c < targetAccessorDatas[t].numComponentsPerElement) {
                            value += weights[t] * targetAccessorDatas[t].get(i, c)
                        }
                    }
                    morphedData.set(i, c, value.coerceIn(0.0f, 1.0f))
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, morphedModel.bufferViewModel.bufferViewData)
        })
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
        return morphedModel
    }
    fun bindTexcoordMorphed(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        renderCommand: MutableList<Runnable>, baseAccessorModel: AccessorModel,
        targetAccessorDatas: List<AccessorFloatData>
    ): AccessorModel {
        val count = baseAccessorModel.count
        val numComponents = baseAccessorModel.elementType.numComponents
        val baseData = AccessorDatas.create(baseAccessorModel) as AccessorFloatData
        val morphedModel = createAccessorModel(GL11.GL_FLOAT, count, baseAccessorModel.elementType, "texcoordMorphed")
        val morphedData = morphedModel.accessorData as AccessorFloatData
        val glBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(glBuffer) })
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, morphedModel.bufferViewModel.byteLength.toLong(), GL15.GL_STATIC_DRAW)
        renderCommand.add(Runnable {
            val weights = Optionals.of(meshModel.weights, nodeModel.weights)
            for (i in 0 until count) {
                for (c in 0 until numComponents) {
                    var value = baseData.get(i, c)
                    for (t in targetAccessorDatas.indices) {
                        if (t < weights.size) {
                            value += weights[t] * targetAccessorDatas[t].get(i, c)
                        }
                    }
                    morphedData.set(i, c, value)
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, morphedModel.bufferViewModel.bufferViewData)
        })
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
        return morphedModel
    }
    fun obtainUnsignedJointsModel(accessorModel: AccessorModel): AccessorModel {
        return jointsAccessorModelUnsignedLookup.getOrPut(accessorModel) {
            val count = accessorModel.count
            val unsignedModel = createAccessorModel(GL11.GL_INT, count, ElementType.VEC4, "unsignedJoints")
            val unsignedData = AccessorDatas.create(unsignedModel) as AccessorIntData
            if (accessorModel.componentDataType == Short::class.javaPrimitiveType) {
                val srcData = AccessorDatas.create(accessorModel) as AccessorShortData
                for (i in 0 until count) {
                    for (c in 0 until 4) unsignedData.set(i, c, java.lang.Short.toUnsignedInt(srcData.get(i, c)))
                }
            } else {
                val srcData = AccessorDatas.create(accessorModel) as AccessorByteData
                for (i in 0 until count) {
                    for (c in 0 until 4) unsignedData.set(i, c, java.lang.Byte.toUnsignedInt(srcData.get(i, c)))
                }
            }
            unsignedModel
        }
    }
    fun obtainDequantizedWeightsModel(accessorModel: AccessorModel): AccessorModel {
        if (accessorModel.componentDataType == Float::class.javaPrimitiveType) return accessorModel
        return weightsAccessorModelDequantizedLookup.getOrPut(accessorModel) {
            val count = accessorModel.count
            val dequantizedModel = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC4, "dequantizedWeights")
            val dstData = AccessorDatas.createFloat(dequantizedModel)
            if (accessorModel.componentDataType == Short::class.javaPrimitiveType) {
                val srcData = AccessorDatas.create(accessorModel) as AccessorShortData
                for (i in 0 until count) {
                    for (c in 0 until 4) dstData.set(i, c, java.lang.Short.toUnsignedInt(srcData.get(i, c)) / 65535.0f)
                }
            } else {
                val srcData = AccessorDatas.create(accessorModel) as AccessorByteData
                for (i in 0 until count) {
                    for (c in 0 until 4) dstData.set(i, c, java.lang.Byte.toUnsignedInt(srcData.get(i, c)) / 255.0f)
                }
            }
            dequantizedModel
        }
    }
    fun obtainVec3FloatMorphedModel(
        nodeModel: NodeModel, meshModel: MeshModel, command: MutableList<Runnable>,
        baseAccessorModel: AccessorModel, targetAccessorDatas: List<AccessorFloatData?>
    ): AccessorModel {
        val morphedModel = AccessorModelCreation.instantiate(baseAccessorModel, "")
        val baseData = AccessorDatas.createFloat(baseAccessorModel)
        val morphedData = AccessorDatas.createFloat(morphedModel)
        val weights = FloatArray(targetAccessorDatas.size)
        val numElements = morphedData.numElements
        val morphingCommands = ArrayList<Runnable>(numElements * 3)
        for (e in 0 until numElements) {
            for (c in 0 until 3) {
                morphingCommands.add(Runnable {
                    var r = baseData.get(e, c)
                    for (i in weights.indices) {
                        val target = targetAccessorDatas[i]
                        if (target != null) r += weights[i] * target.get(e, c)
                    }
                    morphedData.set(e, c, r)
                })
            }
        }
        command.add(Runnable {
            val nodeWeights = nodeModel.weights
            val meshWeights = meshModel.weights
            if (nodeWeights != null) System.arraycopy(nodeWeights, 0, weights, 0, weights.size)
            else if (meshWeights != null) System.arraycopy(meshWeights, 0, weights, 0, weights.size)
            morphingCommands.parallelStream().forEach(Runnable::run)
        })
        return morphedModel
    }
    fun createSoftwareSkinningCommands(
        pointCount: Int, jointMatrices: Array<FloatArray>, attributes: Map<String, AccessorModel>,
        inputPositions: AccessorFloatData, inputNormals: AccessorFloatData, inputTangents: AccessorFloatData,
        outputPositions: AccessorFloatData, outputNormals: AccessorFloatData, outputTangents: AccessorFloatData
    ): List<Runnable> {
        var skinningAttributeCount = 0
        for (name in attributes.keys) { if (name.startsWith("JOINTS_")) skinningAttributeCount++ }
        val jointsAccessorDatas = arrayOfNulls<AccessorIntData>(skinningAttributeCount)
        val weightsAccessorDatas = arrayOfNulls<AccessorFloatData>(skinningAttributeCount)
        attributes.forEach { (name, attribute) ->
            if (name.startsWith("JOINTS_")) jointsAccessorDatas[name.substring("JOINTS_".length).toInt()] = AccessorDatas.create(obtainUnsignedJointsModel(attribute)) as AccessorIntData
            else if (name.startsWith("WEIGHTS_")) weightsAccessorDatas[name.substring("WEIGHTS_".length).toInt()] = AccessorDatas.createFloat(obtainDequantizedWeightsModel(attribute))
        }
        return (0 until pointCount).map { p ->
            Runnable {
                var sm00 = 0f; var sm01 = 0f; var sm02 = 0f; var sm03 = 0f
                var sm10 = 0f; var sm11 = 0f; var sm12 = 0f; var sm13 = 0f
                var sm20 = 0f; var sm21 = 0f; var sm22 = 0f; var sm23 = 0f
                for (i in jointsAccessorDatas.indices) {
                    val jd = jointsAccessorDatas[i]!!
                    val jmx = jointMatrices[jd.get(p, 0)]
                    val jmy = jointMatrices[jd.get(p, 1)]
                    val jmz = jointMatrices[jd.get(p, 2)]
                    val jmw = jointMatrices[jd.get(p, 3)]
                    val wd = weightsAccessorDatas[i]!!
                    val wx = wd.get(p, 0); val wy = wd.get(p, 1); val wz = wd.get(p, 2); val ww = wd.get(p, 3)
                    sm00 += wx * jmx[0] + wy * jmy[0] + wz * jmz[0] + ww * jmw[0]
                    sm01 += wx * jmx[4] + wy * jmy[4] + wz * jmz[4] + ww * jmw[4]
                    sm02 += wx * jmx[8] + wy * jmy[8] + wz * jmz[8] + ww * jmw[8]
                    sm03 += wx * jmx[12] + wy * jmy[12] + wz * jmz[12] + ww * jmw[12]
                    sm10 += wx * jmx[1] + wy * jmy[1] + wz * jmz[1] + ww * jmw[1]
                    sm11 += wx * jmx[5] + wy * jmy[5] + wz * jmz[5] + ww * jmw[5]
                    sm12 += wx * jmx[9] + wy * jmy[9] + wz * jmz[9] + ww * jmw[9]
                    sm13 += wx * jmx[13] + wy * jmy[13] + wz * jmz[13] + ww * jmw[13]
                    sm20 += wx * jmx[2] + wy * jmy[2] + wz * jmz[2] + ww * jmw[2]
                    sm21 += wx * jmx[6] + wy * jmy[6] + wz * jmz[6] + ww * jmw[6]
                    sm22 += wx * jmx[10] + wy * jmy[10] + wz * jmz[10] + ww * jmw[10]
                    sm23 += wx * jmx[14] + wy * jmy[14] + wz * jmz[14] + ww * jmw[14]
                }
                val px = inputPositions.get(p, 0); val py = inputPositions.get(p, 1); val pz = inputPositions.get(p, 2)
                outputPositions.set(p, 0, sm00 * px + sm01 * py + sm02 * pz + sm03)
                outputPositions.set(p, 1, sm10 * px + sm11 * py + sm12 * pz + sm13)
                outputPositions.set(p, 2, sm20 * px + sm21 * py + sm22 * pz + sm23)
                val nx = inputNormals.get(p, 0); val ny = inputNormals.get(p, 1); val nz = inputNormals.get(p, 2)
                outputNormals.set(p, 0, sm00 * nx + sm01 * ny + sm02 * nz)
                outputNormals.set(p, 1, sm10 * nx + sm11 * ny + sm12 * nz)
                outputNormals.set(p, 2, sm20 * nx + sm21 * ny + sm22 * nz)
                val tx = inputTangents.get(p, 0); val ty = inputTangents.get(p, 1); val tz = inputTangents.get(p, 2)
                outputTangents.set(p, 0, sm00 * tx + sm01 * ty + sm02 * tz)
                outputTangents.set(p, 1, sm10 * tx + sm11 * ty + sm12 * tz)
                outputTangents.set(p, 2, sm20 * tx + sm21 * ty + sm22 * tz)
            }
        }
    }
    private data class CpuSkinningBuffers(val glVertexArray: Int, val positionBuffer: Int, val normalBuffer: Int, val tangentBuffer: Int)
    private fun setupCpuSkinningBuffers(
        gltfRenderData: MutableList<Runnable>,
        outputPositions: AccessorModel, outputNormals: AccessorModel, outputTangents: AccessorModel
    ): CpuSkinningBuffers {
        val glVertexArray = GL30.glGenVertexArrays()
        gltfRenderData.add(Runnable { GL30.glDeleteVertexArrays(glVertexArray) })
        GL30.glBindVertexArray(glVertexArray)
        val positionBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(positionBuffer) })
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionBuffer)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, outputPositions.bufferViewModel.byteLength.toLong(), GL15.GL_STATIC_DRAW)
        setupVertexAttrib(outputPositions, vaPosition)
        val normalBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(normalBuffer) })
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, normalBuffer)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, outputNormals.bufferViewModel.byteLength.toLong(), GL15.GL_STATIC_DRAW)
        setupVertexAttrib(outputNormals, vaNormal)
        val tangentBuffer = GL15.glGenBuffers()
        gltfRenderData.add(Runnable { GL15.glDeleteBuffers(tangentBuffer) })
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, tangentBuffer)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, outputTangents.bufferViewModel.byteLength.toLong(), GL15.GL_STATIC_DRAW)
        setupVertexAttrib(outputTangents, at_tangent)
        return CpuSkinningBuffers(glVertexArray, positionBuffer, normalBuffer, tangentBuffer)
    }
    private fun addCpuSkinningDrawCommand(
        gltfRenderData: MutableList<Runnable>, meshPrimitiveModel: MeshPrimitiveModel,
        outputPositions: AccessorModel, outputNormals: AccessorModel, outputTangents: AccessorModel,
        buffers: CpuSkinningBuffers, skinningCommands: List<Runnable>, renderCommand: MutableList<Runnable>
    ) {
        val positionsBufferViewData = outputPositions.bufferViewModel.bufferViewData
        val normalsBufferViewData = outputNormals.bufferViewModel.bufferViewData
        val tangentsBufferViewData = outputTangents.bufferViewModel.bufferViewData
        val glVertexArray = buffers.glVertexArray
        val positionBuffer = buffers.positionBuffer
        val normalBuffer = buffers.normalBuffer
        val tangentBuffer = buffers.tangentBuffer
        val mode = meshPrimitiveModel.mode
        val indices = meshPrimitiveModel.indices
        if (indices != null) {
            val glIndicesBufferView = obtainElementArrayBuffer(gltfRenderData, indices.bufferViewModel)
            val count = indices.count
            val type = indices.componentType
            val offset = indices.byteOffset
            renderCommand.add(Runnable {
                skinningCommands.parallelStream().forEach(Runnable::run)
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionBuffer)
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, positionsBufferViewData)
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, normalBuffer)
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, normalsBufferViewData)
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, tangentBuffer)
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, tangentsBufferViewData)
                GL30.glBindVertexArray(glVertexArray)
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, glIndicesBufferView)
                GL11.glDrawElements(mode, count, type, offset.toLong())
            })
        } else {
            val pointCount = outputPositions.count
            renderCommand.add(Runnable {
                skinningCommands.parallelStream().forEach(Runnable::run)
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionBuffer)
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, positionsBufferViewData)
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, normalBuffer)
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, normalsBufferViewData)
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, tangentBuffer)
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, tangentsBufferViewData)
                GL30.glBindVertexArray(glVertexArray)
                GL11.glDrawArrays(mode, 0, pointCount)
            })
        }
    }
    private fun processCpuSkinnedIncludedTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        jointMatrices: Array<FloatArray>, attributes: Map<String, AccessorModel>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel, tangentsAccessorModel: AccessorModel
    ) {
        val morphTargets = meshPrimitiveModel.targets
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        val outputPositions = if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION"))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(positionsAccessorModel, "")
        targetAccessorDatas = ArrayList(morphTargets.size)
        val outputNormals = if (createMorphTarget(morphTargets, targetAccessorDatas, "NORMAL"))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, normalsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(normalsAccessorModel, "")
        targetAccessorDatas = ArrayList(morphTargets.size)
        val outputTangents = if (createMorphTarget(morphTargets, targetAccessorDatas, "TANGENT"))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, tangentsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(tangentsAccessorModel, "")
        val skinningCommands = createSoftwareSkinningCommands(positionsAccessorModel.count, jointMatrices, attributes,
            AccessorDatas.createFloat(positionsAccessorModel), AccessorDatas.createFloat(normalsAccessorModel), AccessorDatas.createFloat(tangentsAccessorModel),
            AccessorDatas.createFloat(outputPositions), AccessorDatas.createFloat(outputNormals), AccessorDatas.createFloat(outputTangents))
        val buffers = setupCpuSkinningBuffers(gltfRenderData, outputPositions, outputNormals, outputTangents)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addCpuSkinningDrawCommand(gltfRenderData, meshPrimitiveModel, outputPositions, outputNormals, outputTangents, buffers, skinningCommands, renderCommand)
    }
    private fun processCpuSkinnedSimpleTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        jointMatrices: Array<FloatArray>, attributes: Map<String, AccessorModel>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel
    ) {
        val morphTargets = meshPrimitiveModel.targets
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        val outputPositions = if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION"))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(positionsAccessorModel, "")
        val tangentsAccessorModel = obtainTangentsAccessorModel(normalsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        val tangentTargetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        val outputNormals: AccessorModel
        val outputTangents: AccessorModel
        if (createNormalTangentMorphTarget(morphTargets, normalsAccessorModel, tangentsAccessorModel, targetAccessorDatas, tangentTargetAccessorDatas)) {
            outputNormals = obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, normalsAccessorModel, targetAccessorDatas)
            outputTangents = obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, tangentsAccessorModel, tangentTargetAccessorDatas)
        } else {
            outputNormals = AccessorModelCreation.instantiate(normalsAccessorModel, "")
            outputTangents = AccessorModelCreation.instantiate(tangentsAccessorModel, "")
        }
        val skinningCommands = createSoftwareSkinningCommands(positionsAccessorModel.count, jointMatrices, attributes,
            AccessorDatas.createFloat(positionsAccessorModel), AccessorDatas.createFloat(normalsAccessorModel), AccessorDatas.createFloat(tangentsAccessorModel),
            AccessorDatas.createFloat(outputPositions), AccessorDatas.createFloat(outputNormals), AccessorDatas.createFloat(outputTangents))
        val buffers = setupCpuSkinningBuffers(gltfRenderData, outputPositions, outputNormals, outputTangents)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addCpuSkinningDrawCommand(gltfRenderData, meshPrimitiveModel, outputPositions, outputNormals, outputTangents, buffers, skinningCommands, renderCommand)
    }
    private fun processCpuSkinnedMikkTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        jointMatrices: Array<FloatArray>
    ) {
        val unindexed = obtainUnindexed(meshPrimitiveModel)
        val attributes = unindexed.first
        val morphTargets = unindexed.second
        val positionsAccessorModel = attributes["POSITION"]!!
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        val outputPositions = if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION"))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(positionsAccessorModel, "")
        val normalsAccessorModel = attributes["NORMAL"]!!
        targetAccessorDatas = ArrayList(morphTargets.size)
        val outputNormals = if (createMorphTarget(morphTargets, targetAccessorDatas, "NORMAL"))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, normalsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(normalsAccessorModel, "")
        val texcoordsAccessorModel = attributes["TEXCOORD_0"]
        val tangentsAccessorModel = obtainTangentsAccessorModel(meshPrimitiveModel, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        val outputTangents = if (texcoordsAccessorModel != null && createTangentMorphTarget(morphTargets, targetAccessorDatas, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel, "TEXCOORD_0", tangentsAccessorModel))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, tangentsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(tangentsAccessorModel, "")
        val skinningCommands = createSoftwareSkinningCommands(positionsAccessorModel.count, jointMatrices, attributes,
            AccessorDatas.createFloat(positionsAccessorModel), AccessorDatas.createFloat(normalsAccessorModel), AccessorDatas.createFloat(tangentsAccessorModel),
            AccessorDatas.createFloat(outputPositions), AccessorDatas.createFloat(outputNormals), AccessorDatas.createFloat(outputTangents))
        val buffers = setupCpuSkinningBuffers(gltfRenderData, outputPositions, outputNormals, outputTangents)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addCpuSkinningDrawCommand(gltfRenderData, meshPrimitiveModel, outputPositions, outputNormals, outputTangents, buffers, skinningCommands, renderCommand)
    }
    private fun processCpuSkinnedFlatNormalSimpleTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        jointMatrices: Array<FloatArray>
    ) {
        val unindexed = obtainUnindexed(meshPrimitiveModel)
        val attributes = unindexed.first
        val morphTargets = unindexed.second
        val positionsAccessorModel = attributes["POSITION"]!!
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        val outputPositions = if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION"))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(positionsAccessorModel, "")
        val normalsAccessorModel = obtainNormalsAccessorModel(positionsAccessorModel)
        val tangentsAccessorModel = obtainTangentsAccessorModel(normalsAccessorModel)
        val positionTargetDatas = ArrayList<AccessorFloatData?>(morphTargets.size)
        val normalTargetDatas = ArrayList<AccessorFloatData?>(morphTargets.size)
        val tangentTargetDatas = ArrayList<AccessorFloatData?>(morphTargets.size)
        val outputNormals: AccessorModel
        val outputTangents: AccessorModel
        if (createPositionNormalTangentMorphTarget(morphTargets, positionsAccessorModel, normalsAccessorModel, tangentsAccessorModel, positionTargetDatas, normalTargetDatas, tangentTargetDatas)) {
            outputNormals = obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, normalsAccessorModel, normalTargetDatas)
            outputTangents = obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, tangentsAccessorModel, tangentTargetDatas)
        } else {
            outputNormals = AccessorModelCreation.instantiate(normalsAccessorModel, "")
            outputTangents = AccessorModelCreation.instantiate(tangentsAccessorModel, "")
        }
        val skinningCommands = createSoftwareSkinningCommands(positionsAccessorModel.count, jointMatrices, attributes,
            AccessorDatas.createFloat(positionsAccessorModel), AccessorDatas.createFloat(normalsAccessorModel), AccessorDatas.createFloat(tangentsAccessorModel),
            AccessorDatas.createFloat(outputPositions), AccessorDatas.createFloat(outputNormals), AccessorDatas.createFloat(outputTangents))
        val buffers = setupCpuSkinningBuffers(gltfRenderData, outputPositions, outputNormals, outputTangents)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addCpuSkinningDrawCommand(gltfRenderData, meshPrimitiveModel, outputPositions, outputNormals, outputTangents, buffers, skinningCommands, renderCommand)
    }
    private fun processCpuSkinnedFlatNormalMikkTangent(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, renderCommand: MutableList<Runnable>,
        jointMatrices: Array<FloatArray>
    ) {
        val unindexed = obtainUnindexed(meshPrimitiveModel)
        val attributes = unindexed.first
        val morphTargets = unindexed.second
        val positionsAccessorModel = attributes["POSITION"]!!
        var targetAccessorDatas = ArrayList<AccessorFloatData>(morphTargets.size)
        val outputPositions = if (createMorphTarget(morphTargets, targetAccessorDatas, "POSITION"))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, positionsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(positionsAccessorModel, "")
        val normalsAccessorModel = obtainNormalsAccessorModel(positionsAccessorModel)
        val positionTargetDatas = ArrayList<AccessorFloatData?>(morphTargets.size)
        val normalTargetAccessorDatas = ArrayList<AccessorFloatData?>(morphTargets.size)
        val outputNormals = if (createPositionNormalMorphTarget(morphTargets, positionsAccessorModel, normalsAccessorModel, positionTargetDatas, normalTargetAccessorDatas))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, normalsAccessorModel, normalTargetAccessorDatas)
        else AccessorModelCreation.instantiate(normalsAccessorModel, "")
        val texcoordsAccessorModel = attributes["TEXCOORD_0"]
        val tangentsAccessorModel = obtainTangentsAccessorModel(meshPrimitiveModel, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel)
        targetAccessorDatas = ArrayList(morphTargets.size)
        val outputTangents = if (texcoordsAccessorModel != null && createTangentMorphTarget(morphTargets, targetAccessorDatas, positionsAccessorModel, normalsAccessorModel, texcoordsAccessorModel, "TEXCOORD_0", tangentsAccessorModel))
            obtainVec3FloatMorphedModel(nodeModel, meshModel, renderCommand, tangentsAccessorModel, targetAccessorDatas)
        else AccessorModelCreation.instantiate(tangentsAccessorModel, "")
        val skinningCommands = createSoftwareSkinningCommands(positionsAccessorModel.count, jointMatrices, attributes,
            AccessorDatas.createFloat(positionsAccessorModel), AccessorDatas.createFloat(normalsAccessorModel), AccessorDatas.createFloat(tangentsAccessorModel),
            AccessorDatas.createFloat(outputPositions), AccessorDatas.createFloat(outputNormals), AccessorDatas.createFloat(outputTangents))
        val buffers = setupCpuSkinningBuffers(gltfRenderData, outputPositions, outputNormals, outputTangents)
        bindColorAndTexcoord(gltfRenderData, nodeModel, meshModel, meshPrimitiveModel, renderCommand, attributes, morphTargets)
        addCpuSkinningDrawCommand(gltfRenderData, meshPrimitiveModel, outputPositions, outputNormals, outputTangents, buffers, skinningCommands, renderCommand)
    }
    fun createPositionNormalMorphTarget(
        morphTargets: List<Map<String, AccessorModel>>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel,
        positionTargetDatas: MutableList<AccessorFloatData?>, normalTargetDatas: MutableList<AccessorFloatData?>
    ): Boolean {
        var found = false
        val count = positionsAccessorModel.count
        val numTriangles = count / 3
        val positionsData = AccessorDatas.createFloat(positionsAccessorModel)
        val normalsData = AccessorDatas.createFloat(normalsAccessorModel)
        for (morphTarget in morphTargets) {
            val accessor = morphTarget["POSITION"]
            if (accessor != null) {
                found = true
                val deltaPositions = AccessorDatas.createFloat(accessor)
                positionTargetDatas.add(deltaPositions)
                val normalTarget = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC3, "")
                val normalTargetData = normalTarget.accessorData as AccessorFloatData
                normalTargetDatas.add(normalTargetData)
                val v0 = FloatArray(3); val v1 = FloatArray(3); val v2 = FloatArray(3)
                val e01 = FloatArray(3); val e02 = FloatArray(3); val cross = FloatArray(3)
                val n0 = FloatArray(3); val n1 = FloatArray(3)
                for (i in 0 until numTriangles) {
                    val i0 = i * 3; val i1 = i0 + 1; val i2 = i0 + 2
                    for (c in 0 until 3) { v0[c] = positionsData.get(i0, c) + deltaPositions.get(i0, c); v1[c] = positionsData.get(i1, c) + deltaPositions.get(i1, c); v2[c] = positionsData.get(i2, c) + deltaPositions.get(i2, c) }
                    for (c in 0 until 3) n0[c] = normalsData.get(i0, c)
                    VecMath.subtract(v1, v0, e01); VecMath.subtract(v2, v0, e02)
                    VecMath.cross(e01, e02, cross); VecMath.normalize(cross, n1)
                    VecMath.subtract(n1, n0, n1)
                    for (v in i0..i2) { normalTargetData.set(v, 0, n1[0]); normalTargetData.set(v, 1, n1[1]); normalTargetData.set(v, 2, n1[2]) }
                }
            } else {
                positionTargetDatas.add(null)
                normalTargetDatas.add(null)
            }
        }
        return found
    }
    fun createPositionNormalTangentMorphTarget(
        morphTargets: List<Map<String, AccessorModel>>,
        positionsAccessorModel: AccessorModel, normalsAccessorModel: AccessorModel, tangentsAccessorModel: AccessorModel,
        positionTargetDatas: MutableList<AccessorFloatData?>, normalTargetDatas: MutableList<AccessorFloatData?>, tangentTargetDatas: MutableList<AccessorFloatData?>
    ): Boolean {
        var found = false
        val count = positionsAccessorModel.count
        val numTriangles = count / 3
        val positionsData = AccessorDatas.createFloat(positionsAccessorModel)
        val normalsData = AccessorDatas.createFloat(normalsAccessorModel)
        val tangentsData = AccessorDatas.createFloat(tangentsAccessorModel)
        for (morphTarget in morphTargets) {
            val accessor = morphTarget["POSITION"]
            if (accessor != null) {
                found = true
                val deltaPositions = AccessorDatas.createFloat(accessor)
                positionTargetDatas.add(deltaPositions)
                val normalTarget = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC3, "")
                val normalTargetData = normalTarget.accessorData as AccessorFloatData
                normalTargetDatas.add(normalTargetData)
                val tangentTarget = createAccessorModel(GL11.GL_FLOAT, count, ElementType.VEC3, "")
                val tangentTargetData = tangentTarget.accessorData as AccessorFloatData
                tangentTargetDatas.add(tangentTargetData)
                val v0 = FloatArray(3); val v1 = FloatArray(3); val v2 = FloatArray(3)
                val e01 = FloatArray(3); val e02 = FloatArray(3); val cross = FloatArray(3)
                val n0 = FloatArray(3); val n1 = FloatArray(3); val n2 = FloatArray(3)
                val t0 = FloatArray(3); val t1 = FloatArray(3)
                for (i in 0 until numTriangles) {
                    val i0 = i * 3; val i1 = i0 + 1; val i2 = i0 + 2
                    for (c in 0 until 3) { v0[c] = positionsData.get(i0, c) + deltaPositions.get(i0, c); v1[c] = positionsData.get(i1, c) + deltaPositions.get(i1, c); v2[c] = positionsData.get(i2, c) + deltaPositions.get(i2, c) }
                    for (c in 0 until 3) { n0[c] = normalsData.get(i0, c); t0[c] = tangentsData.get(i0, c) }
                    VecMath.subtract(v1, v0, e01); VecMath.subtract(v2, v0, e02)
                    VecMath.cross(e01, e02, cross); VecMath.normalize(cross, n1)
                    n2[0] = -n1[2]; n2[1] = n1[0]; n2[2] = n1[1]
                    VecMath.cross(n1, n2, cross); VecMath.normalize(cross, t1)
                    VecMath.subtract(n1, n0, n1); VecMath.subtract(t1, t0, t1)
                    for (v in i0..i2) {
                        normalTargetData.set(v, 0, n1[0]); normalTargetData.set(v, 1, n1[1]); normalTargetData.set(v, 2, n1[2])
                        tangentTargetData.set(v, 0, t1[0]); tangentTargetData.set(v, 1, t1[1]); tangentTargetData.set(v, 2, t1[2])
                    }
                }
            } else {
                positionTargetDatas.add(null)
                normalTargetDatas.add(null)
                tangentTargetDatas.add(null)
            }
        }
        return found
    }
}