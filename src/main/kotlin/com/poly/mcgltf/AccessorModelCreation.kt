package com.poly.mcgltf
import de.javagl.jgltf.model.AccessorDatas
import de.javagl.jgltf.model.AccessorFloatData
import de.javagl.jgltf.model.AccessorModel
import de.javagl.jgltf.model.ElementType
import de.javagl.jgltf.model.impl.DefaultAccessorModel
import de.javagl.jgltf.model.impl.DefaultBufferModel
import de.javagl.jgltf.model.impl.DefaultBufferViewModel
import de.javagl.jgltf.model.io.Buffers
import java.nio.ByteBuffer

object AccessorModelCreation {
    fun createAccessorModel(componentType: Int, count: Int, elementType: ElementType, bufferUriString: String): AccessorModel {
        val accessorModel = DefaultAccessorModel(componentType, count, elementType)
        val elementSize = accessorModel.elementSizeInBytes
        accessorModel.byteOffset = 0
        val bufferData = Buffers.create(count * elementSize)
        accessorModel.bufferViewModel = createBufferViewModel(bufferUriString, bufferData)
        accessorModel.accessorData = AccessorDatas.create(accessorModel)
        return accessorModel
    }
    fun instantiate(accessorModel: AccessorModel, bufferUriString: String): AccessorModel {
        val instantiated = createAccessorModel(accessorModel.componentType, accessorModel.count, accessorModel.elementType, bufferUriString)
        val src = accessorModel.accessorData as AccessorFloatData
        val dst = instantiated.accessorData as AccessorFloatData
        AccessorDataUtils.copyFloats(dst, src)
        return instantiated
    }
    private fun createBufferViewModel(uriString: String, bufferData: ByteBuffer): DefaultBufferViewModel {
        val bufferModel = DefaultBufferModel()
        bufferModel.uri = uriString
        bufferModel.bufferData = bufferData
        val bufferViewModel = DefaultBufferViewModel(null)
        bufferViewModel.byteOffset = 0
        bufferViewModel.byteLength = bufferData.capacity()
        bufferViewModel.bufferModel = bufferModel
        return bufferViewModel
    }
}
