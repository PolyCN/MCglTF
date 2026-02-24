package com.poly.mcgltf
import com.poly.mcgltf.config.ConfigSystem
import de.javagl.jgltf.model.io.Buffers
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.irisshaders.iris.api.v0.IrisApi
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BooleanSupplier
object MCglTFSystem : ClientModInitializer {
    const val MODID = "mcgltf"
    lateinit var glProfile: GLProfile
        private set
    var glProgramSkinning: Int = -1
        private set
    var defaultColorMap: Int = 0
        private set
    var defaultNormalMap: Int = 0
        private set
    var defaultSpecularMap: Int = 0
        private set
    private val receivers = CopyOnWriteArrayList<GltfModelReceiver>()
    val gltfRenderData = CopyOnWriteArrayList<Runnable>()
    private val bufferCache = ConcurrentHashMap<Identifier, () -> ByteBuffer>()
    private val imageCache = ConcurrentHashMap<Identifier, () -> ByteBuffer>()
    private var shaderModActive: BooleanSupplier = BooleanSupplier { false }
    fun addGltfModelReceiver(receiver: GltfModelReceiver) { receivers.add(receiver) }
    fun removeGltfModelReceiver(receiver: GltfModelReceiver): Boolean = receivers.remove(receiver)
    fun getReceivers(): List<GltfModelReceiver> = receivers
    fun isShaderModActive(): Boolean = shaderModActive.asBoolean
    fun isEnabled(): Boolean = ConfigSystem.current.enabled
    private fun loadCachedResource(
        cache: ConcurrentHashMap<Identifier, () -> ByteBuffer>,
        location: Identifier
    ): ByteBuffer = cache.computeIfAbsent(location) {
        val holder = arrayOfNulls<ByteBuffer>(1)
        val supplier: () -> ByteBuffer = {
            synchronized(holder) {
                if (holder[0] == null) {
                    Minecraft.getInstance().resourceManager.getResource(location).ifPresent { res ->
                        res.open().buffered().use { holder[0] = Buffers.create(it.readBytes()) }
                    }
                }
                holder[0]!!
            }
        }
        supplier
    }.invoke()
    fun getBufferResource(location: Identifier): ByteBuffer = loadCachedResource(bufferCache, location)
    fun getImageResource(location: Identifier): ByteBuffer = loadCachedResource(imageCache, location)
    fun clearResourceCaches() {
        bufferCache.clear()
        imageCache.clear()
    }
    override fun onInitializeClient() {
        val configDir = FabricLoader.getInstance().configDir
        ConfigSystem.load(configDir)
        ConfigSystem.startWatching()
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            shaderModActive = BooleanSupplier {
                IrisApi.getInstance().isShaderPackInUse
            }
        }
    }
}