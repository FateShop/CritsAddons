package com.github.noamm9.critsaddons.ui.customgui

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot

var AbstractContainerScreen<*>.customGui: CustomGui?
    get() = (this as HasCustomGui).getCustomGuiBridge()
    set(value) = (this as HasCustomGui).setCustomGuiBridge(value)

val Slot.originalX: Int get() = (this as CoordRememberingSlot).getOriginalXBridge()
val Slot.originalY: Int get() = (this as CoordRememberingSlot).getOriginalYBridge()

fun Slot.rememberCoords() = (this as CoordRememberingSlot).rememberCoordsBridge()
fun Slot.restoreCoords() = (this as CoordRememberingSlot).restoreCoordsBridge()

fun Slot.setSlotX(x: Int) = (this as CoordRememberingSlot).setXBridge(x)
fun Slot.setSlotY(y: Int) = (this as CoordRememberingSlot).setYBridge(y)
