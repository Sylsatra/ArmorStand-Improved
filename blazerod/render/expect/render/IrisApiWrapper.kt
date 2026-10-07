package top.fifthlight.blazerod.render

import com.mojang.blaze3d.vertex.VertexFormatElement
import com.mojang.blaze3d.pipeline.RenderPipeline
import top.fifthlight.mergetools.api.ExpectFactory

@Suppress("PropertyName")
interface IrisApiWrapper {
    val ENTITY_ID_ELEMENT: VertexFormatElement
    val MID_TEXTURE_ELEMENT: VertexFormatElement
    val TANGENT_ELEMENT: VertexFormatElement
    val shaderPackInUse: Boolean
    fun captureEntityIds(): IrisEntityIds
    fun copyPipeline(source: RenderPipeline, target: RenderPipeline)

    @ExpectFactory
    interface Factory {
        fun create(): IrisApiWrapper
    }
}

data class IrisEntityIds(
    val entity: Int = -1,
    val blockEntity: Int = 0,
    val item: Int = -1,
) {
    val packed0: UInt
        get() = ((entity and 0xFFFF) or ((blockEntity and 0xFFFF) shl 16)).toUInt()
    val packed1: UInt
        get() = (item and 0xFFFF).toUInt()
}
