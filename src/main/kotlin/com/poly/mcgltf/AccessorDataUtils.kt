package com.poly.mcgltf
import de.javagl.jgltf.model.AccessorByteData
import de.javagl.jgltf.model.AccessorData
import de.javagl.jgltf.model.AccessorFloatData
import de.javagl.jgltf.model.AccessorIntData
import de.javagl.jgltf.model.AccessorShortData
import kotlin.math.min
object AccessorDataUtils {
    fun readInts(accessorData: AccessorData): IntArray {
        val n = accessorData.numElements
        val c = accessorData.numComponentsPerElement
        return when (accessorData) {
            is AccessorByteData -> extractInts(n, c) { e, comp -> accessorData.getInt(e, comp) }
            is AccessorShortData -> extractInts(n, c) { e, comp -> accessorData.getInt(e, comp) }
            is AccessorIntData -> extractInts(n, c) { e, comp -> accessorData.get(e, comp) }
            else -> throw IllegalArgumentException("Not a valid index type: $accessorData")
        }
    }
    private inline fun extractInts(numElements: Int, numComponents: Int, getter: (Int, Int) -> Int): IntArray {
        val result = IntArray(numElements * numComponents)
        var idx = 0
        for (e in 0 until numElements) {
            for (c in 0 until numComponents) {
                result[idx++] = getter(e, c)
            }
        }
        return result
    }
    fun readFloats(accessorData: AccessorFloatData, numElements: Int, numComponents: Int): FloatArray {
        val result = FloatArray(numElements * numComponents)
        var idx = 0
        for (e in 0 until numElements) {
            for (c in 0 until numComponents) {
                result[idx++] = accessorData.get(e, c)
            }
        }
        return result
    }
    fun writeFloats(accessorData: AccessorFloatData, numElements: Int, numComponents: Int, data: FloatArray) {
        var idx = 0
        for (e in 0 until numElements) {
            for (c in 0 until numComponents) {
                accessorData.set(e, c, data[idx++])
            }
        }
    }
    fun copyFloats(target: AccessorFloatData, source: AccessorFloatData) {
        val numElements = min(target.numElements, source.numElements)
        val numComponents = min(target.numComponentsPerElement, source.numComponentsPerElement)
        for (e in 0 until numElements) {
            for (c in 0 until numComponents) {
                target.set(e, c, source.get(e, c))
            }
        }
    }
}
