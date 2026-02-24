package com.poly.mcgltf
import org.lwjgl.opengl.GL
sealed class GLProfile {
    data object GL43 : GLProfile()
    data object GL40 : GLProfile()
    data object GL33 : GLProfile()
    data object GL30 : GLProfile()
    companion object {
        fun detect(): GLProfile {
            val caps = GL.getCapabilities()
            return when {
                caps.glTexBufferRange != 0L -> GL43
                caps.glGenTransformFeedbacks != 0L -> GL40
                caps.OpenGL33 -> GL33
                else -> GL30
            }
        }
    }
}