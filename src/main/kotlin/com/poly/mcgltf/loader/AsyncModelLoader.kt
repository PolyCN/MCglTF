package com.poly.mcgltf.loader
import de.javagl.jgltf.model.GltfModel
import de.javagl.jgltf.model.io.GltfModelReader
import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.util.concurrent.ConcurrentHashMap
object AsyncModelLoader {
    enum class LoadState { LOADING, COMPLETED, FAILED }
    private val LOGGER = LoggerFactory.getLogger("MCglTF-Loader")
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val loadStates = ConcurrentHashMap<Identifier, LoadState>()
    fun loadAsync(location: Identifier, resourceManager: ResourceManager): Deferred<GltfModel?> {
        loadStates[location] = LoadState.LOADING
        return scope.async {
            try {
                val model = resourceManager.getResource(location).map { resource ->
                    resource.open().use { stream ->
                        GltfModelReader().readWithoutReferences(BufferedInputStream(stream))
                    }
                }.orElse(null)
                if (model != null) {
                    loadStates[location] = LoadState.COMPLETED
                } else {
                    loadStates[location] = LoadState.FAILED
                    LOGGER.error("""{"event":"model_not_found","location":"$location"}""")
                }
                model
            } catch (e: Exception) {
                loadStates[location] = LoadState.FAILED
                LOGGER.error("""{"event":"model_load_error","location":"$location","error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
                null
            }
        }
    }
    fun loadMultipleAsync(
        locations: Set<Identifier>,
        resourceManager: ResourceManager
    ): Deferred<Map<Identifier, GltfModel>> = scope.async {
        locations.map { location ->
            location to loadAsync(location, resourceManager)
        }.mapNotNull { (location, deferred) ->
            deferred.await()?.let { location to it }
        }.toMap()
    }
    fun scheduleOnMainThread(action: Runnable) {
        Minecraft.getInstance().execute(action)
    }
    fun getState(location: Identifier): LoadState? = loadStates[location]
    fun cancelAll() {
        scope.coroutineContext.cancelChildren()
        loadStates.clear()
    }
    fun clearStates() { loadStates.clear() }
}