package com.poly.mcgltf.cache
import com.poly.mcgltf.RenderedGltfModel
import net.minecraft.resources.Identifier
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
class ModelCachePool(private var maxSize: Int = 64) {
    private val lock = ReentrantReadWriteLock()
    private val cache = object : LinkedHashMap<Identifier, RenderedGltfModel>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Identifier, RenderedGltfModel>): Boolean {
            if (size > maxSize) {
                onEvict(eldest.value)
                return true
            }
            return false
        }
    }
    @Volatile var hitCount: Long = 0L
        private set
    @Volatile var queryCount: Long = 0L
        private set
    private var evictionCallback: ((RenderedGltfModel) -> Unit)? = null
    fun setEvictionCallback(callback: (RenderedGltfModel) -> Unit) { evictionCallback = callback }
    private fun onEvict(model: RenderedGltfModel) { evictionCallback?.invoke(model) }
    fun get(location: Identifier): RenderedGltfModel? = lock.read {
        queryCount++
        cache[location]?.also { hitCount++ }
    }
    fun put(location: Identifier, model: RenderedGltfModel) = lock.write {
        cache[location] = model
    }
    fun setMaxCacheSize(count: Int) = lock.write {
        maxSize = count
        while (cache.size > maxSize) {
            val eldest = cache.entries.iterator().next()
            onEvict(eldest.value)
            cache.remove(eldest.key)
        }
    }
    fun clear() = lock.write {
        cache.values.forEach(::onEvict)
        cache.clear()
        hitCount = 0L
        queryCount = 0L
    }
    fun size(): Int = lock.read { cache.size }
    fun hitRate(): Double = if (queryCount == 0L) 0.0 else hitCount.toDouble() / queryCount
}
