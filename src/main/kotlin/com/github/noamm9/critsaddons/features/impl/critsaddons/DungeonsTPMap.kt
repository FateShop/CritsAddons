package com.github.noamm9.critsaddons.features.impl.critsaddons

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
import com.github.noamm9.utils.dungeons.map.core.Door
import com.github.noamm9.utils.dungeons.map.core.Room
import com.github.noamm9.utils.dungeons.map.core.Tile
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.location.LocationUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import org.lwjgl.glfw.GLFW
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.sqrt

object DungeonsTPMap : Feature(
    name = "Dungeons TP Map",
    description = "Click a completed room and etherwarp doorway-to-doorway using NSR start/end blocks."
) {
    private const val GRID_WORLD_START_X = -185
    private const val GRID_WORLD_START_Z = -185
    private const val GRID_STEP = 16
    private const val DOOR_STAND_DISTANCE = 3
    private const val DOORWAY_MATCH_LIMIT = 7
    private const val LINK_RECOVERY_MAX_DISTANCE = 8

    private val keybindSection = "keybinds"
    private val routingSection = "routing"

    private val openMapKeybind by KeybindSetting("Open Map Keybind")
        .section(keybindSection)
        .withDescription("Opens the completed-room TP map.")

    private val hopDelayMs by SliderSetting("Hop Delay (ms)", 80, 0, 500, 5)
        .section(routingSection)
        .withDescription("Delay between doorway Etherwarp hops.")

    private val renderPath by ToggleSetting("Render Path", true)
        .section(routingSection)
        .withDescription("When hovering rooms or doors on the map, shows the planned room path.")

    private val debugMode by ToggleSetting("Debug", false)
        .section(routingSection)
        .withDescription("Prints route planning details.")

    data class MapSnapshot(
        val tiles: Map<Pair<Int, Int>, Tile>,
        val currentRoom: UniqueRoom?,
        val activeTargetRoomName: String?,
        val activeCompleteRoomName: String?,
        val completeProgressPercent: Int?,
        val completeProgressText: String?,
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

    private data class ReadyRoom(
        val room: UniqueRoom,
        val nav: SecretRoutes.RouteNavigationSnapshot
    )

    private data class DoorSide(
        val room: UniqueRoom,
        val coord: Pair<Int, Int>,
        val standBlock: BlockPos,
        val startBlock: BlockPos,
        val endBlock: BlockPos
    )

    private data class RoomEdge(
        val from: UniqueRoom,
        val to: UniqueRoom,
        val fromStartBlock: BlockPos,
        val fromDoorBlock: BlockPos,
        val toDoorBlock: BlockPos
    )

    private data class RoomTraversal(
        val room: UniqueRoom,
        val startBlock: BlockPos,
        val endBlock: BlockPos,
        val nextRoom: UniqueRoom,
        val nextRoomStartBlock: BlockPos
    )

    private data class TravelPlan(
        val targetRoomName: String,
        val rooms: List<UniqueRoom>,
        val traversals: List<RoomTraversal>
    )

    private var travelJob: Job? = null
    private var completeJob: Job? = null
    private var activeTargetRoomName: String? = null
    private var activeCompleteRoomName: String? = null
    private var completeProgressPercent: Int? = null
    private var completeProgressText: String? = null

    override fun init() {
        register<KeyboardEvent.KeyPressed> {
            if (!enabled) return@register
            if (event.action != GLFW.GLFW_PRESS) return@register
            if (!openMapKeybind.isPressed()) return@register
            if (!LocationUtils.inDungeon || LocationUtils.inBoss) return@register

            event.isCanceled = true
            mc.setScreen(DungeonsTPMapScreen())
        }

        register<WorldChangeEvent> {
            stopTravel(silent = true)
        }
    }

    fun currentSnapshot(): MapSnapshot {
        val currentRoom = resolveCurrentRoom()
        val tiles = buildTileGrid()
        val readyRoomNames = tiles.values
            .filterIsInstance<Room>()
            .mapNotNull { it.uniqueRoom }
            .distinctBy { it.name }
            .filter { readyRoom(it) != null }
            .mapTo(linkedSetOf()) { it.name }

        return MapSnapshot(
            tiles = tiles,
            currentRoom = currentRoom,
            activeTargetRoomName = activeTargetRoomName,
            activeCompleteRoomName = activeCompleteRoomName,
            completeProgressPercent = completeProgressPercent,
            completeProgressText = completeProgressText,
            isTraveling = travelJob?.isActive == true || completeJob?.isActive == true,
            readyRoomNames = readyRoomNames
        )
    }

    fun shouldRenderPathPreview(): Boolean = enabled && renderPath.value

    fun previewForHovered(gridX: Int, gridZ: Int): PathPreview? {
        if (!enabled || !renderPath.value || !LocationUtils.inDungeon || LocationUtils.inBoss) return null
        val currentRoom = resolveCurrentRoom() ?: return null
        val tiles = buildTileGrid()
        val coord = gridX to gridZ
        val targetRoom = (tiles[coord] as? Room)?.uniqueRoom ?: return null
        if (targetRoom.name == currentRoom.name) return null
        val path = buildCompletedRoomTilePath(currentRoom, targetRoom, tiles) ?: return null
        return PathPreview(
            tilePath = path,
            hopCoords = path.filter { tiles[it] is Door },
            label = targetRoom.name
        )
    }

    fun beginTravel(targetRoom: UniqueRoom) {
        if (!enabled) return
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) {
            ChatUtils.modMessage("&cDungeons TP Map only works in dungeon rooms.")
            return
        }

        val currentRoom = resolveCurrentRoom()
            ?: return ChatUtils.modMessage("&cCurrent room is not scanned yet.")

        if (currentRoom.name == targetRoom.name) {
            ChatUtils.modMessage("&eYou are already in &b${targetRoom.name}&e.")
            return
        }

        val plan = buildTravelPlan(currentRoom, targetRoom)
            ?: return ChatUtils.modMessage("&cCould not build a completed-room path to &b${targetRoom.name}&c.")

        stopTravel(silent = true)
        activeTargetRoomName = targetRoom.name
        ChatUtils.modMessage("&aStarting Dungeons TP path to &b${targetRoom.name}&a. Rooms: &e${plan.traversals.size}&a.")

        travelJob = scope.launch {
            val originalSlot = mc.player?.inventory?.selectedSlot ?: 0
            try {
                executePlan(plan)
            } finally {
                SecretRoutes.restoreHeldSneak()
                PlayerUtils.toggleSneak(false)
                PlayerUtils.swapToSlot(originalSlot)
                activeTargetRoomName = null
                travelJob = null
            }
        }
    }

    fun beginCompleteSecretRoute(targetRoom: UniqueRoom) {
        if (!enabled) return
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) {
            ChatUtils.modMessage("&cComplete SR only works in dungeon rooms.")
            return
        }

        val ready = readyRoom(targetRoom)
        if (ready == null) {
            ChatUtils.modMessage("&cCannot start Complete SR for &b${targetRoom.name}&c: room is not TP-ready.")
            readyRoomFailureLines(targetRoom).take(3).forEach {
                ChatUtils.modMessage("&7[DTP] $it")
            }
            return
        }

        val ogEnd = ready.nav.hubWorld
        if (ogEnd == null || ready.nav.startBlocksWorld.none { SecretRoutes.canPlayRoomRouteBetween(targetRoom, it, ogEnd) }) {
            ChatUtils.modMessage("&cCannot start Complete SR for &b${targetRoom.name}&c: no playable route to OG end.")
            return
        }

        if (activeCompleteRoomName == targetRoom.name) {
            ChatUtils.modMessage("&eComplete SR for &b${targetRoom.name}&e is already running.")
            return
        }

        stopTravel(silent = true)
        activeCompleteRoomName = targetRoom.name
        activeTargetRoomName = targetRoom.name
        completeProgressPercent = 0
        completeProgressText = "Pathing"
        ChatUtils.modMessage("&aStarting Complete SR for &b${targetRoom.name}&a.")

        completeJob = scope.launch {
            val originalSlot = mc.player?.inventory?.selectedSlot ?: 0
            try {
                val completed = completeSecretRoute(targetRoom)
                if (!completed) {
                    ChatUtils.modMessage("&cComplete SR failed for &b${targetRoom.name}&c.")
                }
            } finally {
                SecretRoutes.restoreHeldSneak()
                PlayerUtils.toggleSneak(false)
                PlayerUtils.swapToSlot(originalSlot)
                activeTargetRoomName = null
                activeCompleteRoomName = null
                completeProgressPercent = null
                completeProgressText = null
                completeJob = null
            }
        }
    }

    fun stopTravel(silent: Boolean = false) {
        if ((travelJob?.isActive == true || completeJob?.isActive == true) && !silent) {
            ChatUtils.modMessage("&eDungeons TP Map travel stopped.")
        }
        travelJob?.cancel()
        completeJob?.cancel()
        SecretRoutes.restoreHeldSneak()
        PlayerUtils.toggleSneak(false)
        travelJob = null
        completeJob = null
        activeTargetRoomName = null
        activeCompleteRoomName = null
        completeProgressPercent = null
        completeProgressText = null
    }

    private suspend fun executePlan(plan: TravelPlan) {
        if (executeTravelPlan(plan)) {
            ChatUtils.modMessage("&aArrived in &b${plan.targetRoomName}&a.")
        }
    }

    private suspend fun executeTravelPlan(plan: TravelPlan, keepSneakAfterFinalHop: Boolean = false): Boolean {
        for ((index, traversal) in plan.traversals.withIndex()) {
            if (!travelToBlock(traversal.startBlock, "start for ${traversal.room.name}")) {
                ChatUtils.modMessage("&cFailed to reach route start for &e${traversal.room.name}&c.")
                return false
            }
            if (hopDelayMs.value > 0) delay(hopDelayMs.value.toLong())

            if (!travelThroughRoomLinks(traversal)) {
                ChatUtils.modMessage("&cFailed to link through &e${traversal.room.name}&c.")
                return false
            }
            if (hopDelayMs.value > 0) delay(hopDelayMs.value.toLong())

            val keepSneakAfterNextRoomHop = index < plan.traversals.lastIndex || keepSneakAfterFinalHop
            if (!travelToNextRoomStart(traversal, keepSneakAfter = keepSneakAfterNextRoomHop)) {
                ChatUtils.modMessage("&cFailed to enter &e${traversal.nextRoom.name}&c.")
                return false
            }
            debug { "Completed room hop ${index + 1}/${plan.traversals.size}: ${traversal.room.name} -> ${traversal.nextRoom.name}" }
            if (hopDelayMs.value > 0) delay(hopDelayMs.value.toLong())
        }

        return true
    }

    private suspend fun completeSecretRoute(targetRoom: UniqueRoom): Boolean {
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) return false
        val currentRoom = resolveCurrentRoom()
            ?: run {
                ChatUtils.modMessage("&cCurrent room is not scanned yet.")
                return false
            }

        if (currentRoom.name != targetRoom.name) {
            completeProgressText = "Pathing to room"
            val plan = buildTravelPlan(currentRoom, targetRoom) ?: return false
            ChatUtils.modMessage("&aComplete SR pathing to &b${targetRoom.name}&a. Rooms: &e${plan.traversals.size}&a.")
            if (!executeTravelPlan(plan, keepSneakAfterFinalHop = true)) return false
            if (hopDelayMs.value > 0) delay(hopDelayMs.value.toLong())
        }

        val ready = readyRoom(targetRoom) ?: return false
        val ogEnd = ready.nav.hubWorld ?: return false
        val playerBlock = mc.player?.blockPosition()?.below() ?: return false
        val routeStart = chooseCompletionStart(targetRoom, ready.nav, playerBlock, ogEnd)
            ?: run {
                ChatUtils.modMessage("&cNo playable Complete SR start found for &b${targetRoom.name}&c.")
                return false
            }

        completeProgressText = "Moving to route start"
        completeProgressPercent = 0
        if (!travelToBlock(routeStart, "Complete SR start for ${targetRoom.name}")) return false
        if (hopDelayMs.value > 0) delay(hopDelayMs.value.toLong())

        completeProgressText = "Step 0/?"
        val played = SecretRoutes.playRoomRouteBetween(targetRoom, routeStart, ogEnd) { completedSteps, totalSteps ->
            completeProgressPercent = if (totalSteps <= 0) {
                100
            } else {
                ((completedSteps * 100) / totalSteps).coerceIn(0, 100)
            }
            completeProgressText = "Step $completedSteps/$totalSteps"
        }
        if (!played) return false

        completeProgressPercent = 100
        completeProgressText = "Done"
        ChatUtils.modMessage("&aComplete SR finished for &b${targetRoom.name}&a.")
        return true
    }

    private fun chooseCompletionStart(
        room: UniqueRoom,
        nav: SecretRoutes.RouteNavigationSnapshot,
        playerBlock: BlockPos,
        ogEnd: BlockPos
    ): BlockPos? {
        if (SecretRoutes.canPlayRoomRouteBetween(room, playerBlock, ogEnd)) return playerBlock
        return nav.startBlocksWorld
            .filter { SecretRoutes.canPlayRoomRouteBetween(room, it, ogEnd) }
            .minByOrNull { blockDistance(playerBlock, it) }
    }

    private suspend fun travelToBlock(block: BlockPos, label: String): Boolean {
        if (mc.player?.blockPosition()?.below() == block) return true
        debug { "Pathfinding to $label at ${block.toShortString()}." }
        return PathfindSecretRoute.tryEtherwarpTo(block)
    }

    private suspend fun travelThroughRoomLinks(traversal: RoomTraversal): Boolean {
        SecretRoutes.buildDoorwayLinkSteps(traversal.room, traversal.startBlock, traversal.endBlock)
            ?.let { directLinks ->
                debug { "Using direct /nsr link for ${traversal.room.name}: ${traversal.startBlock.toShortString()} -> ${traversal.endBlock.toShortString()} (${directLinks.size} EW)." }
                return travelRouteLinks(
                    directLinks,
                    "door link for ${traversal.room.name}",
                    keepSneakAfterLast = true
                )
            }

        val startLinks = SecretRoutes.buildStartLinkSteps(traversal.room, traversal.startBlock) ?: return false
        val endLinks = SecretRoutes.buildEndLinkSteps(traversal.room, traversal.endBlock) ?: return false

        if (!travelRouteLinks(
                startLinks,
                "start link for ${traversal.room.name}",
                keepSneakAfterLast = endLinks.isNotEmpty()
            )
        ) return false
        if (hopDelayMs.value > 0 && startLinks.isNotEmpty()) delay(hopDelayMs.value.toLong())
        return travelRouteLinks(
            endLinks,
            "end link for ${traversal.room.name}",
            keepSneakAfterLast = true
        )
    }

    private suspend fun travelRouteLinks(
        steps: List<SecretRoutes.RouteLinkStep>,
        label: String,
        keepSneakAfterLast: Boolean = false
    ): Boolean {
        for ((index, step) in steps.withIndex()) {
            if (mc.player?.blockPosition()?.below() != step.fromWorld) {
                ChatUtils.modMessage(
                    "&cCannot continue $label: expected &e${step.fromWorld.toShortString()}&c."
                )
                return false
            }
            debug {
                "Executing recorded $label ${step.fromWorld.toShortString()} -> ${step.toWorld.toShortString()} rotation=${step.rotation} face=${step.direction}"
            }
            val keepSneakAfter = index < steps.lastIndex || keepSneakAfterLast
            if (!SecretRoutes.playRouteLinkStep(step, label, keepSneakAfter = keepSneakAfter)) {
                if (!recoverToRouteBlock(step.toWorld, label)) return false
            }
            if (hopDelayMs.value > 0) delay(hopDelayMs.value.toLong())
        }
        return true
    }

    private suspend fun travelToNextRoomStart(traversal: RoomTraversal, keepSneakAfter: Boolean): Boolean {
        val current = mc.player?.blockPosition()?.below() ?: return false
        if (current != traversal.endBlock) {
            ChatUtils.modMessage(
                "&cCannot enter ${traversal.nextRoom.name}: expected doorway end &e${traversal.endBlock.toShortString()}&c."
            )
            return false
        }

        val rotation = nextRoomHopRotation(traversal.endBlock, traversal.nextRoomStartBlock) ?: return false
        val step = SecretRoutes.RouteLinkStep(
            fromWorld = traversal.endBlock,
            toWorld = traversal.nextRoomStartBlock,
            rotation = rotation,
            direction = null
        )
        val label = "next-room link to ${traversal.nextRoom.name}"
        return SecretRoutes.playRouteLinkStep(step, label, keepSneakAfter = keepSneakAfter) ||
            recoverToRouteBlock(traversal.nextRoomStartBlock, label)
    }

    private suspend fun recoverToRouteBlock(block: BlockPos, label: String): Boolean {
        val current = mc.player?.blockPosition()?.below() ?: return false
        if (current == block) return true

        val distance = blockDistance(current, block)
        if (distance > LINK_RECOVERY_MAX_DISTANCE) {
            debug {
                "Skipping recovery for $label: current=${current.toShortString()} target=${block.toShortString()} distance=$distance."
            }
            return false
        }

        ChatUtils.modMessage(
            "&e[DTP] Link landed at &b${current.toShortString()}&e; recovering to &b${block.toShortString()}&e."
        )
        debug { "Recovering $label with pathfinder current=${current.toShortString()} target=${block.toShortString()} distance=$distance." }
        val recovered = PathfindSecretRoute.tryEtherwarpTo(block)
        if (!recovered) {
            ChatUtils.modMessage("&c[DTP] Recovery failed for &e$label&c.")
            return false
        }

        ChatUtils.modMessage("&a[DTP] Recovered route position.")
        return true
    }

    private fun nextRoomHopRotation(fromEndBlock: BlockPos, toStartBlock: BlockPos): MathUtils.Rotation? {
        val profile = SecretRoutes.getNextRoomLinkProfile()
        if (profile == null) {
            ChatUtils.modMessage("&cRecord a global room-to-room angle first with &b/nsr next&c.")
            return null
        }

        val base = worldRotationFromBlocks(fromEndBlock, toStartBlock) ?: return null
        return MathUtils.Rotation(
            MathUtils.normalizeYaw(base.yaw + profile.yawOffset),
            MathUtils.normalizePitch(base.pitch + profile.pitchOffset)
        )
    }

    private fun worldRotationFromBlocks(from: BlockPos, to: BlockPos): MathUtils.Rotation? {
        val targetX = to.x + 0.5
        val targetY = to.y + 0.5
        val targetZ = to.z + 0.5
        val eyeX = from.x + 0.5
        val eyeY = from.y + 1.62
        val eyeZ = from.z + 0.5
        val dx = targetX - eyeX
        val dy = targetY - eyeY
        val dz = targetZ - eyeZ
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal <= 1.0e-6 && abs(dy) <= 1.0e-6) return null

        val yaw = MathUtils.normalizeYaw((Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat())
        val pitch = MathUtils.normalizePitch((-Math.toDegrees(atan2(dy, horizontal))).toFloat())
        return MathUtils.Rotation(yaw, pitch)
    }

    private fun buildTravelPlan(currentRoom: UniqueRoom, targetRoom: UniqueRoom): TravelPlan? {
        val currentReady = readyRoom(currentRoom)
            ?: return failPlan("Current room is not TP-ready: ${currentRoom.name}", readyRoomFailureLines(currentRoom))
        readyRoom(targetRoom)
            ?: return failPlan("Target room is not TP-ready: ${targetRoom.name}", readyRoomFailureLines(targetRoom))

        val edges = doorwayEdges()
        if (edges.isEmpty()) {
            return failPlan(
                "No usable doorway edges were built.",
                listOf(
                    "Scanned doors=${DungeonInfo.dungeonList.count { it is Door }}",
                    "Ready rooms=${currentSnapshot().readyRoomNames.joinToString().ifBlank { "none" }}",
                    "This usually means doorway start/end blocks did not match within $DOORWAY_MATCH_LIMIT blocks."
                )
            )
        }

        val roomPath = findRoomPath(currentRoom.name, targetRoom.name, edges)
            ?: return failPlan(
                "No completed-room graph path: ${currentRoom.name} -> ${targetRoom.name}",
                listOf(
                    "Edges=${edges.size}",
                    "Current neighbors=${edges.filter { it.from.name == currentRoom.name }.joinToString { it.to.name }.ifBlank { "none" }}",
                    "Target neighbors=${edges.filter { it.to.name == targetRoom.name }.joinToString { it.from.name }.ifBlank { "none" }}"
                )
            )

        val planEdges = roomPath.zipWithNext().map { (fromName, toName) ->
            edges.firstOrNull { it.from.name == fromName && it.to.name == toName }
                ?: return failPlan(
                    "Missing edge for path segment: $fromName -> $toName",
                    listOf("Room path=${roomPath.joinToString(" -> ")}")
                )
        }
        val traversalFailure = mutableListOf<String>()
        val traversals = buildTraversals(currentRoom, planEdges, traversalFailure)
            ?: return failPlan(
                "Could not convert graph path into route-link traversal.",
                traversalFailure.ifEmpty {
                    listOf(
                        "Room path=${roomPath.joinToString(" -> ")}",
                        "Player block=${mc.player?.blockPosition()?.below()?.toShortString() ?: "unknown"}",
                        "Current starts=${currentReady.nav.startBlocksWorld.joinToString { it.toShortString() }}"
                    )
                }
            )

        debug {
            "Plan ${currentRoom.name} -> ${targetRoom.name}: ${roomPath.joinToString(" -> ")}"
        }
        return TravelPlan(targetRoom.name, traversals.map { it.room } + targetRoom, traversals)
    }

    private fun buildTraversals(
        currentRoom: UniqueRoom,
        edges: List<RoomEdge>,
        failure: MutableList<String>
    ): List<RoomTraversal>? {
        if (edges.isEmpty()) return emptyList()

        val playerBlock = mc.player?.blockPosition()?.below()
            ?: run {
                failure.add("Could not resolve player ground block.")
                return null
            }
        var roomEntryStart = chooseInitialStart(currentRoom, playerBlock, edges.first().fromDoorBlock) ?: run {
            failure.add("No valid initial start in ${currentRoom.name} can link to ${edges.first().fromDoorBlock.toShortString()}.")
            failure.add("Player block=${playerBlock.toShortString()}")
            readyRoom(currentRoom)?.nav?.startBlocksWorld
                ?.joinToString { it.toShortString() }
                ?.let { failure.add("Known starts=$it") }
            return null
        }
        val traversals = mutableListOf<RoomTraversal>()

        edges.forEach { edge ->
            if (!canTraverseRoomLinks(edge.from, roomEntryStart, edge.fromDoorBlock)) {
                failure.add("No recorded link chain in ${edge.from.name}.")
                failure.add("Start=${roomEntryStart.toShortString()} -> End=${edge.fromDoorBlock.toShortString()}")
                failure.add("Door link=${SecretRoutes.buildDoorwayLinkSteps(edge.from, roomEntryStart, edge.fromDoorBlock)?.size ?: "missing"}")
                failure.add("Start links=${SecretRoutes.buildStartLinkSteps(edge.from, roomEntryStart)?.size ?: "missing"}")
                failure.add("End links=${SecretRoutes.buildEndLinkSteps(edge.from, edge.fromDoorBlock)?.size ?: "missing"}")
                debug {
                    "Rejected ${edge.from.name}: no link path from ${roomEntryStart.toShortString()} to ${edge.fromDoorBlock.toShortString()}."
                }
                return null
            }
            traversals.add(
                RoomTraversal(
                    room = edge.from,
                    startBlock = roomEntryStart,
                    endBlock = edge.fromDoorBlock,
                    nextRoom = edge.to,
                    nextRoomStartBlock = edge.toDoorBlock
                )
            )
            roomEntryStart = edge.toDoorBlock
        }

        return traversals
    }

    private fun chooseInitialStart(room: UniqueRoom, playerBlock: BlockPos, targetEnd: BlockPos): BlockPos? {
        if (canTraverseRoomLinks(room, playerBlock, targetEnd)) return playerBlock
        val ready = readyRoom(room) ?: return null
        return ready.nav.startBlocksWorld
            .filter { canTraverseRoomLinks(room, it, targetEnd) }
            .minByOrNull { blockDistance(playerBlock, it) }
    }

    private fun canTraverseRoomLinks(room: UniqueRoom, startBlock: BlockPos, endBlock: BlockPos): Boolean {
        return SecretRoutes.buildDoorwayLinkSteps(room, startBlock, endBlock) != null ||
            SecretRoutes.buildStartLinkSteps(room, startBlock) != null &&
            SecretRoutes.buildEndLinkSteps(room, endBlock) != null
    }

    private fun doorwayEdges(): List<RoomEdge> {
        val tiles = DungeonInfo.dungeonList.associateBy { it.x to it.z }
        val result = mutableListOf<RoomEdge>()

        DungeonInfo.dungeonList.filterIsInstance<Door>().forEach { door ->
            val horizontal = listOfNotNull(
                doorSide(tiles[door.x - GRID_STEP to door.z], door.x to door.z),
                doorSide(tiles[door.x + GRID_STEP to door.z], door.x to door.z)
            )
            val vertical = listOfNotNull(
                doorSide(tiles[door.x to door.z - GRID_STEP], door.x to door.z),
                doorSide(tiles[door.x to door.z + GRID_STEP], door.x to door.z)
            )

            result.addAll(edgesForSides(horizontal))
            result.addAll(edgesForSides(vertical))
        }

        return result.distinctBy { listOf(it.from.name, it.to.name, it.fromDoorBlock, it.toDoorBlock).joinToString("|") }
    }

    private fun doorSide(tile: Tile?, doorCoord: Pair<Int, Int>): DoorSide? {
        val room = (tile as? Room)?.uniqueRoom ?: return null
        val ready = readyRoom(room) ?: return null
        val tileCoord = tile.x to tile.z
        val expected = doorwayStandBlock(tileCoord, doorCoord)
        val start = nearestWithin(ready.nav.startBlocksWorld, expected) ?: return null
        val end = nearestWithin(ready.nav.endNodesWorld, expected) ?: return null
        return DoorSide(room, tileCoord, expected, start, end)
    }

    private fun edgesForSides(sides: List<DoorSide>): List<RoomEdge> {
        if (sides.size != 2) return emptyList()
        val a = sides[0]
        val b = sides[1]
        return listOf(
            RoomEdge(a.room, b.room, a.startBlock, a.endBlock, b.startBlock),
            RoomEdge(b.room, a.room, b.startBlock, b.endBlock, a.startBlock)
        )
    }

    private fun doorwayStandBlock(roomCoord: Pair<Int, Int>, doorCoord: Pair<Int, Int>): BlockPos {
        val dx = (doorCoord.first - roomCoord.first).coerceIn(-1, 1)
        val dz = (doorCoord.second - roomCoord.second).coerceIn(-1, 1)
        return BlockPos(
            doorCoord.first - dx * DOOR_STAND_DISTANCE,
            68,
            doorCoord.second - dz * DOOR_STAND_DISTANCE
        )
    }

    private fun nearestWithin(candidates: Set<BlockPos>, expected: BlockPos): BlockPos? {
        return candidates
            .minByOrNull { blockDistance(it, expected) }
            ?.takeIf { blockDistance(it, expected) <= DOORWAY_MATCH_LIMIT }
    }

    private fun findRoomPath(start: String, target: String, edges: List<RoomEdge>): List<String>? {
        val byRoom = edges.groupBy { it.from.name }
        val queue = ArrayDeque<List<String>>()
        val seen = mutableSetOf(start)
        queue.add(listOf(start))

        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val room = path.last()
            if (room == target) return path

            byRoom[room].orEmpty().forEach { edge ->
                if (!seen.add(edge.to.name)) return@forEach
                queue.add(path + edge.to.name)
            }
        }
        return null
    }

    private fun readyRoom(room: UniqueRoom): ReadyRoom? {
        if (!SecretRoutes.isRoomMarkedCompleted(room.name)) return null
        val nav = SecretRoutes.getRoomNavigationSnapshot(room) ?: return null
        if (!nav.sameStartAndOgEnd || nav.hubWorld == null) return null
        if (nav.startBlocksWorld.isEmpty() || nav.endNodesWorld.isEmpty()) return null
        return ReadyRoom(room, nav)
    }

    private fun readyRoomFailureLines(room: UniqueRoom): List<String> {
        if (!SecretRoutes.isRoomMarkedCompleted(room.name)) {
            return listOf("Room is not marked completed in Secret Routes.")
        }

        val nav = SecretRoutes.getRoomNavigationSnapshot(room)
            ?: return listOf("No Secret Routes navigation snapshot exists for this scanned room.")

        val lines = mutableListOf<String>()
        lines.add("sameStartAndOgEnd=${nav.sameStartAndOgEnd}, hub=${nav.hubWorld?.toShortString() ?: "null"}")
        lines.add("starts=${nav.startBlocksWorld.size}, ends=${nav.endNodesWorld.size}, primaryEnd=${nav.primaryEndWorld?.toShortString() ?: "null"}")
        if (!nav.sameStartAndOgEnd) lines.add("TP map fast mode requires the last start block and OG end block to be the same.")
        if (nav.hubWorld == null) lines.add("Missing OG end / hub block.")
        if (nav.startBlocksWorld.isEmpty()) lines.add("No known start blocks.")
        if (nav.endNodesWorld.isEmpty()) lines.add("No known end/end-helper blocks.")
        return lines
    }

    private fun failPlan(reason: String, details: List<String> = emptyList()): TravelPlan? {
        ChatUtils.modMessage("&c[DTP] $reason")
        details.take(5).forEach {
            ChatUtils.modMessage("&7[DTP] $it")
        }
        if (details.size > 5) {
            ChatUtils.modMessage("&7[DTP] ... ${details.size - 5} more details hidden.")
        }
        return null
    }

    private fun buildTileGrid(): Map<Pair<Int, Int>, Tile> {
        val grid = HashMap<Pair<Int, Int>, Tile>()
        DungeonInfo.dungeonList.forEach { tile ->
            if (tile.x == 0 && tile.z == 0) return@forEach
            grid[gridCoord(tile.x, tile.z)] = tile
        }
        return grid
    }

    private fun buildCompletedRoomTilePath(
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
                room.name == currentRoomName || room.name == targetRoomName || readyRoom(room) != null
            }
            else -> false
        }
    }

    private fun gridCoord(worldX: Int, worldZ: Int): Pair<Int, Int> {
        val gx = (worldX - GRID_WORLD_START_X) / GRID_STEP
        val gz = (worldZ - GRID_WORLD_START_Z) / GRID_STEP
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

    private fun scannedRooms(): List<UniqueRoom> {
        return DungeonInfo.dungeonList
            .filterIsInstance<Room>()
            .mapNotNull { it.uniqueRoom }
            .distinctBy { it.name }
    }

    private fun resolveCurrentRoom(): UniqueRoom? {
        return mc.player?.position()?.let(ScanUtils::getRoomFromPos) ?: ScanUtils.currentRoom
    }

    private fun blockDistance(a: BlockPos, b: BlockPos): Int {
        return abs(a.x - b.x) + abs(a.y - b.y) * 2 + abs(a.z - b.z)
    }

    private inline fun debug(message: () -> String) {
        if (debugMode.value) ChatUtils.modMessage("&7[DTPDBG] ${message()}")
    }
}
