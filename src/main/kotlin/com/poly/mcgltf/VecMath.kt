package com.poly.mcgltf
import de.javagl.jgltf.model.*
import kotlin.math.sqrt
object VecMath {
    fun subtract(a: FloatArray, b: FloatArray, result: FloatArray) {
        for (i in a.indices) result[i] = a[i] - b[i]
    }
    fun cross(a: FloatArray, b: FloatArray, result: FloatArray) {
        result[0] = a[1] * b[2] - a[2] * b[1]
        result[1] = a[2] * b[0] - a[0] * b[2]
        result[2] = a[0] * b[1] - a[1] * b[0]
    }
    fun normalize(a: FloatArray, result: FloatArray) {
        var sum = 0f
        for (v in a) sum += v * v
        val scale = 1.0f / sqrt(sum)
        for (i in a.indices) result[i] = a[i] * scale
    }
    fun accessorDataGetFloat(data: AccessorData, element: Int, component: Int): Float = when (data) {
        is AccessorFloatData -> data.get(element, component)
        is AccessorByteData -> if (data.isUnsigned) (data.getInt(element, component).toFloat() / 255f) else (data.get(element, component).toFloat() / 127f)
        is AccessorShortData -> if (data.isUnsigned) (data.getInt(element, component).toFloat() / 65535f) else (data.get(element, component).toFloat() / 32767f)
        is AccessorIntData -> data.get(element, component).toFloat()
        else -> 0f
    }
}
