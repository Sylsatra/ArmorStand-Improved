package top.fifthlight.blazerod.runtime.resource

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import top.fifthlight.blazerod.api.refcount.AbstractRefCount
import top.fifthlight.blazerod.extension.GpuBufferExt
import top.fifthlight.blazerod.extension.createBuffer
import top.fifthlight.blazerod.extension.extraUsage
import top.fifthlight.blazerod.render.GpuIndexBuffer
import top.fifthlight.blazerod.render.RefCountedGpuBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RenderPrimitive @JvmOverloads constructor(
    val vertices: Int,
    val vertexFormatMode: VertexFormat.Mode,
    val gpuVertexBuffer: RefCountedGpuBuffer?,
    val cpuVertexBuffer: ByteBuffer?,
    val indexBuffer: GpuIndexBuffer?,
    val material: RenderMaterial<*>,
    val targets: Targets?,
    val targetGroups: List<MorphTargetGroup>,
    val vertexNormals: FloatArray? = null,
) : AbstractRefCount() {
    private var irisSourceVertexBuffer: GpuBuffer? = null
    private var irisSourceNormalBuffer: GpuBuffer? = null

    override val typeId: String
        get() = "primitive"

    init {
        gpuVertexBuffer?.increaseReferenceCount()
        indexBuffer?.increaseReferenceCount()
        material.increaseReferenceCount()
        if (targetGroups.isEmpty()) {
            require(targets == null) { "Empty target groups with non-empty targets" }
        } else {
            require(targets != null) { "Non-empty target groups with empty targets" }
        }
        require(vertexNormals == null || vertexNormals.size.toLong() == vertices.toLong() * 3) {
            "Vertex normal count does not match primitive vertex count"
        }
    }

    val gpuComplete = gpuVertexBuffer != null && targets?.gpuComplete != false
    val cpuComplete = cpuVertexBuffer != null && targets?.cpuComplete != false

    val supportsIrisCompute: Boolean
        get() = gpuComplete && (indexBuffer == null || indexBuffer.cpuIndices != null)

    fun irisSourceVertexBuffer(createData: () -> ByteBuffer): GpuBufferSlice {
        check(supportsIrisCompute) { "Primitive cannot provide GPU Iris vertex mapping" }
        val buffer = irisSourceVertexBuffer ?: run {
            RenderSystem.getDevice().createBuffer(
                null,
                0,
                GpuBufferExt.EXTRA_USAGE_STORAGE_BUFFER,
                createData(),
            ).also { irisSourceVertexBuffer = it }
        }
        return buffer.slice()
    }

    fun irisSourceNormalBuffer(createData: () -> ByteBuffer): GpuBufferSlice {
        check(supportsIrisCompute && vertexNormals != null) { "Primitive cannot provide GPU Iris source normals" }
        val buffer = irisSourceNormalBuffer ?: run {
            RenderSystem.getDevice().createBuffer(
                null,
                0,
                GpuBufferExt.EXTRA_USAGE_STORAGE_BUFFER,
                createData().order(ByteOrder.nativeOrder()),
            ).also { irisSourceNormalBuffer = it }
        }
        return buffer.slice()
    }

    class Target(
        val gpuBuffer: GpuBuffer?,
        val cpuBuffer: ByteBuffer?,
        val targetsCount: Int,
    ) : AutoCloseable {
        val slice = gpuBuffer?.slice()

        init {
            gpuBuffer?.let {
                val tbo = gpuBuffer.usage() and GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER != 0
                val ssbo = gpuBuffer.extraUsage and GpuBufferExt.EXTRA_USAGE_STORAGE_BUFFER != 0
                require(tbo || ssbo) {
                    "RenderPrimitive's target should have buffer with usage USAGE_UNIFORM_TEXEL_BUFFER or EXTRA_USAGE_STORAGE_BUFFER"
                }
            }
        }

        override fun close() {
            gpuBuffer?.close()
        }
    }

    class Targets(
        val position: Target,
        val color: Target,
        val texCoord: Target,
    ) {
        val gpuComplete = position.gpuBuffer != null && color.gpuBuffer != null && texCoord.gpuBuffer != null
        val cpuComplete = position.cpuBuffer != null && color.cpuBuffer != null && texCoord.cpuBuffer != null
    }

    override fun onClosed() {
        irisSourceVertexBuffer?.close()
        irisSourceVertexBuffer = null
        irisSourceNormalBuffer?.close()
        irisSourceNormalBuffer = null
        gpuVertexBuffer?.decreaseReferenceCount()
        indexBuffer?.decreaseReferenceCount()
        material.decreaseReferenceCount()
        targets?.apply {
            position.close()
            color.close()
            texCoord.close()
        }
    }
}
