package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.features.Feature
import net.minecraft.world.entity.Entity

object InstantCrouch : Feature(
    name = "Instant Crouch",
    description = "Snaps the first-person camera to crouch height immediately instead of easing the view down."
) {
    @JvmStatic
    fun shouldSnapCamera(entity: Entity?): Boolean {
        return enabled && entity != null && entity == mc.player
    }
}
