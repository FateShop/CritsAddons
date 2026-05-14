package com.github.noamm9.critsaddons.commands.impl

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.commands.BaseCommand
import com.github.noamm9.commands.CommandNodeBuilder
import com.github.noamm9.utils.ChatUtils
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.util.concurrent.ConcurrentLinkedQueue

object SendPacketCommand : BaseCommand("sp") {
    private const val PREFIX = "&8[&bSP&8]"
    private val queuedPackets = ConcurrentLinkedQueue<QueuedPacket>()

    init {
        ClientTickEvents.START_CLIENT_TICK.register {
            flushQueuedPacket()
        }
    }

    override fun CommandNodeBuilder.build() {
        literal("use") {
            argument("hand", StringArgumentType.word()) {
                runs { ctx ->
                    sendUseItem(parseHand(StringArgumentType.getString(ctx, "hand")))
                }
            }
            runs {
                sendUseItem(InteractionHand.MAIN_HAND)
            }
        }

        literal("rightclick") {
            literal("target") {
                runs {
                    sendTargetRightClick()
                }
            }
            argument("contents", StringArgumentType.greedyString()) {
                runs { ctx ->
                    sendManualRightClick(StringArgumentType.getString(ctx, "contents"))
                }
            }
            runs {
                chatUsage()
            }
        }

        literal("interact") {
            argument("entityId", IntegerArgumentType.integer()) {
                argument("hand", StringArgumentType.word()) {
                    runs { ctx ->
                        sendInteract(
                            IntegerArgumentType.getInteger(ctx, "entityId"),
                            parseHand(StringArgumentType.getString(ctx, "hand")),
                            secondary = false
                        )
                    }
                }
                runs { ctx ->
                    sendInteract(
                        IntegerArgumentType.getInteger(ctx, "entityId"),
                        InteractionHand.MAIN_HAND,
                        secondary = false
                    )
                }
            }
        }

        literal("attack") {
            argument("entityId", IntegerArgumentType.integer()) {
                runs { ctx ->
                    sendAttack(IntegerArgumentType.getInteger(ctx, "entityId"))
                }
            }
        }

        runs {
            chatUsage()
        }
    }

    private fun sendUseItem(hand: InteractionHand) {
        val player = mc.player ?: return fail("No player.")
        sendPacket(
            ServerboundUseItemPacket(hand, 0, player.yRot, player.xRot),
            "ServerboundUseItemPacket hand=${hand.name} yaw=${player.yRot} pitch=${player.xRot}"
        )
    }

    private fun sendTargetRightClick() {
        val hit = mc.hitResult
        if (hit !is BlockHitResult || hit.type == HitResult.Type.MISS) {
            fail("You are not targeting a block.")
            return
        }

        sendPacket(
            ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0),
            "ServerboundUseItemOnPacket target=${formatBlockHit(hit)}"
        )
    }

    private fun sendManualRightClick(contents: String) {
        val parsed = parseRightClick(contents) ?: return
        val hit = BlockHitResult(parsed.location, parsed.face, parsed.pos, parsed.insideBlock)
        sendPacket(
            ServerboundUseItemOnPacket(parsed.hand, hit, 0),
            "ServerboundUseItemOnPacket hand=${parsed.hand.name} target=${formatBlockHit(hit)}"
        )
    }

    private fun sendInteract(entityId: Int, hand: InteractionHand, secondary: Boolean) {
        val entity = mc.level?.getEntity(entityId)
        if (entity == null) {
            fail("No loaded entity with id $entityId.")
            return
        }

        sendPacket(
            ServerboundInteractPacket.createInteractionPacket(entity, secondary, hand),
            "ServerboundInteractPacket interact entityId=$entityId hand=${hand.name}"
        )
    }

    private fun sendAttack(entityId: Int) {
        val entity = mc.level?.getEntity(entityId)
        if (entity == null) {
            fail("No loaded entity with id $entityId.")
            return
        }

        sendPacket(
            ServerboundInteractPacket.createAttackPacket(entity, mc.player?.isShiftKeyDown == true),
            "ServerboundInteractPacket attack entityId=$entityId"
        )
    }

    private fun sendPacket(packet: Packet<*>, description: String) {
        if (mc.connection?.connection == null) {
            fail("No server connection.")
            return
        }

        queuedPackets.add(QueuedPacket(packet, description))
        ChatUtils.chat("$PREFIX &eQueued for next tick start &7$description")
    }

    private fun flushQueuedPacket() {
        val queued = queuedPackets.poll() ?: return
        val connection = mc.connection?.connection
        if (connection == null) {
            fail("Dropped queued packet because there is no server connection.")
            return
        }

        connection.send(queued.packet)
        ChatUtils.chat("$PREFIX &aSent on tick start &7${queued.description}")
    }

    private fun parseRightClick(contents: String): RightClickPacketData? {
        val tokens = contents.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 4) {
            chatUsage()
            return null
        }

        val values = tokens
            .filter { it.contains('=') }
            .mapNotNull {
                val key = it.substringBefore('=').lowercase()
                val value = it.substringAfter('=', "")
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            .toMap()

        return runCatching {
            val pos = parseBlockPos(values, tokens)
            val face = parseDirection(values["face"] ?: values["side"] ?: tokens.getOrNull(3))
            val hand = parseHand(values["hand"] ?: tokens.getOrNull(4) ?: "main")
            val cursor = parseCursor(values, tokens, face)
            val inside = values["inside"]?.toBooleanStrictOrNull()
                ?: values["insideblock"]?.toBooleanStrictOrNull()
                ?: false

            RightClickPacketData(
                pos = pos,
                face = face,
                hand = hand,
                location = Vec3(pos.x + cursor.x, pos.y + cursor.y, pos.z + cursor.z),
                insideBlock = inside
            )
        }.getOrElse {
            fail("Could not parse rightclick packet contents.")
            chatUsage()
            null
        }
    }

    private fun parseBlockPos(values: Map<String, String>, tokens: List<String>): BlockPos {
        values["pos"]?.let {
            val parts = it.split(',')
            require(parts.size == 3)
            return BlockPos(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }

        val x = values["x"]?.toInt() ?: tokens.getOrNull(0)?.toInt()
        val y = values["y"]?.toInt() ?: tokens.getOrNull(1)?.toInt()
        val z = values["z"]?.toInt() ?: tokens.getOrNull(2)?.toInt()
        require(x != null && y != null && z != null)
        return BlockPos(x, y, z)
    }

    private fun parseCursor(values: Map<String, String>, tokens: List<String>, face: Direction): Vec3 {
        (values["cursor"] ?: values["hit"] ?: values["hitvec"])?.let {
            val parts = it.split(',')
            require(parts.size == 3)
            return Vec3(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble())
        }

        val explicitX = values["cursorx"]?.toDouble() ?: tokens.getOrNull(5)?.toDoubleOrNull()
        val explicitY = values["cursory"]?.toDouble() ?: tokens.getOrNull(6)?.toDoubleOrNull()
        val explicitZ = values["cursorz"]?.toDouble() ?: tokens.getOrNull(7)?.toDoubleOrNull()
        if (explicitX != null && explicitY != null && explicitZ != null) {
            return Vec3(explicitX, explicitY, explicitZ)
        }

        return when (face) {
            Direction.DOWN -> Vec3(0.5, 0.0, 0.5)
            Direction.UP -> Vec3(0.5, 1.0, 0.5)
            Direction.NORTH -> Vec3(0.5, 0.5, 0.0)
            Direction.SOUTH -> Vec3(0.5, 0.5, 1.0)
            Direction.WEST -> Vec3(0.0, 0.5, 0.5)
            Direction.EAST -> Vec3(1.0, 0.5, 0.5)
        }
    }

    private fun parseDirection(value: String?): Direction {
        return Direction.byName(value?.lowercase() ?: "") ?: error("Invalid direction")
    }

    private fun parseHand(value: String?): InteractionHand {
        return when (value?.lowercase()) {
            "off", "offhand", "off_hand" -> InteractionHand.OFF_HAND
            else -> InteractionHand.MAIN_HAND
        }
    }

    private fun formatBlockHit(hit: BlockHitResult): String {
        val pos = hit.blockPos
        val loc = hit.location
        return "${pos.x},${pos.y},${pos.z} face=${hit.direction.name.lowercase()} hit=${"%.3f".format(loc.x)},${"%.3f".format(loc.y)},${"%.3f".format(loc.z)}"
    }

    private fun chatUsage() {
        ChatUtils.chat("$PREFIX &7/sp use [main|off]")
        ChatUtils.chat("$PREFIX &7/sp rightclick target")
        ChatUtils.chat("$PREFIX &7/sp rightclick <x> <y> <z> <face> [main|off] [cursorX cursorY cursorZ]")
        ChatUtils.chat("$PREFIX &7/sp rightclick pos=90,68,-56 face=up hand=main cursor=0.5,1,0.5")
        ChatUtils.chat("$PREFIX &7/sp interact <entityId> [main|off] &8or &7/sp attack <entityId>")
    }

    private fun fail(message: String) {
        ChatUtils.chat("$PREFIX &c$message")
    }

    private data class RightClickPacketData(
        val pos: BlockPos,
        val face: Direction,
        val hand: InteractionHand,
        val location: Vec3,
        val insideBlock: Boolean
    )

    private data class QueuedPacket(
        val packet: Packet<*>,
        val description: String
    )
}
