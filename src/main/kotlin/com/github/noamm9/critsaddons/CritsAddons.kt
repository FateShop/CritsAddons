package com.github.noamm9.critsaddons

import com.github.noamm9.NoammAddons
import net.fabricmc.api.ClientModInitializer

object CritsAddons: ClientModInitializer {
    override fun onInitializeClient() {
        NoammAddons.logger.info("Hi from ${this.javaClass.simpleName}!")
    }
}
