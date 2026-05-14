package com.github.noamm9.critsaddons.mixins;

import com.github.noamm9.critsaddons.features.impl.critsaddons.InstantCrouch;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public class MixinCameraInstantCrouch {
    @Shadow private Entity entity;
    @Shadow private float eyeHeight;
    @Shadow private float eyeHeightOld;

    @Inject(method = "setup", at = @At("HEAD"))
    private void critsaddons$snapEyeHeightBeforeSetup(
        Level level,
        Entity focusedEntity,
        boolean detached,
        boolean thirdPersonReverse,
        float partialTick,
        CallbackInfo ci
    ) {
        critsaddons$snapEyeHeight(focusedEntity);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void critsaddons$snapEyeHeightAfterTick(CallbackInfo ci) {
        critsaddons$snapEyeHeight(this.entity);
    }

    @Unique
    private void critsaddons$snapEyeHeight(Entity focusedEntity) {
        if (!InstantCrouch.shouldSnapCamera(focusedEntity)) return;
        float targetEyeHeight = focusedEntity.getEyeHeight();
        this.eyeHeight = targetEyeHeight;
        this.eyeHeightOld = targetEyeHeight;
    }
}
