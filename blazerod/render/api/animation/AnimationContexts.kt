package top.fifthlight.blazerod.api.animation

import top.fifthlight.blazerod.model.animation.AnimationContext
import top.fifthlight.mergetools.api.ExpectFactory
import top.fifthlight.mergetools.api.ExpectType

interface AnimationContexts {
    fun base(): AnimationContext
    fun entity(@ExpectType("Entity") entity: Any): AnimationContext
    fun livingEntity(@ExpectType("LivingEntity") entity: Any): AnimationContext
    fun player(@ExpectType("Player") player: Any): AnimationContext

    @ExpectFactory
    interface Factory {
        fun create(): AnimationContexts
    }
}