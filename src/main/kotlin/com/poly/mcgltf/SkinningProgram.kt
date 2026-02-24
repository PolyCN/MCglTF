package com.poly.mcgltf
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
object SkinningProgram {
    private const val SHADER_GL43 =
        "#version 430\r\n" +
        "layout(location = 0) in vec4 joint;" +
        "layout(location = 1) in vec4 weight;" +
        "layout(location = 2) in vec3 position;" +
        "layout(location = 3) in vec3 normal;" +
        "layout(location = 4) in vec4 tangent;" +
        "layout(std430, binding = 0) readonly buffer jointMatrixBuffer {mat4 jointMatrices[];};" +
        "out vec3 outPosition;" +
        "out vec3 outNormal;" +
        "out vec4 outTangent;" +
        "void main() {" +
        "mat4 skinMatrix =" +
        " weight.x * jointMatrices[int(joint.x)] +" +
        " weight.y * jointMatrices[int(joint.y)] +" +
        " weight.z * jointMatrices[int(joint.z)] +" +
        " weight.w * jointMatrices[int(joint.w)];" +
        "outPosition = (skinMatrix * vec4(position, 1.0)).xyz;" +
        "mat3 upperLeft = mat3(skinMatrix);" +
        "outNormal = upperLeft * normal;" +
        "outTangent.xyz = upperLeft * tangent.xyz;" +
        "outTangent.w = tangent.w;" +
        "}"
    private const val SHADER_GL33 =
        "#version 330\r\n" +
        "layout(location = 0) in vec4 joint;" +
        "layout(location = 1) in vec4 weight;" +
        "layout(location = 2) in vec3 position;" +
        "layout(location = 3) in vec3 normal;" +
        "layout(location = 4) in vec4 tangent;" +
        "uniform samplerBuffer jointMatrices;" +
        "out vec3 outPosition;" +
        "out vec3 outNormal;" +
        "out vec4 outTangent;" +
        "void main() {" +
        "int jx = int(joint.x) * 4;" +
        "int jy = int(joint.y) * 4;" +
        "int jz = int(joint.z) * 4;" +
        "int jw = int(joint.w) * 4;" +
        "mat4 skinMatrix =" +
        " weight.x * mat4(texelFetch(jointMatrices, jx), texelFetch(jointMatrices, jx + 1), texelFetch(jointMatrices, jx + 2), texelFetch(jointMatrices, jx + 3)) +" +
        " weight.y * mat4(texelFetch(jointMatrices, jy), texelFetch(jointMatrices, jy + 1), texelFetch(jointMatrices, jy + 2), texelFetch(jointMatrices, jy + 3)) +" +
        " weight.z * mat4(texelFetch(jointMatrices, jz), texelFetch(jointMatrices, jz + 1), texelFetch(jointMatrices, jz + 2), texelFetch(jointMatrices, jz + 3)) +" +
        " weight.w * mat4(texelFetch(jointMatrices, jw), texelFetch(jointMatrices, jw + 1), texelFetch(jointMatrices, jw + 2), texelFetch(jointMatrices, jw + 3));" +
        "outPosition = (skinMatrix * vec4(position, 1.0)).xyz;" +
        "mat3 upperLeft = mat3(skinMatrix);" +
        "outNormal = upperLeft * normal;" +
        "outTangent.xyz = upperLeft * tangent.xyz;" +
        "outTangent.w = tangent.w;" +
        "}"
    private val FEEDBACK_VARYINGS = arrayOf<CharSequence>("outPosition", "outNormal", "outTangent")
    fun create(profile: GLProfile): Int = when (profile) {
        is GLProfile.GL43 -> compileAndLink(SHADER_GL43)
        is GLProfile.GL40, is GLProfile.GL33 -> compileAndLink(SHADER_GL33)
        is GLProfile.GL30 -> -1
    }
    private fun compileAndLink(source: String): Int {
        val shader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        val program = GL20.glCreateProgram()
        GL20.glAttachShader(program, shader)
        GL20.glDeleteShader(shader)
        GL30.glTransformFeedbackVaryings(program, FEEDBACK_VARYINGS, GL30.GL_SEPARATE_ATTRIBS)
        GL20.glLinkProgram(program)
        return program
    }
}
