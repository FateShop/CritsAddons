package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.NoammAddons
import com.github.noamm9.NoammAddons.MOD_NAME
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.impl.PacketEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.KeybindSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.JsonUtils
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.ArrayDeque

object PacketSecretRoutes : Feature(
    name = "Packet Secret Routes",
    description = "Records and replays packet-based dungeon room routes into a separate packetSecretRoutes.json.",
    toggled = true
) {
    private const val PREFIX = "&8[&dPSR&8]"
    private const val CONFIG_PATH = "config/$MOD_NAME/packetSecretRoutes.json"
    private val file = File(CONFIG_PATH)

    private val playbackSection = "playback"
    private val recordingSection = "recording"

    private val playbackKeybind by KeybindSetting("Playback Keybind", GLFW.GLFW_KEY_UNKNOWN)
        .section(playbackSection)
        .withDescription("Plays the packet route saved for the current dungeon room.")
    private val onePacketPerTick by ToggleSetting("One Packet Per Tick", true)
        .section(playbackSection)
        .withDescription("Sends at most one recorded packet at the start of each client tick.")
    private val preserveRecordedTickDelay by ToggleSetting("Preserve Recorded Tick Delay", false)
        .section(playbackSection)
        .withDescription("Waits the same number of client ticks between packet sends as the recording.")
    private val maxPacketsPerTick by SliderSetting("Max Packets Per Tick", 4, 1, 20, 1)
        .section(playbackSection)
        .withDescription("Used when One Packet Per Tick is disabled.")
    private val includeMovementPackets by ToggleSetting("Record Movement Packets", true)
        .section(recordingSection)
        .withDescription("Records rotation/position packets. Etherwarp accuracy usually needs rotation packets.")

    private var routesFile = PacketRoutesFile()
    private var activeRecording: RecordingSession? = null
    private var playback: PlaybackSession? = null
    private var clientTick = 0L
    private var replaying = false

    override fun init() {
        loadConfig()

        register<TickEvent.Start> {
            clientTick++

            if (playbackKeybind.isPressed()) {
                playCurrentRoom()
            }

            flushPlayback()
        }

        register<PacketEvent.Sent> {
            if (!enabled || replaying) return@register
            val session = activeRecording ?: return@register
            val snapshot = packetToSnapshot(event.packet) ?: return@register
            val delayTicks = (clientTick - session.lastPacketTick).coerceAtLeast(0).toInt()
            session.packets.add(PacketFrame(delayTicks, snapshot))
            session.lastPacketTick = clientTick
        }
    }

    fun startRecording() {
        val roomName = currentRoomName()
            ?: return fail("You must be standing in a scanned dungeon room to start /psr.")
        if (activeRecording != null) return fail("Save or cancel the current /psr recording first.")
        stopPlayback()

        activeRecording = RecordingSession(roomName, RecordingKind.MAIN, clientTick)
        chat("&aStarted Packet Secret Route recording for &e$roomName&a.")
    }

    fun continueRecording() {
        val roomName = currentRoomName()
            ?: return fail("You must be standing in a scanned dungeon room to use /psr continue.")
        if (activeRecording != null) return fail("Save or cancel the current /psr recording first.")
        val route = routesFile.routes[roomName] ?: return fail("No packet route saved for &e$roomName&c.")
        stopPlayback()

        activeRecording = RecordingSession(
            roomName = roomName,
            kind = RecordingKind.MAIN,
            lastPacketTick = clientTick,
            packets = route.main.toMutableList(),
            basePacketCount = route.main.size
        )
        chat("&aContinuing Packet Secret Route for &e$roomName&a from packet &e${route.main.size + 1}&a.")
    }

    fun startStartLinkRecording() {
        startClipRecording(RecordingKind.START_LINK, "start link")
    }

    fun startDoorwayLinkRecording() {
        startClipRecording(RecordingKind.DOORWAY_LINK, "doorway link")
    }

    fun startEndLinkRecording() {
        startClipRecording(RecordingKind.END_LINK, "end link")
    }

    fun saveRecording() {
        val session = activeRecording ?: return fail("No /psr recording is active.")
        val route = routesFile.routes.getOrPut(session.roomName) { PacketRoomRoute() }
        when (session.kind) {
            RecordingKind.MAIN -> {
                route.main.clear()
                route.main.addAll(session.packets)
            }
            RecordingKind.START_LINK -> route.startLinks.add(PacketClip(session.packets.toMutableList()))
            RecordingKind.DOORWAY_LINK -> route.doorwayLinks.add(PacketClip(session.packets.toMutableList()))
            RecordingKind.END_LINK -> route.endLinks.add(PacketClip(session.packets.toMutableList()))
        }

        activeRecording = null
        saveConfig()
        chat("&aSaved &e${session.packets.size}&a packet${plural(session.packets.size)} for &e${session.roomName}&a.")
    }

    fun cancelRecording() {
        val session = activeRecording ?: return fail("No /psr recording is active.")
        activeRecording = null
        chat("&eCanceled /psr ${session.kind.label} recording for &e${session.roomName}&e.")
    }

    fun deleteCurrentRoomRoute() {
        val roomName = currentRoomName()
            ?: return fail("You must be standing in a scanned dungeon room to delete its packet route.")
        val removed = routesFile.routes.remove(roomName) ?: return fail("No packet route saved for &e$roomName&c.")
        saveConfig()
        chat("&aDeleted packet route for &e$roomName&a with &e${removed.main.size}&a main packets.")
    }

    fun deleteLastStartLink() {
        deleteLastClip(RecordingKind.START_LINK)
    }

    fun deleteLastDoorwayLink() {
        deleteLastClip(RecordingKind.DOORWAY_LINK)
    }

    fun deleteLastEndLink() {
        deleteLastClip(RecordingKind.END_LINK)
    }

    fun playCurrentRoom() {
        val roomName = currentRoomName()
            ?: return fail("You must be standing in a scanned dungeon room to play /psr.")
        val route = routesFile.routes[roomName] ?: return fail("No packet route saved for &e$roomName&c.")
        if (route.main.isEmpty()) return fail("Packet route for &e$roomName&c has no main packets.")
        startPlayback(roomName, route.main, "main route")
    }

    fun playStartLink(index: Int = 0) {
        playClip(RecordingKind.START_LINK, index)
    }

    fun playDoorwayLink(index: Int = 0) {
        playClip(RecordingKind.DOORWAY_LINK, index)
    }

    fun playEndLink(index: Int = 0) {
        playClip(RecordingKind.END_LINK, index)
    }

    fun stopPlayback(message: String? = null) {
        playback = null
        message?.let { chat(it) }
    }

    fun listCurrentRoom() {
        val roomName = currentRoomName()
            ?: return fail("You must be standing in a scanned dungeon room to list /psr.")
        val route = routesFile.routes[roomName] ?: return fail("No packet route saved for &e$roomName&c.")
        chat("&e$roomName&7: main=&b${route.main.size}&7, startLinks=&b${route.startLinks.size}&7, links=&b${route.doorwayLinks.size}&7, endLinks=&b${route.endLinks.size}")
    }

    private fun startClipRecording(kind: RecordingKind, label: String) {
        val roomName = currentRoomName()
            ?: return fail("You must be standing in a scanned dungeon room to start /psr $label.")
        if (activeRecording != null) return fail("Save or cancel the current /psr recording first.")
        stopPlayback()

        activeRecording = RecordingSession(roomName, kind, clientTick)
        chat("&aStarted /psr $label recording for &e$roomName&a. Use &b/psr save&a when done.")
    }

    private fun deleteLastClip(kind: RecordingKind) {
        val roomName = currentRoomName()
            ?: return fail("You must be standing in a scanned dungeon room to delete a /psr ${kind.label}.")
        val route = routesFile.routes[roomName] ?: return fail("No packet route saved for &e$roomName&c.")
        val clips = clipsFor(route, kind)
        if (clips.isEmpty()) return fail("No /psr ${kind.label}s saved for &e$roomName&c.")
        clips.removeAt(clips.lastIndex)
        saveConfig()
        chat("&aDeleted last /psr ${kind.label} for &e$roomName&a.")
    }

    private fun playClip(kind: RecordingKind, index: Int) {
        val roomName = currentRoomName()
            ?: return fail("You must be standing in a scanned dungeon room to play a /psr ${kind.label}.")
        val route = routesFile.routes[roomName] ?: return fail("No packet route saved for &e$roomName&c.")
        val clip = clipsFor(route, kind).getOrNull(index)
            ?: return fail("No /psr ${kind.label} #${index + 1} saved for &e$roomName&c.")
        if (clip.packets.isEmpty()) return fail("/psr ${kind.label} #${index + 1} has no packets.")
        startPlayback(roomName, clip.packets, "${kind.label} #${index + 1}")
    }

    private fun startPlayback(roomName: String, packets: List<PacketFrame>, label: String) {
        activeRecording = null
        playback = PlaybackSession(
            roomName = roomName,
            label = label,
            queue = ArrayDeque(packets.map { PlaybackFrame(it.delayTicks, it.packet) })
        )
        chat("&aStarting /psr playback for &e$roomName&a: &e${packets.size}&a packet${plural(packets.size)} (&b$label&a).")
    }

    private fun flushPlayback() {
        val session = playback ?: return
        val connection = mc.connection?.connection ?: run {
            stopPlayback("&cStopped /psr playback: no server connection.")
            return
        }

        if (session.queue.isEmpty()) {
            stopPlayback("&aFinished /psr playback for &e${session.roomName}&a.")
            return
        }

        session.delayRemaining--
        if (session.delayRemaining > 0) return

        val sends = if (onePacketPerTick.value) 1 else maxPacketsPerTick.value
        var sent = 0
        replaying = true
        try {
            while (sent < sends && session.queue.isNotEmpty()) {
                val frame = session.queue.removeFirst()
                val packet = snapshotToPacket(frame.packet)
                if (packet == null) {
                    fail("Skipped unsupported recorded packet type ${frame.packet.type}.")
                } else {
                    connection.send(packet)
                    sent++
                }

                if (preserveRecordedTickDelay.value && session.queue.isNotEmpty()) {
                    session.delayRemaining = session.queue.first().delayTicks.coerceAtLeast(1)
                    break
                }
            }
        } finally {
            replaying = false
        }
    }

    private fun packetToSnapshot(packet: Packet<*>): PacketSnapshot? {
        val player = mc.player
        return when (packet) {
            is ServerboundSetCarriedItemPacket -> PacketSnapshot(
                type = PacketType.SET_CARRIED_ITEM,
                slot = packet.slot
            )

            is ServerboundPlayerCommandPacket -> PacketSnapshot(
                type = PacketType.PLAYER_COMMAND,
                entityId = packet.id,
                action = packet.action.name,
                data = packet.data
            )

            is ServerboundMovePlayerPacket -> {
                if (!includeMovementPackets.value) return null
                PacketSnapshot(
                    type = when {
                        packet.hasPosition() && packet.hasRotation() -> PacketType.MOVE_POS_ROT
                        packet.hasPosition() -> PacketType.MOVE_POS
                        packet.hasRotation() -> PacketType.MOVE_ROT
                        else -> PacketType.MOVE_STATUS_ONLY
                    },
                    x = packet.getX(player?.x ?: 0.0),
                    y = packet.getY(player?.y ?: 0.0),
                    z = packet.getZ(player?.z ?: 0.0),
                    yaw = packet.getYRot(player?.yRot ?: 0f),
                    pitch = packet.getXRot(player?.xRot ?: 0f),
                    onGround = packet.isOnGround,
                    horizontalCollision = packet.horizontalCollision()
                )
            }

            is ServerboundUseItemOnPacket -> {
                val hit = packet.hitResult
                PacketSnapshot(
                    type = PacketType.USE_ITEM_ON,
                    hand = packet.hand.name,
                    sequence = packet.sequence,
                    blockX = hit.blockPos.x,
                    blockY = hit.blockPos.y,
                    blockZ = hit.blockPos.z,
                    face = hit.direction.name,
                    hitX = hit.location.x,
                    hitY = hit.location.y,
                    hitZ = hit.location.z,
                    insideBlock = hit.isInside,
                    worldBorderHit = hit.isWorldBorderHit
                )
            }

            is ServerboundUseItemPacket -> PacketSnapshot(
                type = PacketType.USE_ITEM,
                hand = packet.hand.name,
                sequence = packet.sequence,
                yaw = packet.yRot,
                pitch = packet.xRot
            )

            is ServerboundSwingPacket -> PacketSnapshot(
                type = PacketType.SWING,
                hand = packet.hand.name
            )

            else -> null
        }
    }

    private fun snapshotToPacket(snapshot: PacketSnapshot): Packet<*>? {
        return when (snapshot.type) {
            PacketType.SET_CARRIED_ITEM -> ServerboundSetCarriedItemPacket(snapshot.slot ?: return null)
            PacketType.PLAYER_COMMAND -> {
                val entity = entityById(snapshot.entityId) ?: mc.player ?: return null
                val action = runCatching {
                    ServerboundPlayerCommandPacket.Action.valueOf(snapshot.action ?: return null)
                }.getOrNull() ?: return null
                ServerboundPlayerCommandPacket(entity, action, snapshot.data ?: 0)
            }
            PacketType.MOVE_ROT -> ServerboundMovePlayerPacket.Rot(
                snapshot.yaw ?: return null,
                snapshot.pitch ?: return null,
                snapshot.onGround ?: false,
                snapshot.horizontalCollision ?: false
            )
            PacketType.MOVE_POS -> ServerboundMovePlayerPacket.Pos(
                snapshot.x ?: return null,
                snapshot.y ?: return null,
                snapshot.z ?: return null,
                snapshot.onGround ?: false,
                snapshot.horizontalCollision ?: false
            )
            PacketType.MOVE_POS_ROT -> ServerboundMovePlayerPacket.PosRot(
                snapshot.x ?: return null,
                snapshot.y ?: return null,
                snapshot.z ?: return null,
                snapshot.yaw ?: return null,
                snapshot.pitch ?: return null,
                snapshot.onGround ?: false,
                snapshot.horizontalCollision ?: false
            )
            PacketType.MOVE_STATUS_ONLY -> ServerboundMovePlayerPacket.StatusOnly(
                snapshot.onGround ?: false,
                snapshot.horizontalCollision ?: false
            )
            PacketType.USE_ITEM_ON -> ServerboundUseItemOnPacket(
                parseHand(snapshot.hand),
                BlockHitResult(
                    Vec3(snapshot.hitX ?: return null, snapshot.hitY ?: return null, snapshot.hitZ ?: return null),
                    parseDirection(snapshot.face),
                    BlockPos(snapshot.blockX ?: return null, snapshot.blockY ?: return null, snapshot.blockZ ?: return null),
                    snapshot.insideBlock ?: false,
                    snapshot.worldBorderHit ?: false
                ),
                snapshot.sequence ?: 0
            )
            PacketType.USE_ITEM -> ServerboundUseItemPacket(
                parseHand(snapshot.hand),
                snapshot.sequence ?: 0,
                snapshot.yaw ?: mc.player?.yRot ?: 0f,
                snapshot.pitch ?: mc.player?.xRot ?: 0f
            )
            PacketType.SWING -> ServerboundSwingPacket(parseHand(snapshot.hand))
        }
    }

    private fun entityById(id: Int?): Entity? {
        if (id == null) return null
        return mc.level?.getEntity(id)
    }

    private fun currentRoomName(): String? {
        return resolveCurrentRoom()?.name?.takeUnless { it == "Unknown" }
    }

    private fun resolveCurrentRoom(): UniqueRoom? {
        val playerRoom = mc.player?.position()?.let(ScanUtils::getRoomFromPos)
        return playerRoom ?: ScanUtils.currentRoom
    }

    private fun loadConfig() {
        if (!file.exists()) {
            routesFile = PacketRoutesFile()
            return
        }

        runCatching {
            FileReader(file).use { reader ->
                routesFile = JsonUtils.gsonBuilder.fromJson(reader, PacketRoutesFile::class.java)
                    ?: PacketRoutesFile()
            }
        }.onFailure {
            routesFile = PacketRoutesFile()
            NoammAddons.logger.error("PacketSecretRoutes failed to load ${file.path}", it)
            fail("Failed to load packetSecretRoutes.json. Check latest.log.")
        }
    }

    private fun saveConfig() {
        runCatching {
            file.parentFile?.mkdirs()
            FileWriter(file).use { writer ->
                JsonUtils.gsonBuilder.toJson(routesFile, writer)
            }
        }.onFailure {
            NoammAddons.logger.error("PacketSecretRoutes failed to save ${file.path}", it)
            fail("Failed to save packetSecretRoutes.json. Check latest.log.")
        }
    }

    private fun clipsFor(route: PacketRoomRoute, kind: RecordingKind): MutableList<PacketClip> {
        return when (kind) {
            RecordingKind.START_LINK -> route.startLinks
            RecordingKind.DOORWAY_LINK -> route.doorwayLinks
            RecordingKind.END_LINK -> route.endLinks
            RecordingKind.MAIN -> error("Main route is not a clip list.")
        }
    }

    private fun parseHand(hand: String?): InteractionHand {
        return when (hand?.uppercase()) {
            "OFF_HAND", "OFF", "OFFHAND" -> InteractionHand.OFF_HAND
            else -> InteractionHand.MAIN_HAND
        }
    }

    private fun parseDirection(face: String?): Direction {
        return Direction.byName(face?.lowercase() ?: "") ?: Direction.UP
    }

    private fun chat(message: String) {
        ChatUtils.chat("$PREFIX $message")
    }

    private fun fail(message: String) {
        chat("&c$message")
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    private enum class RecordingKind(val label: String) {
        MAIN("main route"),
        START_LINK("start link"),
        DOORWAY_LINK("doorway link"),
        END_LINK("end link")
    }

    private enum class PacketType {
        SET_CARRIED_ITEM,
        PLAYER_COMMAND,
        MOVE_POS,
        MOVE_ROT,
        MOVE_POS_ROT,
        MOVE_STATUS_ONLY,
        USE_ITEM_ON,
        USE_ITEM,
        SWING
    }

    private data class RecordingSession(
        val roomName: String,
        val kind: RecordingKind,
        var lastPacketTick: Long,
        val packets: MutableList<PacketFrame> = mutableListOf(),
        val basePacketCount: Int = 0
    )

    private data class PlaybackSession(
        val roomName: String,
        val label: String,
        val queue: ArrayDeque<PlaybackFrame>,
        var delayRemaining: Int = 0
    )

    private data class PlaybackFrame(
        val delayTicks: Int,
        val packet: PacketSnapshot
    )

    private data class PacketRoutesFile(
        val routes: MutableMap<String, PacketRoomRoute> = mutableMapOf()
    )

    private data class PacketRoomRoute(
        val main: MutableList<PacketFrame> = mutableListOf(),
        val startLinks: MutableList<PacketClip> = mutableListOf(),
        val doorwayLinks: MutableList<PacketClip> = mutableListOf(),
        val endLinks: MutableList<PacketClip> = mutableListOf()
    )

    private data class PacketClip(
        val packets: MutableList<PacketFrame> = mutableListOf()
    )

    private data class PacketFrame(
        val delayTicks: Int = 0,
        val packet: PacketSnapshot = PacketSnapshot(PacketType.USE_ITEM)
    )

    private data class PacketSnapshot(
        val type: PacketType,
        val slot: Int? = null,
        val entityId: Int? = null,
        val action: String? = null,
        val data: Int? = null,
        val x: Double? = null,
        val y: Double? = null,
        val z: Double? = null,
        val yaw: Float? = null,
        val pitch: Float? = null,
        val onGround: Boolean? = null,
        val horizontalCollision: Boolean? = null,
        val hand: String? = null,
        val sequence: Int? = null,
        val blockX: Int? = null,
        val blockY: Int? = null,
        val blockZ: Int? = null,
        val face: String? = null,
        val hitX: Double? = null,
        val hitY: Double? = null,
        val hitZ: Double? = null,
        val insideBlock: Boolean? = null,
        val worldBorderHit: Boolean? = null
    )
}
