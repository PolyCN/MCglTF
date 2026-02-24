package com.poly.mcgltf.light
import com.poly.mcgltf.RenderedGltfModel
import com.poly.mcgltf.config.ConfigSystem
import com.poly.mcgltf.config.LightingMode
import com.poly.mcgltf.culling.FrustumCuller
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
object LightProbeSystem {
    private const val RESAMPLE_DISTANCE_SQ = 0.25f
    private const val RESAMPLE_TICK_INTERVAL = 4
    private const val SMOOTH_DURATION_TICKS = 10
    private const val FULL_BRIGHT_BLOCK = 15
    private const val FULL_BRIGHT_SKY = 15
    private val probeCache = ConcurrentHashMap<Int, LightProbeEntry>()
    private val samplePos = BlockPos.MutableBlockPos()
    private val tempVec = Vector3f()
    fun sampleForModel(
        modelId: Int,
        centerX: Double,
        centerY: Double,
        centerZ: Double,
        aabb: FrustumCuller.AABB?
    ): Int {
        val entry = probeCache.computeIfAbsent(modelId) { LightProbeEntry() }
        val dx = centerX - entry.lastX
        val dy = centerY - entry.lastY
        val dz = centerZ - entry.lastZ
        val distSq = (dx * dx + dy * dy + dz * dz).toFloat()
        entry.ticksSinceUpdate++
        if (entry.initialized && distSq < RESAMPLE_DISTANCE_SQ && entry.ticksSinceUpdate < RESAMPLE_TICK_INTERVAL) {
            updateSmoothing(entry)
            return LightTexture.pack(entry.smoothBlock.toInt(), entry.smoothSky.toInt())
        }
        val level = Minecraft.getInstance().level ?: return fullBrightPacked(entry)
        samplePos.set(centerX.toInt(), centerY.toInt(), centerZ.toInt())
        if (!level.hasChunkAt(samplePos)) return cachedOrFullBright(entry)
        val hasSkyLight = level.dimensionType().hasSkyLight()
        var totalBlock = 0f
        var totalSky = 0f
        var totalWeight = 0f
        val centerBlock = level.getBrightness(LightLayer.BLOCK, samplePos).toFloat()
        val centerSky = if (hasSkyLight) level.getBrightness(LightLayer.SKY, samplePos).toFloat() else 0f
        totalBlock += centerBlock * 2f
        totalSky += centerSky * 2f
        totalWeight += 2f
        if (aabb != null) {
            sampleCorner(level, hasSkyLight, centerX + aabb.minX, centerY + aabb.minY, centerZ + aabb.minZ) { b, s -> totalBlock += b; totalSky += s; totalWeight += 1f }
            sampleCorner(level, hasSkyLight, centerX + aabb.maxX, centerY + aabb.minY, centerZ + aabb.minZ) { b, s -> totalBlock += b; totalSky += s; totalWeight += 1f }
            sampleCorner(level, hasSkyLight, centerX + aabb.minX, centerY + aabb.maxY, centerZ + aabb.minZ) { b, s -> totalBlock += b; totalSky += s; totalWeight += 1f }
            sampleCorner(level, hasSkyLight, centerX + aabb.maxX, centerY + aabb.maxY, centerZ + aabb.minZ) { b, s -> totalBlock += b; totalSky += s; totalWeight += 1f }
            sampleCorner(level, hasSkyLight, centerX + aabb.minX, centerY + aabb.minY, centerZ + aabb.maxZ) { b, s -> totalBlock += b; totalSky += s; totalWeight += 1f }
            sampleCorner(level, hasSkyLight, centerX + aabb.maxX, centerY + aabb.minY, centerZ + aabb.maxZ) { b, s -> totalBlock += b; totalSky += s; totalWeight += 1f }
            sampleCorner(level, hasSkyLight, centerX + aabb.minX, centerY + aabb.maxY, centerZ + aabb.maxZ) { b, s -> totalBlock += b; totalSky += s; totalWeight += 1f }
            sampleCorner(level, hasSkyLight, centerX + aabb.maxX, centerY + aabb.maxY, centerZ + aabb.maxZ) { b, s -> totalBlock += b; totalSky += s; totalWeight += 1f }
        }
        val avgBlock = (totalBlock / totalWeight).coerceIn(0f, 15f)
        val avgSky = (totalSky / totalWeight).coerceIn(0f, 15f)
        entry.targetBlock = avgBlock
        entry.targetSky = avgSky
        entry.lastX = centerX
        entry.lastY = centerY
        entry.lastZ = centerZ
        entry.ticksSinceUpdate = 0
        if (!entry.initialized) {
            entry.smoothBlock = avgBlock
            entry.smoothSky = avgSky
            entry.initialized = true
        } else {
            updateSmoothing(entry)
        }
        return LightTexture.pack(entry.smoothBlock.toInt(), entry.smoothSky.toInt())
    }
    fun samplePerVertex(
        modelId: Int,
        vertices: FloatBuffer,
        vertexCount: Int,
        stride: Int,
        lightBuffer: FloatBuffer,
        worldMatrix: Matrix4f
    ) {
        val level = Minecraft.getInstance().level ?: run {
            for (i in 0 until vertexCount) {
                lightBuffer.put(i * 2, FULL_BRIGHT_BLOCK.toFloat())
                lightBuffer.put(i * 2 + 1, FULL_BRIGHT_SKY.toFloat())
            }
            return
        }
        val hasSkyLight = level.dimensionType().hasSkyLight()
        val floatsPerVertex = stride / 4
        for (i in 0 until vertexCount) {
            val base = i * floatsPerVertex
            tempVec.set(vertices.get(base), vertices.get(base + 1), vertices.get(base + 2))
            worldMatrix.transformPosition(tempVec)
            samplePos.set(tempVec.x.toInt(), tempVec.y.toInt(), tempVec.z.toInt())
            if (level.hasChunkAt(samplePos)) {
                lightBuffer.put(i * 2, level.getBrightness(LightLayer.BLOCK, samplePos).toFloat())
                lightBuffer.put(i * 2 + 1, if (hasSkyLight) level.getBrightness(LightLayer.SKY, samplePos).toFloat() else 0f)
            } else {
                lightBuffer.put(i * 2, FULL_BRIGHT_BLOCK.toFloat())
                lightBuffer.put(i * 2 + 1, FULL_BRIGHT_SKY.toFloat())
            }
        }
    }
    fun applyToVanilla(packedLight: Int) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + 1)
        val blockU = (LightTexture.block(packedLight).toFloat() + 0.5f) / 16.0f
        val skyV = (LightTexture.sky(packedLight).toFloat() + 0.5f) / 16.0f
        GL11.glTexCoord2f(blockU, skyV)
        GL20.glVertexAttrib2f(RenderedGltfModel.vaUV1, blockU, skyV)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
    }
    fun applyToIris(program: Int, packedLight: Int) {
        val blockVal = LightTexture.block(packedLight).toFloat() / 15.0f
        val skyVal = LightTexture.sky(packedLight).toFloat() / 15.0f
        val loc = GL20.glGetUniformLocation(program, "iris_LightmapCoord")
        if (loc != -1) {
            GL20.glUniform2f(loc, blockVal, skyVal)
        } else {
            val locAlt = GL20.glGetUniformLocation(program, "lightMapCoord")
            if (locAlt != -1) GL20.glUniform2f(locAlt, blockVal, skyVal)
        }
    }
    fun removeProbe(modelId: Int) { probeCache.remove(modelId) }
    fun clear() { probeCache.clear() }
    fun isPerVertexMode(): Boolean = ConfigSystem.current.lightingMode == LightingMode.PER_VERTEX
    fun isPerModelMode(): Boolean = ConfigSystem.current.lightingMode == LightingMode.PER_MODEL
    private inline fun sampleCorner(
        level: Level, hasSkyLight: Boolean,
        x: Double, y: Double, z: Double,
        consumer: (Float, Float) -> Unit
    ) {
        samplePos.set(x.toInt(), y.toInt(), z.toInt())
        if (!level.hasChunkAt(samplePos)) return
        val block = level.getBrightness(LightLayer.BLOCK, samplePos).toFloat()
        val sky = if (hasSkyLight) level.getBrightness(LightLayer.SKY, samplePos).toFloat() else 0f
        consumer(block, sky)
    }
    private fun updateSmoothing(entry: LightProbeEntry) {
        val alpha = (1.0f / SMOOTH_DURATION_TICKS).coerceIn(0f, 1f)
        entry.smoothBlock += (entry.targetBlock - entry.smoothBlock) * alpha
        entry.smoothSky += (entry.targetSky - entry.smoothSky) * alpha
    }
    private fun fullBrightPacked(entry: LightProbeEntry): Int {
        entry.smoothBlock = FULL_BRIGHT_BLOCK.toFloat()
        entry.smoothSky = FULL_BRIGHT_SKY.toFloat()
        entry.targetBlock = FULL_BRIGHT_BLOCK.toFloat()
        entry.targetSky = FULL_BRIGHT_SKY.toFloat()
        entry.initialized = true
        return LightTexture.pack(FULL_BRIGHT_BLOCK, FULL_BRIGHT_SKY)
    }
    private fun cachedOrFullBright(entry: LightProbeEntry): Int {
        if (entry.initialized) return LightTexture.pack(entry.smoothBlock.toInt(), entry.smoothSky.toInt())
        return fullBrightPacked(entry)
    }
}
class LightProbeEntry {
    var smoothBlock: Float = 0f
    var smoothSky: Float = 0f
    var targetBlock: Float = 0f
    var targetSky: Float = 0f
    var lastX: Double = Double.MAX_VALUE
    var lastY: Double = Double.MAX_VALUE
    var lastZ: Double = Double.MAX_VALUE
    var ticksSinceUpdate: Int = Int.MAX_VALUE
    var initialized: Boolean = false
}
