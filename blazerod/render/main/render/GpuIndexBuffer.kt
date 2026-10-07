package top.fifthlight.blazerod.render

import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.vertex.VertexFormat
import top.fifthlight.blazerod.api.refcount.AbstractRefCount

class GpuIndexBuffer @JvmOverloads constructor(
    val type: VertexFormat.IndexType,
    val length: Int,
    val buffer: RefCountedGpuBuffer,
    val cpuIndices: IntArray? = null,
) : AbstractRefCount() {
    override val typeId: String
        get() = "index_buffer"

    init {
        buffer.increaseReferenceCount()
        require(buffer.inner.size == length * type.bytes) { "Index buffer size mismatch: ${buffer.inner.size} != $length * ${type.bytes}" }
        require(cpuIndices == null || cpuIndices.size == length)
    }

    override fun onClosed() {
        buffer.decreaseReferenceCount()
    }
}

fun RenderPass.setIndexBuffer(indexBuffer: GpuIndexBuffer) = setIndexBuffer(indexBuffer.buffer.inner, indexBuffer.type)
