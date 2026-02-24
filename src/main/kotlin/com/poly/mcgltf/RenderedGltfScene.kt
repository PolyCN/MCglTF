package com.poly.mcgltf
open class RenderedGltfScene {
    val skinningCommands = mutableListOf<Runnable>()
    val vanillaRenderCommands = mutableListOf<Runnable>()
    val shaderModRenderCommands = mutableListOf<Runnable>()
    open fun renderForVanilla() {}
    open fun renderForShaderMod() {}
}
