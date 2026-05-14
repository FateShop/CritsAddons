package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.critsaddons.utils.BlockAimUtils
import com.github.noamm9.event.impl.KeyboardEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.KeybindSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.dungeons.map.DungeonInfo
import com.github.noamm9.utils.dungeons.map.core.Room
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.items.EtherwarpHelper
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object PathfindSecretRoute : Feature(
    name = "Pathfind Secret Route",
    description = "Etherwarps to a known NSR start block in a completed dungeon room."
) {
    private const val INTERACT_DELAY_MS = 60L
    private const val AIM_DELAY_MS = 45L
    private const val LAND_TIMEOUT_MS = 2_500L
    private const val ETHERWARP_EYE_HEIGHT = 1.62
    private const val MODERN_SNEAK_OFFSET = 0.35
    private const val LEGACY_SNEAK_OFFSET = 0.08

    private val modernEtherwarpWorlds = setOf(
        WorldType.Galatea,
        WorldType.GoldMine,
        WorldType.Hub,
        WorldType.End,
        WorldType.Park,
        WorldType.SpiderDen,
        WorldType.TheBarn
    )

    private val keybindSection = "keybinds"
    private val routingSection = "routing"

    private val pathfindKeybind by KeybindSetting("Pathfind Keybind")
        .section(keybindSection)
        .withDescription("Press to try etherwarping to the nearest completed-room NSR start block.")

    private val rotationTimeMs by SliderSetting("Rotation Time (ms)", 170, 20, 500, 5)
        .section(routingSection)
        .withDescription("How long aiming rotations take before the Etherwarp.")

    private val maxAttempts by SliderSetting("Max Start Attempts", 4, 1, 12, 1)
        .section(routingSection)
        .withDescription("How many nearby completed-room start blocks to try before giving up.")

    private val currentRoomFirst by ToggleSetting("Prefer Current Room", true)
        .section(routingSection)
        .withDescription("Try starts in your current room before starts in other completed rooms.")

    private val clickOnLineupMiss by ToggleSetting("Click On Lineup Miss", true)
        .section(routingSection)
        .withDescription("Still right-clicks when the pre-click Etherwarp prediction cannot confirm the target.")

    private val debugMode by ToggleSetting("Debug", false)
        .section(routingSection)
        .withDescription("Prints target selection details.")

    private data class StartCandidate(
        val roomName: String,
        val block: BlockPos,
        val currentRoom: Boolean
    )

    private data class EtherwarpAim(
        val rotation: MathUtils.Rotation,
        val sample: Vec3
    )

    private var pathfindJob: Job? = null

    override fun init() {
        register<KeyboardEvent.KeyPressed> {
            if (!enabled) return@register
            if (event.action != GLFW.GLFW_PRESS) return@register
            if (!pathfindKeybind.isPressed()) return@register
            if (mc.screen != null) return@register
            if (!LocationUtils.inDungeon || LocationUtils.inBoss) return@register

            event.isCanceled = true
            startPathfind()
        }

        register<WorldChangeEvent> {
            stopPathfind()
        }
    }

    private fun startPathfind() {
        if (pathfindJob?.isActive == true) {
            stopPathfind()
            ChatUtils.modMessage("&ePathfind Secret Route stopped.")
            return
        }

        val candidates = collectStartCandidates()
        if (candidates.isEmpty()) {
            ChatUtils.modMessage("&cNo completed-room NSR start blocks are available yet.")
            return
        }

        val originalSlot = mc.player?.inventory?.selectedSlot ?: 0
        ChatUtils.modMessage("&aPathfinding to completed-room NSR start. Candidates: &e${candidates.size}&a.")
        pathfindJob = scope.launch {
            try {
                executePathfind(candidates)
            } finally {
                PlayerUtils.toggleSneak(false)
                PlayerUtils.swapToSlot(originalSlot)
                pathfindJob = null
            }
        }
    }

    private suspend fun executePathfind(candidates: List<StartCandidate>) {
        val attempts = candidates.take(maxAttempts.value)
        for (candidate in attempts) {
            val currentBlock = mc.player?.blockPosition()?.below()
            if (currentBlock == candidate.block) {
                ChatUtils.modMessage("&aAlready on an NSR start block for &e${candidate.roomName}&a.")
                return
            }

            debug { "Trying ${candidate.roomName} start=${candidate.block.toShortString()} currentRoom=${candidate.currentRoom}" }
            if (tryEtherwarpTo(candidate.block)) {
                ChatUtils.modMessage("&aEtherwarped to NSR start for &e${candidate.roomName}&a.")
                return
            }
        }

        ChatUtils.modMessage("&cCould not etherwarp to any completed-room NSR start block.")
    }

    suspend fun tryEtherwarpTo(target: BlockPos): Boolean {
        val player = mc.player ?: return false
        val slot = PlayerUtils.findHotbarSlot { EtherwarpHelper.getEtherwarpDistance(it) != null }
            ?: run {
                ChatUtils.modMessage("&cNo Etherwarp item found on your hotbar.")
                return false
            }

        val stack = player.inventory.getItem(slot)
        val distance = EtherwarpHelper.getEtherwarpDistance(stack) ?: return false
        val targetVec = BlockAimUtils.blockCenter(target)
        if (player.position().distanceTo(targetVec) > distance + 1.5) {
            debug { "Skipping ${target.toShortString()}: outside Etherwarp range distance=${player.position().distanceTo(targetVec)} max=$distance." }
            return false
        }

        PlayerUtils.swapToSlot(slot)
        PlayerUtils.toggleSneak(true)
        delay(INTERACT_DELAY_MS)

        if (!alignTarget(target, distance)) {
            debug { "Could not confirm line-up for ${target.toShortString()}." }
            PlayerUtils.toggleSneak(false)
            if (!clickOnLineupMiss.value) return false
            ChatUtils.modMessage("&eCould not confirm Etherwarp line-up for &b${target.toShortString()}&e; trying click anyway.")
            PlayerUtils.toggleSneak(true)
            delay(INTERACT_DELAY_MS)
        }

        delay(INTERACT_DELAY_MS)
        PlayerUtils.rightClick()
        delay(INTERACT_DELAY_MS)
        PlayerUtils.toggleSneak(false)

        return waitForStandingOn(target)
    }

    private suspend fun alignTarget(target: BlockPos, distance: Double): Boolean {
        val aim = solveEtherwarpAim(target, distance) ?: return false
        PlayerUtils.rotateSmoothly(aim.rotation, rotationTimeMs.value.toLong())
        delay(AIM_DELAY_MS)

        val player = mc.player ?: return false
        val etherPos = EtherwarpHelper.getEtherPos(player.position(), player.lookAngle, distance)
        if (etherPos.succeeded && etherPos.pos == target) {
            debug {
                "Confirmed Etherwarp target=${target.toShortString()} rotation=${aim.rotation} sample=${formatVec(aim.sample)}."
            }
            return true
        }

        debug {
            "Solved rotation missed after rotate target=${target.toShortString()} predicted=${etherPos.pos?.toShortString()} valid=${etherPos.succeeded}."
        }
        return false
    }

    private fun solveEtherwarpAim(target: BlockPos, distance: Double): EtherwarpAim? {
        val player = mc.player ?: return null
        return targetAimPoints(target)
            .asSequence()
            .map { sample -> EtherwarpAim(rotationTo(player, sample), sample) }
            .firstOrNull { aim ->
                val predicted = EtherwarpHelper.getEtherPos(player.position(), lookVec(aim.rotation), distance)
                predicted.succeeded && predicted.pos == target
            }
    }

    private fun targetAimPoints(target: BlockPos): List<Vec3> {
        val offsets = listOf(0.5, 0.35, 0.65, 0.2, 0.8, 0.08, 0.92)
        val heights = listOf(0.98, 0.9, 0.75, 0.5, 0.25, 0.1)
        val points = ArrayList<Vec3>(offsets.size * offsets.size * heights.size)
        heights.forEach { y ->
            offsets.forEach { x ->
                offsets.forEach { z ->
                    points.add(Vec3(target.x + x, target.y + y, target.z + z))
                }
            }
        }
        return points
    }

    private fun rotationTo(player: LocalPlayer, target: Vec3): MathUtils.Rotation {
        val eye = etherwarpCameraPos(player)
        val delta = target.subtract(eye)
        val yaw = -Math.toDegrees(atan2(delta.x, delta.z)).toFloat()
        val pitch = -Math.toDegrees(atan2(delta.y, sqrt(delta.x * delta.x + delta.z * delta.z))).toFloat()
        return MathUtils.Rotation(MathUtils.normalizeYaw(yaw), MathUtils.normalizePitch(pitch))
    }

    private fun etherwarpCameraPos(player: LocalPlayer): Vec3 {
        val sneakOffset = if (player.isCrouching) {
            if (LocationUtils.world in modernEtherwarpWorlds) MODERN_SNEAK_OFFSET else LEGACY_SNEAK_OFFSET
        } else {
            0.0
        }
        return player.position().add(0.0, ETHERWARP_EYE_HEIGHT - sneakOffset, 0.0)
    }

    private fun lookVec(rotation: MathUtils.Rotation): Vec3 {
        val yaw = Math.toRadians(rotation.yaw.toDouble())
        val pitch = Math.toRadians(rotation.pitch.toDouble())
        val pitchCos = cos(pitch)
        return Vec3(
            -sin(yaw) * pitchCos,
            -sin(pitch),
            cos(yaw) * pitchCos
        )
    }

    private fun formatVec(vec: Vec3): String {
        return String.format(java.util.Locale.US, "%.2f,%.2f,%.2f", vec.x, vec.y, vec.z)
    }

    private suspend fun waitForStandingOn(block: BlockPos): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < LAND_TIMEOUT_MS) {
            if (mc.player?.blockPosition()?.below() == block) return true
            delay(50)
        }
        return false
    }

    private fun collectStartCandidates(): List<StartCandidate> {
        val player = mc.player ?: return emptyList()
        val currentRoom = player.position().let(ScanUtils::getRoomFromPos) ?: ScanUtils.currentRoom
        val seen = linkedSetOf<Pair<String, BlockPos>>()

        return DungeonInfo.dungeonList
            .filterIsInstance<Room>()
            .mapNotNull { it.uniqueRoom }
            .distinctBy { it.name }
            .filter { SecretRoutes.isRoomMarkedCompleted(it.name) }
            .flatMap { room ->
                val navigation = SecretRoutes.getRoomNavigationSnapshot(room) ?: return@flatMap emptyList()
                navigation.startBlocksWorld.map { start ->
                    StartCandidate(
                        roomName = room.name,
                        block = start,
                        currentRoom = room.name == currentRoom?.name
                    )
                }
            }
            .filter { seen.add(it.roomName to it.block) }
            .sortedWith(
                compareBy<StartCandidate> {
                    if (currentRoomFirst.value && it.currentRoom) 0 else 1
                }.thenBy {
                    distanceScore(player.blockPosition().below(), it.block)
                }
            )
    }

    private fun distanceScore(from: BlockPos, to: BlockPos): Int {
        return abs(from.x - to.x) + abs(from.y - to.y) * 2 + abs(from.z - to.z)
    }

    private fun stopPathfind() {
        pathfindJob?.cancel()
        PlayerUtils.toggleSneak(false)
        pathfindJob = null
    }

    private inline fun debug(message: () -> String) {
        if (debugMode.value) ChatUtils.modMessage("&7[PSRDBG] ${message()}")
    }
}
