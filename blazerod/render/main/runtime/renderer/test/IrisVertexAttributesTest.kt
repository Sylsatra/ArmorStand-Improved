package top.fifthlight.blazerod.runtime.renderer.test

import com.mojang.blaze3d.vertex.VertexFormat
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import top.fifthlight.blazerod.render.IrisEntityIds
import top.fifthlight.blazerod.runtime.renderer.util.IrisVertexAttributes
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IrisVertexAttributesTest {
    private val layout = IrisVertexAttributes.Layout(64, 0, 16, 24, 28, 36, 44)

    @Test
    fun indexedTrianglesUseActualVerticesAndCapturedIds() {
        val buffer = vertices(
            floatArrayOf(90f, 90f, 90f, 90f, 90f),
            floatArrayOf(1f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f, 1f),
            floatArrayOf(0f, 0f, 0f, 0f, 0f),
        )
        val topology = topology(4, intArrayOf(3, 1, 2))
        val ids = IrisEntityIds(0xABCD, 0xFEDC, 0x1234)

        IrisVertexAttributes.write(buffer, layout, 4, topology, ids)

        for (vertex in intArrayOf(3, 1, 2)) {
            val base = vertex * layout.stride
            assertEquals(0xABCD, buffer.getShort(base + layout.entity).toInt() and 0xFFFF)
            assertEquals(0xFEDC, buffer.getShort(base + layout.entity + 2).toInt() and 0xFFFF)
            assertEquals(0x1234, buffer.getShort(base + layout.entity + 4).toInt() and 0xFFFF)
            assertEquals(1f / 3f, buffer.getFloat(base + layout.midUv), 1e-6f)
            assertEquals(1f / 3f, buffer.getFloat(base + layout.midUv + 4), 1e-6f)
            assertTangent(buffer, vertex, 127, 0, 0, -127)
        }
        assertEquals(90f, buffer.getFloat(layout.midUv))
        assertEquals(0xFEDCABCDu, ids.packed0)
        assertEquals(0x1234u, ids.packed1)
    }

    @Test
    fun sharedVerticesKeepStableBasisAndFirstIncidentMidpoint() {
        val buffer = vertices(
            floatArrayOf(0f, 0f, 0f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f, 1f, 0f),
            floatArrayOf(1f, 1f, 0f, 1f, 1f),
            floatArrayOf(0f, 1f, 0f, 0f, 1f),
        )

        IrisVertexAttributes.write(buffer, layout, 4, topology(4, intArrayOf(0, 1, 2, 0, 2, 3)), IrisEntityIds())

        for (vertex in 0 until 4) {
            assertTangent(buffer, vertex, 127, 0, 0, -127)
        }
        assertEquals(2f / 3f, buffer.getFloat(layout.midUv), 1e-6f)
        assertEquals(1f / 3f, buffer.getFloat(layout.midUv + 4), 1e-6f)
        assertEquals(1f / 3f, buffer.getFloat(3 * layout.stride + layout.midUv), 1e-6f)
        assertEquals(2f / 3f, buffer.getFloat(3 * layout.stride + layout.midUv + 4), 1e-6f)
    }

    @Test
    fun expandedTrianglesHaveOneMidpointPerFace() {
        val original = vertices(
            floatArrayOf(0f, 0f, 0f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f, 1f, 0f),
            floatArrayOf(1f, 1f, 0f, 1f, 1f),
            floatArrayOf(0f, 1f, 0f, 0f, 1f),
        )
        val triangles = topology(4, intArrayOf(0, 1, 2, 0, 2, 3)).triangleIndices
        val expanded = ByteBuffer.allocate(triangles.size * layout.stride).order(ByteOrder.nativeOrder())
        triangles.forEachIndexed { vertex, source ->
            for (offset in 0 until layout.stride) {
                expanded.put(vertex * layout.stride + offset, original.get(source * layout.stride + offset))
            }
        }

        IrisVertexAttributes.write(expanded, layout, 6, topology(6), IrisEntityIds())

        for (vertex in 0 until 6) {
            val base = vertex * layout.stride
            val expectedU = if (vertex < 3) 2f / 3f else 1f / 3f
            val expectedV = if (vertex < 3) 1f / 3f else 2f / 3f
            assertEquals(expectedU, expanded.getFloat(base + layout.midUv), 1e-6f)
            assertEquals(expectedV, expanded.getFloat(base + layout.midUv + 4), 1e-6f)
            assertTangent(expanded, vertex, 127, 0, 0, -127)
        }
    }

    @Test
    fun stripsKeepConsistentWindingAndIgnoreRepeatedIndexFaces() {
        val strip = IrisVertexAttributes.buildTopology(5, VertexFormat.Mode.TRIANGLE_STRIP, intArrayOf(0, 1, 2, 3, 3, 4))

        assertArrayEquals(intArrayOf(0, 1, 2, 2, 1, 3), strip.triangleIndices)
        assertEquals(0, strip.offsets[5] - strip.offsets[4])
    }

    @Test
    fun gpuSourceVertexMappingExpandsTrianglesAndKeepsNonTriangleVertices() {
        val topology = topology(4, intArrayOf(3, 1, 2))
        val expanded = IrisVertexAttributes.packSourceVertexIndices(topology, 4).order(ByteOrder.nativeOrder())
        assertArrayEquals(intArrayOf(3, 1, 2), IntArray(3) { expanded.getInt(it * Int.SIZE_BYTES) })

        val emptyTopology = topology(3, intArrayOf(2, 2, 1))
        val identity = IrisVertexAttributes.packSourceVertexIndices(emptyTopology, 3).order(ByteOrder.nativeOrder())
        assertArrayEquals(intArrayOf(0, 1, 2), IntArray(3) { identity.getInt(it * Int.SIZE_BYTES) })
    }

    @Test
    fun mirroredUvsPreserveIrisHandedness() {
        val buffer = vertices(
            floatArrayOf(0f, 0f, 0f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f, -1f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f, 1f),
        )

        IrisVertexAttributes.write(buffer, layout, 3, topology(3), IrisEntityIds())

        for (vertex in 0 until 3) {
            assertTangent(buffer, vertex, -127, 0, 0, 127)
        }
    }

    @Test
    fun degenerateUvsAndGeometryProduceFiniteOrthogonalFallback() {
        val buffer = vertices(
            floatArrayOf(0f, 0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, Float.NaN, Float.POSITIVE_INFINITY),
        )

        IrisVertexAttributes.write(buffer, layout, 3, topology(3), IrisEntityIds())

        for (vertex in 0 until 3) {
            assertTangent(buffer, vertex, 127, 0, 0, 127)
            assertEquals(0f, buffer.getFloat(vertex * layout.stride + layout.midUv))
            assertEquals(0f, buffer.getFloat(vertex * layout.stride + layout.midUv + 4))
        }
    }

    @Test
    fun transformedGeometryGeneratesMissingNormalsAndMatchingTangents() {
        val buffer = vertices(
            floatArrayOf(0f, 0f, 0f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f, 1f),
        )
        val matrix = Matrix4f().rotateY((Math.PI / 2).toFloat()).scale(2f, 3f, 4f)

        IrisVertexAttributes.write(buffer, layout, 3, topology(3), IrisEntityIds(), matrix, generateNormals = true)

        for (vertex in 0 until 3) {
            val base = vertex * layout.stride
            assertEquals(127, buffer.get(base + layout.normal).toInt())
            assertEquals(0, buffer.get(base + layout.normal + 1).toInt())
            assertEquals(0, buffer.get(base + layout.normal + 2).toInt())
            assertTangent(buffer, vertex, 0, 0, -127, -127)
        }
    }

    @Test
    fun invalidIndicesFailBeforeReadingVertexMemory() {
        assertThrows(IllegalArgumentException::class.java) {
            topology(3, intArrayOf(0, 1, 4))
        }
    }

    private fun topology(count: Int, indices: IntArray? = null) =
        IrisVertexAttributes.buildTopology(count, VertexFormat.Mode.TRIANGLES, indices)

    private fun vertices(vararg data: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocate(data.size * layout.stride).order(ByteOrder.nativeOrder())
        for (index in 0 until buffer.capacity()) {
            buffer.put(index, 0x5A.toByte())
        }
        data.forEachIndexed { vertex, value ->
            val base = vertex * layout.stride
            for (component in 0 until 3) {
                buffer.putFloat(base + layout.position + component * 4, value[component])
            }
            buffer.putFloat(base + layout.uv, value[3])
            buffer.putFloat(base + layout.uv + 4, value[4])
            buffer.put(base + layout.normal, 0)
            buffer.put(base + layout.normal + 1, 0)
            buffer.put(base + layout.normal + 2, 127)
        }
        return buffer
    }

    private fun assertTangent(buffer: ByteBuffer, vertex: Int, x: Int, y: Int, z: Int, handedness: Int) {
        val base = vertex * layout.stride + layout.tangent
        assertEquals(x, buffer.get(base).toInt())
        assertEquals(y, buffer.get(base + 1).toInt())
        assertEquals(z, buffer.get(base + 2).toInt())
        assertEquals(handedness, buffer.get(base + 3).toInt())
    }
}
