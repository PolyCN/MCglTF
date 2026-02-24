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
    }
    private fun processMeshPrimitiveModelSkinned(
        gltfRenderData: MutableList<Runnable>, nodeModel: NodeModel, meshModel: MeshModel,
        meshPrimitiveModel: MeshPrimitiveModel, transforms: Array<FloatArray>,
        vanillaRenderCommands: MutableList<Runnable>, shaderModRenderCommands: MutableList<Runnable>
    ) {
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
}