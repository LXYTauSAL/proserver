package jp.assasans.protanki.server.battles.mode

import jp.assasans.protanki.server.battles.*
import jp.assasans.protanki.server.client.*
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.math.Vector3
import jp.assasans.protanki.server.quests.DeliverFlagQuest
import jp.assasans.protanki.server.quests.questOf
import jp.assasans.protanki.server.toVector
import mu.KotlinLogging

abstract class FlagState(val team: BattleTeam)

class FlagOnPedestalState(team: BattleTeam) : FlagState(team) {
  override fun toString(): String = "${this::class.simpleName}(team=$team)"
}

class FlagDroppedState(team: BattleTeam, val position: Vector3) : FlagState(team) {
  override fun toString(): String = "${this::class.simpleName}(team=$team, position=$position)"
}

class FlagCarryingState(team: BattleTeam, val carrier: BattleTank) : FlagState(team) {
  override fun toString(): String = "${this::class.simpleName}(team=$team, carrier=${carrier.player.user.username})"
}

fun FlagState.asOnPedestal(): FlagOnPedestalState = FlagOnPedestalState(team)
fun FlagState.asDropped(position: Vector3): FlagDroppedState = FlagDroppedState(team, position)
fun FlagState.asCarrying(carrier: BattleTank): FlagCarryingState = FlagCarryingState(team, carrier)

class CaptureTheFlagModeHandler(battle: Battle) : TeamModeHandler(battle) {
  companion object {
    fun builder(): BattleModeHandlerBuilder = { battle -> CaptureTheFlagModeHandler(battle) }
  }

  override val mode: BattleMode get() = BattleMode.CaptureTheFlag

  val flags = mutableMapOf<BattleTeam, FlagState>(
    BattleTeam.Red to FlagOnPedestalState(BattleTeam.Red),
    BattleTeam.Blue to FlagOnPedestalState(BattleTeam.Blue)
  )
  private val logger = KotlinLogging.logger { }
  private val flagOffsetZ = 80

  /*suspend fun captureFlag(flagTeam: BattleTeam, carrier: BattleTank) {
    flags[flagTeam] = flags[flagTeam]!!.asCarrying(carrier) // TODO(Assasans): Non-null assertion

    Command(CommandName.FlagCaptured, carrier.id, flagTeam.key).sendTo(battle)
  }

  suspend fun dropFlag(flagTeam: BattleTeam, carrier: BattleTank, position: Vector3) {
    flags[flagTeam] = flags[flagTeam]!!.asDropped(position) // TODO(Assasans): Non-null assertion

    Command(
      CommandName.FlagDropped,
      FlagDroppedData(
        x = position.x,
        y = position.y,
        z = position.z,
        flagTeam = flagTeam
      ).toJson()
    ).sendTo(battle)
  }

  suspend fun deliverFlag(enemyFlagTeam: BattleTeam, flagTeam: BattleTeam, carrier: BattleTank) {
    flags[enemyFlagTeam] = flags[enemyFlagTeam]!!.asOnPedestal() // TODO(Assasans): Non-null assertion
    teamScores.merge(flagTeam, 1, Int::plus)

    Command(CommandName.FlagDelivered, flagTeam.key, carrier.id).sendTo(battle)
    updateScores()

    carrier.player.user.questOf<DeliverFlagQuest>()?.let { quest ->
      quest.current++
      carrier.socket.updateQuests()
      quest.updateProgress()
    }
  }

  suspend fun returnFlag(flagTeam: BattleTeam, carrier: BattleTank?) {
    flags[flagTeam] = flags[flagTeam]!!.asOnPedestal() // TODO(Assasans): Non-null assertion

    Command(
      CommandName.FlagReturned,
      flagTeam.key,
      carrier?.player?.user?.username ?: null.toString()
    ).sendTo(battle)
  }*/

  suspend fun captureFlag(flagTeam: BattleTeam, carrier: BattleTank) {
    // 原来是 flags[flagTeam]!!，这里改成安全写法，找不到就直接不处理
    val current = flags[flagTeam] ?: return
    flags[flagTeam] = current.asCarrying(carrier)

    Command(CommandName.FlagCaptured, carrier.id, flagTeam.key).sendTo(battle)
  }

  suspend fun dropFlag(flagTeam: BattleTeam, carrier: BattleTank, position: Vector3) {
    val current = flags[flagTeam] ?: return
    flags[flagTeam] = current.asDropped(position)

    // 确保发送正确的掉落位置信息
    Command(
      CommandName.FlagDropped,
      FlagDroppedData(
        x = position.x,
        y = position.y,
        z = position.z + flagOffsetZ,  // 增加高度偏移，与初始化时一致
        flagTeam = flagTeam
      ).toJson()
    ).sendTo(battle)
  }

  suspend fun deliverFlag(enemyFlagTeam: BattleTeam, flagTeam: BattleTeam, carrier: BattleTank) {
    val enemyFlagState = flags[enemyFlagTeam] ?: return
    // 新增防护：如果敌方旗帜已在基座，说明已处理过，直接返回
    if (enemyFlagState is FlagOnPedestalState) {
      logger.warn { "重复触发旗帜交付：${enemyFlagTeam} 旗帜已在基座" }
      return
    }

    flags[enemyFlagTeam] = enemyFlagState.asOnPedestal()  // 敌方旗帜复位
    addTeamScore(flagTeam)  // 仅保留一处加分（根据实际情况选择）
    // teamScores.merge(flagTeam, 1, Int::plus)  // 二选一

    Command(CommandName.FlagDelivered, flagTeam.key, carrier.id).sendTo(battle)
    updateScores()

    // 任务进度更新（保留）
    carrier.player.user.questOf<DeliverFlagQuest>()?.let { quest ->
      quest.current++
      carrier.socket.updateQuests()
      quest.updateProgress()
    }
  }

  suspend fun returnFlag(flagTeam: BattleTeam, carrier: BattleTank?) {
    val current = flags[flagTeam] ?: return
    flags[flagTeam] = current.asOnPedestal()

    Command(
      CommandName.FlagReturned,
      flagTeam.key,
      carrier?.player?.user?.username ?: ""   // ✅ 比原来的 null.toString() 更干净一点
    ).sendTo(battle)
  }

  fun resetFlags() {
    flags.keys.forEach { team -> flags[team] = FlagOnPedestalState(team) }
  }



  override suspend fun playerLeave(player: BattlePlayer) {
    val tank = player.tank ?: return
    val flag = flags.values.filterIsInstance<FlagCarryingState>().singleOrNull { it.carrier == tank } ?: return

    // 修复：用 Vector3 构造函数拷贝位置，替代 copy() 方法
    val dropPosition = Vector3(
      x = tank.position.x,
      y = tank.position.y,
      z = tank.position.z + flagOffsetZ  // 保持原逻辑的高度偏移
    )
    dropFlag(flag.team, tank, dropPosition)

    super.playerLeave(player)
  }

  override suspend fun initModeModel(player: BattlePlayer) {
    Command(
      CommandName.InitCtfModel,
      getCtfModel().toJson()
    ).send(player)
  }

  override suspend fun initPostGui(player: BattlePlayer) {
    Command(
      CommandName.InitFlags,
      getCtfModel().toJson()
    ).send(player)
  }

  private fun getCtfModel(): InitCtfModelData {
    val flags = battle.map.flags ?: throw IllegalStateException("Map has no flags")
    val redFlag = flags[BattleTeam.Red] ?: throw IllegalStateException("Map does not have a red flag")
    val blueFlag = flags[BattleTeam.Blue] ?: throw IllegalStateException("Map does not have a blue flag")

    val redFlagPosition = redFlag.position.toVector()
    val blueFlagPosition = blueFlag.position.toVector()

    redFlagPosition.z += flagOffsetZ
    blueFlagPosition.z += flagOffsetZ

    val redFlagState = this.flags[BattleTeam.Red] ?: throw IllegalStateException("Red flag state is null")
    val blueFlagState = this.flags[BattleTeam.Blue] ?: throw IllegalStateException("Blue flag state is null")

    return InitCtfModelData(
      resources = CtfModelResources().toJson(),
      lighting = CtfModelLighting().toJson(),
      basePosRedFlag = redFlagPosition.toVectorData(),
      basePosBlueFlag = blueFlagPosition.toVectorData(),
      posRedFlag = if(redFlagState is FlagDroppedState) {
        // 修复：用构造函数拷贝位置，替代 copy()
        Vector3(
          x = redFlagState.position.x,
          y = redFlagState.position.y,
          z = redFlagState.position.z + flagOffsetZ
        ).toVectorData()
      } else null,
      posBlueFlag = if(blueFlagState is FlagDroppedState) {
        // 修复：用构造函数拷贝位置，替代 copy()
        Vector3(
          x = blueFlagState.position.x,
          y = blueFlagState.position.y,
          z = blueFlagState.position.z + flagOffsetZ
        ).toVectorData()
      } else null,
      redFlagCarrierId = if(redFlagState is FlagCarryingState) redFlagState.carrier.id else null,
      blueFlagCarrierId = if(blueFlagState is FlagCarryingState) blueFlagState.carrier.id else null
    )
  }

  override suspend fun dump(builder: StringBuilder) {
    super.dump(builder)

    builder.appendLine("    Flags: ")
    flags.forEach { (team, flag) ->
      builder.appendLine("        $team: $flag")
    }
  }
}
