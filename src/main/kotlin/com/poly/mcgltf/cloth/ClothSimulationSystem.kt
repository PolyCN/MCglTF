package com.poly.mcgltf.cloth
import com.poly.mcgltf.RenderedGltfModel
import com.poly.mcgltf.config.ConfigSystem
import de.javagl.jgltf.model.*
import org.joml.Vector3f
import org.lwjgl.opengl.GL15
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
class ClothMesh(
    val vertexCount: Int,
    val positions: FloatArray,
    val prevPositions: FloatArray,
    val normals: FloatArray,
    val constraints: IntArray,
    val restLengths: FloatArray,
    val pinned: BooleanArray,
    var gravity: Float = 9.8f,
    var damping: Float = 0.01f,
    var iterations: Int = 5,
    val windDirection: Vector3f = Vector3f(),
    var windStrength: Float = 0.0f,
    var maxDistance: Float = 10.0f,
    val anchorPositions: FloatArray = FloatArray(vertexCount * 3)
)
object ClothSimulationSystem {
    private val modelClothMeshes = ConcurrentHashMap<GltfModel, List<ClothMeshBinding>>()
    private val tmpVec = Vector3f()
    data class ClothMeshBinding(
        val mesh: ClothMesh,
        val nodeModel: NodeModel,
        val glBuffer: Int,
        val vertexOffset: Int
    )
    fun isEnabled(): Boolean = ConfigSystem.isSubsystemEnabled("clothSimulation")
    fun hasClothSim(nodeModel: NodeModel): Boolean {
        val extras = nodeModel.extras ?: return false
        if (extras is Map<*, *>) return extras["cloth_sim"] == true
        return false
    }
    fun parseClothParams(nodeModel: NodeModel): ClothParams {
        val extras = nodeModel.extras
        val params = ClothParams()
        if (extras !is Map<*, *>) return params
        (extras["gravity"] as? Number)?.let { params.gravity = it.toFloat() }
        (extras["damping"] as? Number)?.let { params.damping = it.toFloat() }
        (extras["iterations"] as? Number)?.let { params.iterations = it.toInt() }
        (extras["wind_strength"] as? Number)?.let { params.windStrength = it.toFloat() }
        (extras["max_distance"] as? Number)?.let { params.maxDistance = it.toFloat() }
        val windDir = extras["wind_direction"]
        if (windDir is List<*> && windDir.size >= 3) {
            params.windDirection.set(
                (windDir[0] as? Number)?.toFloat() ?: 0f,
                (windDir[1] as? Number)?.toFloat() ?: 0f,
                (windDir[2] as? Number)?.toFloat() ?: 0f
            )
        }
        val pinnedSet = extras["pinned_vertices"]
        if (pinnedSet is List<*>) {
            params.pinnedVertices = pinnedSet.mapNotNull { (it as? Number)?.toInt() }.toSet()
        }
        return params
    }
    class ClothParams(
        var gravity: Float = 9.8f,
        var damping: Float = 0.01f,
        var iterations: Int = 5,
        val windDirection: Vector3f = Vector3f(),
        var windStrength: Float = 0.0f,
        var maxDistance: Float = 10.0f,
        var pinnedVertices: Set<Int> = emptySet()
    )
    fun createFromMesh(vertices: FloatArray, indices: IntArray, pinnedVertices: Set<Int>, params: ClothParams = ClothParams()): ClothMesh {
        val vertexCount = vertices.size / 3
        val positions = vertices.copyOf()
        val prevPositions = vertices.copyOf()
        val normals = FloatArray(vertexCount * 3)
        val pinned = BooleanArray(vertexCount)
        pinnedVertices.forEach { if (it in 0 until vertexCount) pinned[it] = true }
        val anchorPositions = vertices.copyOf()
        val edgeSet = LinkedHashSet<Long>()
        val triCount = indices.size / 3
        for (t in 0 until triCount) {
            val i0 = indices[t * 3]; val i1 = indices[t * 3 + 1]; val i2 = indices[t * 3 + 2]
            addEdge(edgeSet, i0, i1)
            addEdge(edgeSet, i1, i2)
            addEdge(edgeSet, i2, i0)
        }
        val constraintCount = edgeSet.size
        val constraints = IntArray(constraintCount * 2)
        val restLengths = FloatArray(constraintCount)
        var ci = 0
        for (edge in edgeSet) {
            val a = (edge shr 32).toInt()
            val b = (edge and 0xFFFFFFFFL).toInt()
            constraints[ci * 2] = a
            constraints[ci * 2 + 1] = b
            restLengths[ci] = vertexDistance(positions, a, b)
            ci++
        }
        computeNormals(positions, indices, normals, vertexCount)
        return ClothMesh(
            vertexCount, positions, prevPositions, normals, constraints, restLengths, pinned,
            params.gravity, params.damping, params.iterations,
            Vector3f(params.windDirection), params.windStrength, params.maxDistance, anchorPositions
        )
    }
    fun simulate(mesh: ClothMesh, dt: Float) {
        if (!isEnabled()) return
        val dt2 = dt * dt
        val oneMinusDamp = 1f - mesh.damping
        val grav = mesh.gravity
        val windX = mesh.windDirection.x * mesh.windStrength
        val windY = mesh.windDirection.y * mesh.windStrength
        val windZ = mesh.windDirection.z * mesh.windStrength
        val pos = mesh.positions
        val prev = mesh.prevPositions
        val pin = mesh.pinned
        val anchor = mesh.anchorPositions
        val maxDist2 = mesh.maxDistance * mesh.maxDistance
        for (i in 0 until mesh.vertexCount) {
            val ix = i * 3; val iy = ix + 1; val iz = ix + 2
            if (pin[i]) {
                pos[ix] = anchor[ix]; pos[iy] = anchor[iy]; pos[iz] = anchor[iz]
                prev[ix] = anchor[ix]; prev[iy] = anchor[iy]; prev[iz] = anchor[iz]
                continue
            }
            val px = pos[ix]; val py = pos[iy]; val pz = pos[iz]
            val vx = (px - prev[ix]) * oneMinusDamp
            val vy = (py - prev[iy]) * oneMinusDamp
            val vz = (pz - prev[iz]) * oneMinusDamp
            prev[ix] = px; prev[iy] = py; prev[iz] = pz
            pos[ix] = px + vx + windX * dt2
            pos[iy] = py + vy - grav * dt2 + windY * dt2
            pos[iz] = pz + vz + windZ * dt2
        }
        val constraints = mesh.constraints
        val restLengths = mesh.restLengths
        val constraintCount = restLengths.size
        repeat(mesh.iterations) {
            for (c in 0 until constraintCount) {
                val ai = constraints[c * 2]; val bi = constraints[c * 2 + 1]
                val ax = ai * 3; val ay = ax + 1; val az = ax + 2
                val bx = bi * 3; val by = bx + 1; val bz = bx + 2
                val dx = pos[bx] - pos[ax]; val dy = pos[by] - pos[ay]; val dz = pos[bz] - pos[az]
                val dist2 = dx * dx + dy * dy + dz * dz
                if (dist2 < 1e-12f) continue
                val dist = sqrt(dist2)
                val diff = (restLengths[c] - dist) / dist
                val pinA = pin[ai]; val pinB = pin[bi]
                if (pinA && pinB) continue
                val factor = if (pinA || pinB) diff else diff * 0.5f
                if (!pinA) {
                    val m = if (pinB) diff else factor
                    pos[ax] -= dx * m; pos[ay] -= dy * m; pos[az] -= dz * m
                }
                if (!pinB) {
                    val m = if (pinA) diff else factor
                    pos[bx] += dx * m; pos[by] += dy * m; pos[bz] += dz * m
                }
            }
        }
        for (i in 0 until mesh.vertexCount) {
            if (pin[i]) continue
            val ix = i * 3; val iy = ix + 1; val iz = ix + 2
            val nearestPin = findNearestPinned(mesh, i)
            if (nearestPin < 0) continue
            val px = nearestPin * 3
            val dx = pos[ix] - anchor[px]; val dy = pos[iy] - anchor[px + 1]; val dz = pos[iz] - anchor[px + 2]
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > maxDist2) {
                val scale = mesh.maxDistance / sqrt(d2)
                pos[ix] = anchor[px] + dx * scale
                pos[iy] = anchor[px + 1] + dy * scale
                pos[iz] = anchor[px + 2] + dz * scale
            }
        }
    }
    fun updatePinnedPositions(mesh: ClothMesh, nodeModel: NodeModel) {
        val globalTransform = RenderedGltfModel.findGlobalTransform(nodeModel)
        for (i in 0 until mesh.vertexCount) {
            if (!mesh.pinned[i]) continue
            val ix = i * 3
            val ox = mesh.anchorPositions[ix]; val oy = mesh.anchorPositions[ix + 1]; val oz = mesh.anchorPositions[ix + 2]
            mesh.positions[ix] = globalTransform[0] * ox + globalTransform[4] * oy + globalTransform[8] * oz + globalTransform[12]
            mesh.positions[ix + 1] = globalTransform[1] * ox + globalTransform[5] * oy + globalTransform[9] * oz + globalTransform[13]
            mesh.positions[ix + 2] = globalTransform[2] * ox + globalTransform[6] * oy + globalTransform[10] * oz + globalTransform[14]
            mesh.prevPositions[ix] = mesh.positions[ix]
            mesh.prevPositions[ix + 1] = mesh.positions[ix + 1]
            mesh.prevPositions[ix + 2] = mesh.positions[ix + 2]
        }
    }
    fun writeBack(mesh: ClothMesh, vertexBuffer: FloatBuffer) {
        vertexBuffer.clear()
        vertexBuffer.put(mesh.positions, 0, mesh.vertexCount * 3)
        vertexBuffer.flip()
    }
    fun writeBackToGlBuffer(mesh: ClothMesh, glBuffer: Int, offset: Long) {
        val buf = ByteBuffer.allocateDirect(mesh.vertexCount * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(mesh.positions, 0, mesh.vertexCount * 3)
        buf.flip()
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, buf)
    }
    fun writeBackNormalsToGlBuffer(mesh: ClothMesh, indices: IntArray, glBuffer: Int, offset: Long) {
        computeNormals(mesh.positions, indices, mesh.normals, mesh.vertexCount)
        val buf = ByteBuffer.allocateDirect(mesh.vertexCount * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(mesh.normals, 0, mesh.vertexCount * 3)
        buf.flip()
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBuffer)
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, offset, buf)
    }
    fun extractVerticesFromAccessor(accessor: AccessorModel): FloatArray? {
        val data = accessor.accessorData
        if (data !is AccessorFloatData) return null
        val count = accessor.count
        val result = FloatArray(count * 3)
        for (i in 0 until count) {
            result[i * 3] = data.get(i, 0)
            result[i * 3 + 1] = data.get(i, 1)
            result[i * 3 + 2] = data.get(i, 2)
        }
        return result
    }
    fun extractIndicesFromAccessor(accessor: AccessorModel): IntArray {
        val data = accessor.accessorData
        val count = accessor.count
        val result = IntArray(count)
        when (data) {
            is AccessorIntData -> for (i in 0 until count) result[i] = data.get(i, 0)
            is AccessorShortData -> for (i in 0 until count) result[i] = data.get(i, 0).toInt() and 0xFFFF
            is AccessorByteData -> for (i in 0 until count) result[i] = data.get(i, 0).toInt() and 0xFF
        }
        return result
    }
    fun registerModel(model: GltfModel, bindings: List<ClothMeshBinding>) {
        if (bindings.isNotEmpty()) modelClothMeshes[model] = bindings
    }
    fun getBindings(model: GltfModel): List<ClothMeshBinding>? = modelClothMeshes[model]
    fun removeModel(model: GltfModel) { modelClothMeshes.remove(model) }
    fun clearAll() { modelClothMeshes.clear() }
    private fun addEdge(set: MutableSet<Long>, a: Int, b: Int) {
        val lo = minOf(a, b); val hi = maxOf(a, b)
        set.add((lo.toLong() shl 32) or hi.toLong())
    }
    private fun vertexDistance(positions: FloatArray, a: Int, b: Int): Float {
        val ax = a * 3; val bx = b * 3
        val dx = positions[bx] - positions[ax]
        val dy = positions[bx + 1] - positions[ax + 1]
        val dz = positions[bx + 2] - positions[ax + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    private fun findNearestPinned(mesh: ClothMesh, vertexIndex: Int): Int {
        var best = -1; var bestDist = Float.MAX_VALUE
        val pos = mesh.positions
        val ix = vertexIndex * 3
        val vx = pos[ix]; val vy = pos[ix + 1]; val vz = pos[ix + 2]
        for (i in 0 until mesh.vertexCount) {
            if (!mesh.pinned[i]) continue
            val px = i * 3
            val dx = mesh.anchorPositions[px] - vx; val dy = mesh.anchorPositions[px + 1] - vy; val dz = mesh.anchorPositions[px + 2] - vz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 < bestDist) { bestDist = d2; best = i }
        }
        return best
    }
    private fun computeNormals(positions: FloatArray, indices: IntArray, normals: FloatArray, vertexCount: Int) {
        Arrays.fill(normals, 0f)
        val triCount = indices.size / 3
        for (t in 0 until triCount) {
            val i0 = indices[t * 3]; val i1 = indices[t * 3 + 1]; val i2 = indices[t * 3 + 2]
            val p0x = i0 * 3; val p1x = i1 * 3; val p2x = i2 * 3
            val e1x = positions[p1x] - positions[p0x]
            val e1y = positions[p1x + 1] - positions[p0x + 1]
            val e1z = positions[p1x + 2] - positions[p0x + 2]
            val e2x = positions[p2x] - positions[p0x]
            val e2y = positions[p2x + 1] - positions[p0x + 1]
            val e2z = positions[p2x + 2] - positions[p0x + 2]
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            normals[p0x] += nx; normals[p0x + 1] += ny; normals[p0x + 2] += nz
            normals[p1x] += nx; normals[p1x + 1] += ny; normals[p1x + 2] += nz
            normals[p2x] += nx; normals[p2x + 1] += ny; normals[p2x + 2] += nz
        }
        for (i in 0 until vertexCount) {
            val ix = i * 3
            val len = sqrt(normals[ix] * normals[ix] + normals[ix + 1] * normals[ix + 1] + normals[ix + 2] * normals[ix + 2])
            if (len > 1e-8f) {
                val inv = 1f / len
                normals[ix] *= inv; normals[ix + 1] *= inv; normals[ix + 2] *= inv
            } else {
                normals[ix] = 0f; normals[ix + 1] = 1f; normals[ix + 2] = 0f
            }
        }
    }
}
