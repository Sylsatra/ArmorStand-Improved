package top.fifthlight.blazerod.runtime.load

import top.fifthlight.blazerod.model.Material.AlphaMode

object TextureAlphaMode {
    inline fun classify(width: Int, height: Int, alphaAt: (Int, Int) -> Int): AlphaMode {
        var transparent = false
        for (y in 0 until height) {
            for (x in 0 until width) {
                when (alphaAt(x, y)) {
                    0 -> transparent = true
                    255 -> Unit
                    else -> return AlphaMode.BLEND
                }
            }
        }
        return if (transparent) AlphaMode.MASK else AlphaMode.OPAQUE
    }
}
