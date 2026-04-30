package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.NoammAddons
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
import com.github.noamm9.utils.ActionBarParser
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.dungeons.map.DungeonInfo
import com.github.noamm9.utils.dungeons.map.core.Door
import com.github.noamm9.utils.dungeons.map.core.Room
import com.github.noamm9.utils.dungeons.map.core.Tile
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.items.EtherwarpHelper
import com.github.noamm9.utils.location.LocationUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

object DungeonsTPMap : Feature(
    name = "Dungeons TP Map",
    description = "Opens a dungeon map and routes through completed NSR doorway links to the selected room."
) {
    private const val GRID_WORLD_START_X = -185
    private const val GRID_WORLD_START_Z = -185
    private const val GRID_WORLD_STEP = 16
    private const val DOORWAY_STAND_DISTANCE = 3
    private const val REQUIRED_BLOCK_Y = 68
    private const val INTERACT_DELAY_MS = 60L
    private const val AIM_DELAY_MS = 45L
    private const val HOP_SETTLE_TIMEOUT_MS = 2_500L
    private const val BLOCK_MATCH_THRESHOLD_XZ = 4

    private val keybindSection = "keybinds"
    private val routingSection = "routing"

    private val openMapKeybind by KeybindSetting("Open Map Keybind")
        .section(keybindSection)
        .withDescription("Press to open the dungeon TP map.")

    private val rotationTimeMs by SliderSetting("Rotation Time (ms)", 170, 20, 500, 5)
        .section(routingSection)
        .withDescription("How long aiming rotations take before each Etherwarp.")

    private val hopDelayMs by SliderSetting("Hop Delay (ms)", 100, 0, 500, 5)
        .section(routingSection)
        .withDescription("Delay between successful Etherwarp hops.")

    private val renderPath by ToggleSetting("Render Path", true)
        .section(routingSection)
        .withDescription("When hovering rooms or doors on the map, shows the planned route and one dot per Etherwarp hop.")

    private val waitForMana by ToggleSetting("Wait For Mana", true)
        .section(routingSection)
        .withDescription("Waits until you have enough mana for the next Etherwarp instead of immediately failing.")

    private val debugMode by ToggleSetting("Debug", false)
        .section(routingSection)
        .withDescription("Prints detailed Dungeons TP Map route/debug messages.")

    private var travelJob: Job? = null
    private var activeTargetRoomName: String? = null

    override fun init() {
        register<KeyboardEvent.KeyPressed> {
            if (!enabled) return@register
            if (event.action != GLFW.GLFW_PRESS) return@register
            if (!openMapKeybind.isPressed()) return@register
            if (!LocationUtils.inDungeon || LocationUtils.inBoss) return@register

            event.isCanceled = true
            debug { "Opening TP map screen. currentRoom=${com.github.noamm9.utils.dungeons.map.utils.ScanUtils.currentRoom?.name}" }
            mc.setScreen(DungeonsTPMapScreen())
        }

        register<WorldChangeEvent> {
            stopTravel(silent = true)
        }
    }

    data class MapSnapshot(
        val tiles: Map<Pair<Int, Int>, Tile>,
        val currentRoom: UniqueRoom?,
        val activeTargetRoomName: String?,
        val isTraveling: Boolean,
        val readyRoomNames: Set<String>
    ) {
        val rooms: List<UniqueRoom>
            get() = tiles.values
                .filterIsInstance<Room>()
                .mapNotNull { it.uniqueRoom }
                .distinctBy { it.name }
    }

    data class PathPreview(
        val tilePath: List<Pair<Int, Int>>,
        val hopCoords: List<Pair<Int, Int>>,
        val label: String
    )

    private data class TravelHop(
        val target: BlockPos,
        val rotation: MathUtils.Rotation?,
        val label: String
    )

    private data class TravelPlan(
        val targetRoomName: String,
        val roomPath: List<Pair<Int, Int>>,
        val hops: List<TravelHop>
    )

    private data class RoomTransition(
        val fromRoom: UniqueRoom,
        val fromCoord: Pair<Int, Int>,
        val doorCoord: Pair<Int, Int>,
        val toRoom: UniqueRoom,
        val toCoord: Pair<Int, Int>
    )

    private data class ReadyRoomData(
        val room: UniqueRoom,
        val navigation: SecretRoutes.RouteNavigationSnapshot
    )

    fun currentSnapshot(): MapSnapshot {
        val tiles = buildTileGrid()
        val rooms = tiles.values
            .filterIsInstance<Room>()
            .mapNotNull { it.uniqueRoom }
            .distinctBy { it.name }
        val readyRoomNames = rooms.filter(::isRoomTravelReady).mapTo(linkedSetOf()) { it.name }
        return MapSnapshot(
            tiles = tiles,
            currentRoom = com.github.noamm9.utils.dungeons.map.utils.ScanUtils.currentRoom,
            activeTargetRoomName = activeTargetRoomName,
            isTraveling = travelJob?.isActive == true,
            readyRoomNames = readyRoomNames
        )
    }

    fun beginTravel(targetRoom: UniqueRoom) {
        if (!enabled) return
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) {
            ChatUtils.modMessage("&cDungeons TP Map only works in dungeon rooms.")
            return
        }

        val currentRoom = com.github.noamm9.utils.dungeons.map.utils.ScanUtils.currentRoom
        if (currentRoom == null) {
            ChatUtils.modMessage("&cCurrent room is not available yet. Wait for the dungeon map to scan.")
            return
        }

        if (currentRoom.name == targetRoom.name) {
            ChatUtils.modMessage("&eYou are already in &b${targetRoom.name}&e.")
            return
        }

        if (!isRoomTravelReady(currentRoom)) {
            ChatUtils.modMessage("&cCurrent room &e${currentRoom.name}&c is not NSR-completed or is missing doorway data.")
            return
        }

        if (!isRoomTravelReady(targetRoom)) {
            ChatUtils.modMessage("&cTarget room &e${targetRoom.name}&c is not NSR-completed or is missing doorway data.")
            return
        }

        val plan = buildTravelPlan(currentRoom, targetRoom, requirePlayerOnStart = true, announceStartFailure = true)
        if (plan == null || plan.hops.isEmpty()) {
            ChatUtils.modMessage("&cCould not build an NSR-completed TP path to &b${targetRoom.name}&c.")
            return
        }

        stopTravel(silent = true)
        activeTargetRoomName = targetRoom.name
        ChatUtils.modMessage("&aStarting TP path to &b${targetRoom.name}&a. Hops: &e${plan.hops.size}&a.")
        debug {
            "Travel plan ready target=${plan.targetRoomName}, roomPath=${plan.roomPath.joinToString(" -> ")}, hops=${plan.hops.joinToString { "${it.label}:${it.target.toShortString()}" }}"
        }

        travelJob = scope.launch {
            val originalSlot = mc.player?.inventory?.selectedSlot ?: 0
            try {
                executeTravel(plan)
            } finally {
                PlayerUtils.toggleSneak(false)
                PlayerUtils.swapToSlot(originalSlot)
                activeTargetRoomName = null
                travelJob = null
                debug { "Travel cleanup target=${plan.targetRoomName}, originalSlot=$originalSlot" }
            }
        }
    }

    fun stopTravel(silent: Boolean = false) {
        if (travelJob?.isActive == true) {
            travelJob?.cancel()
            if (!silent) ChatUtils.modMessage("&eDungeons TP Map travel stopped.")
        }
        PlayerUtils.toggleSneak(false)
        travelJob = null
        activeTargetRoomName = null
    }

    fun shouldRenderPathPreview(): Boolean = enabled && renderPath.value

    fun previewForHovered(gridX: Int, gridZ: Int): PathPreview? {
        if (!enabled || !renderPath.value || !LocationUtils.inDungeon || LocationUtils.inBoss) return null

        val currentRoom = com.github.noamm9.utils.dungeons.map.utils.ScanUtils.currentRoom ?: return null
        if (!isRoomTravelReady(currentRoom)) return null

        val tiles = buildTileGrid()
        val coord = gridX to gridZ
        return when (val tile = tiles[coord]) {
            is Room -> {
                val targetRoom = tile.uniqueRoom ?: return null
                if (targetRoom.name == currentRoom.name) return null
                val roomPath = buildCompletedRoomPath(currentRoom, targetRoom, tiles) ?: return null
                val plan = buildTravelPlanFromRoomPath(
                    currentRoom = currentRoom,
                    targetLabel = targetRoom.name,
                    finalTargetRoom = targetRoom,
                    roomPath = roomPath,
                    tiles = tiles,
                    requirePlayerOnStart = false,
                    announceStartFailure = false
                )
                PathPreview(
                    tilePath = roomPath,
                    hopCoords = plan?.hops?.map { gridCoord(it.target.x, it.target.z) }.orEmpty(),
                    label = targetRoom.name
                )
            }

            is Door -> buildDoorPreview(currentRoom, coord, tiles)
            else -> null
        }
    }

    private suspend fun executeTravel(plan: TravelPlan) {
        plan.hops.forEachIndexed { index, hop ->
            val currentBlock = mc.player?.blockPosition()?.below()
            if (currentBlock == hop.target) {
                debug { "Skipping hop ${index + 1}/${plan.hops.size} already on ${hop.target.toShortString()} label=${hop.label}" }
                return@forEachIndexed
            }

            if (waitForMana.value) {
                waitForRequiredMana(etherwarpManaCost())
            } else if (availableMana() < etherwarpManaCost()) {
                ChatUtils.modMessage("&cNot enough mana for the next Etherwarp hop.")
                stopTravel(silent = true)
                return
            }

            debug { "Executing hop ${index + 1}/${plan.hops.size} label=${hop.label} target=${hop.target.toShortString()} rotation=${hop.rotation}" }
            if (!etherwarpTo(hop)) {
                ChatUtils.modMessage("&cFailed while traveling to &b${plan.targetRoomName}&c.")
                stopTravel(silent = true)
                return
            }

            if (hopDelayMs.value > 0) delay(hopDelayMs.value.toLong())
        }

        ChatUtils.modMessage("&aArrived in &b${plan.targetRoomName}&a.")
    }

    private fun buildTravelPlan(
        currentRoom: UniqueRoom,
        targetRoom: UniqueRoom,
        requirePlayerOnStart: Boolean,
        announceStartFailure: Boolean
    ): TravelPlan? {
        val tiles = buildTileGrid()
        val roomPath = buildCompletedRoomPath(currentRoom, targetRoom, tiles) ?: return null
        return buildTravelPlanFromRoomPath(
            currentRoom = currentRoom,
            targetLabel = targetRoom.name,
            finalTargetRoom = targetRoom,
            roomPath = roomPath,
            tiles = tiles,
            requirePlayerOnStart = requirePlayerOnStart,
            announceStartFailure = announceStartFailure
        )
    }

    private fun buildTravelPlanFromRoomPath(
        currentRoom: UniqueRoom,
        targetLabel: String,
        finalTargetRoom: UniqueRoom?,
        roomPath: List<Pair<Int, Int>>,
        tiles: Map<Pair<Int, Int>, Tile>,
        requirePlayerOnStart: Boolean,
        announceStartFailure: Boolean
    ): TravelPlan? {
        val transitions = extractTransitions(roomPath, tiles)
        if (transitions.any { !isRoomTravelReady(it.fromRoom) || !isRoomTravelReady(it.toRoom) }) return null

        val playerBlock = mc.player?.blockPosition()?.below() ?: return null
        var currentData = readyRoomData(currentRoom) ?: return null
        val hops = mutableListOf<TravelHop>()

        val startBlock = when {
            playerBlock in currentData.navigation.startBlocksWorld -> playerBlock
            requirePlayerOnStart -> {
                if (announceStartFailure) {
                    ChatUtils.modMessage("&cStand on a valid NSR start block for &e${currentRoom.name}&c before using Dungeons TP Map.")
                }
                debug {
                    "Refusing plan: playerBlock=${playerBlock.toShortString()} is not a valid start block for room=${currentRoom.name}. Starts=${
                        currentData.navigation.startBlocksWorld.joinToString { it.toShortString() }
                    }"
                }
                return null
            }
            else -> selectNearestBlock(currentData.navigation.startBlocksWorld, playerBlock) ?: return null
        }
        hops.addAll(buildStartChainHops(currentData.room, startBlock) ?: return null)

        transitions.forEach { transition ->
            val nextData = readyRoomData(transition.toRoom) ?: return null
            val exitBlock = selectExitBlock(currentData, transition, finalTargetRoom ?: transition.toRoom)
                ?: return null
            hops.addAll(buildEndChainHops(currentData.room, exitBlock) ?: return null)

            val nextStartExpected = doorwayStandBlock(transition.toCoord, transition.doorCoord)
            val nextStart = matchDoorwayBlock(nextData.navigation.startBlocksWorld, nextStartExpected)
                ?: return null

            val nextRotation = nextRoomHopRotation(exitBlock, nextStart) ?: return null
            hops.add(TravelHop(nextStart, nextRotation, "Enter ${transition.toRoom.name}"))
            hops.addAll(buildStartChainHops(nextData.room, nextStart) ?: return null)
            currentData = nextData
        }

        val normalizedHops = hops.fold(mutableListOf<TravelHop>()) { acc, hop ->
            if (acc.lastOrNull()?.target != hop.target || acc.lastOrNull()?.rotation != hop.rotation) {
                acc.add(hop)
            }
            acc
        }

        return TravelPlan(
            targetRoomName = targetLabel,
            roomPath = roomPath,
            hops = normalizedHops
        )
    }

    private fun buildDoorPreview(
        currentRoom: UniqueRoom,
        doorCoord: Pair<Int, Int>,
        tiles: Map<Pair<Int, Int>, Tile>
    ): PathPreview? {
        val tilePath = buildCompletedPathToCoord(currentRoom, doorCoord, tiles) ?: return null
        val plan = buildDoorPreviewPlan(currentRoom, tilePath, tiles)
        return PathPreview(
            tilePath = tilePath,
            hopCoords = plan?.hops?.map { gridCoord(it.target.x, it.target.z) }.orEmpty(),
            label = "Door"
        )
    }

    private fun buildCompletedPathToCoord(
        currentRoom: UniqueRoom,
        targetCoord: Pair<Int, Int>,
        tiles: Map<Pair<Int, Int>, Tile>
    ): List<Pair<Int, Int>>? {
        val startCoords = currentRoom.tiles.map { gridCoord(it.x, it.z) }.toSet()
        if (startCoords.isEmpty() || targetCoord !in tiles) return null

        val queue = ArrayDeque<Pair<Int, Int>>()
        val previous = HashMap<Pair<Int, Int>, Pair<Int, Int>?>()
        startCoords.forEach {
            queue.add(it)
            previous[it] = null
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == targetCoord) {
                val path = ArrayDeque<Pair<Int, Int>>()
                var cursor: Pair<Int, Int>? = current
                while (cursor != null) {
                    path.addFirst(cursor)
                    cursor = previous[cursor]
                }
                return path.toList()
            }

            neighbors(current).forEach { next ->
                if (previous.containsKey(next)) return@forEach
                val tile = tiles[next] ?: return@forEach
                if (!isPassableTile(tile, currentRoom.name, currentRoom.name)) return@forEach
                previous[next] = current
                queue.add(next)
            }
        }

        return null
    }

    private fun buildDoorPreviewPlan(
        currentRoom: UniqueRoom,
        tilePath: List<Pair<Int, Int>>,
        tiles: Map<Pair<Int, Int>, Tile>
    ): TravelPlan? {
        if (tilePath.isEmpty()) return null

        val currentData = readyRoomData(currentRoom) ?: return null
        val playerBlock = mc.player?.blockPosition()?.below() ?: return null
        val startBlock = if (playerBlock in currentData.navigation.startBlocksWorld) {
            playerBlock
        } else {
            selectNearestBlock(currentData.navigation.startBlocksWorld, playerBlock) ?: return null
        }

        val hops = mutableListOf<TravelHop>()
        hops.addAll(buildStartChainHops(currentData.room, startBlock) ?: return null)
        hops.addAll(
            buildPartialExitHops(
                currentRoom = currentRoom,
                currentData = currentData,
                tilePath = tilePath,
                tiles = tiles
            ) ?: return null
        )

        return TravelPlan(
            targetRoomName = "Door",
            roomPath = tilePath,
            hops = hops
        )
    }

    private fun buildPartialExitHops(
        currentRoom: UniqueRoom,
        currentData: ReadyRoomData,
        tilePath: List<Pair<Int, Int>>,
        tiles: Map<Pair<Int, Int>, Tile>
    ): List<TravelHop>? {
        if (tilePath.size < 2) return emptyList()
        val finalCoord = tilePath.last()
        val finalTile = tiles[finalCoord] ?: return emptyList()
        if (finalTile !is Door) return emptyList()

        val previousCoord = tilePath[tilePath.lastIndex - 1]
        val previousTile = tiles[previousCoord] as? Room ?: return emptyList()
        val previousRoom = previousTile.uniqueRoom ?: return emptyList()
        if (previousRoom.name != currentRoom.name) return emptyList()

        val exitExpected = doorwayStandBlock(previousCoord, finalCoord)
        val exitBlock = matchDoorwayBlock(currentData.navigation.endNodesWorld, exitExpected)
            ?: currentData.navigation.primaryEndWorld
            ?: currentData.navigation.endNodesWorld.firstOrNull()
            ?: return null

        return buildEndChainHops(currentRoom, exitBlock)
    }

    private fun buildCompletedRoomPath(
        currentRoom: UniqueRoom,
        targetRoom: UniqueRoom,
        tiles: Map<Pair<Int, Int>, Tile>
    ): List<Pair<Int, Int>>? {
        val startCoords = currentRoom.tiles.map { gridCoord(it.x, it.z) }.toSet()
        val goalCoords = targetRoom.tiles.map { gridCoord(it.x, it.z) }.toSet()
        if (startCoords.isEmpty() || goalCoords.isEmpty()) return null

        val queue = ArrayDeque<Pair<Int, Int>>()
        val previous = HashMap<Pair<Int, Int>, Pair<Int, Int>?>()
        startCoords.forEach {
            queue.add(it)
            previous[it] = null
        }

        var found: Pair<Int, Int>? = null
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in goalCoords) {
                found = current
                break
            }

            neighbors(current).forEach { next ->
                if (previous.containsKey(next)) return@forEach
                val tile = tiles[next] ?: return@forEach
                if (!isPassableTile(tile, currentRoom.name, targetRoom.name)) return@forEach
                previous[next] = current
                queue.add(next)
            }
        }

        val end = found ?: return null
        val path = ArrayDeque<Pair<Int, Int>>()
        var cursor: Pair<Int, Int>? = end
        while (cursor != null) {
            path.addFirst(cursor)
            cursor = previous[cursor]
        }
        return path.toList()
    }

    private fun isPassableTile(tile: Tile, currentRoomName: String, targetRoomName: String): Boolean {
        return when (tile) {
            is Door -> true
            is Room -> {
                val room = tile.uniqueRoom ?: return false
                room.name == currentRoomName || room.name == targetRoomName || isRoomTravelReady(room)
            }
            else -> false
        }
    }

    private fun extractTransitions(
        roomPath: List<Pair<Int, Int>>,
        tiles: Map<Pair<Int, Int>, Tile>
    ): List<RoomTransition> {
        val transitions = mutableListOf<RoomTransition>()
        for (index in 0..roomPath.lastIndex - 2) {
            val first = tiles[roomPath[index]]
            val second = tiles[roomPath[index + 1]]
            val third = tiles[roomPath[index + 2]]
            if (first !is Room || second !is Door || third !is Room) continue

            val fromRoom = first.uniqueRoom ?: continue
            val toRoom = third.uniqueRoom ?: continue
            if (fromRoom.name == toRoom.name) continue

            transitions.add(
                RoomTransition(
                    fromRoom = fromRoom,
                    fromCoord = roomPath[index],
                    doorCoord = roomPath[index + 1],
                    toRoom = toRoom,
                    toCoord = roomPath[index + 2]
                )
            )
        }
        return transitions
    }

    private fun readyRoomData(room: UniqueRoom): ReadyRoomData? {
        if (!SecretRoutes.isRoomMarkedCompleted(room.name)) return null
        val navigation = SecretRoutes.getRoomNavigationSnapshot(room) ?: return null
        if (!navigation.sameStartAndOgEnd || navigation.hubWorld == null) return null
        if (navigation.startBlocksWorld.isEmpty() || navigation.endNodesWorld.isEmpty()) return null
        return ReadyRoomData(room, navigation)
    }

    private fun isRoomTravelReady(room: UniqueRoom): Boolean = readyRoomData(room) != null

    private fun buildStartChainHops(room: UniqueRoom, fromStartBlock: BlockPos): List<TravelHop>? {
        return SecretRoutes.buildStartLinkSteps(room, fromStartBlock)
            ?.map { TravelHop(it.toWorld, it.rotation, "Route ${room.name} start chain") }
    }

    private fun buildEndChainHops(room: UniqueRoom, endBlock: BlockPos): List<TravelHop>? {
        return SecretRoutes.buildEndLinkSteps(room, endBlock)
            ?.map { TravelHop(it.toWorld, it.rotation, "Route ${room.name} end chain") }
    }

    private fun selectExitBlock(
        roomData: ReadyRoomData,
        transition: RoomTransition,
        finalTargetRoom: UniqueRoom
    ): BlockPos? {
        val markedCompleted = SecretRoutes.isRoomMarkedCompleted(roomData.room.name)
        if (!markedCompleted) {
            val fallback = roomData.navigation.endNodesWorld.firstOrNull()
            debug { "Exit select room=${roomData.room.name} completed=false usingFirstEnd=${fallback?.toShortString()}" }
            return fallback
        }

        if (isBloodRoom(finalTargetRoom) && roomData.navigation.primaryEndWorld != null) {
            val bloodEnd = roomData.navigation.primaryEndWorld
            debug { "Exit select room=${roomData.room.name} bloodTarget=true usingPrimaryEnd=${bloodEnd.toShortString()}" }
            return bloodEnd
        }

        val exitExpected = doorwayStandBlock(transition.fromCoord, transition.doorCoord)
        val matched = matchDoorwayBlock(roomData.navigation.endNodesWorld, exitExpected)
            ?: roomData.navigation.primaryEndWorld
            ?: roomData.navigation.endNodesWorld.firstOrNull()

        debug {
            "Exit select room=${roomData.room.name} bloodTarget=false expected=${exitExpected.toShortString()} matched=${matched?.toShortString()}"
        }
        return matched
    }

    private fun doorwayStandBlock(roomCoord: Pair<Int, Int>, doorCoord: Pair<Int, Int>): BlockPos {
        val directionX = (doorCoord.first - roomCoord.first).coerceIn(-1, 1)
        val directionZ = (doorCoord.second - roomCoord.second).coerceIn(-1, 1)
        val doorWorldX = gridWorldX(doorCoord.first)
        val doorWorldZ = gridWorldZ(doorCoord.second)
        return BlockPos(
            doorWorldX - directionX * DOORWAY_STAND_DISTANCE,
            REQUIRED_BLOCK_Y,
            doorWorldZ - directionZ * DOORWAY_STAND_DISTANCE
        )
    }

    private fun matchDoorwayBlock(candidates: Set<BlockPos>, expected: BlockPos): BlockPos? {
        return candidates
            .minByOrNull { abs(it.x - expected.x) + abs(it.z - expected.z) + abs(it.y - expected.y) * 2 }
            ?.takeIf { maxOf(abs(it.x - expected.x), abs(it.z - expected.z)) <= BLOCK_MATCH_THRESHOLD_XZ }
    }

    private fun selectNearestBlock(candidates: Set<BlockPos>, origin: BlockPos): BlockPos? {
        return candidates.minByOrNull { abs(it.x - origin.x) + abs(it.y - origin.y) * 2 + abs(it.z - origin.z) }
    }

    private fun isBloodRoom(room: UniqueRoom): Boolean {
        return room.name.contains("blood", ignoreCase = true)
    }

    private fun nextRoomHopRotation(fromEndBlock: BlockPos, toStartBlock: BlockPos): MathUtils.Rotation? {
        val profile = SecretRoutes.getNextRoomLinkProfile()
        if (profile == null) {
            ChatUtils.modMessage("&cRecord a global room-to-room angle first with &b/nsr next&c.")
            debug { "Missing /nsr next profile for cross-room hop ${fromEndBlock.toShortString()} -> ${toStartBlock.toShortString()}" }
            return null
        }

        val base = worldRotationFromBlocks(fromEndBlock, toStartBlock) ?: return null
        val rotation = MathUtils.Rotation(
            MathUtils.normalizeYaw(base.yaw + profile.yawOffset),
            MathUtils.normalizePitch(base.pitch + profile.pitchOffset)
        )
        debug {
            "Next-room rotation from=${fromEndBlock.toShortString()} to=${toStartBlock.toShortString()} " +
                "base=${formatRotation(base)} profileOffsets=${formatProfile(profile)} final=${formatRotation(rotation)}"
        }
        return rotation
    }

    private fun worldRotationFromBlocks(from: BlockPos, to: BlockPos): MathUtils.Rotation? {
        val target = BlockAimUtils.blockCenter(to)
        val eye = Vec3(from.x + 0.5, from.y + 1.62, from.z + 0.5)
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal <= 1.0e-6 && abs(dy) <= 1.0e-6) return null

        val worldYaw = MathUtils.normalizeYaw((Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat())
        val worldPitch = MathUtils.normalizePitch((-Math.toDegrees(atan2(dy, horizontal))).toFloat())
        return MathUtils.Rotation(worldYaw, worldPitch)
    }

    private fun formatRotation(rotation: MathUtils.Rotation): String {
        return String.format(Locale.US, "(yaw=%.2f,pitch=%.2f)", rotation.yaw, rotation.pitch)
    }

    private fun formatProfile(profile: SecretRoutes.NextRoomLinkProfile): String {
        return String.format(Locale.US, "(yaw=%.2f,pitch=%.2f)", profile.yawOffset, profile.pitchOffset)
    }

    private suspend fun etherwarpTo(hop: TravelHop): Boolean {
        val slot = PlayerUtils.findHotbarSlot { EtherwarpHelper.getEtherwarpDistance(it) != null }
            ?: run {
                ChatUtils.modMessage("&cNo Etherwarp item found on your hotbar.")
                return false
            }

        PlayerUtils.swapToSlot(slot)
        PlayerUtils.toggleSneak(true)
        delay(INTERACT_DELAY_MS)

        if (!alignEtherwarpTarget(hop.target, hop.rotation)) {
            PlayerUtils.toggleSneak(false)
            ChatUtils.modMessage("&cCould not line up Etherwarp target &e${hop.target.toShortString()}&c.")
            return false
        }

        delay(INTERACT_DELAY_MS)
        PlayerUtils.rightClick()
        delay(INTERACT_DELAY_MS)
        PlayerUtils.toggleSneak(false)

        return waitForStandingOn(hop.target)
    }

    private suspend fun alignEtherwarpTarget(target: BlockPos, rotation: MathUtils.Rotation?): Boolean {
        if (rotation != null) {
            PlayerUtils.rotateSmoothly(rotation, rotationTimeMs.value.toLong())
            delay(AIM_DELAY_MS)
            debug { "Rotation align target=${target.toShortString()} rotation=$rotation" }
            return true
        }

        BlockAimUtils.aimAt(BlockAimUtils.blockCenter(target), rotationTimeMs.value.toLong())
        delay(AIM_DELAY_MS)

        val player = mc.player ?: return false
        val range = maxOf(6.0, player.position().distanceTo(BlockAimUtils.blockCenter(target)) + 2.0)
        val linedUp = MathUtils.raytrace(player, range) == target
        debug { "Direct align target=${target.toShortString()} linedUp=$linedUp" }
        return linedUp
    }

    private suspend fun waitForStandingOn(block: BlockPos): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < HOP_SETTLE_TIMEOUT_MS) {
            if (mc.player?.blockPosition()?.below() == block) return true
            delay(50)
        }
        return false
    }

    private suspend fun waitForRequiredMana(requiredMana: Int) {
        while (travelJob?.isActive == true && availableMana() < requiredMana) {
            delay(100)
        }
    }

    private fun findEtherwarpDistance(): Double? {
        val slot = PlayerUtils.findHotbarSlot { EtherwarpHelper.getEtherwarpDistance(it) != null } ?: return null
        val stack = PlayerUtils.getHotbarSlot(slot) ?: return null
        return EtherwarpHelper.getEtherwarpDistance(stack)
    }

    private fun availableMana(): Int {
        return ActionBarParser.currentMana + ActionBarParser.overflowMana
    }

    private fun etherwarpManaCost(): Int = 100

    private fun buildTileGrid(): Map<Pair<Int, Int>, Tile> {
        val grid = HashMap<Pair<Int, Int>, Tile>()
        DungeonInfo.dungeonList.forEach { tile ->
            if (tile.x == 0 && tile.z == 0) return@forEach
            grid[gridCoord(tile.x, tile.z)] = tile
        }
        return grid
    }

    private fun gridCoord(worldX: Int, worldZ: Int): Pair<Int, Int> {
        val gx = (worldX - GRID_WORLD_START_X) / GRID_WORLD_STEP
        val gz = (worldZ - GRID_WORLD_START_Z) / GRID_WORLD_STEP
        return gx to gz
    }

    private fun neighbors(coord: Pair<Int, Int>): List<Pair<Int, Int>> {
        val (x, z) = coord
        return listOf(
            x + 1 to z,
            x - 1 to z,
            x to z + 1,
            x to z - 1
        )
    }

    private fun gridWorldX(gridX: Int): Int = GRID_WORLD_START_X + gridX * GRID_WORLD_STEP
    private fun gridWorldZ(gridZ: Int): Int = GRID_WORLD_START_Z + gridZ * GRID_WORLD_STEP

    private fun debug(message: () -> String) {
        if (!debugMode.value) return
        val text = message()
        NoammAddons.logger.info("[DungeonsTPMap] $text")
        ChatUtils.modMessage("&8[&bTPDBG&8] &7$text")
    }
}
