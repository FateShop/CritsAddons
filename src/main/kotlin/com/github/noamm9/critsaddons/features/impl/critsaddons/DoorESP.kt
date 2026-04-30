package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ColorSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.dungeons.map.DungeonInfo
import com.github.noamm9.utils.dungeons.map.core.Door
import com.github.noamm9.utils.dungeons.map.core.DoorType
import com.github.noamm9.utils.dungeons.map.core.Room
import com.github.noamm9.utils.dungeons.map.core.Tile
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render3D
import net.minecraft.core.BlockPos
import java.awt.Color

object DoorESP : Feature(
    name = "Door ESP",
    description = "Highlights the blocks around dungeon doorways instead of the door block itself."
) {
    private const val GRID_STEP = 16
    private const val DOOR_FRAME_BASE_Y = 69
    private const val DOOR_FRAME_TOP_Y = 72
    private const val DOOR_HALF_WIDTH = 2

    private val renderSection = "render"

    private val renderThroughWalls by ToggleSetting("Render Through Walls", true)
        .section(renderSection)
        .withDescription("Renders doorway ESP through walls.")

    private val witherDoorColor by ColorSetting("Wither Door Color", Color(64, 255, 64, 170), true)
        .section(renderSection)
        .withDescription("Color used for wither/addon doors.")

    private val bloodDoorColor by ColorSetting("Blood Door Color", Color(255, 64, 64, 170), true)
        .section(renderSection)
        .withDescription("Color used for blood doors.")

    private val normalDoorColor by ColorSetting("Other Door Color", Color(255, 255, 255, 255), true)
        .section(renderSection)
        .withDescription("Opaque white by default for normal mapped doorways.")

    override fun init() {
        register<RenderWorldEvent> {
            if (!enabled || !LocationUtils.inDungeon || LocationUtils.inBoss) return@register

            val tileLookup = DungeonInfo.dungeonList
                .associateBy { it.x to it.z }

            DungeonInfo.dungeonList
                .filterIsInstance<Door>()
                .forEach { door ->
                    val frameBlocks = doorwayFrameBlocks(door, tileLookup)
                    val color = colorForDoor(door)
                    frameBlocks.forEach { pos ->
                        Render3D.renderBlock(
                            event.ctx,
                            pos,
                            color,
                            phase = renderThroughWalls.value
                        )
                    }
                }
        }
    }

    private fun colorForDoor(door: Door): Color {
        return when (door.type) {
            DoorType.WITHER -> witherDoorColor.value
            DoorType.BLOOD -> bloodDoorColor.value
            else -> normalDoorColor.value
        }
    }

    private fun doorwayFrameBlocks(
        door: Door,
        tileLookup: Map<Pair<Int, Int>, Tile>
    ): List<BlockPos> {
        val hasRoomsX = isRoomTile(tileLookup[door.x - GRID_STEP to door.z]) || isRoomTile(tileLookup[door.x + GRID_STEP to door.z])
        val hasRoomsZ = isRoomTile(tileLookup[door.x to door.z - GRID_STEP]) || isRoomTile(tileLookup[door.x to door.z + GRID_STEP])

        return when {
            hasRoomsX && !hasRoomsZ -> outlineBlocksXPlane(door.x, door.z)
            hasRoomsZ && !hasRoomsX -> outlineBlocksZPlane(door.x, door.z)
            else -> outlineBlocksXPlane(door.x, door.z) + outlineBlocksZPlane(door.x, door.z)
        }.distinct()
    }

    private fun isRoomTile(tile: Tile?): Boolean = tile is Room

    private fun outlineBlocksXPlane(centerX: Int, centerZ: Int): List<BlockPos> {
        val blocks = mutableListOf<BlockPos>()
        for (y in DOOR_FRAME_BASE_Y..DOOR_FRAME_TOP_Y) {
            for (zOffset in -DOOR_HALF_WIDTH..DOOR_HALF_WIDTH) {
                val isPerimeter =
                    y == DOOR_FRAME_BASE_Y ||
                    y == DOOR_FRAME_TOP_Y ||
                    zOffset == -DOOR_HALF_WIDTH ||
                    zOffset == DOOR_HALF_WIDTH
                if (!isPerimeter) continue
                blocks.add(BlockPos(centerX, y, centerZ + zOffset))
            }
        }
        return blocks
    }

    private fun outlineBlocksZPlane(centerX: Int, centerZ: Int): List<BlockPos> {
        val blocks = mutableListOf<BlockPos>()
        for (y in DOOR_FRAME_BASE_Y..DOOR_FRAME_TOP_Y) {
            for (xOffset in -DOOR_HALF_WIDTH..DOOR_HALF_WIDTH) {
                val isPerimeter =
                    y == DOOR_FRAME_BASE_Y ||
                    y == DOOR_FRAME_TOP_Y ||
                    xOffset == -DOOR_HALF_WIDTH ||
                    xOffset == DOOR_HALF_WIDTH
                if (!isPerimeter) continue
                blocks.add(BlockPos(centerX + xOffset, y, centerZ))
            }
        }
        return blocks
    }
}
