package ru.governix.mahoyu.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.governix.mahoyu.AdaptationManager;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "modifyAppliedDamage(Lnet/minecraft/entity/damage/DamageSource;F)F",
            at = @At("RETURN"), cancellable = true)
    private void mahoyu$applyAdaptation(DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof PlayerEntity player) {
            cir.setReturnValue(AdaptationManager.reduceDamage(player, source, cir.getReturnValue()));
        }
    }

    @Inject(method = "damage(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/damage/DamageSource;F)Z",
            at = @At("RETURN"))
    private void mahoyu$recordAdaptation(ServerWorld world, DamageSource source, float amount,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && (Object) this instanceof ServerPlayerEntity player) {
            AdaptationManager.recordDamage(player, source, world);
        }
    }
}

