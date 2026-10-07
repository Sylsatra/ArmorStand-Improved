package top.fifthlight.blazerod.runtime.renderer.util

import org.joml.Matrix4f
import org.joml.Vector3f
import top.fifthlight.blazerod.runtime.data.RenderSkinBuffer
import java.nio.ByteBuffer

object SkinningNormals {
    fun transform(
        normal: Vector3f,
        vertices: ByteBuffer,
        jointOffset: Int,
        weightOffset: Int,
        skin: RenderSkinBuffer,
        result: Vector3f,
        jointNormal: Vector3f,
        matrix: Matrix4f,
    ) {
        result.zero()
        var totalWeight = 0f
        repeat(4) { influence ->
            val weight = vertices.getFloat(weightOffset + influence * 4)
            val joint = vertices.getShort(jointOffset + influence * 2).toInt() and 0xFFFF
            if (weight.isFinite() && weight > 1E-6f && joint < skin.jointSize) {
                skin.getNormalMatrix(joint, matrix)
                matrix.transformDirection(normal, jointNormal)
                result.fma(weight, jointNormal)
                totalWeight += weight
            }
        }
        if (totalWeight > 1E-6f) {
            normal.set(result)
        }
    }
}
