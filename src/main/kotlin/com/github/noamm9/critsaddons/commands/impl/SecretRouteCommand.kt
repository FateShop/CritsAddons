package com.github.noamm9.critsaddons.commands.impl

import com.github.noamm9.commands.BaseCommand
import com.github.noamm9.commands.CommandNodeBuilder
import com.github.noamm9.critsaddons.features.impl.critsaddons.SecretRoutes

object SecretRouteCommand: BaseCommand("nsr") {
    override fun CommandNodeBuilder.build() {
        literal("save") {
            runs {
                SecretRoutes.saveRecording()
            }
        }

        literal("cancel") {
            runs {
                SecretRoutes.cancelRecording()
            }
        }

        literal("wait") {
            runs {
                SecretRoutes.insertWaitStep()
            }
        }

        literal("start") {
            literal("delete") {
                runs {
                    SecretRoutes.deleteStartLinkFromCurrentBlock()
                }
            }
            runs {
                SecretRoutes.startStartPathRecording()
            }
        }

        literal("bat") {
            runs {
                SecretRoutes.insertBatWaitStep()
            }
        }

        literal("kill") {
            runs {
                SecretRoutes.killDuringRecording()
            }
        }

        literal("delete") {
            runs {
                SecretRoutes.deleteCurrentRoomRoute()
            }
        }

        runs {
            SecretRoutes.startRecording()
        }
    }
}
