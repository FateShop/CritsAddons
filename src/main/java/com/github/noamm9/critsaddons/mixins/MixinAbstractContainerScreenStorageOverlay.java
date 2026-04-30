package com.github.noamm9.critsaddons.mixins;

import com.github.noamm9.critsaddons.ui.customgui.CoordRememberingSlot;
import com.github.noamm9.critsaddons.ui.customgui.CustomGui;
import com.github.noamm9.critsaddons.ui.customgui.HasCustomGui;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractContainerScreen.class, priority = 2000)
public abstract class MixinAbstractContainerScreenStorageOverlay<T extends AbstractContainerMenu> extends Screen implements HasCustomGui {
    @Shadow @Final protected T menu;
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageHeight;
    @Shadow protected int imageWidth;

    @Unique private CustomGui critsaddons$customGui;
    @Unique private boolean critsaddons$rememberedSlots = false;
    @Unique private int critsaddons$originalBackgroundWidth;
    @Unique private int critsaddons$originalBackgroundHeight;

    protected MixinAbstractContainerScreenStorageOverlay(Component title) {
        super(title);
    }

    @Nullable
    @Override
    public CustomGui getCustomGuiBridge() {
        return critsaddons$customGui;
    }

    @Override
    public void setCustomGuiBridge(@Nullable CustomGui gui) {
        if (this.critsaddons$customGui != null) {
            imageHeight = critsaddons$originalBackgroundHeight;
            imageWidth = critsaddons$originalBackgroundWidth;
        }
        if (gui != null) {
            critsaddons$originalBackgroundHeight = imageHeight;
            critsaddons$originalBackgroundWidth = imageWidth;
        }
        this.critsaddons$customGui = gui;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void critsaddons$onInit(CallbackInfo ci) {
        if (critsaddons$customGui != null) {
            critsaddons$customGui.onInit();
        }
    }

    @Inject(method = "renderLabels", at = @At("HEAD"), cancellable = true)
    private void critsaddons$onRenderLabels(GuiGraphics context, int mouseX, int mouseY, CallbackInfo ci) {
        if (critsaddons$customGui != null && !critsaddons$customGui.shouldDrawForeground()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSlot", at = @At("HEAD"), cancellable = true)
    private void critsaddons$beforeSlotRender(GuiGraphics guiGraphics, Slot slot, CallbackInfo ci) {
        if (critsaddons$customGui != null) {
            if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory)) {
                ci.cancel();
                return;
            }
            critsaddons$customGui.beforeSlotRender(guiGraphics, slot);
        }
    }

    @Inject(method = "renderSlot", at = @At("TAIL"))
    private void critsaddons$afterSlotRender(GuiGraphics guiGraphics, Slot slot, CallbackInfo ci) {
        if (critsaddons$customGui != null) {
            critsaddons$customGui.afterSlotRender(guiGraphics, slot);
        }
    }

    @Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
    private void critsaddons$hasClickedOutside(double mouseX, double mouseY, int left, int top, CallbackInfoReturnable<Boolean> cir) {
        if (critsaddons$customGui != null) {
            cir.setReturnValue(critsaddons$customGui.isClickOutsideBounds(mouseX, mouseY));
        }
    }

    @Inject(method = "isHovering(Lnet/minecraft/world/inventory/Slot;DD)Z", at = @At("HEAD"), cancellable = true)
    private void critsaddons$isHovering(Slot slot, double pointX, double pointY, CallbackInfoReturnable<Boolean> cir) {
        if (critsaddons$customGui != null) {
            cir.setReturnValue(critsaddons$customGui.isPointOverSlot(slot, this.leftPos, this.topPos, pointX, pointY));
        }
    }

    @Inject(method = "renderBackground", at = @At("HEAD"))
    private void critsaddons$moveSlotsBeforeRender(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (critsaddons$customGui != null) {
            for (Slot slot : menu.slots) {
                if (!critsaddons$rememberedSlots) {
                    ((CoordRememberingSlot) slot).rememberCoordsBridge();
                }
                critsaddons$customGui.moveSlot(slot);
            }
            critsaddons$rememberedSlots = true;
        } else if (critsaddons$rememberedSlots) {
            for (Slot slot : menu.slots) {
                ((CoordRememberingSlot) slot).restoreCoordsBridge();
            }
            critsaddons$rememberedSlots = false;
        }
    }

    @WrapWithCondition(
        method = "renderBackground",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderBg(Lnet/minecraft/client/gui/GuiGraphics;FII)V")
    )
    private boolean critsaddons$preventDrawingBackground(AbstractContainerScreen<?> instance, GuiGraphics context, float delta, int mouseX, int mouseY) {
        if (critsaddons$customGui != null) {
            critsaddons$customGui.render(context, delta, mouseX, mouseY);
            return false;
        }
        return true;
    }

    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void critsaddons$onVoluntaryExit(CallbackInfo ci) {
        if (critsaddons$customGui != null && !critsaddons$customGui.onVoluntaryExit()) {
            ci.cancel();
        }
    }

    @WrapOperation(
        method = "mouseClicked",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z")
    )
    private boolean critsaddons$overrideMouseClicks(AbstractContainerScreen<?> instance, MouseButtonEvent click, boolean doubled, Operation<Boolean> original) {
        if (critsaddons$customGui != null && critsaddons$customGui.mouseClick(click, doubled)) {
            return true;
        }
        return original.call(instance, click, doubled);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void critsaddons$overrideMouseDrags(MouseButtonEvent click, double offsetX, double offsetY, CallbackInfoReturnable<Boolean> cir) {
        if (critsaddons$customGui != null && critsaddons$customGui.mouseDragged(click, offsetX, offsetY)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void critsaddons$overrideKeyPressed(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        if (critsaddons$customGui != null && critsaddons$customGui.keyPressed(input)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void critsaddons$overrideMouseReleases(MouseButtonEvent click, CallbackInfoReturnable<Boolean> cir) {
        if (critsaddons$customGui != null && critsaddons$customGui.mouseReleased(click)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void critsaddons$overrideMouseScroll(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
        if (critsaddons$customGui != null && critsaddons$customGui.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            cir.setReturnValue(true);
        }
    }
}
