package com.github.noamm9.critsaddons.mixins;

import com.github.noamm9.critsaddons.ui.customgui.CoordRememberingSlot;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Slot.class)
public class MixinSlotStorageOverlay implements CoordRememberingSlot {
    @Shadow public int x;
    @Shadow public int y;

    @Unique private int critsaddons$originalX;
    @Unique private int critsaddons$originalY;

    @Override
    public void rememberCoordsBridge() {
        this.critsaddons$originalX = this.x;
        this.critsaddons$originalY = this.y;
    }

    @Override
    public void restoreCoordsBridge() {
        this.x = this.critsaddons$originalX;
        this.y = this.critsaddons$originalY;
    }

    @Override
    public int getOriginalXBridge() {
        return this.critsaddons$originalX;
    }

    @Override
    public int getOriginalYBridge() {
        return this.critsaddons$originalY;
    }

    @Override
    public void setXBridge(int x) {
        this.x = x;
    }

    @Override
    public void setYBridge(int y) {
        this.y = y;
    }
}
