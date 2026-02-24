package com.poly.mcgltf.collision
import com.poly.mcgltf.config.ConfigSystem
import de.javagl.jgltf.model.*
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import java.util.*
import java.util.concurrent.ConcurrentHashMap
data class OBB(
    val center: Vector3f = Vector3f(),
    val halfExtents: Vector3f = Vector3f(),
    val rotation: Matrix3f = Matrix3f()
)
object OBBCollisionSystem {
    private val modelOBBs = ConcurrentHashMap<GltfModel, OBB>()
    private val entityOBBs = ConcurrentHashMap<Int, OBB>()
    private val tmpVecA = Vector3f()
    private val tmpVecB = Vector3f()
    private val tmpVecC = Vector3f()
    private val tmpMat3 = Matrix3f()
    private val tmpAxes = Array(15) { Vector3f() }
    fun computeOBB(model: GltfModel): OBB = modelOBBs.computeIfAbsent(model, ::computeOBBFromModel)
    fun getEntityOBB(entity: Entity): OBB? = entityOBBs[entity.id]
    fun updateEntityOBB(entity: Entity, modelOBB: OBB, modelMatrix: Matrix4f) {
        val obb = entityOBBs.computeIfAbsent(entity.id) { OBB(Vector3f(), Vector3f(), Matrix3f()) }
        modelMatrix.transformPosition(modelOBB.center, obb.center)
        modelMatrix.get3x3(obb.rotation)
        obb.rotation.mul(modelOBB.rotation)
        val sx = tmpVecA.set(modelMatrix.m00(), modelMatrix.m01(), modelMatrix.m02()).length()
        val sy = tmpVecA.set(modelMatrix.m10(), modelMatrix.m11(), modelMatrix.m12()).length()
        val sz = tmpVecA.set(modelMatrix.m20(), modelMatrix.m21(), modelMatrix.m22()).length()
        obb.halfExtents.set(modelOBB.halfExtents.x * sx, modelOBB.halfExtents.y * sy, modelOBB.halfExtents.z * sz)
    }
    fun removeEntity(entityId: Int) { entityOBBs.remove(entityId) }
    fun testOBBvsOBB(a: OBB, b: OBB): Boolean {
        val ra = a.rotation; val rb = b.rotation
        tmpMat3.set(ra).transpose().mul(rb)
        tmpVecA.set(b.center).sub(a.center)
        val t = Vector3f(tmpVecA)
        tmpVecA.set(t.dot(ra.m00, ra.m01, ra.m02), t.dot(ra.m10, ra.m11, ra.m12), t.dot(ra.m20, ra.m21, ra.m22))
        val absR = Array(3) { i -> FloatArray(3) { j ->
            val v = when (i * 3 + j) {
                0 -> tmpMat3.m00; 1 -> tmpMat3.m10; 2 -> tmpMat3.m20
                3 -> tmpMat3.m01; 4 -> tmpMat3.m11; 5 -> tmpMat3.m21
                6 -> tmpMat3.m02; 7 -> tmpMat3.m12; else -> tmpMat3.m22
            }
            kotlin.math.abs(v) + 1e-6f
        }}
        val ae = floatArrayOf(a.halfExtents.x, a.halfExtents.y, a.halfExtents.z)
        val be = floatArrayOf(b.halfExtents.x, b.halfExtents.y, b.halfExtents.z)
        val tv = floatArrayOf(tmpVecA.x, tmpVecA.y, tmpVecA.z)
        for (i in 0..2) {
            if (kotlin.math.abs(tv[i]) > ae[i] + be[0] * absR[i][0] + be[1] * absR[i][1] + be[2] * absR[i][2]) return false
        }
        for (j in 0..2) {
            if (kotlin.math.abs(tv[0] * absR[0][j] + tv[1] * absR[1][j] + tv[2] * absR[2][j]) > be[j] + ae[0] * absR[0][j] + ae[1] * absR[1][j] + ae[2] * absR[2][j]) return false
        }
        return testCrossAxes(ae, be, tv, absR)
    }
    private fun testCrossAxes(ae: FloatArray, be: FloatArray, tv: FloatArray, absR: Array<FloatArray>): Boolean {
        val r = Array(3) { i -> FloatArray(3) { j -> absR[i][j] - 1e-6f } }
        if (kotlin.math.abs(tv[2] * r[1][0] - tv[1] * r[2][0]) > ae[1] * absR[2][0] + ae[2] * absR[1][0] + be[1] * absR[0][2] + be[2] * absR[0][1]) return false
        if (kotlin.math.abs(tv[2] * r[1][1] - tv[1] * r[2][1]) > ae[1] * absR[2][1] + ae[2] * absR[1][1] + be[0] * absR[0][2] + be[2] * absR[0][0]) return false
        if (kotlin.math.abs(tv[2] * r[1][2] - tv[1] * r[2][2]) > ae[1] * absR[2][2] + ae[2] * absR[1][2] + be[0] * absR[0][1] + be[1] * absR[0][0]) return false
        if (kotlin.math.abs(tv[0] * r[2][0] - tv[2] * r[0][0]) > ae[0] * absR[2][0] + ae[2] * absR[0][0] + be[1] * absR[1][2] + be[2] * absR[1][1]) return false
        if (kotlin.math.abs(tv[0] * r[2][1] - tv[2] * r[0][1]) > ae[0] * absR[2][1] + ae[2] * absR[0][1] + be[0] * absR[1][2] + be[2] * absR[1][0]) return false
        if (kotlin.math.abs(tv[0] * r[2][2] - tv[2] * r[0][2]) > ae[0] * absR[2][2] + ae[2] * absR[0][2] + be[0] * absR[1][1] + be[1] * absR[1][0]) return false
        if (kotlin.math.abs(tv[1] * r[0][0] - tv[0] * r[1][0]) > ae[0] * absR[1][0] + ae[1] * absR[0][0] + be[1] * absR[2][2] + be[2] * absR[2][1]) return false
        if (kotlin.math.abs(tv[1] * r[0][1] - tv[0] * r[1][1]) > ae[0] * absR[1][1] + ae[1] * absR[0][1] + be[0] * absR[2][2] + be[2] * absR[2][0]) return false
        if (kotlin.math.abs(tv[1] * r[0][2] - tv[0] * r[1][2]) > ae[0] * absR[1][2] + ae[1] * absR[0][2] + be[0] * absR[2][1] + be[1] * absR[2][0]) return false
        return true
    }
    fun testOBBvsAABB(obb: OBB, aabb: AABB): Boolean {
        val aabbOBB = OBB(
            Vector3f(((aabb.minX + aabb.maxX) * 0.5).toFloat(), ((aabb.minY + aabb.maxY) * 0.5).toFloat(), ((aabb.minZ + aabb.maxZ) * 0.5).toFloat()),
            Vector3f(((aabb.maxX - aabb.minX) * 0.5).toFloat(), ((aabb.maxY - aabb.minY) * 0.5).toFloat(), ((aabb.maxZ - aabb.minZ) * 0.5).toFloat()),
            Matrix3f()
        )
        return testOBBvsOBB(obb, aabbOBB)
    }
    fun containsPoint(obb: OBB, point: Vector3f): Boolean {
        tmpVecA.set(point).sub(obb.center)
        tmpVecB.set(tmpVecA)
        obb.rotation.transformTranspose(tmpVecB)
        return kotlin.math.abs(tmpVecB.x) <= obb.halfExtents.x &&
               kotlin.math.abs(tmpVecB.y) <= obb.halfExtents.y &&
               kotlin.math.abs(tmpVecB.z) <= obb.halfExtents.z
    }
    fun testEntityCollision(a: Entity, b: Entity): Boolean {
        if (!isEnabled()) return a.boundingBox.intersects(b.boundingBox)
        if (!a.boundingBox.intersects(b.boundingBox)) return false
        val obbA = entityOBBs[a.id] ?: return true
        val obbB = entityOBBs[b.id] ?: return testOBBvsAABB(obbA, b.boundingBox)
        return testOBBvsOBB(obbA, obbB)
    }
    fun clearCache() { modelOBBs.clear(); entityOBBs.clear() }
    fun isEnabled(): Boolean = ConfigSystem.isSubsystemEnabled("obbCollision")
    private fun computeOBBFromModel(model: GltfModel): OBB {
        val vertices = collectVertices(model)
        if (vertices.isEmpty()) return OBB(Vector3f(), Vector3f(0.5f, 0.5f, 0.5f), Matrix3f())
        return computeOBBFromVertices(vertices)
    }
    private fun collectVertices(model: GltfModel): FloatArray {
        val coords = mutableListOf<Float>()
        val visited = Collections.newSetFromMap(IdentityHashMap<MeshPrimitiveModel, Boolean>())
        for (scene in model.sceneModels) {
            for (node in scene.nodeModels) {
                collectNodeVertices(node, visited, coords)
            }
        }
        return coords.toFloatArray()
    }
    private fun collectNodeVertices(node: NodeModel, visited: MutableSet<MeshPrimitiveModel>, coords: MutableList<Float>) {
        for (mesh in node.meshModels) {
            for (primitive in mesh.meshPrimitiveModels) {
                if (!visited.add(primitive)) continue
                val accessor = primitive.attributes["POSITION"] ?: continue
                val data = accessor.accessorData
                if (data !is AccessorFloatData) continue
                for (i in 0 until accessor.count) {
                    coords.add(data.get(i, 0))
                    coords.add(data.get(i, 1))
                    coords.add(data.get(i, 2))
                }
            }
        }
        for (child in node.children) collectNodeVertices(child, visited, coords)
    }
    private fun computeOBBFromVertices(verts: FloatArray): OBB {
        val n = verts.size / 3
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in 0 until n) { cx += verts[i * 3]; cy += verts[i * 3 + 1]; cz += verts[i * 3 + 2] }
        cx /= n; cy /= n; cz /= n
        var c00 = 0f; var c01 = 0f; var c02 = 0f; var c11 = 0f; var c12 = 0f; var c22 = 0f
        for (i in 0 until n) {
            val dx = verts[i * 3] - cx; val dy = verts[i * 3 + 1] - cy; val dz = verts[i * 3 + 2] - cz
            c00 += dx * dx; c01 += dx * dy; c02 += dx * dz
            c11 += dy * dy; c12 += dy * dz; c22 += dz * dz
        }
        val inv = 1f / n
        c00 *= inv; c01 *= inv; c02 *= inv; c11 *= inv; c12 *= inv; c22 *= inv
        val eigenvectors = Matrix3f()
        jacobiEigendecomposition(c00, c01, c02, c11, c12, c22, eigenvectors)
        val center = Vector3f()
        val halfExtents = Vector3f()
        var minP = Float.MAX_VALUE; var maxP = -Float.MAX_VALUE
        var minQ = Float.MAX_VALUE; var maxQ = -Float.MAX_VALUE
        var minR = Float.MAX_VALUE; var maxR = -Float.MAX_VALUE
        val ax0 = Vector3f(eigenvectors.m00, eigenvectors.m01, eigenvectors.m02)
        val ax1 = Vector3f(eigenvectors.m10, eigenvectors.m11, eigenvectors.m12)
        val ax2 = Vector3f(eigenvectors.m20, eigenvectors.m21, eigenvectors.m22)
        for (i in 0 until n) {
            val px = verts[i * 3]; val py = verts[i * 3 + 1]; val pz = verts[i * 3 + 2]
            val p = px * ax0.x + py * ax0.y + pz * ax0.z
            val q = px * ax1.x + py * ax1.y + pz * ax1.z
            val r = px * ax2.x + py * ax2.y + pz * ax2.z
            if (p < minP) minP = p; if (p > maxP) maxP = p
            if (q < minQ) minQ = q; if (q > maxQ) maxQ = q
            if (r < minR) minR = r; if (r > maxR) maxR = r
        }
        val midP = (minP + maxP) * 0.5f; val midQ = (minQ + maxQ) * 0.5f; val midR = (minR + maxR) * 0.5f
        center.set(
            ax0.x * midP + ax1.x * midQ + ax2.x * midR,
            ax0.y * midP + ax1.y * midQ + ax2.y * midR,
            ax0.z * midP + ax1.z * midQ + ax2.z * midR
        )
        halfExtents.set((maxP - minP) * 0.5f, (maxQ - minQ) * 0.5f, (maxR - minR) * 0.5f)
        return OBB(center, halfExtents, eigenvectors)
    }
    private fun jacobiEigendecomposition(a00: Float, a01: Float, a02: Float, a11: Float, a12: Float, a22: Float, out: Matrix3f) {
        val a = floatArrayOf(a00, a01, a02, a01, a11, a12, a02, a12, a22)
        val v = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        repeat(50) {
            val offDiag = a[1] * a[1] + a[2] * a[2] + a[5] * a[5]
            if (offDiag < 1e-12f) return@repeat
            jacobiRotate(a, v, 0, 1)
            jacobiRotate(a, v, 0, 2)
            jacobiRotate(a, v, 1, 2)
        }
        val eigenvalues = floatArrayOf(a[0], a[4], a[8])
        val indices = intArrayOf(0, 1, 2)
        if (eigenvalues[indices[0]] < eigenvalues[indices[1]]) indices[0] = indices[1].also { indices[1] = indices[0] }
        if (eigenvalues[indices[0]] < eigenvalues[indices[2]]) indices[0] = indices[2].also { indices[2] = indices[0] }
        if (eigenvalues[indices[1]] < eigenvalues[indices[2]]) indices[1] = indices[2].also { indices[2] = indices[1] }
        out.set(
            v[indices[0] * 3], v[indices[0] * 3 + 1], v[indices[0] * 3 + 2],
            v[indices[1] * 3], v[indices[1] * 3 + 1], v[indices[1] * 3 + 2],
            v[indices[2] * 3], v[indices[2] * 3 + 1], v[indices[2] * 3 + 2]
        )
    }
    private fun jacobiRotate(a: FloatArray, v: FloatArray, p: Int, q: Int) {
        val app = a[p * 3 + p]; val aqq = a[q * 3 + q]; val apq = a[p * 3 + q]
        if (kotlin.math.abs(apq) < 1e-12f) return
        val tau = (aqq - app) / (2f * apq)
        val t = if (tau >= 0f) 1f / (tau + kotlin.math.sqrt(1f + tau * tau)) else -1f / (-tau + kotlin.math.sqrt(1f + tau * tau))
        val c = 1f / kotlin.math.sqrt(1f + t * t)
        val s = t * c
        a[p * 3 + p] = app - t * apq
        a[q * 3 + q] = aqq + t * apq
        a[p * 3 + q] = 0f; a[q * 3 + p] = 0f
        for (r in 0..2) {
            if (r == p || r == q) continue
            val arp = a[r * 3 + p]; val arq = a[r * 3 + q]
            a[r * 3 + p] = c * arp - s * arq; a[p * 3 + r] = a[r * 3 + p]
            a[r * 3 + q] = s * arp + c * arq; a[q * 3 + r] = a[r * 3 + q]
        }
        for (i in 0..2) {
            val vip = v[i * 3 + p]; val viq = v[i * 3 + q]
            v[i * 3 + p] = c * vip - s * viq
            v[i * 3 + q] = s * vip + c * viq
        }
    }
}
