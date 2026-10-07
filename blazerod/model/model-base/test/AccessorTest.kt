package top.fifthlight.blazerod.model.test

import top.fifthlight.blazerod.model.Accessor
import top.fifthlight.blazerod.model.Buffer
import top.fifthlight.blazerod.model.BufferView
import top.fifthlight.blazerod.model.readByteBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class AccessorTest {
    @Test
    fun readByteBufferKeepsLittleEndianUnsignedShortIndices() {
        val source = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1)
            .putShort(300)
            .flip()
        val accessor = Accessor(
            bufferView = BufferView(
                buffer = Buffer(buffer = source),
                byteLength = source.remaining(),
                byteOffset = 0,
                byteStride = 0,
            ),
            componentType = Accessor.ComponentType.UNSIGNED_SHORT,
            count = 2,
            type = Accessor.AccessorType.SCALAR,
        )

        val indices = accessor.readByteBuffer()

        assertEquals(ByteOrder.LITTLE_ENDIAN, indices.order())
        assertEquals(1, indices.getShort(0).toInt())
        assertEquals(300, indices.getShort(2).toInt())
    }
}
