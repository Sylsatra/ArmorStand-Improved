package top.fifthlight.blazerod.runtime.renderer.test

import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import top.fifthlight.blazerod.runtime.data.RenderSkinBuffer
import top.fifthlight.blazerod.runtime.renderer.util.SkinningNormals
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SkinningNormalsTest {
    @Test
    fun normalsFollowJointRotationAndIgnoreTranslation() {
        val skin = RenderSkinBuffer(1)
        skin.setMatrix(0, Matrix4f().translation(40f, 50f, 60f).rotateY((Math.PI / 2).toFloat()))
        val vertices = vertex(intArrayOf(0, 0, 0, 0), floatArrayOf(1f, 0f, 0f, 0f))
        val normal = Vector3f(0f, 0f, 1f)

        transform(normal, vertices, skin)

        assertEquals(1f, normal.x, 1e-6f)
        assertEquals(0f, normal.y, 1e-6f)
        assertEquals(0f, normal.z, 1e-6f)
    }

    @Test
    fun normalsUseInverseTransposeForScaledJoints() {
        val skin = RenderSkinBuffer(1)
        skin.setMatrix(0, Matrix4f().scale(2f, 1f, 4f))
        val vertices = vertex(intArrayOf(0, 0, 0, 0), floatArrayOf(1f, 0f, 0f, 0f))
        val normal = Vector3f(1f, 1f, 1f)

        transform(normal, vertices, skin)

        assertEquals(.5f, normal.x, 1e-6f)
        assertEquals(1f, normal.y, 1e-6f)
        assertEquals(.25f, normal.z, 1e-6f)
    }

    @Test
    fun invalidOrZeroInfluencesPreserveOriginalNormal() {
        val skin = RenderSkinBuffer(1)
        skin.setMatrix(0, Matrix4f().rotateX(1f))
        val vertices = vertex(intArrayOf(65535, 0, 0, 0), floatArrayOf(1f, 0f, Float.NaN, -1f))
        val normal = Vector3f(.2f, .3f, .4f)

        transform(normal, vertices, skin)

        assertEquals(Vector3f(.2f, .3f, .4f), normal)
    }

    private fun transform(normal: Vector3f, vertices: ByteBuffer, skin: RenderSkinBuffer) {
        SkinningNormals.transform(normal, vertices, 0, 8, skin, Vector3f(), Vector3f(), Matrix4f())
    }

    private fun vertex(joints: IntArray, weights: FloatArray): ByteBuffer =
        ByteBuffer.allocate(24).order(ByteOrder.nativeOrder()).apply {
            repeat(4) {
                putShort(it * 2, joints[it].toShort())
                putFloat(8 + it * 4, weights[it])
            }
        }
}
