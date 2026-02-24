package com.poly.mcgltf
import com.poly.mcgltf.cache.ModelCachePool
import com.poly.mcgltf.config.ConfigSystem
import com.poly.mcgltf.iris.IrisRenderedGltfModel
import com.poly.mcgltf.loader.AsyncModelLoader
import com.poly.mcgltf.material.MaterialOverrideSystem
import de.javagl.jgltf.model.GltfModel
import de.javagl.jgltf.model.io.Buffers
import de.javagl.jgltf.model.io.GltfModelReader
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.fabricmc.loader.api.FabricLoader
import net.irisshaders.iris.api.v0.IrisApi
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import org.lwjgl.opengl.*
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BooleanSupplier
object MCglTFSystem : ClientModInitializer {
    const val MODID = "mcgltf"
    private val LOGGER = LoggerFactory.getLogger("MCglTF")
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
    val modelCachePool = ModelCachePool()
    fun addGltfModelReceiver(receiver: GltfModelReceiver) { receivers.add(receiver) }
    fun removeGltfModelReceiver(receiver: GltfModelReceiver): Boolean = receivers.remove(receiver)
    fun getReceivers(): List<GltfModelReceiver> = receivers
    fun isShaderModActive(): Boolean = shaderModActive.asBoolean
    fun isIrisLoaded(): Boolean = FabricLoader.getInstance().isModLoaded("iris")
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
    fun executeGlCleanup() {
        gltfRenderData.forEach(Runnable::run)
        gltfRenderData.clear()
    }
    fun onResourceManagerReload(resourceManager: ResourceManager) {
        if (!isEnabled()) return
        executeGlCleanup()
        modelCachePool.clear()
        AsyncModelLoader.cancelAll()
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
        val currentTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        try {
            MaterialOverrideSystem.onResourceManagerReload(resourceManager)
            val lookup = collectReceiverModels()
            loadModelsParallel(lookup, resourceManager)
            processLoadedModels(lookup)
            cleanupGlBindings()
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture)
            clearResourceCaches()
        }
    }
    private fun collectReceiverModels(): MutableMap<Identifier, Pair<GltfModel?, MutableList<GltfModelReceiver>>> {
        val lookup = LinkedHashMap<Identifier, Pair<GltfModel?, MutableList<GltfModelReceiver>>>()
        receivers.forEach { receiver ->
            val location = receiver.getModelLocation()
            val entry = lookup.getOrPut(location) { Pair(null, mutableListOf()) }
            entry.second.add(receiver)
        }
        return lookup
    }
    private fun loadModelsParallel(
        lookup: MutableMap<Identifier, Pair<GltfModel?, MutableList<GltfModelReceiver>>>,
        resourceManager: ResourceManager
    ) {
        val loaded = lookup.keys.toList().parallelStream().map { location ->
            val reader = GltfModelReader()
            try {
                val model = resourceManager.getResource(location).map { resource ->
                    resource.open().use { stream ->
                        reader.readWithoutReferences(BufferedInputStream(stream))
                    }
                }.orElse(null)
                if (model == null) {
                    LOGGER.error("""{"event":"model_not_found","location":"$location"}""")
                }
                location to model
            } catch (e: Exception) {
                LOGGER.error("""{"event":"model_load_error","location":"$location","error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
                location to null
            }
        }.toList()
        loaded.forEach { (location, model) ->
            lookup.computeIfPresent(location) { _, pair -> Pair(model, pair.second) }
        }
    }
    private fun processLoadedModels(
        lookup: Map<Identifier, Pair<GltfModel?, MutableList<GltfModelReceiver>>>
    ) {
        val useIris = isIrisLoaded()
        lookup.forEach { (_, pair) ->
            val gltfModel = pair.first ?: return@forEach
            val modelReceivers = pair.second
            val iterator = modelReceivers.iterator()
            while (iterator.hasNext()) {
                val receiver = iterator.next()
                if (receiver.isReceiveSharedModel(gltfModel, gltfRenderData)) {
                    val renderedModel = if (useIris) IrisRenderedGltfModel(gltfRenderData, gltfModel) else RenderedGltfModel(gltfRenderData, gltfModel)
                    receiver.onReceiveSharedModel(renderedModel)
                    while (iterator.hasNext()) {
                        val next = iterator.next()
                        if (next.isReceiveSharedModel(gltfModel, gltfRenderData)) {
                            next.onReceiveSharedModel(renderedModel)
                        }
                    }
                    return@forEach
                }
            }
        }
    }
    private fun cleanupGlBindings() {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)
        GL30.glBindVertexArray(0)
        when (glProfile) {
            is GLProfile.GL43 -> {
                GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0)
                GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0)
                GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0)
            }
            is GLProfile.GL40 -> {
                GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0)
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0)
                GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0)
            }
            is GLProfile.GL33 -> {
                GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0)
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0)
            }
            is GLProfile.GL30 -> {}
        }
    }
    private fun initGlResources() {
        glProfile = GLProfile.detect()
        glProgramSkinning = SkinningProgram.create(glProfile)
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
        val currentTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        defaultColorMap = createDefaultTexture(byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1))
        defaultNormalMap = createDefaultTexture(byteArrayOf(-128, -128, -1, -1, -128, -128, -1, -1, -128, -128, -1, -1, -128, -128, -1, -1))
        defaultSpecularMap = createDefaultTexture(byteArrayOf(0, 0, 0, -1, 0, 0, 0, -1, 0, 0, 0, -1, 0, 0, 0, -1))
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture)
    }
    private fun createDefaultTexture(pixels: ByteArray): Int {
        val tex = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 2, 2, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, Buffers.create(pixels))
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0)
        return tex
    }
    override fun onInitializeClient() {
        val configDir = FabricLoader.getInstance().configDir
        ConfigSystem.load(configDir)
        ConfigSystem.startWatching()
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            shaderModActive = BooleanSupplier { IrisApi.getInstance().isShaderPackInUse }
        }
        Minecraft.getInstance().execute { initGlResources() }
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(object : SimpleSynchronousResourceReloadListener {
            override fun getFabricId(): Identifier = Identifier.fromNamespaceAndPath(MODID, "gltf_reload_listener")
            override fun onResourceManagerReload(resourceManager: ResourceManager) {
                this@MCglTFSystem.onResourceManagerReload(resourceManager)
            }
        })
    }
}