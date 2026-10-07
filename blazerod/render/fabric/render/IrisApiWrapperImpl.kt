package top.fifthlight.blazerod.render

import com.mojang.blaze3d.vertex.VertexFormatElement
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.fabricmc.loader.api.FabricLoader
import net.irisshaders.iris.api.v0.IrisApi
import net.irisshaders.iris.vertices.IrisVertexFormats
import net.irisshaders.iris.pipeline.IrisPipelines
import net.irisshaders.iris.uniforms.CapturedRenderingState
import top.fifthlight.mergetools.api.ActualConstructor
import top.fifthlight.mergetools.api.ActualImpl

@ActualImpl(IrisApiWrapper::class)
class IrisApiWrapperImpl @ActualConstructor("create") constructor() : IrisApiWrapper {
    override val ENTITY_ID_ELEMENT: VertexFormatElement
        get() = IrisVertexFormats.ENTITY_ID_ELEMENT
    override val MID_TEXTURE_ELEMENT: VertexFormatElement
        get() = IrisVertexFormats.MID_TEXTURE_ELEMENT
    override val TANGENT_ELEMENT: VertexFormatElement
        get() = IrisVertexFormats.TANGENT_ELEMENT

    private val irisApi = if (FabricLoader.getInstance().isModLoaded("iris")) {
        IrisApi.getInstance()
    } else {
        null
    }

    override val shaderPackInUse: Boolean
        get() = irisApi?.isShaderPackInUse == true

    override fun captureEntityIds(): IrisEntityIds {
        if (irisApi == null) {
            return IrisEntityIds()
        }
        val state = CapturedRenderingState.INSTANCE
        return IrisEntityIds(
            entity = state.currentRenderedEntity,
            blockEntity = state.currentRenderedBlockEntity,
            item = state.currentRenderedItem,
        )
    }

    override fun copyPipeline(source: RenderPipeline, target: RenderPipeline) {
        if (irisApi != null) {
            IrisPipelines.copyPipeline(source, target)
        }
    }
}
