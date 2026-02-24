package com.poly.mcgltf
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
object GltfVanillaProgram {
    private var program = 0
    var uModelViewMat = -1
        private set
    var uProjMat = -1
        private set
    var uLight0Direction = -1
        private set
    var uLight1Direction = -1
        private set
    var uColorModulator = -1
        private set
    var uFogStart = -1
        private set
    var uFogEnd = -1
        private set
    var uFogColor = -1
        private set
    private const val VERTEX_SOURCE =
        "#version 150\n" +
        "in vec3 Position;\n" +
        "in vec4 Color;\n" +
        "in vec2 UV0;\n" +
        "in ivec2 UV1;\n" +
        "in ivec2 UV2;\n" +
        "in vec3 Normal;\n" +
        "uniform mat4 ModelViewMat;\n" +
        "uniform mat4 ProjMat;\n" +
        "uniform vec3 Light0_Direction;\n" +
        "uniform vec3 Light1_Direction;\n" +
        "out float vertexDistance;\n" +
        "out vec4 vertexColor;\n" +
        "out vec4 lightMapColor;\n" +
        "out vec4 overlayColor;\n" +
        "out vec2 texCoord0;\n" +
        "out vec3 normal;\n" +
        "void main() {\n" +
        "gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
        "vertexDistance = length((ModelViewMat * vec4(Position, 1.0)).xyz);\n" +
        "float light0 = max(0.0, dot(normalize((ModelViewMat * vec4(Normal, 0.0)).xyz), Light0_Direction));\n" +
        "float light1 = max(0.0, dot(normalize((ModelViewMat * vec4(Normal, 0.0)).xyz), Light1_Direction));\n" +
        "float brightness = min(1.0, light0 + light1 + 0.4);\n" +
        "vertexColor = Color * vec4(brightness, brightness, brightness, 1.0);\n" +
        "lightMapColor = vec4(1.0);\n" +
        "overlayColor = vec4(1.0);\n" +
        "texCoord0 = UV0;\n" +
        "normal = Normal;\n" +
        "}\n"
    private const val FRAGMENT_SOURCE =
        "#version 150\n" +
        "uniform sampler2D Sampler0;\n" +
        "uniform vec4 ColorModulator;\n" +
        "uniform float FogStart;\n" +
        "uniform float FogEnd;\n" +
        "uniform vec4 FogColor;\n" +
        "in float vertexDistance;\n" +
        "in vec4 vertexColor;\n" +
        "in vec4 lightMapColor;\n" +
        "in vec4 overlayColor;\n" +
        "in vec2 texCoord0;\n" +
        "in vec3 normal;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;\n" +
        "if (color.a < 0.1) discard;\n" +
        "float fogFactor = clamp((FogEnd - vertexDistance) / (FogEnd - FogStart), 0.0, 1.0);\n" +
        "fragColor = vec4(mix(FogColor.rgb, color.rgb, fogFactor), color.a);\n" +
        "}\n"
    fun create(): Int {
        if (program != 0) return program
        val vertShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SOURCE)
        val fragShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SOURCE)
        program = GL20.glCreateProgram()
        GL20.glAttachShader(program, vertShader)
        GL20.glAttachShader(program, fragShader)
        GL20.glBindAttribLocation(program, RenderedGltfModel.vaPosition, "Position")
        GL20.glBindAttribLocation(program, RenderedGltfModel.vaColor, "Color")
        GL20.glBindAttribLocation(program, RenderedGltfModel.vaUV0, "UV0")
        GL20.glBindAttribLocation(program, RenderedGltfModel.vaUV1, "UV1")
        GL20.glBindAttribLocation(program, RenderedGltfModel.vaUV2, "UV2")
        GL20.glBindAttribLocation(program, RenderedGltfModel.vaNormal, "Normal")
        GL20.glLinkProgram(program)
        GL20.glDeleteShader(vertShader)
        GL20.glDeleteShader(fragShader)
        cacheUniformLocations()
        return program
    }
    private fun compileShader(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        return shader
    }
    private fun cacheUniformLocations() {
        uModelViewMat = GL20.glGetUniformLocation(program, "ModelViewMat")
        uProjMat = GL20.glGetUniformLocation(program, "ProjMat")
        uLight0Direction = GL20.glGetUniformLocation(program, "Light0_Direction")
        uLight1Direction = GL20.glGetUniformLocation(program, "Light1_Direction")
        uColorModulator = GL20.glGetUniformLocation(program, "ColorModulator")
        uFogStart = GL20.glGetUniformLocation(program, "FogStart")
        uFogEnd = GL20.glGetUniformLocation(program, "FogEnd")
        uFogColor = GL20.glGetUniformLocation(program, "FogColor")
        GL20.glUseProgram(program)
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler0"), 0)
        GL20.glUseProgram(0)
    }
    fun getId(): Int = program
    fun destroy() {
        if (program != 0) {
            GL20.glDeleteProgram(program)
            program = 0
        }
    }
}
