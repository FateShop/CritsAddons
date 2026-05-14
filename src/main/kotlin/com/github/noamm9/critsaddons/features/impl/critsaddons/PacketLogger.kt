package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.PacketEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.TextInputSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
import net.minecraft.network.protocol.common.ServerboundPongPacket
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket

object PacketLogger : Feature(
    name = "Packet Logger",
    description = "Logs packets your client sends to the server in chat."
) {
    private val displaySection = "display"
    private val safetySection = "safety"

    private val nameFilter by TextInputSetting("Name Filter", "")
        .section(displaySection)
        .withDescription("Only logs packets whose class name contains this text. Leave empty to log all outgoing packets.")

    private val ignoreCommonPackets by ToggleSetting("Ignore Common Packets", true)
        .section(displaySection)
        .withDescription("Hides noisy tick, keep-alive, pong, and ping packets.")

    private val showDetails by ToggleSetting("Show Details", false)
        .section(displaySection)
        .withDescription("Also prints packet.toString(). This can be noisy for some packets.")

    private val maxDetailLength by SliderSetting("Max Detail Length", 180, 40, 500, 10)
        .section(displaySection)
        .withDescription("Maximum length of the details text when Show Details is enabled.")

    private val maxPacketsPerSecond by SliderSetting("Max Per Second", 60, 0, 200, 5)
        .section(safetySection)
        .withDescription("Maximum packet chat lines per second. Set to 0 for unlimited.")

    private var windowStartedAt = 0L
    private var loggedThisWindow = 0
    private var droppedThisWindow = 0

    override fun init() {
        register<PacketEvent.Sent> {
            if (ignoreCommonPackets.value && event.packet.isCommonNoise()) return@register

            val packetName = event.packet.displayName()
            val filter = nameFilter.value.trim()
            if (filter.isNotEmpty() && !packetName.contains(filter, ignoreCase = true)) return@register

            if (!allowLogLine()) return@register

            ChatUtils.chat(formatMessage(event.packet, packetName))
        }
    }

    override fun onDisable() {
        resetRateLimit()
        super.onDisable()
    }

    private fun allowLogLine(): Boolean {
        val max = maxPacketsPerSecond.value
        if (max <= 0) return true

        val now = System.currentTimeMillis()
        if (windowStartedAt == 0L || now - windowStartedAt >= 1_000L) {
            if (droppedThisWindow > 0) {
                ChatUtils.chat("&8[&bC2S&8] &7Skipped &e$droppedThisWindow &7packet log lines due to the rate limit.")
            }
            windowStartedAt = now
            loggedThisWindow = 0
            droppedThisWindow = 0
        }

        if (loggedThisWindow >= max) {
            droppedThisWindow++
            return false
        }

        loggedThisWindow++
        return true
    }

    private fun resetRateLimit() {
        windowStartedAt = 0L
        loggedThisWindow = 0
        droppedThisWindow = 0
    }

    private fun formatMessage(packet: Packet<*>, packetName: String): String {
        val base = "&8[&bC2S&8] &7$packetName"
        if (!showDetails.value) return base

        val detail = packet.toString()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > maxDetailLength.value) it.take(maxDetailLength.value) + "..." else it }

        return "$base &8- &f$detail"
    }

    private fun Packet<*>.displayName(): String {
        return javaClass.name
            .removePrefix("net.minecraft.network.protocol.")
            .substringAfterLast('.')
            .replace('$', '.')
    }

    private fun Packet<*>.isCommonNoise(): Boolean {
        return this is ServerboundClientTickEndPacket ||
            this is ServerboundKeepAlivePacket ||
            this is ServerboundPongPacket ||
            this is ServerboundPingRequestPacket
    }
}
