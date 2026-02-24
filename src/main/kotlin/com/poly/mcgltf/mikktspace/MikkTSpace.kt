package com.poly.mcgltf.mikktspace
import net.minecraft.util.Mth
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
interface MikkTSpaceContext {
    fun getNumFaces(): Int
    fun getNumVerticesOfFace(face: Int): Int
    fun getPosition(posOut: FloatArray, face: Int, vert: Int)
    fun getNormal(normOut: FloatArray, face: Int, vert: Int)
    fun getTexCoord(texOut: FloatArray, face: Int, vert: Int)
    fun setTSpaceBasic(tangent: FloatArray, sign: Float, face: Int, vert: Int)
    fun setTSpace(tangent: FloatArray, biTangent: FloatArray, magS: Float, magT: Float, isOrientationPreserving: Boolean, face: Int, vert: Int)
}
object MikktspaceTangentGenerator {
    private const val MARK_DEGENERATE = 1
    private const val QUAD_ONE_DEGEN_TRI = 2
    private const val GROUP_WITH_ANY = 4
    private const val ORIENT_PRESERVING = 8
    private const val INTERNAL_RND_SORT_SEED = 39871946L and 0xffffffffL
    private const val CELLS = 2048
    private fun makeIndex(face: Int, vert: Int): Int = (face shl 2) or (vert and 0x3)
    private fun indexToFace(indexIn: Int): Int = indexIn shr 2
    private fun indexToVert(indexIn: Int): Int = indexIn and 0x3
    fun genTangSpaceDefault(ctx: MikkTSpaceContext): Boolean = genTangSpace(ctx, 180.0f)
    fun genTangSpace(ctx: MikkTSpaceContext, angularThreshold: Float): Boolean {
        val iNrFaces = ctx.getNumFaces()
        val fThresCos = Mth.cos(((angularThreshold * Math.PI.toFloat()) / 180.0f).toDouble())
        var iNrTrianglesIn = 0
        for (f in 0 until iNrFaces) {
            val verts = ctx.getNumVerticesOfFace(f)
            if (verts == 3) ++iNrTrianglesIn
            else if (verts == 4) iNrTrianglesIn += 2
        }
        if (iNrTrianglesIn <= 0) return false
        val piTriListIn = IntArray(3 * iNrTrianglesIn)
        val pTriInfos = Array(iNrTrianglesIn) { TriInfo() }
        val iNrTSPaces = generateInitialVerticesIndexList(pTriInfos, piTriListIn, ctx, iNrTrianglesIn)
        generateSharedVerticesIndexList(piTriListIn, ctx, iNrTrianglesIn)
        val iTotTris = iNrTrianglesIn
        var iDegenTriangles = 0
        for (t in 0 until iTotTris) {
            val p0 = getPosition(ctx, piTriListIn[t * 3])
            val p1 = getPosition(ctx, piTriListIn[t * 3 + 1])
            val p2 = getPosition(ctx, piTriListIn[t * 3 + 2])
            if (p0.eq(p1) || p0.eq(p2) || p1.eq(p2)) {
                pTriInfos[t].flag = pTriInfos[t].flag or MARK_DEGENERATE
                ++iDegenTriangles
            }
        }
        iNrTrianglesIn = iTotTris - iDegenTriangles
        degenPrologue(pTriInfos, piTriListIn, iNrTrianglesIn, iTotTris)
        initTriInfo(pTriInfos, piTriListIn, ctx, iNrTrianglesIn)
        val pGroups = arrayOfNulls<Group>(iNrTrianglesIn * 3)
        val piGroupTrianglesBuffer = IntArray(iNrTrianglesIn * 3)
        val iNrActiveGroups = build4RuleGroups(pTriInfos, pGroups, piGroupTrianglesBuffer, piTriListIn, iNrTrianglesIn)
        val psTspace = Array(iNrTSPaces) { TSpace().apply { os.set(1f, 0f, 0f); magS = 1f; ot.set(0f, 1f, 0f); magT = 1f } }
        generateTSpaces(psTspace, pTriInfos, pGroups, iNrActiveGroups, piTriListIn, fThresCos, ctx)
        degenEpilogue(psTspace, pTriInfos, piTriListIn, ctx, iNrTrianglesIn, iTotTris)
        var index = 0
        for (f in 0 until iNrFaces) {
            val verts = ctx.getNumVerticesOfFace(f)
            if (verts != 3 && verts != 4) continue
            for (i in 0 until verts) {
                val ts = psTspace[index]
                val tang = floatArrayOf(ts.os.x, ts.os.y, ts.os.z)
                val bitang = floatArrayOf(ts.ot.x, ts.ot.y, ts.ot.z)
                ctx.setTSpace(tang, bitang, ts.magS, ts.magT, ts.orient, f, i)
                ctx.setTSpaceBasic(tang, if (ts.orient) 1.0f else -1.0f, f, i)
                ++index
            }
        }
        return true
    }
    private fun getPosition(ctx: MikkTSpaceContext, index: Int): Vec3 {
        val buf = FloatArray(3)
        ctx.getPosition(buf, indexToFace(index), indexToVert(index))
        return Vec3(buf[0], buf[1], buf[2])
    }
    private fun getNormal(ctx: MikkTSpaceContext, index: Int): Vec3 {
        val buf = FloatArray(3)
        ctx.getNormal(buf, indexToFace(index), indexToVert(index))
        return Vec3(buf[0], buf[1], buf[2])
    }
    private fun getTexCoord(ctx: MikkTSpaceContext, index: Int): Vec3 {
        val buf = FloatArray(2)
        ctx.getTexCoord(buf, indexToFace(index), indexToVert(index))
        return Vec3(buf[0], buf[1], 1.0f)
    }
    private fun calcTexArea(ctx: MikkTSpaceContext, indices: IntArray): Float {
        val t1 = getTexCoord(ctx, indices[0])
        val t2 = getTexCoord(ctx, indices[1])
        val t3 = getTexCoord(ctx, indices[2])
        val t21x = t2.x - t1.x; val t21y = t2.y - t1.y
        val t31x = t3.x - t1.x; val t31y = t3.y - t1.y
        val area = t21x * t31y - t21y * t31x
        return if (area < 0) -area else area
    }
    private fun isNotZero(v: Float): Boolean = abs(v) > 0
    private fun findGridCell(min: Float, max: Float, v: Float): Int {
        val idx = (CELLS * ((v - min) / (max - min))).toInt()
        return if (idx < CELLS) (if (idx >= 0) idx else 0) else (CELLS - 1)
    }
    private fun generateInitialVerticesIndexList(pTriInfos: Array<TriInfo>, piTriListOut: IntArray, ctx: MikkTSpaceContext, iNrTrianglesIn: Int): Int {
        var iTSpacesOffs = 0
        var iDstTriIndex = 0
        for (f in 0 until ctx.getNumFaces()) {
            val verts = ctx.getNumVerticesOfFace(f)
            if (verts != 3 && verts != 4) continue
            pTriInfos[iDstTriIndex].orgFaceNumber = f
            pTriInfos[iDstTriIndex].tSpacesOffs = iTSpacesOffs
            if (verts == 3) {
                pTriInfos[iDstTriIndex].vertNum[0] = 0; pTriInfos[iDstTriIndex].vertNum[1] = 1; pTriInfos[iDstTriIndex].vertNum[2] = 2
                piTriListOut[iDstTriIndex * 3] = makeIndex(f, 0)
                piTriListOut[iDstTriIndex * 3 + 1] = makeIndex(f, 1)
                piTriListOut[iDstTriIndex * 3 + 2] = makeIndex(f, 2)
                ++iDstTriIndex
            } else {
                pTriInfos[iDstTriIndex + 1].orgFaceNumber = f
                pTriInfos[iDstTriIndex + 1].tSpacesOffs = iTSpacesOffs
                val i0 = makeIndex(f, 0); val i1 = makeIndex(f, 1); val i2 = makeIndex(f, 2); val i3 = makeIndex(f, 3)
                val t0 = getTexCoord(ctx, i0); val t1 = getTexCoord(ctx, i1); val t2 = getTexCoord(ctx, i2); val t3 = getTexCoord(ctx, i3)
                val distSQ02 = t2.sub(t0).lengthSq(); val distSQ13 = t3.sub(t1).lengthSq()
                val bQuadDiagIs02 = if (distSQ02 < distSQ13) true
                    else if (distSQ13 < distSQ02) false
                    else {
                        val p0 = getPosition(ctx, i0); val p1 = getPosition(ctx, i1); val p2 = getPosition(ctx, i2); val p3 = getPosition(ctx, i3)
                        p3.sub(p1).lengthSq() >= p2.sub(p0).lengthSq()
                    }
                if (bQuadDiagIs02) {
                    pTriInfos[iDstTriIndex].vertNum[0] = 0; pTriInfos[iDstTriIndex].vertNum[1] = 1; pTriInfos[iDstTriIndex].vertNum[2] = 2
                    piTriListOut[iDstTriIndex * 3] = i0; piTriListOut[iDstTriIndex * 3 + 1] = i1; piTriListOut[iDstTriIndex * 3 + 2] = i2
                    ++iDstTriIndex
                    pTriInfos[iDstTriIndex].vertNum[0] = 0; pTriInfos[iDstTriIndex].vertNum[1] = 2; pTriInfos[iDstTriIndex].vertNum[2] = 3
                    piTriListOut[iDstTriIndex * 3] = i0; piTriListOut[iDstTriIndex * 3 + 1] = i2; piTriListOut[iDstTriIndex * 3 + 2] = i3
                    ++iDstTriIndex
                } else {
                    pTriInfos[iDstTriIndex].vertNum[0] = 0; pTriInfos[iDstTriIndex].vertNum[1] = 1; pTriInfos[iDstTriIndex].vertNum[2] = 3
                    piTriListOut[iDstTriIndex * 3] = i0; piTriListOut[iDstTriIndex * 3 + 1] = i1; piTriListOut[iDstTriIndex * 3 + 2] = i3
                    ++iDstTriIndex
                    pTriInfos[iDstTriIndex].vertNum[0] = 1; pTriInfos[iDstTriIndex].vertNum[1] = 2; pTriInfos[iDstTriIndex].vertNum[2] = 3
                    piTriListOut[iDstTriIndex * 3] = i1; piTriListOut[iDstTriIndex * 3 + 1] = i2; piTriListOut[iDstTriIndex * 3 + 2] = i3
                    ++iDstTriIndex
                }
            }
            iTSpacesOffs += verts
        }
        for (t in 0 until iNrTrianglesIn) pTriInfos[t].flag = 0
        return iTSpacesOffs
    }
    private fun generateSharedVerticesIndexList(piTriList: IntArray, ctx: MikkTSpaceContext, iNrTrianglesIn: Int) {
        val vMin = getPosition(ctx, piTriList[0]).clone()
        val vMax = vMin.clone()
        for (i in 1 until iNrTrianglesIn * 3) {
            val vP = getPosition(ctx, piTriList[i])
            if (vMin.x > vP.x) vMin.x = vP.x else if (vMax.x < vP.x) vMax.x = vP.x
            if (vMin.y > vP.y) vMin.y = vP.y else if (vMax.y < vP.y) vMax.y = vP.y
            if (vMin.z > vP.z) vMin.z = vP.z else if (vMax.z < vP.z) vMax.z = vP.z
        }
        val vDim = vMax.sub(vMin)
        var iChannel = 0; var fMin = vMin.x; var fMax = vMax.x
        if (vDim.y > vDim.x && vDim.y > vDim.z) { iChannel = 1; fMin = vMin.y; fMax = vMax.y }
        else if (vDim.z > vDim.x) { iChannel = 2; fMin = vMin.z; fMax = vMax.z }
        val piHashTable = IntArray(iNrTrianglesIn * 3)
        val piHashCount = IntArray(CELLS)
        val piHashOffsets = IntArray(CELLS)
        val piHashCount2 = IntArray(CELLS)
        for (i in 0 until iNrTrianglesIn * 3) {
            val vP = getPosition(ctx, piTriList[i])
            val fVal = when (iChannel) { 0 -> vP.x; 1 -> vP.y; else -> vP.z }
            ++piHashCount[findGridCell(fMin, fMax, fVal)]
        }
        piHashOffsets[0] = 0
        for (k in 1 until CELLS) piHashOffsets[k] = piHashOffsets[k - 1] + piHashCount[k - 1]
        for (i in 0 until iNrTrianglesIn * 3) {
            val vP = getPosition(ctx, piTriList[i])
            val fVal = when (iChannel) { 0 -> vP.x; 1 -> vP.y; else -> vP.z }
            val iCell = findGridCell(fMin, fMax, fVal)
            piHashTable[piHashOffsets[iCell] + piHashCount2[iCell]] = i
            ++piHashCount2[iCell]
        }
        var iMaxCount = piHashCount[0]
        for (k in 1 until CELLS) if (iMaxCount < piHashCount[k]) iMaxCount = piHashCount[k]
        val pTmpVert = Array(iMaxCount) { TmpVert() }
        for (k in 0 until CELLS) {
            val iEntries = piHashCount[k]
            if (iEntries < 2) continue
            for (e in 0 until iEntries) {
                val j = piHashTable[piHashOffsets[k] + e]
                val vP = getPosition(ctx, piTriList[j])
                pTmpVert[e].vert[0] = vP.x; pTmpVert[e].vert[1] = vP.y; pTmpVert[e].vert[2] = vP.z
                pTmpVert[e].index = j
            }
            mergeVertsFast(piTriList, pTmpVert, ctx, 0, iEntries - 1)
        }
    }
    private fun mergeVertsFast(piTriList: IntArray, pTmpVert: Array<TmpVert>, ctx: MikkTSpaceContext, iLIn: Int, iRIn: Int) {
        val fvMin = FloatArray(3) { pTmpVert[iLIn].vert[it] }
        val fvMax = FloatArray(3) { fvMin[it] }
        for (l in (iLIn + 1)..iRIn) {
            for (c in 0..2) {
                if (fvMin[c] > pTmpVert[l].vert[c]) fvMin[c] = pTmpVert[l].vert[c]
                else if (fvMax[c] < pTmpVert[l].vert[c]) fvMax[c] = pTmpVert[l].vert[c]
            }
        }
        val dx = fvMax[0] - fvMin[0]; val dy = fvMax[1] - fvMin[1]; val dz = fvMax[2] - fvMin[2]
        val channel = if (dy > dx && dy > dz) 1 else if (dz > dx) 2 else 0
        val fSep = 0.5f * (fvMax[channel] + fvMin[channel])
        if (fSep >= fvMax[channel] || fSep <= fvMin[channel]) {
            for (l in iLIn..iRIn) {
                val i = pTmpVert[l].index
                val vP = getPosition(ctx, piTriList[i])
                val vN = getNormal(ctx, piTriList[i])
                val vT = getTexCoord(ctx, piTriList[i])
                var bNotFound = true; var l2 = iLIn; var i2rec = -1
                while (l2 < l && bNotFound) {
                    val i2 = pTmpVert[l2].index
                    val vP2 = getPosition(ctx, piTriList[i2])
                    val vN2 = getNormal(ctx, piTriList[i2])
                    val vT2 = getTexCoord(ctx, piTriList[i2])
                    i2rec = i2
                    if (vP.x == vP2.x && vP.y == vP2.y && vP.z == vP2.z &&
                        vN.x == vN2.x && vN.y == vN2.y && vN.z == vN2.z &&
                        vT.x == vT2.x && vT.y == vT2.y && vT.z == vT2.z) bNotFound = false
                    else ++l2
                }
                if (!bNotFound) piTriList[i] = piTriList[i2rec]
            }
        } else {
            var iL = iLIn; var iR = iRIn
            while (iL < iR) {
                var bReadyLeftSwap = false; var bReadyRightSwap = false
                while (!bReadyLeftSwap && iL < iR) { bReadyLeftSwap = pTmpVert[iL].vert[channel] >= fSep; if (!bReadyLeftSwap) ++iL }
                while (!bReadyRightSwap && iL < iR) { bReadyRightSwap = pTmpVert[iR].vert[channel] < fSep; if (!bReadyRightSwap) --iR }
                if (bReadyLeftSwap && bReadyRightSwap) { val tmp = pTmpVert[iL]; pTmpVert[iL] = pTmpVert[iR]; pTmpVert[iR] = tmp; ++iL; --iR }
            }
            if (iL == iR) { if (pTmpVert[iR].vert[channel] < fSep) ++iL else --iR }
            if (iLIn < iR) mergeVertsFast(piTriList, pTmpVert, ctx, iLIn, iR)
            if (iL < iRIn) mergeVertsFast(piTriList, pTmpVert, ctx, iL, iRIn)
        }
    }
    private fun initTriInfo(pTriInfos: Array<TriInfo>, piTriListIn: IntArray, ctx: MikkTSpaceContext, iNrTrianglesIn: Int) {
        for (f in 0 until iNrTrianglesIn) {
            for (i in 0..2) {
                pTriInfos[f].faceNeighbors[i] = -1
                pTriInfos[f].assignedGroup[i] = null
                pTriInfos[f].os.set(0f, 0f, 0f); pTriInfos[f].ot.set(0f, 0f, 0f)
                pTriInfos[f].magS = 0f; pTriInfos[f].magT = 0f
                pTriInfos[f].flag = pTriInfos[f].flag or GROUP_WITH_ANY
            }
        }
        for (f in 0 until iNrTrianglesIn) {
            val v1 = getPosition(ctx, piTriListIn[f * 3]); val v2 = getPosition(ctx, piTriListIn[f * 3 + 1]); val v3 = getPosition(ctx, piTriListIn[f * 3 + 2])
            val t1 = getTexCoord(ctx, piTriListIn[f * 3]); val t2 = getTexCoord(ctx, piTriListIn[f * 3 + 1]); val t3 = getTexCoord(ctx, piTriListIn[f * 3 + 2])
            val t21x = t2.x - t1.x; val t21y = t2.y - t1.y; val t31x = t3.x - t1.x; val t31y = t3.y - t1.y
            val d1 = v2.sub(v1); val d2 = v3.sub(v1)
            val fSignedAreaSTx2 = t21x * t31y - t21y * t31x
            val vOs = d1.scale(t31y).subLocal(d2.scale(t21y))
            val vOt = d1.scale(-t31x).addLocal(d2.scale(t21x))
            pTriInfos[f].flag = pTriInfos[f].flag or (if (fSignedAreaSTx2 > 0) ORIENT_PRESERVING else 0)
            if (isNotZero(fSignedAreaSTx2)) {
                val fAbsArea = abs(fSignedAreaSTx2)
                val fLenOs = vOs.length(); val fLenOt = vOt.length()
                val fS = if ((pTriInfos[f].flag and ORIENT_PRESERVING) == 0) -1.0f else 1.0f
                if (isNotZero(fLenOs)) pTriInfos[f].os = vOs.scaleLocal(fS / fLenOs)
                if (isNotZero(fLenOt)) pTriInfos[f].ot = vOt.scaleLocal(fS / fLenOt)
                pTriInfos[f].magS = fLenOs / fAbsArea
                pTriInfos[f].magT = fLenOt / fAbsArea
                if (isNotZero(pTriInfos[f].magS) && isNotZero(pTriInfos[f].magT))
                    pTriInfos[f].flag = pTriInfos[f].flag and GROUP_WITH_ANY.inv()
            }
        }
        var t = 0
        while (t < iNrTrianglesIn - 1) {
            val iFOa = pTriInfos[t].orgFaceNumber; val iFOb = pTriInfos[t + 1].orgFaceNumber
            if (iFOa == iFOb) {
                val bIsDegA = (pTriInfos[t].flag and MARK_DEGENERATE) != 0
                val bIsDegB = (pTriInfos[t + 1].flag and MARK_DEGENERATE) != 0
                if (!bIsDegA && !bIsDegB) {
                    val bOrientA = (pTriInfos[t].flag and ORIENT_PRESERVING) != 0
                    val bOrientB = (pTriInfos[t + 1].flag and ORIENT_PRESERVING) != 0
                    if (bOrientA != bOrientB) {
                        val bChooseFirst = (pTriInfos[t + 1].flag and GROUP_WITH_ANY) != 0 ||
                            calcTexArea(ctx, piTriListIn.copyOfRange(t * 3, t * 3 + 3)) >= calcTexArea(ctx, piTriListIn.copyOfRange((t + 1) * 3, (t + 1) * 3 + 3))
                        val t0 = if (bChooseFirst) t else t + 1; val t1 = if (bChooseFirst) t + 1 else t
                        pTriInfos[t1].flag = pTriInfos[t1].flag and ORIENT_PRESERVING.inv()
                        pTriInfos[t1].flag = pTriInfos[t1].flag or (pTriInfos[t0].flag and ORIENT_PRESERVING)
                    }
                }
                t += 2
            } else ++t
        }
        val pEdges = Array(iNrTrianglesIn * 3) { Edge() }
        buildNeighborsFast(pTriInfos, pEdges, piTriListIn, iNrTrianglesIn)
    }
    private fun build4RuleGroups(pTriInfos: Array<TriInfo>, pGroups: Array<Group?>, piGroupTriBuf: IntArray, piTriListIn: IntArray, iNrTrianglesIn: Int): Int {
        var iNrActiveGroups = 0; var iOffset = 0
        for (f in 0 until iNrTrianglesIn) {
            for (i in 0..2) {
                if ((pTriInfos[f].flag and GROUP_WITH_ANY) == 0 && pTriInfos[f].assignedGroup[i] == null) {
                    val vertIndex = piTriListIn[f * 3 + i]
                    val group = Group().apply {
                        vertexRepresentative = vertIndex
                        orientationPreserving = (pTriInfos[f].flag and ORIENT_PRESERVING) != 0
                    }
                    pTriInfos[f].assignedGroup[i] = group
                    pGroups[iNrActiveGroups] = group
                    ++iNrActiveGroups
                    addTriToGroup(group, f)
                    val bOrPre = (pTriInfos[f].flag and ORIENT_PRESERVING) != 0
                    val neighL = pTriInfos[f].faceNeighbors[i]
                    val neighR = pTriInfos[f].faceNeighbors[if (i > 0) i - 1 else 2]
                    if (neighL >= 0) assignRecur(piTriListIn, pTriInfos, neighL, group)
                    if (neighR >= 0) assignRecur(piTriListIn, pTriInfos, neighR, group)
                    for (j in 0 until group.nrFaces) piGroupTriBuf[iOffset + j] = group.faceIndices[j]
                    iOffset += group.nrFaces
                }
            }
        }
        return iNrActiveGroups
    }
    private fun addTriToGroup(group: Group, triIndex: Int) { group.faceIndices.add(triIndex); ++group.nrFaces }
    private fun assignRecur(piTriListIn: IntArray, psTriInfos: Array<TriInfo>, iMyTriIndex: Int, pGroup: Group): Boolean {
        val pMyTriInfo = psTriInfos[iMyTriIndex]
        val iVertRep = pGroup.vertexRepresentative
        val index = 3 * iMyTriIndex
        val i = when {
            piTriListIn[index] == iVertRep -> 0
            piTriListIn[index + 1] == iVertRep -> 1
            piTriListIn[index + 2] == iVertRep -> 2
            else -> return false
        }
        if (pMyTriInfo.assignedGroup[i] === pGroup) return true
        if (pMyTriInfo.assignedGroup[i] != null) return false
        if ((pMyTriInfo.flag and GROUP_WITH_ANY) != 0) {
            if (pMyTriInfo.assignedGroup[0] == null && pMyTriInfo.assignedGroup[1] == null && pMyTriInfo.assignedGroup[2] == null) {
                pMyTriInfo.flag = pMyTriInfo.flag and ORIENT_PRESERVING.inv()
                pMyTriInfo.flag = pMyTriInfo.flag or (if (pGroup.orientationPreserving) ORIENT_PRESERVING else 0)
            }
        }
        val bOrient = (pMyTriInfo.flag and ORIENT_PRESERVING) != 0
        if (bOrient != pGroup.orientationPreserving) return false
        addTriToGroup(pGroup, iMyTriIndex)
        pMyTriInfo.assignedGroup[i] = pGroup
        val neighL = pMyTriInfo.faceNeighbors[i]
        val neighR = pMyTriInfo.faceNeighbors[if (i > 0) i - 1 else 2]
        if (neighL >= 0) assignRecur(piTriListIn, psTriInfos, neighL, pGroup)
        if (neighR >= 0) assignRecur(piTriListIn, psTriInfos, neighR, pGroup)
        return true
    }
    private fun generateTSpaces(psTspace: Array<TSpace>, pTriInfos: Array<TriInfo>, pGroups: Array<Group?>,
                               iNrActiveGroups: Int, piTriListIn: IntArray, fThresCos: Float, ctx: MikkTSpaceContext): Boolean {
        var iMaxNrFaces = 0
        for (g in 0 until iNrActiveGroups) { val nf = pGroups[g]!!.nrFaces; if (iMaxNrFaces < nf) iMaxNrFaces = nf }
        if (iMaxNrFaces == 0) return true
        val pSubGroupTspace = arrayOfNulls<TSpace>(iMaxNrFaces)
        val pUniSubGroups = arrayOfNulls<SubGroup>(iMaxNrFaces)
        val pTmpMembers = IntArray(iMaxNrFaces)
        for (g in 0 until iNrActiveGroups) {
            val pGroup = pGroups[g]!!
            var iUniqueSubGroups = 0
            for (i in 0 until pGroup.nrFaces) {
                val f = pGroup.faceIndices[i]
                val idx = when {
                    pTriInfos[f].assignedGroup[0] === pGroup -> 0
                    pTriInfos[f].assignedGroup[1] === pGroup -> 1
                    else -> 2
                }
                val iVertIndex = piTriListIn[f * 3 + idx]
                val n = getNormal(ctx, iVertIndex)
                val vOs = pTriInfos[f].os.sub(n.scale(n.dot(pTriInfos[f].os))).normalizeLocal()
                val vOt = pTriInfos[f].ot.sub(n.scale(n.dot(pTriInfos[f].ot))).normalizeLocal()
                var iMembers = 0
                for (j in 0 until pGroup.nrFaces) {
                    val t = pGroup.faceIndices[j]
                    val vOs2 = pTriInfos[t].os.sub(n.scale(n.dot(pTriInfos[t].os))).normalizeLocal()
                    val vOt2 = pTriInfos[t].ot.sub(n.scale(n.dot(pTriInfos[t].ot))).normalizeLocal()
                    val bAny = ((pTriInfos[f].flag or pTriInfos[t].flag) and GROUP_WITH_ANY) != 0
                    val bSameOrgFace = pTriInfos[f].orgFaceNumber == pTriInfos[t].orgFaceNumber
                    val fCosS = vOs.dot(vOs2); val fCosT = vOt.dot(vOt2)
                    if (bAny || bSameOrgFace || (fCosS > fThresCos && fCosT > fThresCos))
                        pTmpMembers[iMembers++] = t
                }
                val tmpGroup = SubGroup(iMembers, pTmpMembers)
                if (iMembers > 1) quickSort(pTmpMembers, 0, iMembers - 1, INTERNAL_RND_SORT_SEED)
                var bFound = false; var l = 0
                while (l < iUniqueSubGroups && !bFound) { bFound = compareSubGroups(tmpGroup, pUniSubGroups[l]!!); if (!bFound) ++l }
                if (!bFound) {
                    val pIndices = pTmpMembers.copyOfRange(0, iMembers)
                    pUniSubGroups[iUniqueSubGroups] = SubGroup(iMembers, pIndices)
                    pSubGroupTspace[iUniqueSubGroups] = evalTspace(pTmpMembers, iMembers, piTriListIn, pTriInfos, ctx, pGroup.vertexRepresentative)
                    ++iUniqueSubGroups
                }
                val iOffs = pTriInfos[f].tSpacesOffs
                val iVert = pTriInfos[f].vertNum[idx].toInt()
                val pTSOut = psTspace[iOffs + iVert]
                if (pTSOut.counter == 1) {
                    pTSOut.copyFrom(avgTSpace(pTSOut, pSubGroupTspace[l]!!))
                    pTSOut.counter = 2; pTSOut.orient = pGroup.orientationPreserving
                } else {
                    pTSOut.copyFrom(pSubGroupTspace[l]!!)
                    pTSOut.counter = 1; pTSOut.orient = pGroup.orientationPreserving
                }
            }
        }
        return true
    }
    private fun evalTspace(faceIndices: IntArray, iFaces: Int, piTriListIn: IntArray, pTriInfos: Array<TriInfo>,
                           ctx: MikkTSpaceContext, iVertexRepresentative: Int): TSpace {
        val res = TSpace()
        var fAngleSum = 0f
        for (face in 0 until iFaces) {
            val f = faceIndices[face]
            if ((pTriInfos[f].flag and GROUP_WITH_ANY) != 0) continue
            val i = when {
                piTriListIn[3 * f] == iVertexRepresentative -> 0
                piTriListIn[3 * f + 1] == iVertexRepresentative -> 1
                else -> 2
            }
            val index = piTriListIn[3 * f + i]
            val n = getNormal(ctx, index)
            val vOs = pTriInfos[f].os.sub(n.scale(n.dot(pTriInfos[f].os))).normalizeLocal()
            val vOt = pTriInfos[f].ot.sub(n.scale(n.dot(pTriInfos[f].ot))).normalizeLocal()
            val i2 = piTriListIn[3 * f + (if (i < 2) i + 1 else 0)]
            val i1 = piTriListIn[3 * f + i]
            val i0 = piTriListIn[3 * f + (if (i > 0) i - 1 else 2)]
            val p0 = getPosition(ctx, i0); val p1 = getPosition(ctx, i1); val p2 = getPosition(ctx, i2)
            val v1 = p0.sub(p1).subLocal(n.scale(n.dot(p0.sub(p1)))).normalizeLocal()
            val v2 = p2.sub(p1).subLocal(n.scale(n.dot(p2.sub(p1)))).normalizeLocal()
            var fCos = v1.dot(v2)
            fCos = if (fCos > 1f) 1f else if (fCos < -1f) -1f else fCos
            val fAngle = acos(fCos)
            res.os.addLocal(vOs.scaleLocal(fAngle))
            res.ot.addLocal(vOt.scaleLocal(fAngle))
            res.magS += fAngle * pTriInfos[f].magS
            res.magT += fAngle * pTriInfos[f].magT
            fAngleSum += fAngle
        }
        res.os.normalizeLocal(); res.ot.normalizeLocal()
        if (fAngleSum > 0) { res.magS /= fAngleSum; res.magT /= fAngleSum }
        return res
    }
    private fun avgTSpace(tS0: TSpace, tS1: TSpace): TSpace {
        val res = TSpace()
        if (tS0.magS == tS1.magS && tS0.magT == tS1.magT && tS0.os.eq(tS1.os) && tS0.ot.eq(tS1.ot)) {
            res.magS = tS0.magS; res.magT = tS0.magT; res.os.setFrom(tS0.os); res.ot.setFrom(tS0.ot)
        } else {
            res.magS = 0.5f * (tS0.magS + tS1.magS); res.magT = 0.5f * (tS0.magT + tS1.magT)
            res.os.setFrom(tS0.os).addLocal(tS1.os).normalizeLocal()
            res.ot.setFrom(tS0.ot).addLocal(tS1.ot).normalizeLocal()
        }
        return res
    }
    private fun compareSubGroups(pg1: SubGroup, pg2: SubGroup): Boolean {
        if (pg1.nrFaces != pg2.nrFaces) return false
        for (i in 0 until pg1.nrFaces) if (pg1.triMembers[i] != pg2.triMembers[i]) return false
        return true
    }
    private fun quickSort(buf: IntArray, iLeft: Int, iRight: Int, uSeedIn: Long) {
        var uSeed = uSeedIn
        val t = (uSeed and 31).toInt()
        uSeed = ((uSeed shl t) or (uSeed ushr (32 - t))) and 0xffffffffL
        uSeed = (uSeed + t.toLong() + 3) and 0xffffffffL
        var iL = iLeft; var iR = iRight
        val n = iR - iL + 1
        val index = (uSeed % n).toInt()
        val iMid = buf[index + iL]
        do {
            while (buf[iL] < iMid) ++iL
            while (buf[iR] > iMid) --iR
            if (iL <= iR) { val tmp = buf[iL]; buf[iL] = buf[iR]; buf[iR] = tmp; ++iL; --iR }
        } while (iL <= iR)
        if (iLeft < iR) quickSort(buf, iLeft, iR, uSeed)
        if (iL < iRight) quickSort(buf, iL, iRight, uSeed)
    }
    private fun quickSortEdges(pEdges: Array<Edge>, iLeft: Int, iRight: Int, channel: Int, uSeedIn: Long) {
        val iElems = iRight - iLeft + 1
        if (iElems < 2) return
        if (iElems == 2) {
            if (pEdges[iLeft].array[channel] > pEdges[iRight].array[channel]) { val tmp = pEdges[iLeft]; pEdges[iLeft] = pEdges[iRight]; pEdges[iRight] = tmp }
            return
        }
        var uSeed = uSeedIn
        val t = (uSeed and 31).toInt()
        uSeed = ((uSeed shl t) or (uSeed ushr (32 - t))) and 0xffffffffL
        uSeed = (uSeed + t.toLong() + 3) and 0xffffffffL
        var iL = iLeft; var iR = iRight
        val n = iR - iL + 1
        val index = (uSeed % n).toInt()
        val iMid = pEdges[index + iL].array[channel]
        do {
            while (pEdges[iL].array[channel] < iMid) ++iL
            while (pEdges[iR].array[channel] > iMid) --iR
            if (iL <= iR) { val tmp = pEdges[iL]; pEdges[iL] = pEdges[iR]; pEdges[iR] = tmp; ++iL; --iR }
        } while (iL <= iR)
        if (iLeft < iR) quickSortEdges(pEdges, iLeft, iR, channel, uSeed)
        if (iL < iRight) quickSortEdges(pEdges, iL, iRight, channel, uSeed)
    }
    private fun buildNeighborsFast(pTriInfos: Array<TriInfo>, pEdges: Array<Edge>, piTriListIn: IntArray, iNrTrianglesIn: Int) {
        val uSeed = INTERNAL_RND_SORT_SEED
        for (f in 0 until iNrTrianglesIn) {
            for (i in 0..2) {
                val i0 = piTriListIn[f * 3 + i]; val i1 = piTriListIn[f * 3 + (if (i < 2) i + 1 else 0)]
                pEdges[f * 3 + i].array[0] = if (i0 < i1) i0 else i1
                pEdges[f * 3 + i].array[1] = if (i0 < i1) i1 else i0
                pEdges[f * 3 + i].array[2] = f
            }
        }
        val iEntries = iNrTrianglesIn * 3
        quickSortEdges(pEdges, 0, iEntries - 1, 0, uSeed)
        var iCurStartIndex = 0
        for (i in 1 until iEntries) {
            if (pEdges[iCurStartIndex].array[0] != pEdges[i].array[0]) {
                quickSortEdges(pEdges, iCurStartIndex, i - 1, 1, uSeed)
                iCurStartIndex = i
            }
        }
        quickSortEdges(pEdges, iCurStartIndex, iEntries - 1, 1, uSeed)
        iCurStartIndex = 0
        for (i in 1 until iEntries) {
            if (pEdges[iCurStartIndex].array[0] != pEdges[i].array[0] || pEdges[iCurStartIndex].array[1] != pEdges[i].array[1]) {
                quickSortEdges(pEdges, iCurStartIndex, i - 1, 2, uSeed)
                iCurStartIndex = i
            }
        }
        quickSortEdges(pEdges, iCurStartIndex, iEntries - 1, 2, uSeed)
        val i0A = IntArray(1); val i1A = IntArray(1); val edgenumA = IntArray(1); val edgenumB = IntArray(1)
        val triList = IntArray(3)
        for (i in 0 until iEntries) {
            val ei0 = pEdges[i].array[0]; val ei1 = pEdges[i].array[1]; val g = pEdges[i].array[2]
            System.arraycopy(piTriListIn, g * 3, triList, 0, 3)
            getEdge(i0A, i1A, edgenumA, triList, ei0, ei1)
            if (pTriInfos[g].faceNeighbors[edgenumA[0]] == -1) {
                var j = i + 1; var bNotFound = true
                val i0B = IntArray(1); val i1B = IntArray(1)
                while (j < iEntries && pEdges[j].array[0] == ei0 && pEdges[j].array[1] == ei1 && bNotFound) {
                    val t = pEdges[j].array[2]
                    System.arraycopy(piTriListIn, t * 3, triList, 0, 3)
                    getEdge(i1B, i0B, edgenumB, triList, pEdges[j].array[0], pEdges[j].array[1])
                    if (i0A[0] == i0B[0] && i1A[0] == i1B[0] && pTriInfos[t].faceNeighbors[edgenumB[0]] == -1) bNotFound = false
                    else ++j
                }
                if (!bNotFound) {
                    val t2 = pEdges[j].array[2]
                    pTriInfos[g].faceNeighbors[edgenumA[0]] = t2
                    pTriInfos[t2].faceNeighbors[edgenumB[0]] = g
                }
            }
        }
    }
    private fun getEdge(i0Out: IntArray, i1Out: IntArray, edgenumOut: IntArray, indices: IntArray, i0In: Int, i1In: Int) {
        if (indices[0] == i0In || indices[0] == i1In) {
            if (indices[1] == i0In || indices[1] == i1In) { edgenumOut[0] = 0; i0Out[0] = indices[0]; i1Out[0] = indices[1] }
            else { edgenumOut[0] = 2; i0Out[0] = indices[2]; i1Out[0] = indices[0] }
        } else { edgenumOut[0] = 1; i0Out[0] = indices[1]; i1Out[0] = indices[2] }
    }
    private fun degenPrologue(pTriInfos: Array<TriInfo>, piTriListOut: IntArray, iNrTrianglesIn: Int, iTotTris: Int) {
        var t = 0
        while (t < iTotTris - 1) {
            if (pTriInfos[t].orgFaceNumber == pTriInfos[t + 1].orgFaceNumber) {
                val bIsDegA = (pTriInfos[t].flag and MARK_DEGENERATE) != 0
                val bIsDegB = (pTriInfos[t + 1].flag and MARK_DEGENERATE) != 0
                if (bIsDegA xor bIsDegB) { pTriInfos[t].flag = pTriInfos[t].flag or QUAD_ONE_DEGEN_TRI; pTriInfos[t + 1].flag = pTriInfos[t + 1].flag or QUAD_ONE_DEGEN_TRI }
                t += 2
            } else ++t
        }
        var iNextGood = 1; t = 0; var bStillFinding = true
        while (t < iNrTrianglesIn && bStillFinding) {
            if ((pTriInfos[t].flag and MARK_DEGENERATE) == 0) {
                if (iNextGood < t + 2) iNextGood = t + 2
            } else {
                var bJustDegen = true
                while (bJustDegen && iNextGood < iTotTris) {
                    if ((pTriInfos[iNextGood].flag and MARK_DEGENERATE) == 0) bJustDegen = false else ++iNextGood
                }
                val t0 = t; val t1 = iNextGood; ++iNextGood
                if (!bJustDegen) {
                    for (i in 0..2) { val idx = piTriListOut[t0 * 3 + i]; piTriListOut[t0 * 3 + i] = piTriListOut[t1 * 3 + i]; piTriListOut[t1 * 3 + i] = idx }
                    val tmp = pTriInfos[t0]; pTriInfos[t0] = pTriInfos[t1]; pTriInfos[t1] = tmp
                } else bStillFinding = false
            }
            if (bStillFinding) ++t
        }
    }
    private fun degenEpilogue(psTspace: Array<TSpace>, pTriInfos: Array<TriInfo>, piTriListIn: IntArray, ctx: MikkTSpaceContext, iNrTrianglesIn: Int, iTotTris: Int) {
        for (t in iNrTrianglesIn until iTotTris) {
            if ((pTriInfos[t].flag and QUAD_ONE_DEGEN_TRI) != 0) continue
            for (i in 0..2) {
                val index1 = piTriListIn[t * 3 + i]
                var bNotFound = true; var j = 0
                while (bNotFound && j < 3 * iNrTrianglesIn) {
                    if (index1 == piTriListIn[j]) bNotFound = false else ++j
                }
                if (!bNotFound) {
                    val iTri = j / 3; val iVert = j % 3
                    psTspace[pTriInfos[t].tSpacesOffs + pTriInfos[t].vertNum[i].toInt()] =
                        psTspace[pTriInfos[iTri].tSpacesOffs + pTriInfos[iTri].vertNum[iVert].toInt()]
                }
            }
        }
        for (t in 0 until iNrTrianglesIn) {
            if ((pTriInfos[t].flag and QUAD_ONE_DEGEN_TRI) == 0) continue
            val pV = pTriInfos[t].vertNum
            val iFlag = (1 shl pV[0].toInt()) or (1 shl pV[1].toInt()) or (1 shl pV[2].toInt())
            val iMissingIndex = when { (iFlag and 2) == 0 -> 1; (iFlag and 4) == 0 -> 2; (iFlag and 8) == 0 -> 3; else -> 0 }
            val iOrgF = pTriInfos[t].orgFaceNumber
            val vDstP = getPosition(ctx, makeIndex(iOrgF, iMissingIndex))
            var bNotFound = true; var i = 0
            while (bNotFound && i < 3) {
                val iVert = pV[i].toInt()
                val vSrcP = getPosition(ctx, makeIndex(iOrgF, iVert))
                if (vSrcP.eq(vDstP)) {
                    psTspace[pTriInfos[t].tSpacesOffs + iMissingIndex] = psTspace[pTriInfos[t].tSpacesOffs + iVert]
                    bNotFound = false
                } else ++i
            }
        }
    }
    private class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
        fun set(nx: Float, ny: Float, nz: Float): Vec3 { x = nx; y = ny; z = nz; return this }
        fun setFrom(o: Vec3): Vec3 { x = o.x; y = o.y; z = o.z; return this }
        fun clone(): Vec3 = Vec3(x, y, z)
        fun sub(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
        fun subLocal(o: Vec3): Vec3 { x -= o.x; y -= o.y; z -= o.z; return this }
        fun addLocal(o: Vec3): Vec3 { x += o.x; y += o.y; z += o.z; return this }
        fun scale(s: Float): Vec3 = Vec3(x * s, y * s, z * s)
        fun scaleLocal(s: Float): Vec3 { x *= s; y *= s; z *= s; return this }
        fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
        fun lengthSq(): Float = x * x + y * y + z * z
        fun length(): Float = sqrt(lengthSq())
        fun normalizeLocal(): Vec3 { val len = length(); if (len > 0f) { val inv = 1f / len; x *= inv; y *= inv; z *= inv }; return this }
        fun eq(o: Vec3): Boolean = x == o.x && y == o.y && z == o.z
    }
    private class TSpace {
        var os: Vec3 = Vec3(); var magS: Float = 0f
        var ot: Vec3 = Vec3(); var magT: Float = 0f
        var counter: Int = 0; var orient: Boolean = false
        fun copyFrom(ts: TSpace) { os.setFrom(ts.os); magS = ts.magS; ot.setFrom(ts.ot); magT = ts.magT; counter = ts.counter; orient = ts.orient }
    }
    private class TriInfo {
        val faceNeighbors = IntArray(3)
        val assignedGroup = arrayOfNulls<Group>(3)
        var os: Vec3 = Vec3(); var ot: Vec3 = Vec3()
        var magS: Float = 0f; var magT: Float = 0f
        var orgFaceNumber: Int = 0; var flag: Int = 0; var tSpacesOffs: Int = 0
        val vertNum = ByteArray(4)
    }
    private class Group {
        var nrFaces: Int = 0
        val faceIndices = ArrayList<Int>()
        var vertexRepresentative: Int = 0
        var orientationPreserving: Boolean = false
    }
    private class SubGroup(val nrFaces: Int, val triMembers: IntArray)
    private class TmpVert { val vert = FloatArray(3); var index: Int = 0 }
    private class Edge { val array = IntArray(3) }
}
