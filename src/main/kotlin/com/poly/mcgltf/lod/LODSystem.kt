package com.poly.mcgltf.lod
import com.poly.mcgltf.RenderedGltfModel
import com.poly.mcgltf.RenderedGltfScene
import com.poly.mcgltf.config.ConfigSystem
import net.minecraft.resources.Identifier
import org.joml.Vector3f
import java.util.concurrent.ConcurrentHashMap
data class LODLevel(
    val model: RenderedGltfModel,
    val minDistanceSq: Float,
    val maxDistanceSq: Float
)
data class LODSelection(
    val primary: RenderedGltfScene,
    val secondary: RenderedGltfScene?,
    val blendFactor: Float
)
object LODSystem {
    private val registry = ConcurrentHashMap<Identifier, Array<LODLevel>>()
    private val cameraPos = Vector3f()
    private const val DEFAULT_TRANSITION_BAND_SQ = 25.0f
    fun register(location: Identifier, levels: List<Pair<RenderedGltfModel, Float>>) {
        if (levels.isEmpty()) return
        val sorted = levels.sortedBy { it.second }
        val lodLevels = Array(sorted.size) { i ->
            val minDist = if (i == 0) 0.0f else sorted[i].second
            val maxDist = if (i < sorted.size - 1) sorted[i + 1].second else Float.MAX_VALUE
            LODLevel(sorted[i].first, minDist * minDist, maxDist * maxDist)
        }
        registry[location] = lodLevels
    }
    fun registerWithDistances(location: Identifier, levels: List<Triple<RenderedGltfModel, Float, Float>>) {
        if (levels.isEmpty()) return
        val sorted = levels.sortedBy { it.second }
        registry[location] = Array(sorted.size) { i ->
            LODLevel(sorted[i].first, sorted[i].second * sorted[i].second, sorted[i].third * sorted[i].third)
        }
    }
    fun unregister(location: Identifier) { registry.remove(location) }
    fun clear() { registry.clear() }
    fun hasLOD(location: Identifier): Boolean = registry.containsKey(location)
    fun updateCameraPosition(x: Float, y: Float, z: Float) { cameraPos.set(x, y, z) }
    fun selectLOD(location: Identifier, modelCenterX: Float, modelCenterY: Float, modelCenterZ: Float, sceneIndex: Int = 0): LODSelection? {
        if (!isEnabled()) return null
        val levels = registry[location] ?: return null
        val distSq = cameraPos.distanceSquared(modelCenterX, modelCenterY, modelCenterZ)
        for (i in levels.indices) {
            val level = levels[i]
            if (distSq < level.maxDistanceSq) {
                val primary = level.model.renderedGltfScenes.getOrNull(sceneIndex) ?: return null
                val transitionStart = level.maxDistanceSq - DEFAULT_TRANSITION_BAND_SQ
                if (distSq > transitionStart && i < levels.size - 1) {
                    val next = levels[i + 1]
                    val secondary = next.model.renderedGltfScenes.getOrNull(sceneIndex)
                    val range = level.maxDistanceSq - transitionStart
                    val blend = if (range > 0.0f) (distSq - transitionStart) / range else 0.0f
                    return LODSelection(primary, secondary, blend.coerceIn(0.0f, 1.0f))
                }
                return LODSelection(primary, null, 0.0f)
            }
        }
        val last = levels.last()
        val scene = last.model.renderedGltfScenes.getOrNull(sceneIndex) ?: return null
        return LODSelection(scene, null, 0.0f)
    }
    fun selectModel(location: Identifier, modelCenterX: Float, modelCenterY: Float, modelCenterZ: Float): RenderedGltfModel? {
        if (!isEnabled()) return null
        val levels = registry[location] ?: return null
        val distSq = cameraPos.distanceSquared(modelCenterX, modelCenterY, modelCenterZ)
        for (level in levels) {
            if (distSq < level.maxDistanceSq) return level.model
        }
        return levels.last().model
    }
    fun getLevelCount(location: Identifier): Int = registry[location]?.size ?: 0
    fun isEnabled(): Boolean = ConfigSystem.isSubsystemEnabled("lodSystem")
}
