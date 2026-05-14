package com.github.noamm9.critsaddons.commands.impl

import com.github.noamm9.commands.BaseCommand
import com.github.noamm9.commands.CommandNodeBuilder
import com.github.noamm9.critsaddons.features.impl.critsaddons.PacketSecretRoutes
import com.mojang.brigadier.arguments.IntegerArgumentType

object PacketSecretRouteCommand : BaseCommand("psr") {
    override fun CommandNodeBuilder.build() {
        literal("save") {
            runs {
                PacketSecretRoutes.saveRecording()
            }
        }

        literal("cancel") {
            runs {
                PacketSecretRoutes.cancelRecording()
            }
        }

        literal("continue") {
            runs {
                PacketSecretRoutes.continueRecording()
            }
        }

        literal("play") {
            runs {
                PacketSecretRoutes.playCurrentRoom()
            }
        }

        literal("stop") {
            runs {
                PacketSecretRoutes.stopPlayback("&eStopped /psr playback.")
            }
        }

        literal("list") {
            runs {
                PacketSecretRoutes.listCurrentRoom()
            }
        }

        literal("start") {
            literal("delete") {
                runs {
                    PacketSecretRoutes.deleteLastStartLink()
                }
            }
            literal("play") {
                argument("index", IntegerArgumentType.integer(1)) {
                    runs { ctx ->
                        PacketSecretRoutes.playStartLink(IntegerArgumentType.getInteger(ctx, "index") - 1)
                    }
                }
                runs {
                    PacketSecretRoutes.playStartLink()
                }
            }
            runs {
                PacketSecretRoutes.startStartLinkRecording()
            }
        }

        literal("link") {
            literal("delete") {
                runs {
                    PacketSecretRoutes.deleteLastDoorwayLink()
                }
            }
            literal("play") {
                argument("index", IntegerArgumentType.integer(1)) {
                    runs { ctx ->
                        PacketSecretRoutes.playDoorwayLink(IntegerArgumentType.getInteger(ctx, "index") - 1)
                    }
                }
                runs {
                    PacketSecretRoutes.playDoorwayLink()
                }
            }
            runs {
                PacketSecretRoutes.startDoorwayLinkRecording()
            }
        }

        literal("end") {
            literal("delete") {
                runs {
                    PacketSecretRoutes.deleteLastEndLink()
                }
            }
            literal("play") {
                argument("index", IntegerArgumentType.integer(1)) {
                    runs { ctx ->
                        PacketSecretRoutes.playEndLink(IntegerArgumentType.getInteger(ctx, "index") - 1)
                    }
                }
                runs {
                    PacketSecretRoutes.playEndLink()
                }
            }
            runs {
                PacketSecretRoutes.startEndLinkRecording()
            }
        }

        literal("delete") {
            runs {
                PacketSecretRoutes.deleteCurrentRoomRoute()
            }
        }

        runs {
            PacketSecretRoutes.startRecording()
        }
    }
}
