package com.github.noamm9.critsaddons.ui.customgui

interface HasCustomGui {
    fun getCustomGuiBridge(): CustomGui?
    fun setCustomGuiBridge(gui: CustomGui?)
}

interface CoordRememberingSlot {
    fun rememberCoordsBridge()
    fun restoreCoordsBridge()
    fun getOriginalXBridge(): Int
    fun getOriginalYBridge(): Int
    fun setXBridge(x: Int)
    fun setYBridge(y: Int)
}
