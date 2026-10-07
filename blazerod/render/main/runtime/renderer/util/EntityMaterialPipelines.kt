package top.fifthlight.blazerod.runtime.renderer.util

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderPass
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.ResourceLocation
import top.fifthlight.blazerod.model.Material.AlphaMode
import top.fifthlight.blazerod.render.IrisApis
import top.fifthlight.blazerod.runtime.resource.RenderMaterial
import top.fifthlight.blazerod.runtime.resource.RenderTexture

object EntityMaterialPipelines {
    private data class Key(
        val alphaMode: AlphaMode,
        val alphaCutoff: Float,
        val doubleSided: Boolean,
    )

    private val pipelines = mutableMapOf<Key, RenderPipeline>()

    fun stageOrder(material: RenderMaterial<*>): Int = when (material.alphaMode) {
        AlphaMode.OPAQUE -> 0
        AlphaMode.MASK -> 1
        AlphaMode.BLEND -> 2
    }

    fun pipeline(material: RenderMaterial<*>): RenderPipeline {
        val alphaMode = material.alphaMode
        val alphaCutoff = if (alphaMode == AlphaMode.MASK) material.alphaCutoff else .1f
        val doubleSided = material.doubleSided
        val source = when (alphaMode) {
            AlphaMode.OPAQUE -> RenderPipelines.ENTITY_SOLID
            AlphaMode.MASK -> if (doubleSided) {
                RenderPipelines.ENTITY_CUTOUT_NO_CULL
            } else {
                RenderPipelines.ENTITY_CUTOUT
            }

            AlphaMode.BLEND -> RenderPipelines.ENTITY_TRANSLUCENT
        }
        if (source.isCull == !doubleSided && (alphaMode != AlphaMode.MASK || alphaCutoff == .1f)) {
            return source
        }
        val key = Key(alphaMode, alphaCutoff, doubleSided)
        return pipelines.getOrPut(key) {
            val builder = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(
                    ResourceLocation.fromNamespaceAndPath(
                        "blazerod",
                        "pipeline/entity_${alphaMode.name.lowercase()}_${if (doubleSided) "no_cull" else "cull"}_${alphaCutoff.toBits().toUInt().toString(16)}",
                    )
                )
                .withSampler("Sampler1")
                .withCull(!doubleSided)
            if (alphaMode != AlphaMode.OPAQUE) {
                builder.withShaderDefine("ALPHA_CUTOUT", alphaCutoff)
            }
            if (alphaMode == AlphaMode.BLEND) {
                builder.withBlend(BlendFunction.TRANSLUCENT)
            }
            builder.build().also { IrisApis.copyPipeline(source, it) }
        }
    }

    fun bindBaseColor(pass: RenderPass, material: RenderMaterial<*>) {
        pass.bindSampler("Sampler0", (material.baseColorTexture ?: RenderTexture.WHITE_RGBA_TEXTURE).view)
    }
}
