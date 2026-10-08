package top.fifthlight.blazerod.runtime.renderer

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.opengl.GlRenderPass
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.VertexFormat
import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector4f
import top.fifthlight.blazerod.BlazeRod
import top.fifthlight.blazerod.api.render.Renderer
import top.fifthlight.blazerod.api.resource.RenderTask
import top.fifthlight.blazerod.extension.*
import top.fifthlight.blazerod.model.toVector4f
import top.fifthlight.blazerod.render.BlazerodVertexFormats
import top.fifthlight.blazerod.render.IrisApis
import top.fifthlight.blazerod.render.setIndexBuffer
import top.fifthlight.blazerod.runtime.RenderSceneImpl
import top.fifthlight.blazerod.runtime.RenderTaskImpl
import top.fifthlight.blazerod.runtime.TaskMap
import top.fifthlight.blazerod.runtime.data.MorphTargetBuffer
import top.fifthlight.blazerod.runtime.data.RenderSkinBuffer
import top.fifthlight.blazerod.runtime.node.component.PrimitiveComponent
import top.fifthlight.blazerod.runtime.resource.RenderMaterial
import top.fifthlight.blazerod.runtime.resource.RenderPrimitive
import top.fifthlight.blazerod.runtime.renderer.util.EntityMaterialPipelines
import top.fifthlight.blazerod.runtime.renderer.util.IrisVertexAttributes
import top.fifthlight.blazerod.runtime.uniform.ComputeDataUniformBuffer
import top.fifthlight.blazerod.runtime.uniform.MorphDataUniformBuffer
import top.fifthlight.blazerod.runtime.uniform.SkinModelIndicesUniformBuffer
import top.fifthlight.blazerod.systems.ComputePass
import top.fifthlight.blazerod.systems.ComputePipeline
import top.fifthlight.blazerod.util.bitmap.BitmapItem
import top.fifthlight.blazerod.util.gpushaderpool.GpuShaderDataPool
import top.fifthlight.blazerod.util.gpushaderpool.ofSsbo
import top.fifthlight.blazerod.util.gpushaderpool.upload
import top.fifthlight.blazerod.util.math.ceilDiv
import top.fifthlight.blazerod.util.objectpool.ObjectPool
import java.util.*

class ComputeShaderTransformRenderer private constructor() :
    ScheduledRendererImpl<ComputeShaderTransformRenderer, ComputeShaderTransformRenderer.Type>() {

    @Suppress("NOTHING_TO_INLINE")
    @JvmInline
    private value class PipelineInfo(val bitmap: BitmapItem = BitmapItem()) {
        constructor(
            skinned: Boolean = false,
            irisVertexFormat: Boolean = false,
            morphed: Boolean = false,
            irisSourceNormals: Boolean = false,
        ) : this(Unit.run {
            var item = BitmapItem()
            if (skinned) {
                item += ELEMENT_SKINNED
            }
            if (irisVertexFormat) {
                item += ELEMENT_IRIS_VERTEX_FORMAT
            }
            if (morphed) {
                item += ELEMENT_MORPHED
            }
            if (irisSourceNormals) {
                item += ELEMENT_IRIS_SOURCE_NORMALS
            }
            item
        })

        constructor(
            material: RenderMaterial<*>,
            irisVertexFormat: Boolean,
            irisSourceNormals: Boolean,
        ) : this(
            skinned = material.skinned,
            irisVertexFormat = irisVertexFormat,
            morphed = material.morphed,
            irisSourceNormals = irisSourceNormals,
        )

        val skinned
            get() = ELEMENT_SKINNED in bitmap
        val irisVertexFormat
            get() = ELEMENT_IRIS_VERTEX_FORMAT in bitmap
        val morphed
            get() = ELEMENT_MORPHED in bitmap
        val irisSourceNormals
            get() = ELEMENT_IRIS_SOURCE_NORMALS in bitmap

        fun nameSuffix() = buildString {
            if (skinned) {
                append("_skinned")
            }
            if (irisVertexFormat) {
                append("_iris_vertex_format")
            }
            if (morphed) {
                append("_morphed")
            }
            if (irisSourceNormals) {
                append("_iris_source_normals")
            }
        }

        companion object {
            val ELEMENT_SKINNED = BitmapItem.Element.of(0)
            val ELEMENT_IRIS_VERTEX_FORMAT = BitmapItem.Element.of(1)
            val ELEMENT_MORPHED = BitmapItem.Element.of(2)
            val ELEMENT_IRIS_SOURCE_NORMALS = BitmapItem.Element.of(3)
        }

        inline operator fun plus(element: BitmapItem.Element) =
            PipelineInfo(bitmap + element)

        inline operator fun minus(element: BitmapItem.Element) =
            PipelineInfo(bitmap - element)

        inline operator fun contains(element: BitmapItem.Element) =
            element in bitmap

        override fun toString(): String {
            return "PipelineInfo(skinned=$skinned, irisVertexFormat=$irisVertexFormat, morphed=$morphed, irisSourceNormals=$irisSourceNormals)"
        }
    }

    companion object Type : Renderer.Type<ComputeShaderTransformRenderer, Type>() {
        override val id: String
            get() = "compute_shader"
        override val isAvailable: Boolean by lazy {
            val device = RenderSystem.getDevice()
            device.supportSsbo && device.supportComputeShader && device.supportMemoryBarrier && device.supportShaderPacking
        }

        override val supportScheduling: Boolean
            get() = true

        override fun create() = ComputeShaderTransformRenderer()

        private val pipelineCache = mutableMapOf<RenderMaterial.Descriptor, Int2ReferenceMap<ComputePipeline>>()
        private val irisAttributePipelineCache =
            mutableMapOf<Triple<RenderMaterial.Descriptor, Boolean, Boolean>, ComputePipeline>()

        private fun getPipeline(
            material: RenderMaterial<*>,
            irisVertexFormat: Boolean,
            irisSourceNormals: Boolean,
        ): ComputePipeline {
            val pipelineInfo = PipelineInfo(
                material = material,
                irisVertexFormat = irisVertexFormat,
                irisSourceNormals = irisSourceNormals,
            )
            val materialMap = pipelineCache.getOrPut(material.descriptor) { Int2ReferenceAVLTreeMap() }
            return materialMap.getOrPut(pipelineInfo.bitmap.inner) {
                ComputePipeline.builder().apply {
                    withLocation(ResourceLocation.fromNamespaceAndPath("blazerod", "vertex_transform" + pipelineInfo.nameSuffix()))
                    withComputeShader(ResourceLocation.fromNamespaceAndPath("blazerod", "compute/vertex_transform"))
                    withShaderDefine("SUPPORT_SSBO")
                    withShaderDefine("COMPUTE_SHADER")
                    withStorageBuffer("SourceVertexData")
                    withStorageBuffer("TargetVertexData")
                    if (pipelineInfo.irisVertexFormat) {
                        withShaderDefine("IRIS_VERTEX_FORMAT")
                        withShaderDefine("IRIS_DIRECT_VERTEX_FORMAT")
                    }
                    if (pipelineInfo.irisSourceNormals) {
                        withShaderDefine("IRIS_SOURCE_NORMALS")
                        withStorageBuffer("IrisSourceNormalsData")
                    }
                    if (pipelineInfo.morphed) {
                        withShaderDefine("MORPHED")
                        withShaderDefine("MAX_ENABLED_MORPH_TARGETS", BlazeRod.MAX_ENABLED_MORPH_TARGETS)
                        withUniform("MorphData", UniformType.UNIFORM_BUFFER)
                        withStorageBuffer("MorphPositionBlock")
                        withStorageBuffer("MorphColorBlock")
                        withStorageBuffer("MorphTexCoordBlock")
                        withStorageBuffer("MorphTargetIndicesData")
                        withStorageBuffer("MorphWeightsData")
                    }
                    if (pipelineInfo.skinned) {
                        withShaderDefine("SKINNED")
                        withUniform("SkinModelIndices", UniformType.UNIFORM_BUFFER)
                        withStorageBuffer("JointsData")
                    }
                    withUniform("ComputeData", UniformType.UNIFORM_BUFFER)
                    withShaderDefine("INSTANCE_SIZE", BlazeRod.INSTANCE_SIZE)
                    withShaderDefine("COMPUTE_LOCAL_SIZE", BlazeRod.COMPUTE_LOCAL_SIZE)
                    withShaderDefine("INPUT_MATERIAL", material.descriptor.id)
                }.build()
            }
        }

        private fun getIrisAttributePipeline(
            material: RenderMaterial<*>,
            generateNormals: Boolean,
            generateTangents: Boolean,
        ): ComputePipeline =
            irisAttributePipelineCache.getOrPut(Triple(material.descriptor, generateNormals, generateTangents)) {
                ComputePipeline.builder().apply {
                    withLocation(ResourceLocation.fromNamespaceAndPath(
                        "blazerod",
                        "vertex_iris_attributes${if (generateNormals) "_generated_normals" else ""}",
                    ))
                    withComputeShader(ResourceLocation.fromNamespaceAndPath("blazerod", "compute/iris_vertex_attributes"))
                    withShaderDefine("SUPPORT_SSBO")
                    withShaderDefine("COMPUTE_SHADER")
                    withShaderDefine("IRIS_VERTEX_FORMAT")
                    if (generateNormals) {
                        withShaderDefine("GENERATE_NORMALS")
                    }
                    if (generateTangents) {
                        withShaderDefine("GENERATE_TANGENTS")
                    }
                    withStorageBuffer("SourceVertexData")
                    withStorageBuffer("TargetVertexData")
                    withStorageBuffer("IrisTriangleIndicesData")
                    withUniform("ComputeData", UniformType.UNIFORM_BUFFER)
                    withShaderDefine("COMPUTE_LOCAL_SIZE", BlazeRod.COMPUTE_LOCAL_SIZE)
                }.build()
            }
    }

    override val type: Type
        get() = Type

    private val dataPool = GpuShaderDataPool.ofSsbo()
    private val vertexDataPool = GpuShaderDataPool.create(
        usage = GpuBuffer.USAGE_VERTEX,
        extraUsage = GpuBufferExt.EXTRA_USAGE_STORAGE_BUFFER,
        alignment = RenderSystem.getDevice().ssboOffsetAlignment,
        supportSlicing = false,
    )

    private fun prepareCompute(
        primitive: RenderPrimitive,
        task: RenderTaskImpl,
        skinBuffer: RenderSkinBuffer?,
        targetBuffer: MorphTargetBuffer?,
        targetVertexFormat: VertexFormat,
        irisVertexFormat: Boolean,
        modelNormalMatrix: Matrix4fc,
        modelTangentMatrix: Matrix4fc,
    ): ComputeOutput {
        val material = primitive.material
        val directIrisOutput = irisVertexFormat && material.descriptor.id == 0 && primitive.vertexNormals != null
        val irisTopology = if (irisVertexFormat && !directIrisOutput) {
            irisTopology(primitive)
        } else {
            null
        }
        val irisExpanded = irisTopology?.triangleIndices?.isNotEmpty() == true
        val expandIrisOutput = irisExpanded && !directIrisOutput
        val outputVertices = if (expandIrisOutput) irisTopology!!.triangleIndices.size else primitive.vertices
        val outputMode = if (expandIrisOutput) VertexFormat.Mode.TRIANGLES else primitive.vertexFormatMode
        val irisSourceIndices = if (irisVertexFormat && !directIrisOutput) {
            primitive.irisSourceVertexBuffer {
                IrisVertexAttributes.packSourceVertexIndices(irisTopology!!, primitive.vertices)
            }
        } else {
            null
        }
        val sourceNormals = primitive.vertexNormals
        val irisSourceNormals = if (irisVertexFormat && sourceNormals != null) {
            primitive.irisSourceNormalBuffer {
                IrisVertexAttributes.packSourceVertexNormals(sourceNormals, primitive.vertices)
            }
        } else {
            null
        }
        val transformVertexFormat = if (directIrisOutput) {
            targetVertexFormat
        } else if (irisVertexFormat) {
            BlazerodVertexFormats.ENTITY_PADDED
        } else {
            targetVertexFormat
        }
        val transformVertices = if (directIrisOutput) outputVertices else primitive.vertices
        val transformedVertexData = vertexDataPool.allocate(
            transformVertexFormat.vertexSize * transformVertices,
        )
        val targetVertexData = if (irisVertexFormat && !directIrisOutput) {
            vertexDataPool.allocate(targetVertexFormat.vertexSize * outputVertices)
        } else {
            transformedVertexData
        }
        val computeDataUniformBufferSlice: GpuBufferSlice
        val irisAttributeData: GpuBufferSlice?
        var skinModelIndicesBufferSlice: GpuBufferSlice? = null
        var skinJointBufferSlice: GpuBufferSlice? = null
        var morphDataUniformBufferSlice: GpuBufferSlice? = null
        var morphWeightsBufferSlice: GpuBufferSlice? = null
        var morphTargetIndicesBufferSlice: GpuBufferSlice? = null

        computeDataUniformBufferSlice = ComputeDataUniformBuffer.write {
            this.modelNormalMatrix = modelNormalMatrix
            totalVertices = transformVertices.toUInt()
            uv1 = OverlayTexture.NO_OVERLAY.toUInt()
            uv2 = task.light.toUInt()
            this.modelTangentMatrix = modelTangentMatrix
            irisEntity0 = task.irisEntityIds.packed0
            irisEntity1 = task.irisEntityIds.packed1
            this.irisExpanded = if (expandIrisOutput) 1u else 0u
        }
        irisAttributeData = if (irisVertexFormat && !directIrisOutput) {
            ComputeDataUniformBuffer.write {
                this.modelNormalMatrix = modelNormalMatrix
                totalVertices = outputVertices.toUInt()
                uv1 = OverlayTexture.NO_OVERLAY.toUInt()
                uv2 = task.light.toUInt()
                this.modelTangentMatrix = modelTangentMatrix
                irisEntity0 = task.irisEntityIds.packed0
                irisEntity1 = task.irisEntityIds.packed1
                this.irisExpanded = if (irisExpanded) 1u else 0u
            }
        } else {
            null
        }
        skinBuffer?.let { skinBuffer ->
            skinModelIndicesBufferSlice = SkinModelIndicesUniformBuffer.write {
                skinJoints = skinBuffer.jointSize
            }
            skinJointBufferSlice = dataPool.upload(skinBuffer.buffer)
        }
        targetBuffer?.let { targetBuffer ->
            primitive.targets?.let { targets ->
                morphDataUniformBufferSlice = MorphDataUniformBuffer.write {
                    totalVertices = primitive.vertices
                    posTargets = targets.position.targetsCount
                    colorTargets = targets.color.targetsCount
                    texCoordTargets = targets.texCoord.targetsCount
                    totalTargets =
                        targets.position.targetsCount + targets.color.targetsCount + targets.texCoord.targetsCount
                }
            }
            morphWeightsBufferSlice = dataPool.upload(targetBuffer.weightsBuffer)
            morphTargetIndicesBufferSlice = dataPool.upload(targetBuffer.indicesBuffer)
        }

        val pipeline = getPipeline(
            material = material,
            irisVertexFormat = directIrisOutput,
            irisSourceNormals = irisSourceNormals != null,
        )
        val attributePipeline = if (irisVertexFormat && !directIrisOutput) {
            getIrisAttributePipeline(
                material = material,
                generateNormals = material.descriptor.id == 0 && primitive.vertexNormals == null,
                // BlazeRod's Unlit materials have no normal-map input. Keep their Iris
                // tangent attribute valid, but avoid deriving a full tangent basis per triangle.
                generateTangents = material.descriptor.id != 0,
            )
        } else {
            null
        }

        return ComputeOutput(
            vertexBuffer = targetVertexData,
            transformedVertexBuffer = transformedVertexData,
            vertices = outputVertices,
            transformVertices = transformVertices,
            irisAttributeInvocations = if (!irisVertexFormat) {
                0
            } else if (irisExpanded) {
                outputVertices / 3
            } else {
                outputVertices
            },
            mode = outputMode,
            useIndexBuffer = !expandIrisOutput && primitive.indexBuffer != null,
            primitive = primitive,
            pipeline = pipeline,
            computeData = computeDataUniformBufferSlice,
            irisAttributeData = irisAttributeData,
            irisSourceIndices = irisSourceIndices,
            irisSourceNormals = irisSourceNormals,
            skinModelIndices = skinModelIndicesBufferSlice,
            skinJoints = skinJointBufferSlice,
            morphData = morphDataUniformBufferSlice,
            morphWeights = morphWeightsBufferSlice,
            morphTargetIndices = morphTargetIndicesBufferSlice,
            irisAttributePipeline = attributePipeline,
        )
    }

    private data class ComputeOutput(
        val vertexBuffer: GpuBufferSlice,
        val transformedVertexBuffer: GpuBufferSlice,
        val vertices: Int,
        val transformVertices: Int,
        val irisAttributeInvocations: Int,
        val mode: VertexFormat.Mode,
        val useIndexBuffer: Boolean,
        val primitive: RenderPrimitive,
        val pipeline: ComputePipeline,
        val computeData: GpuBufferSlice,
        val irisAttributeData: GpuBufferSlice?,
        val irisSourceIndices: GpuBufferSlice?,
        val irisSourceNormals: GpuBufferSlice?,
        val skinModelIndices: GpuBufferSlice?,
        val skinJoints: GpuBufferSlice?,
        val morphData: GpuBufferSlice?,
        val morphWeights: GpuBufferSlice?,
        val morphTargetIndices: GpuBufferSlice?,
        val irisAttributePipeline: ComputePipeline?,
    )

    private fun dispatchCompute(output: ComputeOutput, pass: ComputePass) {
        val primitive = output.primitive
        val material = primitive.material
        val device = RenderSystem.getDevice()
        pass.setPipeline(output.pipeline)

        if (GlRenderPass.VALIDATION) {
            require(material.skinned == (output.skinJoints != null)) {
                "Primitive's skin data ${output.skinJoints != null} and material skinned ${material.skinned} not matching"
            }
        }
        pass.setStorageBuffer("SourceVertexData", primitive.gpuVertexBuffer!!.inner.slice())
        pass.setStorageBuffer("TargetVertexData", output.transformedVertexBuffer)
        output.irisSourceNormals?.let { pass.setStorageBuffer("IrisSourceNormalsData", it) }
        pass.setUniform("ComputeData", output.computeData)
        output.skinJoints?.let { skinJointBuffer ->
            if (device.supportSsbo) {
                pass.setStorageBuffer("JointsData", skinJointBuffer)
            } else {
                pass.setUniform("Joints", skinJointBuffer)
            }
        }
        output.skinModelIndices?.let { pass.setUniform("SkinModelIndices", it) }
        output.morphData?.let { pass.setUniform("MorphData", it) }
        output.morphWeights?.let { morphWeightsBuffer ->
            if (device.supportSsbo) {
                pass.setStorageBuffer("MorphWeightsData", morphWeightsBuffer)
            } else {
                pass.setUniform("MorphWeights", morphWeightsBuffer)
            }
        }
        output.morphTargetIndices?.let { morphTargetIndicesBuffer ->
            if (device.supportSsbo) {
                pass.setStorageBuffer("MorphTargetIndicesData", morphTargetIndicesBuffer)
            } else {
                pass.setUniform("MorphTargetIndices", morphTargetIndicesBuffer)
            }
        }
        primitive.targets?.let { targets ->
            pass.setStorageBuffer("MorphPositionBlock", targets.position.slice!!)
            pass.setStorageBuffer("MorphColorBlock", targets.color.slice!!)
            pass.setStorageBuffer("MorphTexCoordBlock", targets.texCoord.slice!!)
        }
        pass.dispatch(output.transformVertices ceilDiv BlazeRod.COMPUTE_LOCAL_SIZE, 1, 1)
    }

    private fun dispatchIrisAttributes(output: ComputeOutput, pass: ComputePass) {
        val pipeline = output.irisAttributePipeline ?: return
        pass.setPipeline(pipeline)
        pass.setStorageBuffer("SourceVertexData", output.transformedVertexBuffer)
        pass.setStorageBuffer("TargetVertexData", output.vertexBuffer)
        pass.setStorageBuffer("IrisTriangleIndicesData", requireNotNull(output.irisSourceIndices))
        pass.setUniform("ComputeData", requireNotNull(output.irisAttributeData))
        pass.dispatch(output.irisAttributeInvocations ceilDiv BlazeRod.COMPUTE_LOCAL_SIZE, 1, 1)
    }

    private class ComputeItem private constructor() {
        private var released = true
        private var _primitiveComponent: PrimitiveComponent? = null
        private var _renderTask: RenderTaskImpl? = null
        private var _vertexFormat: VertexFormat? = null
        private var _computeOutput: ComputeOutput? = null

        val primitiveComponent
            get() = _primitiveComponent!!
        val renderTask
            get() = _renderTask!!
        val vertexFormat
            get() = _vertexFormat!!
        val computeOutput
            get() = _computeOutput!!

        fun release() {
            if (released) {
                return
            }
            POOL.release(this)
        }

        companion object {
            private val POOL = ObjectPool(
                identifier = "compute_item",
                create = ::ComputeItem,
                onAcquired = {
                    released = false
                },
                onReleased = {
                    released = true
                    _primitiveComponent = null
                    _renderTask = null
                    _vertexFormat = null
                    _computeOutput = null
                },
                onClosed = {},
            )

            fun acquire(
                primitiveComponent: PrimitiveComponent,
                renderTask: RenderTaskImpl,
                vertexFormat: VertexFormat,
                computeOutput: ComputeOutput,
            ) = POOL.acquire().apply {
                _primitiveComponent = primitiveComponent
                _renderTask = renderTask
                _vertexFormat = vertexFormat
                _computeOutput = computeOutput
            }
        }
    }

    private val modelMatrix = Matrix4f()
    private val modelNormalMatrix = Matrix4f()
    private val modelTangentMatrix = Matrix4f()
    private val renderTasks = mutableListOf<RenderTaskImpl>()
    private val computeItems = mutableListOf<ComputeItem>()
    private val fallbackTasks = TaskMap()
    private val irisTopologies = WeakHashMap<RenderPrimitive, IrisVertexAttributes.Topology>()
    private val cpuRendererDelegate = lazy { CpuTransformRenderer.create() }

    private fun irisTopology(primitive: RenderPrimitive) = irisTopologies.getOrPut(primitive) {
        IrisVertexAttributes.buildTopology(
            primitive.vertices,
            primitive.vertexFormatMode,
            primitive.indexBuffer?.cpuIndices,
        )
    }

    private fun supportsIrisCompute(primitive: RenderPrimitive): Boolean {
        if (!primitive.supportsIrisCompute) {
            return false
        }
        val requiredStorageBindings = 3 +
            (if (primitive.material.morphed) 5 else 0) +
            (if (primitive.material.skinned) 1 else 0) +
            (if (primitive.vertexNormals != null) 1 else 0)
        return requiredStorageBindings <= RenderSystem.getDevice().maxSsboBindings
    }

    override fun schedule(task: RenderTask) {
        val task = task as RenderTaskImpl
        val instance = task.instance
        val scene = instance.scene
        if (scene.primitiveComponents.any {
                !it.primitive.gpuComplete || (IrisApis.shaderPackInUse && !supportsIrisCompute(it.primitive))
            }) {
            fallbackTasks.addTask(task)
            return
        }
        renderTasks.add(task)
        val preparedItems = ArrayList<ComputeItem>(scene.primitiveComponents.size)
        for (primitiveComponent in scene.primitiveComponents) {
            val primitive = primitiveComponent.primitive

            task.localMatricesBuffer.content.getPositionMatrix(
                primitiveComponent.primitiveIndex,
                modelMatrix,
            )
            modelMatrix.mulLocal(task.modelMatrix)
            modelMatrix.normal(modelNormalMatrix)
            modelTangentMatrix.set(modelMatrix)

            val irisVertexFormat = IrisApis.shaderPackInUse
            val targetVertexFormat = if (irisVertexFormat) {
                BlazerodVertexFormats.IRIS_ENTITY_PADDED
            } else {
                BlazerodVertexFormats.ENTITY_PADDED
            }
            val computeOutput = prepareCompute(
                primitive = primitive,
                task = task,
                skinBuffer = primitiveComponent.skinIndex?.let { task.skinBuffer[it] }?.content,
                targetBuffer = primitiveComponent.morphedPrimitiveIndex?.let { task.morphTargetBuffer[it] }?.content,
                targetVertexFormat = targetVertexFormat,
                irisVertexFormat = irisVertexFormat,
                modelNormalMatrix = modelNormalMatrix,
                modelTangentMatrix = modelTangentMatrix,
            )

            val item = ComputeItem.acquire(
                primitiveComponent = primitiveComponent,
                renderTask = task,
                vertexFormat = targetVertexFormat,
                computeOutput = computeOutput,
            )

            preparedItems.add(item)
            computeItems.add(item)
        }

        if (preparedItems.isNotEmpty()) {
            val commandEncoder = RenderSystem.getDevice().createCommandEncoder()
            BlazeRodGpuTimer.measure(BlazeRodGpuTimer.TRANSFORM) {
                commandEncoder.createComputePass { "BlazeRod transform batch" }.use { pass ->
                    for (item in preparedItems) {
                        dispatchCompute(item.computeOutput, pass)
                    }
                }
            }

            if (preparedItems.any { it.computeOutput.irisAttributePipeline != null }) {
                commandEncoder.memoryBarrier(CommandEncoderExt.BARRIER_STORAGE_BUFFER_BIT)
                BlazeRodGpuTimer.measure(BlazeRodGpuTimer.IRIS_ATTRIBUTES) {
                    commandEncoder.createComputePass { "BlazeRod Iris attribute batch" }.use { pass ->
                        for (item in preparedItems) {
                            if (item.computeOutput.irisAttributePipeline == null) continue
                            dispatchIrisAttributes(item.computeOutput, pass)
                        }
                    }
                }
            }
        }
    }

    private val baseColor = Vector4f()
    override fun executeTasks(
        colorFrameBuffer: GpuTextureView,
        depthFrameBuffer: GpuTextureView?,
    ) {
        fallbackTasks.executeTasks { scene, tasks ->
            for (task in tasks) {
                cpuRendererDelegate.value.render(colorFrameBuffer, depthFrameBuffer, task, scene)
            }
        }
        if (computeItems.isEmpty()) {
            BlazeRodGpuTimer.reportIfReady()
            return
        }

        val device = RenderSystem.getDevice()
        val commandEncoder = device.createCommandEncoder()
        commandEncoder.memoryBarrier(CommandEncoderExt.BARRIER_STORAGE_BUFFER_BIT or CommandEncoderExt.BARRIER_VERTEX_BUFFER_BIT)

        BlazeRodGpuTimer.measure(BlazeRodGpuTimer.DRAW) {
            for (stage in 0..2) {
                val drawItems = computeItems.asSequence()
                    .filter {
                        EntityMaterialPipelines.stageOrder(it.primitiveComponent.primitive.material) == stage
                    }
                    .map { item ->
                        val task = item.renderTask
                        val primitiveComponent = item.primitiveComponent
                        val primitive = primitiveComponent.primitive
                        val material = primitive.material

                        task.localMatricesBuffer.content.getPositionMatrix(
                            primitiveComponent.primitiveIndex,
                            modelMatrix,
                        )
                        modelMatrix.mulLocal(task.modelMatrix)
                        modelMatrix.mulLocal(RenderSystem.getModelViewStack())

                        // Dynamic uniform writes map a GPU buffer, so prepare them before
                        // opening the render pass below.
                        val dynamicUniforms = RenderSystem.getDynamicUniforms().writeTransform(
                            modelMatrix,
                            material.baseColor.toVector4f(baseColor),
                            RenderSystem.getModelOffset(),
                            RenderSystem.getTextureMatrix(),
                            RenderSystem.getShaderLineWidth()
                        )
                        item to dynamicUniforms
                    }
                    .toList()
                if (drawItems.isEmpty()) continue

                commandEncoder.createRenderPass(
                    { "BlazeRod render stage $stage" },
                    colorFrameBuffer,
                    OptionalInt.empty(),
                    depthFrameBuffer,
                    OptionalDouble.empty()
                ).use { renderPass ->
                    for ((item, dynamicUniforms) in drawItems) {
                        val task = item.renderTask
                        val primitiveComponent = item.primitiveComponent
                        val primitive = primitiveComponent.primitive
                        val material = primitive.material

                        with(renderPass) {
                            setPipeline(EntityMaterialPipelines.pipeline(material))
                            RenderSystem.bindDefaultUniforms(this)
                            setUniform("DynamicTransforms", dynamicUniforms)
                            bindSampler(
                                "Sampler2",
                                Minecraft.getInstance().gameRenderer.lightTexture().textureView
                            )
                            bindSampler(
                                "Sampler1",
                                Minecraft.getInstance().gameRenderer.overlayTexture().texture.textureView
                            )
                            EntityMaterialPipelines.bindBaseColor(this, material)

                            setVertexFormat(item.vertexFormat)
                            setVertexFormatMode(item.computeOutput.mode)
                            setVertexBuffer(0, item.computeOutput.vertexBuffer.buffer())
                            primitive.indexBuffer?.takeIf { item.computeOutput.useIndexBuffer }?.let { indices ->
                                setIndexBuffer(indices)
                                drawIndexed(0, 0, indices.length, 1)
                            } ?: run {
                                draw(0, item.computeOutput.vertices)
                            }
                        }
                    }
                }
            }
        }
        computeItems.forEach { it.release() }
        computeItems.clear()
        renderTasks.forEach { it.release() }
        renderTasks.clear()
        BlazeRodGpuTimer.reportIfReady()
    }

    override fun render(
        colorFrameBuffer: GpuTextureView,
        depthFrameBuffer: GpuTextureView?,
        scene: RenderSceneImpl,
        primitive: RenderPrimitive,
        primitiveIndex: Int,
        task: RenderTaskImpl,
        skinBuffer: RenderSkinBuffer?,
        targetBuffer: MorphTargetBuffer?,
    ) {
        if (!primitive.gpuComplete || (IrisApis.shaderPackInUse && !supportsIrisCompute(primitive))) {
            cpuRendererDelegate.value.render(colorFrameBuffer, depthFrameBuffer, scene, primitive, primitiveIndex, task, skinBuffer, targetBuffer)
            return
        }

        val device = RenderSystem.getDevice()
        val material = primitive.material

        task.localMatricesBuffer.content.getPositionMatrix(primitiveIndex, modelMatrix)
        modelMatrix.mulLocal(task.modelMatrix)
        modelMatrix.normal(modelNormalMatrix)
        modelTangentMatrix.set(modelMatrix)
        modelMatrix.mulLocal(RenderSystem.getModelViewStack())

        val irisVertexFormat = IrisApis.shaderPackInUse
        val targetVertexFormat = if (irisVertexFormat) {
            BlazerodVertexFormats.IRIS_ENTITY_PADDED
        } else {
            BlazerodVertexFormats.ENTITY_PADDED
        }
        val computeOutput = prepareCompute(
            primitive = primitive,
            task = task,
            skinBuffer = skinBuffer,
            targetBuffer = targetBuffer,
            targetVertexFormat = targetVertexFormat,
            irisVertexFormat = irisVertexFormat,
            modelNormalMatrix = modelNormalMatrix,
            modelTangentMatrix = modelTangentMatrix,
        )

        val commandEncoder = device.createCommandEncoder()
        commandEncoder.createComputePass { "BlazeRod transform pass" }.use { pass ->
            dispatchCompute(computeOutput, pass)
        }
        if (irisVertexFormat) {
            commandEncoder.memoryBarrier(CommandEncoderExt.BARRIER_STORAGE_BUFFER_BIT)
            commandEncoder.createComputePass { "BlazeRod Iris attribute pass" }.use { pass ->
                dispatchIrisAttributes(computeOutput, pass)
            }
        }

        commandEncoder.memoryBarrier(CommandEncoderExt.BARRIER_STORAGE_BUFFER_BIT or CommandEncoderExt.BARRIER_VERTEX_BUFFER_BIT)

        val dynamicUniforms = RenderSystem.getDynamicUniforms().writeTransform(
            modelMatrix,
            material.baseColor.toVector4f(baseColor),
            RenderSystem.getModelOffset(),
            RenderSystem.getTextureMatrix(),
            RenderSystem.getShaderLineWidth()
        )

        commandEncoder.createRenderPass(
            { "BlazeRod render pass" },
            colorFrameBuffer,
            OptionalInt.empty(),
            depthFrameBuffer,
            OptionalDouble.empty()
        ).use {
            with(it) {
                setPipeline(EntityMaterialPipelines.pipeline(material))
                RenderSystem.bindDefaultUniforms(this)
                setUniform("DynamicTransforms", dynamicUniforms)
                bindSampler("Sampler2", Minecraft.getInstance().gameRenderer.lightTexture().textureView)
                bindSampler("Sampler1", Minecraft.getInstance().gameRenderer.overlayTexture().texture.textureView)
                EntityMaterialPipelines.bindBaseColor(this, material)

                setVertexFormat(targetVertexFormat)
                setVertexFormatMode(computeOutput.mode)
                setVertexBuffer(0, computeOutput.vertexBuffer.buffer())
                primitive.indexBuffer?.takeIf { computeOutput.useIndexBuffer }?.let { indices ->
                    setIndexBuffer(indices)
                    drawIndexed(0, 0, indices.length, 1)
                } ?: run {
                    draw(0, computeOutput.vertices)
                }
            }
        }
    }

    override fun rotate() {
        fallbackTasks.discardTasks()
        if (cpuRendererDelegate.isInitialized()) {
            cpuRendererDelegate.value.rotate()
        }
        computeItems.forEach { it.release() }
        computeItems.clear()
        renderTasks.forEach { it.release() }
        renderTasks.clear()
        dataPool.rotate()
        vertexDataPool.rotate()
    }

    override fun close() {
        fallbackTasks.close()
        if (cpuRendererDelegate.isInitialized()) {
            cpuRendererDelegate.value.close()
        }
        irisTopologies.clear()
        computeItems.forEach { it.release() }
        computeItems.clear()
        renderTasks.forEach { it.release() }
        renderTasks.clear()
        dataPool.close()
        vertexDataPool.close()
        BlazeRodGpuTimer.close()
    }
}
