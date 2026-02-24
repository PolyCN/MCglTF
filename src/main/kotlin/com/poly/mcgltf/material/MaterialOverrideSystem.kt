package com.poly.mcgltf.material
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
@Serializable
data class MaterialOverrideEntry(
    val materialName: String,
    val baseColorTexture: String? = null,
    val normalTexture: String? = null,
    val emissiveTexture: String? = null,
    val baseColorFactor: List<Float>? = null,
    val metallic: Float? = null,
    val roughness: Float? = null
)
@Serializable
data class MaterialOverrideConfig(
    val overrides: List<MaterialOverrideEntry> = emptyList()
)
data class ResolvedOverride(
    val baseColorTexture: Identifier? = null,
    val normalTexture: Identifier? = null,
    val emissiveTexture: Identifier? = null,
    val baseColorFactor: FloatArray? = null,
    val metallic: Float? = null,
    val roughness: Float? = null,
    val priority: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedOverride) return false
        return baseColorTexture == other.baseColorTexture
                && normalTexture == other.normalTexture
                && emissiveTexture == other.emissiveTexture
                && baseColorFactor.contentEquals(other.baseColorFactor)
                && metallic == other.metallic
                && roughness == other.roughness
                && priority == other.priority
    }
    override fun hashCode(): Int {
        var result = baseColorTexture?.hashCode() ?: 0
        result = 31 * result + (normalTexture?.hashCode() ?: 0)
        result = 31 * result + (emissiveTexture?.hashCode() ?: 0)
        result = 31 * result + (baseColorFactor?.contentHashCode() ?: 0)
        result = 31 * result + (metallic?.hashCode() ?: 0)
        result = 31 * result + (roughness?.hashCode() ?: 0)
        result = 31 * result + priority
        return result
    }
    private fun FloatArray?.contentEquals(other: FloatArray?): Boolean = when {
        this == null && other == null -> true
        this != null && other != null -> this.contentEquals(other)
        else -> false
    }
    private fun FloatArray?.contentHashCode(): Int = this?.contentHashCode() ?: 0
}

object MaterialOverrideSystem {
    private val LOGGER = LoggerFactory.getLogger("MCglTF-MaterialOverride")
    private val JSON = Json { ignoreUnknownKeys = true }
    private const val OVERRIDE_PATH_PREFIX = "mcgltf_overrides"
    private val resourcePackOverrides = ConcurrentHashMap<OverrideKey, ResolvedOverride>()
    private val apiOverrides = ConcurrentHashMap<OverrideKey, ResolvedOverride>()
    private data class OverrideKey(val modelLocation: Identifier, val materialName: String)
    fun registerOverride(
        modelLocation: Identifier,
        materialName: String,
        override: ResolvedOverride
    ) {
        apiOverrides[OverrideKey(modelLocation, materialName)] = override.copy(priority = 1)
    }
    fun removeOverride(modelLocation: Identifier, materialName: String) {
        apiOverrides.remove(OverrideKey(modelLocation, materialName))
    }
    fun clearApiOverrides() {
        apiOverrides.clear()
    }
    fun clearApiOverrides(modelLocation: Identifier) {
        apiOverrides.keys.removeIf { it.modelLocation == modelLocation }
    }
    fun getOverride(modelLocation: Identifier, materialName: String): ResolvedOverride? {
        val key = OverrideKey(modelLocation, materialName)
        return apiOverrides[key] ?: resourcePackOverrides[key]
    }
    fun getEffectiveBaseColorTexture(
        modelLocation: Identifier, materialName: String,
        original: Identifier?, resourceManager: ResourceManager
    ): Identifier? {
        val override = getOverride(modelLocation, materialName) ?: return original
        val overrideTex = override.baseColorTexture ?: return original
        return if (resourceExists(resourceManager, overrideTex)) overrideTex else original
    }
    fun getEffectiveNormalTexture(
        modelLocation: Identifier, materialName: String,
        original: Identifier?, resourceManager: ResourceManager
    ): Identifier? {
        val override = getOverride(modelLocation, materialName) ?: return original
        val overrideTex = override.normalTexture ?: return original
        return if (resourceExists(resourceManager, overrideTex)) overrideTex else original
    }
    fun getEffectiveEmissiveTexture(
        modelLocation: Identifier, materialName: String,
        original: Identifier?, resourceManager: ResourceManager
    ): Identifier? {
        val override = getOverride(modelLocation, materialName) ?: return original
        val overrideTex = override.emissiveTexture ?: return original
        return if (resourceExists(resourceManager, overrideTex)) overrideTex else original
    }
    fun getEffectiveBaseColorFactor(
        modelLocation: Identifier, materialName: String,
        original: FloatArray
    ): FloatArray {
        val override = getOverride(modelLocation, materialName) ?: return original
        return override.baseColorFactor ?: original
    }
    fun getEffectiveMetallic(
        modelLocation: Identifier, materialName: String,
        original: Float
    ): Float {
        val override = getOverride(modelLocation, materialName) ?: return original
        return override.metallic ?: original
    }
    fun getEffectiveRoughness(
        modelLocation: Identifier, materialName: String,
        original: Float
    ): Float {
        val override = getOverride(modelLocation, materialName) ?: return original
        return override.roughness ?: original
    }
    fun onResourceManagerReload(resourceManager: ResourceManager) {
        resourcePackOverrides.clear()
        val resources = resourceManager.listResources(OVERRIDE_PATH_PREFIX) { id ->
            id.path.endsWith(".json")
        }
        resources.forEach { (id, resource) ->
            try {
                val text = resource.open().buffered().use { it.reader().readText() }
                val config = JSON.decodeFromString(MaterialOverrideConfig.serializer(), text)
                val modelName = extractModelName(id)
                val modelLocation = Identifier.fromNamespaceAndPath(id.namespace, modelName)
                config.overrides.forEach { entry ->
                    val resolved = resolveEntry(entry)
                    resourcePackOverrides[OverrideKey(modelLocation, entry.materialName)] = resolved
                }
            } catch (e: SerializationException) {
                LOGGER.error("""{"event":"override_parse_error","id":"$id","error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
            } catch (e: Exception) {
                LOGGER.error("""{"event":"override_load_error","id":"$id","error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
            }
        }
    }
    private fun extractModelName(overrideId: Identifier): String {
        val path = overrideId.path
        val prefixEnd = path.indexOf(OVERRIDE_PATH_PREFIX) + OVERRIDE_PATH_PREFIX.length + 1
        val withoutPrefix = if (prefixEnd < path.length) path.substring(prefixEnd) else path
        return if (withoutPrefix.endsWith(".json")) withoutPrefix.substring(0, withoutPrefix.length - 5) else withoutPrefix
    }
    private fun resolveEntry(entry: MaterialOverrideEntry): ResolvedOverride = ResolvedOverride(
        baseColorTexture = entry.baseColorTexture?.let { Identifier.tryParse(it) },
        normalTexture = entry.normalTexture?.let { Identifier.tryParse(it) },
        emissiveTexture = entry.emissiveTexture?.let { Identifier.tryParse(it) },
        baseColorFactor = entry.baseColorFactor?.takeIf { it.size >= 4 }?.toFloatArray(),
        metallic = entry.metallic,
        roughness = entry.roughness,
        priority = 0
    )
    private fun resourceExists(resourceManager: ResourceManager, location: Identifier): Boolean =
        resourceManager.getResource(location).isPresent
}