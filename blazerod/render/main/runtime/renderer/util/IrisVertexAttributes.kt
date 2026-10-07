package top.fifthlight.blazerod.runtime.renderer.util

import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import org.joml.Matrix4fc
import org.joml.Vector3f
import top.fifthlight.blazerod.render.IrisApis
import top.fifthlight.blazerod.render.IrisEntityIds
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

object IrisVertexAttributes {
    data class Topology(
        val offsets: IntArray,
        val neighbors: IntArray,
        val triangleIndices: IntArray = IntArray(0),
    )

    data class Layout(
        val stride: Int,
        val position: Int,
        val uv: Int,
        val normal: Int,
        val entity: Int,
        val midUv: Int,
        val tangent: Int,
    )

    fun buildTopology(vertexCount: Int, mode: VertexFormat.Mode, indices: IntArray?): Topology {
        require(vertexCount >= 0)
        indices?.forEach { require(it in 0 until vertexCount) }
        val count = indices?.size ?: vertexCount
        fun vertex(index: Int) = indices?.get(index) ?: index
        fun visitTriangles(visitor: (Int, Int, Int) -> Unit) {
            when (mode) {
                VertexFormat.Mode.TRIANGLES -> {
                    for (index in 0 until count - 2 step 3) {
                        visitor(vertex(index), vertex(index + 1), vertex(index + 2))
                    }
                }

                VertexFormat.Mode.TRIANGLE_STRIP -> {
                    for (index in 0 until count - 2) {
                        if ((index and 1) == 0) {
                            visitor(vertex(index), vertex(index + 1), vertex(index + 2))
                        } else {
                            visitor(vertex(index + 1), vertex(index), vertex(index + 2))
                        }
                    }
                }

                VertexFormat.Mode.TRIANGLE_FAN -> {
                    for (index in 1 until count - 1) {
                        visitor(vertex(0), vertex(index), vertex(index + 1))
                    }
                }

                VertexFormat.Mode.QUADS -> {
                    for (index in 0 until count - 3 step 4) {
                        visitor(vertex(index), vertex(index + 1), vertex(index + 2))
                        visitor(vertex(index), vertex(index + 2), vertex(index + 3))
                    }
                }

                else -> Unit
            }
        }
        val offsets = IntArray(vertexCount + 1)
        visitTriangles { a, b, c ->
            if (a != b && b != c && c != a) {
                offsets[a + 1] += 2
                offsets[b + 1] += 2
                offsets[c + 1] += 2
            }
        }
        for (index in 1..vertexCount) {
            offsets[index] += offsets[index - 1]
        }
        val neighbors = IntArray(offsets[vertexCount])
        val triangleIndices = IntArray(neighbors.size / 2)
        var triangleCursor = 0
        val cursor = offsets.copyOf()
        fun append(vertex: Int, first: Int, second: Int) {
            neighbors[cursor[vertex]++] = first
            neighbors[cursor[vertex]++] = second
        }
        visitTriangles { a, b, c ->
            if (a != b && b != c && c != a) {
                triangleIndices[triangleCursor++] = a
                triangleIndices[triangleCursor++] = b
                triangleIndices[triangleCursor++] = c
                append(a, b, c)
                append(b, c, a)
                append(c, a, b)
            }
        }
        return Topology(offsets, neighbors, triangleIndices)
    }

    fun write(
        buffer: ByteBuffer,
        format: VertexFormat,
        vertexCount: Int,
        topology: Topology,
        entityIds: IrisEntityIds,
        tangentMatrix: Matrix4fc? = null,
        generateNormals: Boolean = false,
    ) {
        if (!IrisApis.shaderPackInUse) {
            return
        }
        val entity = IrisApis.ENTITY_ID_ELEMENT
        val midUv = IrisApis.MID_TEXTURE_ELEMENT
        val tangent = IrisApis.TANGENT_ELEMENT
        if (!format.contains(entity) || !format.contains(midUv) || !format.contains(tangent)) {
            return
        }
        write(
            buffer,
            Layout(
                stride = format.vertexSize,
                position = format.getOffset(VertexFormatElement.POSITION),
                uv = format.getOffset(VertexFormatElement.UV0),
                normal = format.getOffset(VertexFormatElement.NORMAL),
                entity = format.getOffset(entity),
                midUv = format.getOffset(midUv),
                tangent = format.getOffset(tangent),
            ),
            vertexCount,
            topology,
            entityIds,
            tangentMatrix,
            generateNormals,
        )
    }

    fun write(
        buffer: ByteBuffer,
        layout: Layout,
        vertexCount: Int,
        topology: Topology,
        entityIds: IrisEntityIds,
        tangentMatrix: Matrix4fc? = null,
        generateNormals: Boolean = false,
    ) {
        require(topology.offsets.size == vertexCount + 1)
        require(buffer.limit().toLong() >= vertexCount.toLong() * layout.stride)
        val edge1 = Vector3f()
        val edge2 = Vector3f()
        val normal = Vector3f()
        val tangent = Vector3f()
        val bitangent = Vector3f()
        val generatedNormal = Vector3f()
        for (vertex in 0 until vertexCount) {
            val base = vertex * layout.stride
            buffer.putShort(base + layout.entity, entityIds.entity.toShort())
            buffer.putShort(base + layout.entity + 2, entityIds.blockEntity.toShort())
            buffer.putShort(base + layout.entity + 4, entityIds.item.toShort())
            val u = buffer.getFloat(base + layout.uv)
            val v = buffer.getFloat(base + layout.uv + 4)
            var midU = if (u.isFinite()) u else 0f
            var midV = if (v.isFinite()) v else 0f
            val x = buffer.getFloat(base + layout.position)
            val y = buffer.getFloat(base + layout.position + 4)
            val z = buffer.getFloat(base + layout.position + 8)
            tangent.zero()
            bitangent.zero()
            generatedNormal.zero()
            val start = topology.offsets[vertex]
            val end = topology.offsets[vertex + 1]
            for (neighbor in start until end step 2) {
                val first = topology.neighbors[neighbor] * layout.stride
                val second = topology.neighbors[neighbor + 1] * layout.stride
                val firstU = buffer.getFloat(first + layout.uv)
                val firstV = buffer.getFloat(first + layout.uv + 4)
                val secondU = buffer.getFloat(second + layout.uv)
                val secondV = buffer.getFloat(second + layout.uv + 4)
                if (neighbor == start) {
                    val candidateU = (u + firstU + secondU) / 3f
                    val candidateV = (v + firstV + secondV) / 3f
                    if (candidateU.isFinite() && candidateV.isFinite()) {
                        midU = candidateU
                        midV = candidateV
                    }
                }
                edge1.set(
                    buffer.getFloat(first + layout.position) - x,
                    buffer.getFloat(first + layout.position + 4) - y,
                    buffer.getFloat(first + layout.position + 8) - z,
                )
                edge2.set(
                    buffer.getFloat(second + layout.position) - x,
                    buffer.getFloat(second + layout.position + 4) - y,
                    buffer.getFloat(second + layout.position + 8) - z,
                )
                tangentMatrix?.transformDirection(edge1)
                tangentMatrix?.transformDirection(edge2)
                val du1 = firstU - u
                val dv1 = firstV - v
                val du2 = secondU - u
                val dv2 = secondV - v
                val determinant = du1 * dv2 - du2 * dv1
                val crossX = edge1.y * edge2.z - edge1.z * edge2.y
                val crossY = edge1.z * edge2.x - edge1.x * edge2.z
                val crossZ = edge1.x * edge2.y - edge1.y * edge2.x
                val area = sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)
                if (area.isFinite() && area >= 1e-8f) {
                    generatedNormal.add(crossX, crossY, crossZ)
                }
                if (!determinant.isFinite() || abs(determinant) < 1e-8f || !area.isFinite() || area < 1e-8f) {
                    continue
                }
                val weight = area / determinant
                val tx = (edge1.x * dv2 - edge2.x * dv1) * weight
                val ty = (edge1.y * dv2 - edge2.y * dv1) * weight
                val tz = (edge1.z * dv2 - edge2.z * dv1) * weight
                val bx = (edge2.x * du1 - edge1.x * du2) * weight
                val by = (edge2.y * du1 - edge1.y * du2) * weight
                val bz = (edge2.z * du1 - edge1.z * du2) * weight
                if (tx.isFinite() && ty.isFinite() && tz.isFinite() && bx.isFinite() && by.isFinite() && bz.isFinite()) {
                    tangent.add(tx, ty, tz)
                    bitangent.add(bx, by, bz)
                }
            }
            normal.set(
                (buffer.get(base + layout.normal).toInt() / 127f).coerceIn(-1f, 1f),
                (buffer.get(base + layout.normal + 1).toInt() / 127f).coerceIn(-1f, 1f),
                (buffer.get(base + layout.normal + 2).toInt() / 127f).coerceIn(-1f, 1f),
            )
            if (generateNormals && generatedNormal.lengthSquared().isFinite() && generatedNormal.lengthSquared() >= 1e-8f) {
                normal.set(generatedNormal)
            }
            if (normal.lengthSquared() < 1e-8f) {
                normal.set(0f, 1f, 0f)
            } else {
                normal.normalize()
            }
            if (generateNormals) {
                buffer.put(base + layout.normal, pack(normal.x))
                buffer.put(base + layout.normal + 1, pack(normal.y))
                buffer.put(base + layout.normal + 2, pack(normal.z))
            }
            val projection = tangent.dot(normal)
            tangent.sub(normal.x * projection, normal.y * projection, normal.z * projection)
            if (!tangent.lengthSquared().isFinite() || tangent.lengthSquared() < 1e-8f) {
                if (abs(normal.y) < 0.999f) {
                    tangent.set(normal.z, 0f, -normal.x)
                } else {
                    tangent.set(0f, -normal.z, normal.y)
                }
            }
            tangent.normalize()
            val handedness = if (
                (tangent.y * normal.z - tangent.z * normal.y) * bitangent.x +
                (tangent.z * normal.x - tangent.x * normal.z) * bitangent.y +
                (tangent.x * normal.y - tangent.y * normal.x) * bitangent.z < 0f
            ) -1f else 1f
            buffer.putFloat(base + layout.midUv, midU)
            buffer.putFloat(base + layout.midUv + 4, midV)
            buffer.put(base + layout.tangent, pack(tangent.x))
            buffer.put(base + layout.tangent + 1, pack(tangent.y))
            buffer.put(base + layout.tangent + 2, pack(tangent.z))
            buffer.put(base + layout.tangent + 3, pack(handedness))
        }
    }

    private fun pack(value: Float) = (value.coerceIn(-1f, 1f) * 127f).toInt().toByte()
}
