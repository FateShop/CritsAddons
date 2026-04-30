package com.github.noamm9.critsaddons.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

@Mixin(AbstractContainerScreen.class)
public interface AccessorAbstractContainerScreenStorageOverlay {
    @Accessor("leftPos")
    int getLeftPosStorageOverlay();

    @Accessor("topPos")
    int getTopPosStorageOverlay();

    @Accessor("leftPos")
    void setLeftPosStorageOverlay(int value);

    @Accessor("topPos")
    void setTopPosStorageOverlay(int value);

    @Accessor("imageWidth")
    int getImageWidthStorageOverlay();

    @Accessor("imageHeight")
    int getImageHeightStorageOverlay();

    @Accessor("imageWidth")
    void setImageWidthStorageOverlay(int value);

    @Accessor("imageHeight")
    void setImageHeightStorageOverlay(int value);
}
