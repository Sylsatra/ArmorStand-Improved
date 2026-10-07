package top.fifthlight.blazerod.runtime.test

import kotlin.test.Test
import kotlin.test.assertEquals
import top.fifthlight.blazerod.model.Material.AlphaMode
import top.fifthlight.blazerod.runtime.load.TextureAlphaMode

class TextureAlphaModeTest {
    @Test
    fun fullyOpaqueTextureUsesOpaquePass() {
        assertEquals(AlphaMode.OPAQUE, TextureAlphaMode.classify(2, 2) { _, _ -> 255 })
    }

    @Test
    fun binaryTransparencyUsesCutoutPass() {
        val alpha = intArrayOf(255, 0, 255, 255)
        assertEquals(AlphaMode.MASK, TextureAlphaMode.classify(2, 2) { x, y -> alpha[y * 2 + x] })
    }

    @Test
    fun intermediateAlphaUsesBlendedPass() {
        for (alpha in 1..254) {
            assertEquals(AlphaMode.BLEND, TextureAlphaMode.classify(2, 1) { x, _ -> if (x == 0) 255 else alpha })
        }
    }
}
