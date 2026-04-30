package com.github.noamm9.critsaddons.mixins;

import com.github.noamm9.critsaddons.event.impl.ScreenChangeEvent;
import com.github.noamm9.event.EventBus;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftSetScreenStorageOverlay {
    @Shadow @Nullable public Screen screen;

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void critsaddons$onSetScreen(Screen screen, CallbackInfo ci, @Local(argsOnly = true) LocalRef<Screen> screenRef) {
        ScreenChangeEvent event = new ScreenChangeEvent(this.screen, screen);
        EventBus.post(event);
        if (event.getOverrideScreen() != null) {
            screenRef.set(event.getOverrideScreen());
        }
    }
}
