package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.utils.dungeons.map.core.Door
import com.github.noamm9.utils.dungeons.map.core.DoorType
import com.github.noamm9.utils.dungeons.map.core.Room
import com.github.noamm9.utils.render.Render2D
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.awt.Color
import kotlin.math.roundToInt

class DungeonsTPMapScreen : Screen(Component.literal("Dungeons TP Map")) {
    private data class Layout(
        val panelX: Int,
        val panelY: Int,
        val panelWidth: Int,
        val panelHeight: Int,
        val mapX: Int,
        val mapY: Int,
        val cellSize: Int
    )

    private data class RoomBounds(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

    private val panelColor = Color(18, 18, 24, 225)
    private val borderColor = Color(70, 70, 78, 255)
    private val roomReadyColor = Color(48, 112, 72, 220)
    private val roomBlockedColor = Color(112, 52, 52, 220)
    private val currentRoomColor = Color(54, 132, 168, 220)
    private val activeTargetColor = Color(170, 146, 54, 230)
    private val witherDoorColor = Color(72, 200, 88, 255)
    private val bloodDoorColor = Color(210, 72, 72, 255)
    private val normalDoorColor = Color(235, 235, 235, 255)
    private val emptyColor = Color(34, 34, 40, 180)
    private val roomNameBgColor = Color(8, 8, 12, 160)
    private val previewPathColor = Color(102, 235, 255, 210)
    private val previewPathShadowColor = Color(14, 28, 34, 175)
    private val previewHopDotColor = Color(255, 212, 92, 255)
    private val previewHopDotBorderColor = Color(255, 255, 255, 235)
    private val completeActiveColor = Color(240, 240, 240, 235)
    private val textColor = 0xFFFFFFFF.toInt()
    private val mutedTextColor = 0xFFD8D8D8.toInt()
    private val legendTextColor = 0xFFF2F2F2.toInt()

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(context, mouseX, mouseY, partialTick)
        val snapshot = DungeonsTPMap.currentSnapshot()
        val layout = buildLayout()

        Render2D.drawRect(context, layout.panelX, layout.panelY, layout.panelWidth, layout.panelHeight, panelColor)
        Render2D.drawBorder(context, layout.panelX, layout.panelY, layout.panelWidth, layout.panelHeight, borderColor)

        context.drawString(font, Component.literal("Dungeons TP Map"), layout.panelX + 10, layout.panelY + 10, textColor, true)
        val currentRoomLabel = snapshot.currentRoom?.name ?: "Unknown"
        context.drawString(font, Component.literal("Current: $currentRoomLabel"), layout.panelX + 10, layout.panelY + 26, mutedTextColor, false)
        val statusText = when {
            snapshot.activeCompleteRoomName != null -> {
                val progress = snapshot.completeProgressPercent?.let { " $it%" }.orEmpty()
                "Complete SR: ${snapshot.activeCompleteRoomName}$progress"
            }
            snapshot.isTraveling -> "Traveling: ${snapshot.activeTargetRoomName ?: "Unknown"}"
            else -> "Click a completed room to route to it"
        }
        context.drawString(font, Component.literal(statusText), layout.panelX + 10, layout.panelY + 38, mutedTextColor, false)

        drawMap(context, mouseX, mouseY, layout, snapshot)
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() != 0) return super.mouseClicked(click, doubled)

        val snapshot = DungeonsTPMap.currentSnapshot()
        val layout = buildLayout()
        val room = findClickedRoom(click.x(), click.y(), layout, snapshot) ?: return super.mouseClicked(click, doubled)
        if (isShiftClick(click)) {
            DungeonsTPMap.beginCompleteSecretRoute(room)
            onClose()
            return true
        }

        DungeonsTPMap.beginTravel(room)
        onClose()
        return true
    }

    private fun drawMap(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        layout: Layout,
        snapshot: DungeonsTPMap.MapSnapshot
    ) {
        val roomBoundsByName = linkedMapOf<String, RoomBounds>()
        val hoveredCoord = hoveredCoord(mouseX, mouseY, layout)
        val preview = hoveredCoord
            ?.takeIf { DungeonsTPMap.shouldRenderPathPreview() }
            ?.let { DungeonsTPMap.previewForHovered(it.first, it.second) }

        Render2D.drawRect(
            context,
            layout.mapX - 2,
            layout.mapY - 2,
            layout.cellSize * 11 + 4,
            layout.cellSize * 11 + 4,
            Color(10, 10, 14, 140)
        )

        for (gridZ in 0..10) {
            for (gridX in 0..10) {
                val tile = snapshot.tiles[gridX to gridZ]
                val x = layout.mapX + gridX * layout.cellSize
                val y = layout.mapY + gridZ * layout.cellSize
                val hovered = mouseX in x until x + layout.cellSize && mouseY in y until y + layout.cellSize

                val fillColor = when (tile) {
                    is Room -> {
                        val room = tile.uniqueRoom
                        when {
                            room == null -> emptyColor
                            room.name == snapshot.currentRoom?.name -> currentRoomColor
                            room.name == snapshot.activeCompleteRoomName -> completeActiveColor
                            room.name == snapshot.activeTargetRoomName -> activeTargetColor
                            room.name in snapshot.readyRoomNames -> roomReadyColor
                            else -> roomBlockedColor
                        }
                    }

                    is Door -> colorForDoor(tile)
                    else -> emptyColor
                }

                Render2D.drawRect(context, x, y, layout.cellSize, layout.cellSize, fillColor)
                if (hovered) {
                    Render2D.drawBorder(context, x, y, layout.cellSize, layout.cellSize, Color.WHITE, 1)
                }

                if (tile is Room) {
                    val room = tile.uniqueRoom ?: continue
                    val existing = roomBoundsByName[room.name]
                    val bounds = if (existing == null) {
                        RoomBounds(x, y, x + layout.cellSize, y + layout.cellSize)
                    } else {
                        RoomBounds(
                            minX = minOf(existing.minX, x),
                            minY = minOf(existing.minY, y),
                            maxX = maxOf(existing.maxX, x + layout.cellSize),
                            maxY = maxOf(existing.maxY, y + layout.cellSize)
                        )
                    }
                    roomBoundsByName[room.name] = bounds
                }
            }
        }

        if (preview != null) {
            drawPathPreview(context, layout, preview)
        }

        roomBoundsByName.forEach { (roomName, bounds) ->
            val text = roomLabel(roomName, (bounds.maxX - bounds.minX) - 8)
            if (text.isBlank()) return@forEach

            val textWidth = font.width(text)
            val textX = bounds.minX + ((bounds.maxX - bounds.minX) - textWidth) / 2
            val textY = bounds.minY + ((bounds.maxY - bounds.minY) - font.lineHeight) / 2

            Render2D.drawRect(
                context,
                textX - 2,
                textY - 1,
                textWidth + 4,
                font.lineHeight + 2,
                roomNameBgColor
            )
            context.drawString(font, Component.literal(text), textX, textY, textColor, true)
        }

        if (preview != null) {
            drawPreviewDots(context, layout, preview)
        }

        val legendY = layout.mapY + layout.cellSize * 11 + 12
        drawLegend(context, layout.panelX + 10, legendY, "Current", currentRoomColor)
        drawLegend(context, layout.panelX + 98, legendY, "Ready", roomReadyColor)
        drawLegend(context, layout.panelX + 178, legendY, "Blocked", roomBlockedColor)
        drawLegend(context, layout.panelX + 270, legendY, "Door", normalDoorColor)
        drawLegend(context, layout.panelX + 346, legendY, "Target", activeTargetColor)

        var infoY = legendY + 20
        preview?.let {
            context.drawString(
                font,
                Component.literal("Preview: ${it.label}"),
                layout.panelX + 10,
                infoY,
                mutedTextColor,
                false
            )
            infoY += 12
        }

        snapshot.activeCompleteRoomName?.let { active ->
            val progress = snapshot.completeProgressPercent?.let { "$it%" } ?: "0%"
            val detail = snapshot.completeProgressText?.let { " ($it)" }.orEmpty()
            context.drawString(
                font,
                Component.literal("Complete SR: $active $progress$detail"),
                layout.panelX + 10,
                infoY,
                textColor,
                true
            )
        }
    }

    private fun drawLegend(context: GuiGraphics, x: Int, y: Int, label: String, color: Color) {
        Render2D.drawRect(context, x, y, 12, 12, color)
        context.drawString(font, Component.literal(label), x + 18, y + 2, legendTextColor, true)
    }

    private fun colorForDoor(door: Door): Color {
        return when (door.type) {
            DoorType.WITHER -> witherDoorColor
            DoorType.BLOOD -> bloodDoorColor
            else -> normalDoorColor
        }
    }

    private fun findClickedRoom(
        mouseX: Double,
        mouseY: Double,
        layout: Layout,
        snapshot: DungeonsTPMap.MapSnapshot
    ): com.github.noamm9.utils.dungeons.map.core.UniqueRoom? {
        for (gridZ in 0..10) {
            for (gridX in 0..10) {
                val x = layout.mapX + gridX * layout.cellSize
                val y = layout.mapY + gridZ * layout.cellSize
                if (mouseX !in x.toDouble()..<(x + layout.cellSize).toDouble()) continue
                if (mouseY !in y.toDouble()..<(y + layout.cellSize).toDouble()) continue
                val room = (snapshot.tiles[gridX to gridZ] as? Room)?.uniqueRoom
                if (room != null) return room
            }
        }
        return null
    }

    private fun hoveredCoord(mouseX: Int, mouseY: Int, layout: Layout): Pair<Int, Int>? {
        for (gridZ in 0..10) {
            for (gridX in 0..10) {
                val x = layout.mapX + gridX * layout.cellSize
                val y = layout.mapY + gridZ * layout.cellSize
                if (mouseX !in x until x + layout.cellSize) continue
                if (mouseY !in y until y + layout.cellSize) continue
                return gridX to gridZ
            }
        }
        return null
    }

    private fun drawPathPreview(context: GuiGraphics, layout: Layout, preview: DungeonsTPMap.PathPreview) {
        val centers = preview.tilePath.map { cellCenter(layout, it.first, it.second) }
        centers.zipWithNext().forEach { (from, to) ->
            drawPreviewLine(context, from.first, from.second, to.first, to.second, previewPathShadowColor, 5)
            drawPreviewLine(context, from.first, from.second, to.first, to.second, previewPathColor, 3)
        }
    }

    private fun drawPreviewDots(context: GuiGraphics, layout: Layout, preview: DungeonsTPMap.PathPreview) {
        preview.hopCoords
            .distinct()
            .forEach { (gridX, gridZ) ->
                val (centerX, centerY) = cellCenter(layout, gridX, gridZ)
                val outerRadius = maxOf(4, layout.cellSize / 6)
                val innerRadius = maxOf(2, outerRadius - 2)
                Render2D.drawRect(
                    context,
                    centerX - outerRadius,
                    centerY - outerRadius,
                    outerRadius * 2,
                    outerRadius * 2,
                    previewHopDotBorderColor
                )
                Render2D.drawRect(
                    context,
                    centerX - innerRadius,
                    centerY - innerRadius,
                    innerRadius * 2,
                    innerRadius * 2,
                    previewHopDotColor
                )
            }
    }

    private fun drawPreviewLine(
        context: GuiGraphics,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        color: Color,
        thickness: Int
    ) {
        val dx = (endX - startX).toFloat()
        val dy = (endY - startY).toFloat()
        val steps = maxOf(kotlin.math.abs(endX - startX), kotlin.math.abs(endY - startY), 1)
        for (step in 0..steps) {
            val progress = step / steps.toFloat()
            val x = (startX + dx * progress).roundToInt()
            val y = (startY + dy * progress).roundToInt()
            Render2D.drawRect(
                context,
                x - thickness / 2,
                y - thickness / 2,
                thickness,
                thickness,
                color
            )
        }
    }

    private fun cellCenter(layout: Layout, gridX: Int, gridZ: Int): Pair<Int, Int> {
        return (
            layout.mapX + gridX * layout.cellSize + layout.cellSize / 2
            ) to (
            layout.mapY + gridZ * layout.cellSize + layout.cellSize / 2
            )
    }

    private fun buildLayout(): Layout {
        val panelWidth = minOf(width - 20, 500)
        val cellSize = ((panelWidth - 40) / 11).coerceAtMost(36).coerceAtLeast(22)
        val mapSize = cellSize * 11
        val panelHeight = mapSize + 116
        val panelX = width / 2 - panelWidth / 2
        val panelY = height / 2 - panelHeight / 2
        val mapX = panelX + (panelWidth - mapSize) / 2
        val mapY = panelY + 56
        return Layout(panelX, panelY, panelWidth, panelHeight, mapX, mapY, cellSize)
    }

    private fun roomLabel(roomName: String, maxWidth: Int): String {
        if (font.width(roomName) <= maxWidth) return roomName

        val initials = roomName
            .split(' ', '-', '_')
            .filter { it.isNotBlank() }
            .joinToString("") { it.first().uppercase() }
        if (initials.isNotBlank() && font.width(initials) <= maxWidth) return initials

        var trimmed = roomName
        while (trimmed.isNotEmpty() && font.width("$trimmed...") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }

        return when {
            trimmed.isNotEmpty() -> "$trimmed..."
            roomName.length >= 3 && font.width(roomName.take(3)) <= maxWidth -> roomName.take(3)
            roomName.length >= 2 && font.width(roomName.take(2)) <= maxWidth -> roomName.take(2)
            else -> ""
        }
    }

    private fun isShiftClick(click: MouseButtonEvent): Boolean =
        click.modifiers() and GLFW.GLFW_MOD_SHIFT != 0

    override fun isPauseScreen(): Boolean = false
    override fun shouldCloseOnEsc(): Boolean = true
}
