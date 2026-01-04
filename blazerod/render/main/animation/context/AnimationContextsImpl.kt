package top.fifthlight.blazerod.animation.context

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import top.fifthlight.blazerod.api.animation.AnimationContexts
import top.fifthlight.mergetools.api.ActualConstructor
import top.fifthlight.mergetools.api.ActualImpl
import top.fifthlight.mergetools.api.ActualType

@ActualImpl(AnimationContexts::class)
object AnimationContextsImpl : AnimationContexts {
    @JvmStatic
    @ActualConstructor("create")
    fun create(): AnimationContexts = this

    override fun base() = BaseAnimationContext()

    override fun entity(@ActualType(Entity::class) entity: Any) =
        EntityAnimationContext(entity as Entity)

    override fun livingEntity(@ActualType(LivingEntity::class) entity: Any) =
        LivingEntityAnimationContext(entity as LivingEntity)

    override fun player(@ActualType(Player::class) player: Any) =
        PlayerEntityAnimationContext(player as Player)
}
