package com.poly.mcgltf
import de.javagl.jgltf.model.GltfModel
import net.minecraft.resources.Identifier
interface GltfModelReceiver {
    fun getModelLocation(): Identifier
    fun onReceiveSharedModel(renderedModel: RenderedGltfModel) {}
    fun isReceiveSharedModel(gltfModel: GltfModel, gltfRenderData: MutableList<Runnable>): Boolean = true
}