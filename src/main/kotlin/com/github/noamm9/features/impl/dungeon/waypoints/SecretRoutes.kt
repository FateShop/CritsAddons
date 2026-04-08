package com.github.noamm9.critsaddons.features.impl.critsaddons.waypoints

import com.github.noamm9.NoammAddons
import com.github.noamm9.NoammAddons.MOD_NAME
import com.github.noamm9.event.EventPriority
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.KeyboardEvent
import com.github.noamm9.event.impl.PacketEvent
import com.github.noamm9.event.impl.PlayerInteractEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ColorSetting
import com.github.noamm9.ui.clickgui.components.impl.KeybindSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.showIf
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ActionBarParser
import com.github.noamm9.utils.BlockAimUtils
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.JsonUtils
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.Utils.equalsOneOf
import com.github.noamm9.utils.dungeons.DungeonUtils
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.items.EtherwarpHelper
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render3D
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.io.File
import java.io.FileReader
import java.io.FileWriter

object SecretRoutes : Feature(
    description = "Record and replay one secret route per dungeon room.",
    name = "Secret Routes",
    toggled = true
) {
    private const val ROTATION_TIME_MS = 170L
    private const val INTERACT_DELAY_MS = 75L
    private const val WARP_SETTLE_TIMEOUT_MS = 3_000L
    private const val WAIT_TIMEOUT_MS = 5_000L
    private const val MANA_CHECK_INTERVAL_MS = 100L
    private const val BLOCK_MESSAGE_COOLDOWN_MS = 1_000L
    private const val BREAK_RECORD_COOLDOWN_MS = 300L
    private const val BREAK_STEP_DELAY_MS = 100L
    private const val TNT_RECORD_COOLDOWN_MS = 300L

    private val playbackKeybind by KeybindSetting("Playback Keybind").section("keybinds")
    private val renderThroughWalls by ToggleSetting("Render Through Walls", true).section("render")
    private val partialRoutes by ToggleSetting("Partial Routes", false).withDescription("Shows only the route up to the next cached secret for the room.").section("render")
    private val renderStart by ToggleSetting("Show Start Block", true)
    private val startBlockColor by ColorSetting("Start Block Color", Color(80, 220, 255, 120), true).showIf { renderStart.value }
    private val renderEtherwarpLines by ToggleSetting("Show Etherwarp Lines", true).section("rendered steps")
    private val etherwarpLineColor by ColorSetting("Etherwarp Line Color", Color(85, 255, 255, 180), true).showIf { renderEtherwarpLines.value }
    private val etherwarpLineWidth by SliderSetting("Etherwarp Line Width", 2.5f, 1f, 8f, 0.1f).showIf { renderEtherwarpLines.value }
    private val renderEtherwarpTargets by ToggleSetting("Show Etherwarp Targets", true)
    private val etherwarpTargetColor by ColorSetting("Etherwarp Target Color", Color(85, 255, 255, 120), true).showIf { renderEtherwarpTargets.value }
    private val renderTntTargets by ToggleSetting("Show TNT Targets", true)
    private val tntTargetColor by ColorSetting("TNT Target Color", Color(255, 140, 0, 120), true).showIf { renderTntTargets.value }
    private val renderHyperionTargets by ToggleSetting("Show Hyperion Targets", true)
    private val hyperionTargetColor by ColorSetting("Hyperion Target Color", Color(255, 0, 255, 120), true).showIf { renderHyperionTargets.value }
    private val renderBreakTargets by ToggleSetting("Show Break Targets", true)
    private val breakTargetColor by ColorSetting("Break Target Color", Color(255, 64, 64, 120), true).showIf { renderBreakTargets.value }
    private val renderSecretTargets by ToggleSetting("Show Secret Targets", true)
    private val secretTargetColor by ColorSetting("Secret Target Color", Color(0, 255, 120, 120), true).showIf { renderSecretTargets.value }
    private val autoHyperionOnLowEhp by ToggleSetting("Auto Hyperion on Low EHP", true).section("safety")
    private val lowEhpMissingThreshold by SliderSetting("Hyperion Heal Missing EHP %", 65, 1, 100, 1).showIf { autoHyperionOnLowEhp.value }
    private val etherwarpManaCost by SliderSetting("Etherwarp Mana Required", 100, 0, 500, 5).section("costs")
    private val hyperionManaCost by SliderSetting("Hyperion Mana Required", 300, 0, 1000, 5)
    private val configFile = File("config/$MOD_NAME/secretRoutes.json")

    private enum class RouteStepType {
        ETHERWARP,
        PLACE_TNT,
        BREAK_BLOCK,
        USE_HYPERION,
        RIGHT_CLICK_SECRET,
        WAIT_FOR_SECRET_PROGRESS,
        WAIT_FOR_BAT_SPAWN
    }

    private data class RouteStep(
        val type: RouteStepType,
        val pos: BlockPos? = null,
        val direction: Direction? = null,
        val secondaryPos: BlockPos? = null,
        val yaw: Float? = null,
        val pitch: Float? = null
    )

    private data class RoomRoute(
        val startBlock: BlockPos,
        val steps: MutableList<RouteStep> = mutableListOf()
    )

    private data class RecordingSession(
        val roomName: String,
        val startBlock: BlockPos,
        val steps: MutableList<RouteStep> = mutableListOf()
    )

    private data class RoomContext(
        val room: UniqueRoom,
        val rotation: Int,
        val corner: BlockPos
    )

    private val routes = mutableMapOf<String, RoomRoute>()
    private val completedSecretsByRoom = mutableMapOf<String, Int>()
    private val lastSecretProgressAtByRoom = mutableMapOf<String, Long>()

    private var recording: RecordingSession? = null
    private var playbackJob: Job? = null
    private var activePlaybackRoomName: String? = null
    private var lastBlockedMessageAt = 0L
    private var lastBreakRecord: Pair<BlockPos, Long>? = null
    private var lastTntRecord: Triple<BlockPos, Direction, Long>? = null

    override fun init() {
        loadConfig()

        register<TickEvent.Start> {
            if (playbackJob?.isActive != true) return@register
            if (mc.screen != null) releaseMovement()
        }

        register<KeyboardEvent.KeyPressed> {
            if (event.action != GLFW.GLFW_PRESS) return@register
            if (mc.screen != null) return@register
            if (!LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            if (!playbackKeybind.isPressed()) return@register

            event.isCanceled = true
            if (playbackJob?.isActive == true) {
                stopPlayback("&eSecret Route playback stopped.")
                return@register
            }
            beginPlayback()
        }

        register<RenderWorldEvent> {
            if (!LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            val ctx = currentRoomContext() ?: return@register
            val route = routes[ctx.room.name] ?: return@register
            renderRoute(event, ctx, route)
        }

        register<DungeonEvent.SecretEvent> {
            val roomName = ScanUtils.currentRoom?.name ?: return@register
            if (!routes.containsKey(roomName)) return@register
            markRoomSecretProgress(roomName)
        }

        register<PlayerInteractEvent.RIGHT_CLICK.AIR>(EventPriority.HIGHEST) {
            if (LocationUtils.inDungeon && !LocationUtils.inBoss) {
                PersistentSecretHeads.findGhostHeadTargetForRoute()?.let { markCurrentRoomSecretClick(it) }
            }

            if (!isRecordingCurrentRoom()) return@register
            if (recordGhostHeadClick()) return@register
            if (isTntItemId(event.item?.skyblockId) && recordTntStepFromHitResult()) return@register
            if (recordEtherwarpStep()) return@register

            event.isCanceled = true
            blockMessage("Only etherwarp is allowed while looking at air during route recording.")
        }

        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK>(EventPriority.HIGHEST) {
            if (LocationUtils.inDungeon && !LocationUtils.inBoss) {
                markCurrentRoomSecretClick(event.pos)
            }

            if (!isRecordingCurrentRoom()) return@register
            val itemId = event.item?.skyblockId

            when {
                recordGhostHeadClick() -> return@register
                isRouteRightClickTarget(event.pos) -> return@register
                isTntItemId(itemId) -> {
                    recordTntStepFromHitResult()
                    return@register
                }

                itemId == "HYPERION" -> {
                    appendStep(RouteStep(RouteStepType.USE_HYPERION, currentRelativePos(event.pos)))
                    return@register
                }

                recordEtherwarpStep() -> return@register
                else -> {
                    event.isCanceled = true
                    blockMessage("Only etherwarp, TNT, Hyperion, and secret clicks are allowed while recording.")
                }
            }
        }

        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY>(EventPriority.HIGHEST) {
            if (!isRecordingCurrentRoom()) return@register
            event.isCanceled = true
            blockMessage("Entity interactions are blocked while route recording is active.")
        }

        register<PlayerInteractEvent.LEFT_CLICK.AIR>(EventPriority.HIGHEST) {
            if (!isRecordingCurrentRoom()) return@register
            event.isCanceled = true
            blockMessage("Only Dungeoneering Pickaxe breaks are allowed while recording.")
        }

        register<PlayerInteractEvent.LEFT_CLICK.ENTITY>(EventPriority.HIGHEST) {
            if (!isRecordingCurrentRoom()) return@register
            event.isCanceled = true
            blockMessage("Entity attacks are blocked while route recording is active.")
        }

        register<PlayerInteractEvent.LEFT_CLICK.BLOCK>(EventPriority.HIGHEST) {
            if (!isRecordingCurrentRoom()) return@register
            if (event.item?.skyblockId != "DUNGEONBREAKER") {
                event.isCanceled = true
                blockMessage("Only the Dungeoneering Pickaxe can record break steps.")
                return@register
            }

            if (shouldRecordBreak(event.pos)) {
                appendStep(RouteStep(RouteStepType.BREAK_BLOCK, currentRelativePos(event.pos)))
            }
        }

        register<PacketEvent.Sent>(EventPriority.HIGHEST) {
            if (!isRecordingCurrentRoom()) return@register
            val packet = event.packet as? ServerboundUseItemOnPacket ?: return@register
            val held = mc.player?.mainHandItem ?: return@register
            val clicked = packet.hitResult.blockPos
            val direction = packet.hitResult.direction

            when {
                isTntItemId(held.skyblockId) -> recordTntStep(clicked, direction)
                isRouteRightClickTarget(clicked) -> appendStep(
                    RouteStep(
                        type = RouteStepType.RIGHT_CLICK_SECRET,
                        pos = currentRelativePos(clicked),
                        direction = direction
                    )
                )
            }
        }

        register<DungeonEvent.RoomEvent.onExit> {
            val session = recording ?: return@register
            if (session.roomName != event.room.name) return@register
            cancelRecording("Left ${event.room.name}; route recording canceled.")
        }

        register<DungeonEvent.RoomEvent.onExit> {
            if (activePlaybackRoomName != event.room.name) return@register
            stopPlayback("&eLeft ${event.room.name}; Secret Route playback canceled.")
        }

        register<DungeonEvent.BossEnterEvent> {
            clearSecretProgressCache()
        }

        register<WorldChangeEvent> {
            recording = null
            lastBreakRecord = null
            lastTntRecord = null
            clearSecretProgressCache()
            releaseMovement()
            stopPlayback()
        }
    }

    fun startRecording() {
        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to start /nsr.")
        stopPlayback()

        val startBlock = mc.player?.blockPosition()?.below() ?: return ChatUtils.modMessage("&cCould not resolve your starting block.")
        recording = RecordingSession(ctx.room.name, toRelative(startBlock, ctx))
        lastBreakRecord = null
        lastTntRecord = null
        ChatUtils.modMessage("&aStarted recording Secret Route for &e${ctx.room.name}&a.")
    }

    fun saveRecording() {
        val session = recording ?: return ChatUtils.modMessage("&cNo Secret Route recording is active.")
        if (session.steps.isEmpty()) return ChatUtils.modMessage("&cNo steps recorded for ${session.roomName}.")

        routes[session.roomName] = RoomRoute(session.startBlock, session.steps.toMutableList())
        saveConfig()
        recording = null
        lastBreakRecord = null
        lastTntRecord = null
        ChatUtils.modMessage("&aSaved Secret Route for &e${session.roomName}&a with &e${routes[session.roomName]?.steps?.size}&a steps.")
    }

    fun cancelRecording(message: String? = null) {
        if (recording == null) return
        recording = null
        lastBreakRecord = null
        lastTntRecord = null
        ChatUtils.modMessage("&e${message ?: "Secret Route recording canceled."}")
    }

    fun insertWaitStep() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first before adding a wait step.")
        appendStep(RouteStep(RouteStepType.WAIT_FOR_SECRET_PROGRESS))
    }

    fun insertBatWaitStep() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first before adding a bat wait step.")
        appendStep(RouteStep(RouteStepType.WAIT_FOR_BAT_SPAWN))
    }

    fun deleteCurrentRoomRoute() {
        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to delete its route.")
        val removed = routes.remove(ctx.room.name)
            ?: return ChatUtils.modMessage("&cNo Secret Route saved for &e${ctx.room.name}&c.")

        saveConfig()
        ChatUtils.modMessage("&aDeleted Secret Route for &e${ctx.room.name}&a with &e${removed.steps.size}&a steps.")
    }

    private fun loadConfig() {
        if (!configFile.exists()) return

        runCatching {
            FileReader(configFile).use { reader ->
                val type = object : TypeToken<MutableMap<String, RoomRoute>>() {}.type
                val loaded = JsonUtils.gsonBuilder.fromJson<MutableMap<String, RoomRoute>>(reader, type) ?: return@use
                routes.clear()
                routes.putAll(loaded)
                NoammAddons.logger.info("${this.javaClass.simpleName} Config loaded: ${routes.size} rooms.")
            }
        }.onFailure {
            NoammAddons.logger.error("${this.javaClass.simpleName} Failed to load config!", it)
        }
    }

    private fun saveConfig() {
        runCatching {
            configFile.parentFile?.mkdirs()
            FileWriter(configFile).use { writer ->
                JsonUtils.gsonBuilder.toJson(routes, writer)
            }
            NoammAddons.logger.info("${this.javaClass.simpleName} Config saved.")
        }.onFailure {
            NoammAddons.logger.error("${this.javaClass.simpleName} Failed to save config!", it)
        }
    }

    private fun beginPlayback() {
        if (recording != null) return ChatUtils.modMessage("&cSave or cancel the current /nsr recording before playback.")
        if (playbackJob?.isActive == true) return ChatUtils.modMessage("&eSecret Route playback is already running.")

        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to play a route.")
        val route = routes[ctx.room.name] ?: return ChatUtils.modMessage("&cNo Secret Route saved for &e${ctx.room.name}&c.")
        val startBlock = toWorld(route.startBlock, ctx)

        if (!isStandingOn(startBlock)) {
            return ChatUtils.modMessage("&eStand on the Secret Route start block at &b${startBlock.toShortString()}&e to begin playback.")
        }

        activePlaybackRoomName = ctx.room.name
        playbackJob = scope.launch {
            runCatching { playRoute(ctx, route) }
                .onFailure {
                    if (it !is CancellationException) {
                        ChatUtils.modMessage("&cSecret Route playback failed: ${it.message ?: it::class.simpleName}")
                    }
                }
            playbackJob = null
            activePlaybackRoomName = null
        }
    }

    private suspend fun playRoute(ctx: RoomContext, route: RoomRoute) {
        for (step in route.steps) {
            if (!ensureSurvival()) return
            waitForMana(step)

            when (step.type) {
                RouteStepType.ETHERWARP -> {
                    val target = step.pos?.let { toWorld(it, ctx) } ?: continue
                    if (!etherwarpTo(target, false, step.rotation(ctx))) return
                }

                RouteStepType.PLACE_TNT -> {
                    val support = step.pos?.let { toWorld(it, ctx) } ?: continue
                    val face = step.direction ?: Direction.UP
                    if (!useTntStep(support, face, step.rotation(ctx))) return
                }

                RouteStepType.BREAK_BLOCK -> {
                    val target = step.pos?.let { toWorld(it, ctx) } ?: continue
                    if (!attackBlock(target, "DUNGEONBREAKER", "Dungeoneering Pickaxe", step.rotation(ctx))) return
                }

                RouteStepType.USE_HYPERION -> {
                    val target = step.pos?.let { toWorld(it, ctx) } ?: continue
                    if (!useTargetedStep(target, null, arrayOf("HYPERION"), "Hyperion", 0.1, step.rotation(ctx))) return
                }

                RouteStepType.RIGHT_CLICK_SECRET -> {
                    val target = step.pos?.let { toWorld(it, ctx) } ?: continue
                    if (!useTargetedStep(target, step.direction, arrayOf("DUNGEONBREAKER"), "Dungeoneering Pickaxe", rotation = step.rotation(ctx))) return
                    markRoomSecretProgress(ctx.room.name)
                }

                RouteStepType.WAIT_FOR_SECRET_PROGRESS -> {
                    if (waitForSecretProgress(ctx.room)) {
                        markRoomSecretProgress(ctx.room.name)
                    }
                }
                RouteStepType.WAIT_FOR_BAT_SPAWN -> waitForBatSpawn()
            }
        }

        ChatUtils.modMessage("&aFinished Secret Route for &e${ctx.room.name}&a.")
        releaseMovement()
    }

    private suspend fun ensureSurvival(): Boolean {
        if (!autoHyperionOnLowEhp.value) return true
        val currentPercent = currentEhpPercent() ?: return true
        val missingPercent = (1.0 - currentPercent).coerceAtLeast(0.0)
        if (missingPercent < lowEhpMissingThreshold.value / 100.0) return true

        val floor = mc.player?.blockPosition()?.below() ?: return true
        if (findItemSlot("HYPERION") == null) {
            ChatUtils.modMessage("&cLow EHP detected, but Hyperion is not on your hotbar.")
            return true
        }

        waitForMana(hyperionManaCost.value, "low EHP Hyperion")
        ChatUtils.modMessage("&eLow EHP detected. Using Hyperion before continuing the route.")
        return useTargetedStep(floor, null, arrayOf("HYPERION"), "Hyperion", 0.1)
    }

    private suspend fun waitForMana(step: RouteStep) {
        val requiredMana = requiredManaFor(step) ?: return
        waitForMana(requiredMana, step.type.name.lowercase().replace('_', ' '))
    }

    private suspend fun waitForMana(requiredMana: Int, label: String) {
        var announcedWait = false

        while (playbackJob?.isActive == true) {
            if (availableMana() >= requiredMana) return

            if (!announcedWait) {
                ChatUtils.modMessage("&eWaiting for mana: need &b$requiredMana&e for $label.")
                announcedWait = true
            }

            delay(MANA_CHECK_INTERVAL_MS)
        }
    }

    private suspend fun etherwarpTo(target: BlockPos, isStartStep: Boolean, rotation: MathUtils.Rotation? = null): Boolean {
        PlayerUtils.findHotbarSlot { EtherwarpHelper.getEtherwarpDistance(it) != null }
            ?.let(PlayerUtils::swapToSlot)
            ?: return abortPlayback("&cNo etherwarp item found in your hotbar.")

        PlayerUtils.toggleSneak(true)
        delay(INTERACT_DELAY_MS)
        if (!aimForStep(target, rotation)) {
            PlayerUtils.toggleSneak(false)
            val prefix = if (isStartStep) "start block" else "etherwarp target"
            return abortPlayback("&cCould not get line of sight to the $prefix at &e${target.toShortString()}&c.")
        }

        delay(50)
        PlayerUtils.rightClick()
        delay(50)
        PlayerUtils.toggleSneak(false)

        val landed = waitForStandingOn(target)
        if (!landed) {
            val prefix = if (isStartStep) "start block" else "etherwarp target"
            return abortPlayback("&cFailed to land on the $prefix at &e${target.toShortString()}&c.")
        }

        if (isStartStep) ChatUtils.modMessage("&aSecret Route start block reached.")
        return true
    }

    private suspend fun useTargetedStep(
        block: BlockPos,
        face: Direction?,
        itemIds: Array<String>?,
        itemName: String,
        yOffset: Double = 0.5,
        rotation: MathUtils.Rotation? = null
    ): Boolean {
        if (itemIds != null) {
            val slot = findItemSlot(*itemIds)
                ?: return abortPlayback("&c$itemName is not on your hotbar.")
            PlayerUtils.swapToSlot(slot)
            delay(INTERACT_DELAY_MS)
        }

        val targetVec = when {
            face != null -> BlockAimUtils.blockFaceCenter(block, face)
            else -> BlockAimUtils.blockCenter(block, yOffset)
        }

        if (!aimForStep(block, rotation, targetVec)) {
            return abortPlayback("&cCould not aim at ${itemName.lowercase()} target &e${block.toShortString()}&c.")
        }

        delay(INTERACT_DELAY_MS)
        PlayerUtils.rightClick()
        delay(150)
        return true
    }

    private suspend fun useTntStep(block: BlockPos, face: Direction, rotation: MathUtils.Rotation?): Boolean {
        val slot = findTntSlot() ?: return abortPlayback("&cNo Superboom/Infinity Boom TNT is on your hotbar.")
        PlayerUtils.swapToSlot(slot)
        delay(INTERACT_DELAY_MS)
        return useTargetedStep(block, face, null, "Superboom TNT", rotation = rotation)
    }

    private suspend fun attackBlock(block: BlockPos, itemId: String, itemName: String, rotation: MathUtils.Rotation? = null): Boolean {
        val slot = findItemSlot(itemId) ?: return abortPlayback("&c$itemName is not on your hotbar.")

        PlayerUtils.swapToSlot(slot)
        delay(INTERACT_DELAY_MS)

        if (!aimForStep(block, rotation)) {
            return abortPlayback("&cCould not aim at block break target &e${block.toShortString()}&c.")
        }

        PlayerUtils.leftClick()
        delay(BREAK_STEP_DELAY_MS)
        return true
    }

    private suspend fun waitForSecretProgress(room: UniqueRoom): Boolean {
        val initial = currentRoomSecretCount(room)
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < WAIT_TIMEOUT_MS) {
            val current = currentRoomSecretCount(room)
            if (current > initial) return true
            delay(50)
        }

        ChatUtils.modMessage("&eSkipped wait step after ${WAIT_TIMEOUT_MS / 1000}s without secret progress.")
        return false
    }

    private suspend fun waitForBatSpawn() {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < WAIT_TIMEOUT_MS) {
            if (PersistentSecretHeads.hasSpawnedBatInCurrentRoom()) return
            delay(50)
        }
        ChatUtils.modMessage("&eSkipped bat wait step after ${WAIT_TIMEOUT_MS / 1000}s without a bat spawning.")
    }

    private suspend fun aimAtTarget(block: BlockPos, vec: Vec3 = BlockAimUtils.blockCenter(block)): Boolean {
        BlockAimUtils.aimAt(vec, ROTATION_TIME_MS)
        delay(INTERACT_DELAY_MS)

        val player = mc.player ?: return false
        val range = maxOf(6.0, player.position().distanceTo(vec) + 2.0)
        return MathUtils.raytrace(player, range) == block
    }

    private suspend fun aimForStep(block: BlockPos, rotation: MathUtils.Rotation?, fallbackVec: Vec3 = BlockAimUtils.blockCenter(block)): Boolean {
        if (rotation == null) return aimAtTarget(block, fallbackVec)

        PlayerUtils.rotateSmoothly(rotation, ROTATION_TIME_MS)
        delay(INTERACT_DELAY_MS)
        return true
    }

    private suspend fun waitForStandingOn(block: BlockPos): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < WARP_SETTLE_TIMEOUT_MS) {
            if (mc.player?.blockPosition()?.below() == block) return true
            delay(50)
        }
        return false
    }

    private fun isStandingOn(block: BlockPos): Boolean {
        return mc.player?.blockPosition()?.below() == block
    }

    private fun stopPlayback(message: String? = null) {
        playbackJob?.cancel()
        playbackJob = null
        activePlaybackRoomName = null
        releaseMovement()
        if (message != null) ChatUtils.modMessage(message)
    }

    private fun findItemSlot(vararg itemIds: String): Int? {
        return PlayerUtils.findHotbarSlot { stack -> stack.skyblockId.equalsOneOf(*itemIds) }
    }

    private fun findTntSlot(): Int? {
        return PlayerUtils.findHotbarSlot { stack -> isTntItemId(stack.skyblockId) }
    }

    private fun currentRoomSecretCount(room: UniqueRoom): Int {
        return ActionBarParser.secrets ?: room.foundSecrets
    }

    private fun availableMana(): Int {
        return ActionBarParser.currentMana + ActionBarParser.overflowMana
    }

    private fun currentEhpPercent(): Double? {
        val maxHealth = ActionBarParser.maxHealth.takeIf { it > 0 } ?: return null
        val defenseMultiplier = 1 + (ActionBarParser.currentDefense / 100.0)
        val maxEhp = maxHealth * defenseMultiplier
        if (maxEhp <= 0.0) return null
        return ActionBarParser.effectiveHP / maxEhp
    }

    private fun requiredManaFor(step: RouteStep): Int? {
        return when (step.type) {
            RouteStepType.ETHERWARP -> etherwarpManaCost.value
            RouteStepType.USE_HYPERION -> hyperionManaCost.value
            else -> null
        }
    }

    private fun isRecordingCurrentRoom(): Boolean {
        val session = recording ?: return false
        val roomName = ScanUtils.currentRoom?.name ?: return false
        return roomName == session.roomName
    }

    private fun recordEtherwarpStep(): Boolean {
        val player = mc.player ?: return false
        val distance = EtherwarpHelper.getEtherwarpDistance(player.mainHandItem) ?: return false
        val etherPos = EtherwarpHelper.getEtherPos(player.position(), player.lookAngle, distance)
        val target = etherPos.pos ?: return false
        if (!etherPos.succeeded) return false

        appendStep(RouteStep(RouteStepType.ETHERWARP, currentRelativePos(target)))
        return true
    }

    private fun recordGhostHeadClick(): Boolean {
        val target = PersistentSecretHeads.findGhostHeadTargetForRoute() ?: return false
        appendStep(RouteStep(RouteStepType.RIGHT_CLICK_SECRET, currentRelativePos(target)))
        return true
    }

    private fun recordTntStepFromHitResult(): Boolean {
        val hit = mc.hitResult as? BlockHitResult ?: return false
        return recordTntStep(hit.blockPos, hit.direction)
    }

    private fun recordTntStep(clicked: BlockPos, direction: Direction): Boolean {
        if (!shouldRecordTnt(clicked, direction)) return false

        appendStep(
            RouteStep(
                type = RouteStepType.PLACE_TNT,
                pos = currentRelativePos(clicked),
                direction = direction,
                secondaryPos = currentRelativePos(clicked.relative(direction))
            )
        )
        return true
    }

    private fun shouldRecordBreak(pos: BlockPos): Boolean {
        val now = System.currentTimeMillis()
        val last = lastBreakRecord
        if (last != null && last.first == pos && now - last.second < BREAK_RECORD_COOLDOWN_MS) return false
        lastBreakRecord = pos to now
        return true
    }

    private fun shouldRecordTnt(pos: BlockPos, direction: Direction): Boolean {
        val now = System.currentTimeMillis()
        val last = lastTntRecord
        if (last != null && last.first == pos && last.second == direction && now - last.third < TNT_RECORD_COOLDOWN_MS) return false
        lastTntRecord = Triple(pos, direction, now)
        return true
    }

    private fun appendStep(step: RouteStep) {
        val session = recording ?: return
        val rotation = currentRelativeRotation()
        val recordedStep = when {
            step.yaw != null && step.pitch != null -> step
            step.type == RouteStepType.WAIT_FOR_SECRET_PROGRESS || step.type == RouteStepType.WAIT_FOR_BAT_SPAWN -> step
            else -> step.copy(yaw = rotation?.yaw, pitch = rotation?.pitch)
        }

        session.steps.add(recordedStep)
        ChatUtils.modMessage("&7Recorded &e${recordedStep.type.name.lowercase().replace('_', ' ')}&7 step (${session.steps.size}).")
    }

    private fun currentRelativeRotation(): MathUtils.Rotation? {
        val player = mc.player ?: return null
        val ctx = currentRoomContext() ?: return null
        val relativeYaw = MathUtils.normalizeYaw(player.yRot + ctx.rotation.toFloat())
        return MathUtils.Rotation(relativeYaw, player.xRot)
    }

    private fun currentRelativePos(worldPos: BlockPos): BlockPos {
        val ctx = currentRoomContext() ?: error("Secret Routes requires a valid room context.")
        return toRelative(worldPos, ctx)
    }

    private fun toRelative(worldPos: BlockPos, ctx: RoomContext): BlockPos {
        return ScanUtils.getRelativeCoord(worldPos, ctx.corner, ctx.rotation)
    }

    private fun toWorld(relativePos: BlockPos, ctx: RoomContext): BlockPos {
        return ScanUtils.getRealCoord(relativePos, ctx.corner, ctx.rotation)
    }

    private fun currentRoomContext(): RoomContext? {
        val room = ScanUtils.currentRoom ?: return null
        val corner = room.corner ?: return null
        val rotation = room.rotation?.let { 360 - it } ?: return null
        return RoomContext(room, rotation, corner)
    }

    private fun renderRoute(event: RenderWorldEvent, ctx: RoomContext, route: RoomRoute) {
        val steps = route.steps.stepsForRender(ctx.room.name)
        val startBlock = toWorld(route.startBlock, ctx)
        val phase = renderThroughWalls.value

        if (renderStart.value) {
            Render3D.renderBlock(event.ctx, startBlock, startBlockColor.value, phase = phase)
            Render3D.renderString(
                "Start",
                startBlock.x + 0.5,
                startBlock.y + 1.2,
                startBlock.z + 0.5,
                scale = 1.5f,
                color = Color.WHITE,
                phase = phase
            )
        }

        if (renderEtherwarpLines.value) {
            val etherwarpPoints = buildList {
                add(startBlock)
                steps.filter { it.type == RouteStepType.ETHERWARP }
                    .mapNotNullTo(this) { it.pos?.let { pos -> toWorld(pos, ctx) } }
            }

            etherwarpPoints.zipWithNext { from, to ->
                Render3D.renderLine(
                    event.ctx,
                    Vec3.atCenterOf(from),
                    Vec3.atCenterOf(to),
                    etherwarpLineColor.value,
                    etherwarpLineWidth.value,
                    phase
                )
            }
        }

        steps.forEachIndexed { index, step ->
            when (step.type) {
                RouteStepType.ETHERWARP -> {
                    if (!renderEtherwarpTargets.value) return@forEachIndexed
                    val target = step.pos?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, etherwarpTargetColor.value, "EW ${index + 1}", phase)
                }

                RouteStepType.PLACE_TNT -> {
                    if (!renderTntTargets.value) return@forEachIndexed
                    val target = (step.secondaryPos ?: step.pos)?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, tntTargetColor.value, "TNT", phase)
                }

                RouteStepType.BREAK_BLOCK -> {
                    if (!renderBreakTargets.value) return@forEachIndexed
                    val target = step.pos?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, breakTargetColor.value, "Break", phase)
                }

                RouteStepType.USE_HYPERION -> {
                    if (!renderHyperionTargets.value) return@forEachIndexed
                    val target = step.pos?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, hyperionTargetColor.value, "Hyp", phase)
                }

                RouteStepType.RIGHT_CLICK_SECRET -> {
                    if (!renderSecretTargets.value) return@forEachIndexed
                    val target = step.pos?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, secretTargetColor.value, "Secret", phase)
                }

                RouteStepType.WAIT_FOR_SECRET_PROGRESS,
                RouteStepType.WAIT_FOR_BAT_SPAWN -> Unit
            }
        }
    }

    private fun renderTarget(
        event: RenderWorldEvent,
        pos: BlockPos,
        color: Color,
        label: String,
        phase: Boolean
    ) {
        Render3D.renderBlock(event.ctx, pos, color, phase = phase)
        Render3D.renderString(
            label,
            pos.x + 0.5,
            pos.y + 1.2,
            pos.z + 0.5,
            scale = 1.1f,
            color = Color.WHITE,
            phase = phase
        )
    }

    private fun List<RouteStep>.stepsForRender(roomName: String): List<RouteStep> {
        if (!partialRoutes.value) return this

        val boundaryIndexes = indices.filter { index ->
            this[index].type == RouteStepType.RIGHT_CLICK_SECRET || this[index].type == RouteStepType.WAIT_FOR_SECRET_PROGRESS
        }
        if (boundaryIndexes.isEmpty()) return this

        val completed = completedSecretsByRoom[roomName] ?: 0
        if (completed >= boundaryIndexes.size) return emptyList()

        val lastIndex = boundaryIndexes[completed]
        return subList(0, lastIndex + 1)
    }

    private fun markCurrentRoomSecretClick(worldPos: BlockPos) {
        val ctx = currentRoomContext() ?: return
        val route = routes[ctx.room.name] ?: return
        if (route.steps.none { it.type == RouteStepType.RIGHT_CLICK_SECRET && it.pos?.let { pos -> toWorld(pos, ctx) } == worldPos }) return
        markRoomSecretProgress(ctx.room.name)
    }

    private fun markRoomSecretProgress(roomName: String) {
        val route = routes[roomName] ?: return
        val maxSecrets = route.steps.count {
            it.type == RouteStepType.RIGHT_CLICK_SECRET || it.type == RouteStepType.WAIT_FOR_SECRET_PROGRESS
        }
        if (maxSecrets == 0) return

        val now = System.currentTimeMillis()
        val last = lastSecretProgressAtByRoom[roomName] ?: 0L
        if (now - last < 350L) return
        lastSecretProgressAtByRoom[roomName] = now

        val next = ((completedSecretsByRoom[roomName] ?: 0) + 1).coerceAtMost(maxSecrets)
        completedSecretsByRoom[roomName] = next
    }

    private fun clearSecretProgressCache() {
        completedSecretsByRoom.clear()
        lastSecretProgressAtByRoom.clear()
    }

    private fun abortPlayback(message: String): Boolean {
        ChatUtils.modMessage(message)
        stopPlayback()
        return false
    }

    private fun blockMessage(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedMessageAt < BLOCK_MESSAGE_COOLDOWN_MS) return
        lastBlockedMessageAt = now
        ChatUtils.modMessage("&e$message")
    }

    private fun releaseMovement() {
        listOf(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump)
            .forEach { it.isDown = false }
    }

    private fun isTntItemId(itemId: String?): Boolean {
        val id = itemId ?: return false
        return (id.contains("SUPERBOOM") && id.contains("TNT")) || (id.contains("BOOM") && id.contains("TNT"))
    }

    private fun isRouteRightClickTarget(pos: BlockPos): Boolean {
        if (DungeonUtils.isSecret(pos)) return true
        val block = mc.level?.getBlockState(pos)?.block ?: return false
        return block.equalsOneOf(Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.REDSTONE_BLOCK)
    }

    private fun RouteStep.rotation(ctx: RoomContext): MathUtils.Rotation? {
        val yaw = yaw ?: return null
        val pitch = pitch ?: return null
        val worldYaw = MathUtils.normalizeYaw(yaw - ctx.rotation.toFloat())
        return MathUtils.Rotation(worldYaw, pitch)
    }
}
