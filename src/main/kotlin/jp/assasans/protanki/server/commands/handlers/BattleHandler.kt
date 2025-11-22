package jp.assasans.protanki.server.commands.handlers

import kotlin.time.Duration.Companion.seconds
import com.squareup.moshi.Moshi
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import jp.assasans.protanki.server.battles.*
import jp.assasans.protanki.server.client.*
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandHandler
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.commands.ICommandHandler
import jp.assasans.protanki.server.extensions.launchDelayed
import jp.assasans.protanki.server.math.distanceTo

class BattleHandler : ICommandHandler, KoinComponent {
    private val logger = KotlinLogging.logger { }

    private val json by inject<Moshi>()

    @CommandHandler(CommandName.Ping)
    suspend fun ping(socket: UserSocket) {
        val player = socket.battlePlayer ?: return

        player.sequence++

        val initBattle = if (player.isSpectator) player.sequence == BattlePlayerConstants.SPECTATOR_INIT_SEQUENCE
        else player.sequence == BattlePlayerConstants.USER_INIT_SEQUENCE
        if (initBattle) {
            logger.debug { "Init battle for ${player.user.username}..." }
            player.initBattle()
        }

        Command(CommandName.Pong).send(socket)
    }

    @CommandHandler(CommandName.GetInitDataLocalTank)
    suspend fun getInitDataLocalTank(socket: UserSocket) {
        val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")

        player.initLocal()
    }

    @CommandHandler(CommandName.Move)
    suspend fun move(socket: UserSocket, data: MoveData) {
        moveInternal(socket, data)
    }

    @CommandHandler(CommandName.FullMove)
    suspend fun fullMove(socket: UserSocket, data: FullMoveData) {
        moveInternal(socket, data)
    }


    private suspend fun moveInternal(socket: UserSocket, data: MoveData) {
        // logger.trace { "Tank move: [ ${data.position.x}, ${data.position.y}, ${data.position.z} ]" }

        // 客户端在刚进房间 / 刚离开房间时，还可能继续发一两帧 move 包，所以这里要做 null 保护
        val player = socket.battlePlayer ?: return
        val tank = player.tank ?: return

        // 只有 SemiActive / Active 才允许移动，其它状态一律忽略
        if (tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
            // Dead 基本是正常情况，不要一直打 warn
            if (tank.state != TankState.Dead) {
                logger.warn { "Invalid tank state for movement: ${tank.state}" }
            }
            return
        }

        // 真正更新服务器上的位置 / 朝向
        tank.position.copyFrom(data.position.toVector())
        tank.orientation.fromEulerAngles(data.orientation.toVector())

        // （可选）掉出地图检测：你之前如果在 Battle 里加了 fallDeathZ，这里一起用上
        player.battle?.let { battle ->
            if (
                !tank.selfDestructing &&
                tank.state != TankState.Dead &&
                tank.position.z < battle.fallDeathZ
            ) {
                logger.debug {
                    "Tank ${tank.id} fell out of map: z=${tank.position.z}, threshold=${battle.fallDeathZ}, self-destructing"
                }
                tank.selfDestruct(silent = false)
                return
            }
        }

        if (data is FullMoveData) {
            val count = Command(
                CommandName.ClientFullMove,
                ClientFullMoveData(tank.id, data).toJson()
            ).sendTo(player.battle, exclude = player)

            logger.trace { "Synced full move to $count players" }
        } else {
            val count = Command(
                CommandName.ClientMove,
                ClientMoveData(tank.id, data).toJson()
            ).sendTo(player.battle, exclude = player)

            logger.trace { "Synced move to $count players" }
        }
    }

    // 复活时用的“重叠检测”：和任何 Active 坦克距离小于一定阈值就算重叠
    private fun isOverlappingWithActiveTank(tank: BattleTank, minDistance: Double): Boolean {
        val battle = tank.battle

        return battle.players
            .asSequence()
            .mapNotNull { it.tank }
            .filter { it !== tank }
            .filter { it.state == TankState.Active } // 只有已经实体化的坦克会阻挡复活
            .any { other ->
                val dist = tank.position.distanceTo(other.position)
                dist < minDistance
            }
    }


    @CommandHandler(CommandName.RotateTurret)
    suspend fun rotateTurret(socket: UserSocket, data: RotateTurretData) {
        val player = socket.battlePlayer ?: return
        val tank = player.tank ?: return

        if (tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
            if (tank.state != TankState.Dead) {
                logger.warn { "Invalid tank state for rotate turret: ${tank.state}" }
            }
            return
        }

        val count = Command(
            CommandName.ClientRotateTurret,
            ClientRotateTurretData(tank.id, data).toJson()
        ).sendTo(player.battle, exclude = player)

        logger.trace { "Synced rotate turret to $count players" }
    }

/*  @CommandHandler(CommandName.RotateTurret)
  suspend fun rotateTurret(socket: UserSocket, data: RotateTurretData) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    if(tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
      logger.warn { "Invalid tank state for rotate turret: ${tank.state}" }
    }

    val count = Command(
      CommandName.ClientRotateTurret,
      ClientRotateTurretData(tank.id, data).toJson()
    ).sendTo(player.battle, exclude = player)

    logger.trace { "Synced rotate turret to $count players" }
  }*/

    /*@CommandHandler(CommandName.MovementControl)
    suspend fun movementControl(socket: UserSocket, data: MovementControlData) {
      val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
      val tank = player.tank ?: throw Exception("No Tank")

      if(tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
        logger.warn { "Invalid tank state for movement control: ${tank.state}" }
      }

      val count = Command(
        CommandName.ClientMovementControl,
        ClientMovementControlData(tank.id, data).toJson()
      ).sendTo(player.battle, exclude = player)

      logger.trace { "Synced movement control to $count players" }
    }*/

    @CommandHandler(CommandName.MovementControl)
    suspend fun movementControl(socket: UserSocket, data: MovementControlData) {
        val player = socket.battlePlayer ?: return
        val tank = player.tank ?: return

        if (tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
            if (tank.state != TankState.Dead) {
                logger.warn { "Invalid tank state for movement control: ${tank.state}" }
            }
            return
        }

        val count = Command(
            CommandName.ClientMovementControl,
            ClientMovementControlData(tank.id, data).toJson()
        ).sendTo(player.battle, exclude = player)

        logger.trace { "Synced movement control to $count players" }
    }


    @CommandHandler(CommandName.SelfDestruct)
    suspend fun selfDestruct(socket: UserSocket) {
        val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
        val tank = player.tank ?: throw Exception("No Tank")

        logger.debug { "Started self-destruct for ${socket.user!!.username}" }

        if (player.battle.properties[BattleProperty.InstantSelfDestruct]) {
            tank.selfDestruct()
        } else {
            tank.selfDestructing = true
            tank.coroutineScope.launchDelayed(10.seconds) {
                tank.selfDestructing = false
                tank.selfDestruct()
            }
        }
    }

    @CommandHandler(CommandName.ReadyToRespawn)
    suspend fun readyToRespawn(socket: UserSocket) {
        val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")

        player.respawn()
    }

    /*  @CommandHandler(CommandName.ReadyToSpawn)
      suspend fun readyToSpawn(socket: UserSocket) {
        val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
        val tank = player.tank ?: throw Exception("No Tank")

        val newTank = player.createTank()
        newTank.position = tank.position
        newTank.orientation = tank.orientation

        newTank.spawn()

        delay(3000)
        newTank.activate()
      }*/

    @CommandHandler(CommandName.ReadyToSpawn)
    suspend fun readyToSpawn(socket: UserSocket) {
        val player = socket.battlePlayer ?: return
        val oldTank = player.tank ?: return

        // 创建本次复活用的新坦克
        val newTank = player.createTank()
        newTank.position = oldTank.position
        newTank.orientation = oldTank.orientation

        // 进入 SemiActive（透明 / 无敌），客户端看到的是“幽灵状态”
        newTank.spawn()

        // 最少透明 3 秒，再按是否重叠决定是否激活
        val baseGhostDurationMs = 3000L
        val checkIntervalMs = 100L
        val collisionDistance = 800.0 // 判定“压在一起”的距离，可以之后按手感再调

        val start = System.currentTimeMillis()

        while(true) {
            // 玩家离开战斗 / 换坦克 / 被别的逻辑干掉时就停止等待
            if(player.tank !== newTank || newTank.state == TankState.Dead) return

            val now = System.currentTimeMillis()
            val baseTimePassed = now - start >= baseGhostDurationMs

            // 是否和任意实体坦克重叠
            val overlapping = isOverlappingWithActiveTank(newTank, collisionDistance)

            // 3 秒已经过去 & 不重叠，才真正复活
            if(baseTimePassed && !overlapping) {
                newTank.activate()
                return
            }

            // 否则继续保持 SemiActive 等待，100ms 检查一次
            delay(checkIntervalMs)
        }
    }



    @CommandHandler(CommandName.ExitFromBattle)
    suspend fun exitFromBattle(socket: UserSocket, destinationScreen: String) {
        val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
        val battle = player.battle

        player.deactivate(terminate = true)
        battle.players.remove(player)

        Command(CommandName.UnloadBattle).send(socket)

        socket.initChatMessages()

        when (destinationScreen) {
            "BATTLE_SELECT" -> {
                Command(CommandName.StartLayoutSwitch, "BATTLE_SELECT").send(socket)
                socket.loadLobbyResources()
                Command(CommandName.EndLayoutSwitch, "BATTLE_SELECT", "BATTLE_SELECT").send(socket)

                socket.screen = Screen.BattleSelect
                socket.initBattleList()

                logger.debug { "Select battle ${battle.id} -> ${battle.title}" }

                battle.selectFor(socket)
                battle.showInfoFor(socket)
            }

            "GARAGE" -> {
                Command(CommandName.StartLayoutSwitch, "GARAGE").send(socket)
                socket.screen = Screen.Garage
                socket.loadGarageResources()
                socket.initGarage()
                Command(CommandName.EndLayoutSwitch, "GARAGE", "GARAGE").send(socket)
            }
        }
    }

    @CommandHandler(CommandName.TriggerMine)
    suspend fun triggerMine(socket: UserSocket, key: String) {
        val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
        val tank = player.tank ?: throw Exception("No Tank")
        val battle = player.battle

        val username = key.substringBeforeLast("_")
        val id = key.substringAfterLast("_").toInt()

        val mine = battle.mineProcessor.mines[id]
        if (mine == null) {
            logger.warn { "Attempt to activate missing mine: $username@$id" }
            return
        }

        mine.trigger(tank)
    }
}
