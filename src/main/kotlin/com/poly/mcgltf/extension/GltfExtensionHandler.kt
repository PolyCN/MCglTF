package com.poly.mcgltf.extension
import de.javagl.jgltf.impl.v2.GlTFProperty
import de.javagl.jgltf.impl.v2.Material
import de.javagl.jgltf.model.MaterialModel
import java.util.concurrent.ConcurrentHashMap
data class TextureTransform(
    val offset: FloatArray = floatArrayOf(0f, 0f),
    val scale: FloatArray = floatArrayOf(1f, 1f),
    val rotation: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextureTransform) return false
        return offset.contentEquals(other.offset) && scale.contentEquals(other.scale) && rotation == other.rotation
    }
    override fun hashCode(): Int {
        var result = offset.contentHashCode()
        result = 31 * result + scale.contentHashCode()
        result = 31 * result + rotation.hashCode()
        return result
    }
}
data class ExtensionResult(
    val unlit: Boolean = false,
    val emissiveStrength: Float = 1.0f,
    val baseColorTextureTransform: TextureTransform? = null,
    val normalTextureTransform: TextureTransform? = null,
    val emissiveTextureTransform: TextureTransform? = null,
    val metallicRoughnessTextureTransform: TextureTransform? = null,
    val occlusionTextureTransform: TextureTransform? = null
)
fun interface GltfExtensionProcessor {
    fun process(extensions: Map<String, Any>, builder: ExtensionResultBuilder)
}
class ExtensionResultBuilder {
    var unlit: Boolean = false
    var emissiveStrength: Float = 1.0f
    var baseColorTextureTransform: TextureTransform? = null
    var normalTextureTransform: TextureTransform? = null
    var emissiveTextureTransform: TextureTransform? = null
    var metallicRoughnessTextureTransform: TextureTransform? = null
    var occlusionTextureTransform: TextureTransform? = null
    fun build(): ExtensionResult = ExtensionResult(
        unlit, emissiveStrength,
        baseColorTextureTransform, normalTextureTransform,
        emissiveTextureTransform, metallicRoughnessTextureTransform,
        occlusionTextureTransform
    )
}

object GltfExtensionHandler {
    private const val KHR_MATERIALS_UNLIT = "KHR_materials_unlit"
    private const val KHR_TEXTURE_TRANSFORM = "KHR_texture_transform"
    private const val KHR_MATERIALS_EMISSIVE_STRENGTH = "KHR_materials_emissive_strength"
    private val materialProcessors = ConcurrentHashMap<String, GltfExtensionProcessor>()
    private val cache = ConcurrentHashMap<MaterialModel, ExtensionResult>()
    init {
        registerMaterialProcessor(KHR_MATERIALS_UNLIT, GltfExtensionProcessor { _, builder ->
            builder.unlit = true
        })
        registerMaterialProcessor(KHR_MATERIALS_EMISSIVE_STRENGTH, GltfExtensionProcessor { extensions, builder ->
            val ext = extensions[KHR_MATERIALS_EMISSIVE_STRENGTH] as? Map<*, *> ?: return@GltfExtensionProcessor
            val strength = ext["emissiveStrength"]
            if (strength is Number) builder.emissiveStrength = strength.toFloat()
        })
    }
    fun registerMaterialProcessor(extensionName: String, processor: GltfExtensionProcessor) {
        materialProcessors[extensionName] = processor
    }
    fun removeMaterialProcessor(extensionName: String) {
        materialProcessors.remove(extensionName)
    }
    fun process(materialModel: MaterialModel): ExtensionResult {
        return cache.getOrPut(materialModel) { processFromModelOnly(materialModel) }
    }
    fun process(materialModel: MaterialModel, rawMaterial: Material): ExtensionResult {
        return cache.getOrPut(materialModel) { processWithRawMaterial(materialModel, rawMaterial) }
    }
    fun clearCache() {
        cache.clear()
    }
    private fun processFromModelOnly(materialModel: MaterialModel): ExtensionResult {
        val extensions = materialModel.extensions
        if (extensions == null || extensions.isEmpty()) return ExtensionResult()
        val builder = ExtensionResultBuilder()
        applyMaterialProcessors(extensions, builder)
        return builder.build()
    }
    private fun processWithRawMaterial(materialModel: MaterialModel, rawMaterial: Material): ExtensionResult {
        val builder = ExtensionResultBuilder()
        val extensions = materialModel.extensions
        if (extensions != null && extensions.isNotEmpty()) {
            applyMaterialProcessors(extensions, builder)
        }
        extractTextureTransforms(rawMaterial, builder)
        return builder.build()
    }
    @Suppress("UNCHECKED_CAST")
    private fun applyMaterialProcessors(extensions: Map<String, Any>, builder: ExtensionResultBuilder) {
        for ((name, processor) in materialProcessors) {
            if (extensions.containsKey(name)) {
                processor.process(extensions, builder)
            }
        }
    }
    private fun extractTextureTransforms(rawMaterial: Material, builder: ExtensionResultBuilder) {
        rawMaterial.pbrMetallicRoughness?.let { pbr ->
            pbr.baseColorTexture?.let { builder.baseColorTextureTransform = extractTextureTransform(it) }
            pbr.metallicRoughnessTexture?.let { builder.metallicRoughnessTextureTransform = extractTextureTransform(it) }
        }
        rawMaterial.normalTexture?.let { builder.normalTextureTransform = extractTextureTransform(it) }
        rawMaterial.emissiveTexture?.let { builder.emissiveTextureTransform = extractTextureTransform(it) }
        rawMaterial.occlusionTexture?.let { builder.occlusionTextureTransform = extractTextureTransform(it) }
    }
    private fun extractTextureTransform(textureInfo: GlTFProperty): TextureTransform? {
        val extensions = textureInfo.extensions ?: return null
        val transformData = extensions[KHR_TEXTURE_TRANSFORM] as? Map<*, *> ?: return null
        return parseTextureTransform(transformData)
    }
    private fun parseTextureTransform(data: Map<*, *>): TextureTransform {
        val offset = parseFloatArray(data["offset"], floatArrayOf(0f, 0f))
        val scale = parseFloatArray(data["scale"], floatArrayOf(1f, 1f))
        val rotation = (data["rotation"] as? Number)?.toFloat() ?: 0f
        return TextureTransform(offset, scale, rotation)
    }
    @Suppress("UNCHECKED_CAST")
    private fun parseFloatArray(value: Any?, default: FloatArray): FloatArray {
        if (value == null) return default
        return when (value) {
            is List<*> -> {
                val list = value as List<Number>
                FloatArray(list.size) { list[it].toFloat() }
            }
            is FloatArray -> value
            is DoubleArray -> FloatArray(value.size) { value[it].toFloat() }
            else -> default
        }
    }
}
