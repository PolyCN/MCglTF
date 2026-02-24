package com.poly.mcgltf.culling
import com.poly.mcgltf.RenderedGltfModel
import com.poly.mcgltf.config.ConfigSystem
import com.poly.mcgltf.instancing.GPUInstancingSystem
import de.javagl.jgltf.model.*
import org.joml.FrustumIntersection
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import java.util.*
import java.util.concurrent.ConcurrentHashMap
object FrustumCuller {
    private val frustum = FrustumIntersection()
    private val transformedMin = Vector3f()
    private val transformedMax = Vector3f()
    private val modelAABBs = ConcurrentHashMap<GltfModel, AABB>()
    private const val SKINNING_EXPAND_FACTOR = 1.5f
    data class AABB(
        val minX: Float, val minY: Float, val minZ: Float,
        val maxX: Float, val maxY: Float, val maxZ: Float
    ) {
        fun expand(factor: Float): AABB {
            val cx = (minX + maxX) * 0.5f
            val cy = (minY + maxY) * 0.5f
            val cz = (minZ + maxZ) * 0.5f
            val hx = (maxX - minX) * 0.5f * factor
            val hy = (maxY - minY) * 0.5f * factor
            val hz = (maxZ - minZ) * 0.5f * factor
            return AABB(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz)
        }
    }
    fun updateFrustum(viewProjection: Matrix4fc) {
        frustum.set(viewProjection, false)
    }
    fun computeAABB(model: GltfModel): AABB {
        return modelAABBs.computeIfAbsent(model) { computeAABBFromModel(it) }
    }
    fun computeSkinnedAABB(model: GltfModel): AABB {
        return computeAABB(model).expand(SKINNING_EXPAND_FACTOR)
    }
    fun isVisible(modelMatrix: Matrix4fc, aabb: AABB): Boolean {
        if (!isEnabled()) return true
        (modelMatrix as Matrix4f).transformAab(
            aabb.minX, aabb.minY, aabb.minZ,
            aabb.maxX, aabb.maxY, aabb.maxZ,
            transformedMin, transformedMax
        )
        return frustum.testAab(
            transformedMin.x, transformedMin.y, transformedMin.z,
            transformedMax.x, transformedMax.y, transformedMax.z
        )
    }
    fun isVisible(modelMatrix: Matrix4fc, model: RenderedGltfModel): Boolean {
        if (!isEnabled()) return true
        val hasSkin = model.gltfModel.skinModels.isNotEmpty()
        val aabb = if (hasSkin) computeSkinnedAABB(model.gltfModel) else computeAABB(model.gltfModel)
        return isVisible(modelMatrix, aabb)
    }
    fun cullInstances(
        instancing: GPUInstancingSystem,
        aabb: AABB,
        instanceMatrices: List<Matrix4f>,
        outVisible: MutableList<Matrix4f>
    ) {
        if (!isEnabled()) {
            outVisible.addAll(instanceMatrices)
            return
        }
        for (matrix in instanceMatrices) {
            matrix.transformAab(
                aabb.minX, aabb.minY, aabb.minZ,
                aabb.maxX, aabb.maxY, aabb.maxZ,
                transformedMin, transformedMax
            )
            if (frustum.testAab(
                    transformedMin.x, transformedMin.y, transformedMin.z,
                    transformedMax.x, transformedMax.y, transformedMax.z
                )) {
                outVisible.add(matrix)
            }
        }
    }
    fun clearCache() { modelAABBs.clear() }
    fun isEnabled(): Boolean = ConfigSystem.isSubsystemEnabled("frustumCulling")
    private fun computeAABBFromModel(model: GltfModel): AABB {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        val visited = Collections.newSetFromMap(IdentityHashMap<MeshPrimitiveModel, Boolean>())
        for (scene in model.sceneModels) {
            for (node in scene.nodeModels) {
                collectPrimitiveBounds(node, visited) { pMinX, pMinY, pMinZ, pMaxX, pMaxY, pMaxZ ->
                    if (pMinX < minX) minX = pMinX
                    if (pMinY < minY) minY = pMinY
                    if (pMinZ < minZ) minZ = pMinZ
                    if (pMaxX > maxX) maxX = pMaxX
                    if (pMaxY > maxY) maxY = pMaxY
                    if (pMaxZ > maxZ) maxZ = pMaxZ
                }
            }
        }
        if (minX > maxX) return AABB(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)
        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }
    private fun collectPrimitiveBounds(
        node: NodeModel,
        visited: MutableSet<MeshPrimitiveModel>,
        consumer: (Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        for (mesh in node.meshModels) {
            for (primitive in mesh.meshPrimitiveModels) {
                if (!visited.add(primitive)) continue
                val posAccessor = primitive.attributes["POSITION"] ?: continue
                val min = posAccessor.min
                val max = posAccessor.max
                if (min != null && max != null && min.size >= 3 && max.size >= 3) {
                    consumer(
                        min[0].toFloat(), min[1].toFloat(), min[2].toFloat(),
                        max[0].toFloat(), max[1].toFloat(), max[2].toFloat()
                    )
                } else {
                    computeBoundsFromAccessorData(posAccessor, consumer)
                }
            }
        }
        for (child in node.children) {
            collectPrimitiveBounds(child, visited, consumer)
        }
    }
    private inline fun computeBoundsFromAccessorData(
        accessor: AccessorModel,
        consumer: (Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        val data = accessor.accessorData
        if (data !is AccessorFloatData) return
        val count = accessor.count
        if (count == 0) return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until count) {
            val x = data.get(i, 0); val y = data.get(i, 1); val z = data.get(i, 2)
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }
        consumer(minX, minY, minZ, maxX, maxY, maxZ)
    }
}