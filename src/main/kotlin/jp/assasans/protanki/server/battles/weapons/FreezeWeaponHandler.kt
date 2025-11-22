//package jp.assasans.protanki.server.battles.weapons
//
//import jp.assasans.protanki.server.battles.*
//import jp.assasans.protanki.server.battles.mode.TeamModeHandler
//import jp.assasans.protanki.server.battles.sendTo
//import jp.assasans.protanki.server.client.send
//import jp.assasans.protanki.server.client.ChangeTankSpecificationData
//import jp.assasans.protanki.server.client.toJson
//import jp.assasans.protanki.server.client.weapons.freeze.FireTarget
//import jp.assasans.protanki.server.client.weapons.freeze.StartFire
//import jp.assasans.protanki.server.client.weapons.freeze.StopFire
//import jp.assasans.protanki.server.commands.Command
//import jp.assasans.protanki.server.commands.CommandName
//import jp.assasans.protanki.server.garage.ServerGarageUserItemWeapon
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//
//class FreezeWeaponHandler(
//  player: BattlePlayer,
//  weapon: ServerGarageUserItemWeapon
//) : WeaponHandler(player, weapon) {
//
//  private var fireStarted: Boolean = false
//
//  // === 冰冻 tick 配置 ===
//  private val tickMs: Long = 500L                   // 每 0.5 秒一个 tick
//  private val freezeIncreasePerTick = 0.25          // 每个 tick 命中 +25% 冰冻
//  private val freezeRecoverPerTick = 0.20           // 每个 tick 未命中 -20% 冰冻
//  private val freezeMax = 0.70                      // 冰冻值最大 0.7 -> 剩余 30% 速度
//
//  private data class FreezeState(
//    val baseSpec: ChangeTankSpecificationData,
//    var power: Double,      // 当前冰冻值 [0.0, 0.7]
//    var lastHit: Long,
//    var job: Job
//  )
//
//  // tankId -> 冰冻状态
//  private val freezes: MutableMap<String, FreezeState> = mutableMapOf()
//
//  /** 只冻敌人，友军不受影响 */
//  private fun isEnemy(source: BattleTank, target: BattleTank): Boolean {
//    if (source == target) return true // 自伤是否生效由 SelfDamageEnabled 控制
//
//    val battle = source.battle
//    return when (battle.modeHandler) {
//      is TeamModeHandler -> source.player.team != target.player.team
//      else -> true // 死斗模式：所有人互为敌人
//    }
//  }
//
//  suspend fun fireStart(startFire: StartFire) {
//    val tank = player.tank ?: throw Exception("No Tank")
//    val battle = player.battle
//
//    fireStarted = true
//
//    Command(CommandName.ClientStartFire, tank.id)
//      .send(battle.players.exclude(player).ready())
//  }
//
//  suspend fun fireTarget(target: FireTarget) {
//    val sourceTank = player.tank ?: throw Exception("No Tank")
//    val battle = player.battle
//
//    // 没在开火状态就忽略（安全兜底）
//    if (!fireStarted) return
//
//    // 找到真实被命中的敌方坦克
//    val targetTanks = battle.players
//      .mapNotNull { p -> p.tank }
//      .filter { t -> target.targets.contains(t.id) }
//      .filter { t -> t.state == TankState.Active }
//      .filter { t -> isEnemy(sourceTank, t) }
//
//    targetTanks.forEach { targetTank ->
//      // 1. 正常结算伤害
//      val result = damageCalculator.calculate(sourceTank, targetTank)
//      battle.damageProcessor.dealDamage(
//        sourceTank,
//        targetTank,
//        result.damage,
//        result.isCritical
//      )
//
//      // 2. 叠加冰冻减速
//      applyFreezeHit(targetTank)
//    }
//  }
//
//  /**
//   * 被冰打中一次：
//   * - 如果已经在冻结中，只更新 lastHit（后面的协程会每 0.5 秒叠加/恢复）
//   * - 如果是第一次命中，记录原始参数并启动一个 tick 协程
//   */
//  private fun applyFreezeHit(targetTank: BattleTank) {
//    val battle = targetTank.battle
//    val tankId = targetTank.id
//    val now = System.currentTimeMillis()
//
//    val existing = freezes[tankId]
//    if (existing != null) {
//      existing.lastHit = now
//      return
//    }
//
//    // 第一次被命中：记录原始物理参数
//    val baseSpec = ChangeTankSpecificationData.fromPhysics(
//      targetTank.hull.modification.physics,
//      targetTank.weapon.item.modification.physics
//    )
//
//    val job = targetTank.coroutineScope.launch {
//      while (true) {
//        delay(tickMs)
//
//        val state = freezes[tankId] ?: break
//        val currentTime = System.currentTimeMillis()
//        val hitRecently = currentTime - state.lastHit <= tickMs
//
//        if (hitRecently) {
//          // 持续命中：每 tick 冻结值 +25%，最多 70%
//          state.power = (state.power + freezeIncreasePerTick).coerceAtMost(freezeMax)
//        } else {
//          // 未命中：每 tick 冻结值 -20%，最少 0
//          state.power = (state.power - freezeRecoverPerTick).coerceAtLeast(0.0)
//        }
//
//        // 冻结值已经清零并且最近没再命中：恢复原始参数并结束
//        if (state.power <= 0.0 && !hitRecently) {
//          Command(
//            CommandName.ChangeTankSpecification,
//            tankId,
//            baseSpec.toJson()
//          ).sendTo(battle)
//
//          freezes.remove(tankId)
//          break
//        }
//
//        // 当前速度系数：1 - 冻结值
//        val factor = 1.0 - state.power
//
//        // 按 factor 缩放底盘/炮塔相关所有移动 & 转向参数
//        val slowSpec = baseSpec.copy().apply {
//          // 底盘直线移动
//          speed               = baseSpec.speed               * factor
//          acceleration        = baseSpec.acceleration        * factor
//          reverseAcceleration = baseSpec.reverseAcceleration * factor
//          sideAcceleration    = baseSpec.sideAcceleration    * factor
//
//          // 底盘转向
//          turnSpeed               = baseSpec.turnSpeed               * factor
//          turnAcceleration        = baseSpec.turnAcceleration        * factor
//          reverseTurnAcceleration = baseSpec.reverseTurnAcceleration * factor
//
//          // 炮塔转向
//          turretRotationSpeed    = baseSpec.turretRotationSpeed    * factor
//          turretTurnAcceleration = baseSpec.turretTurnAcceleration * factor
//        }
//
//        Command(
//          CommandName.ChangeTankSpecification,
//          tankId,
//          slowSpec.toJson()
//        ).sendTo(battle)
//      }
//    }
//
//    freezes[tankId] = FreezeState(
//      baseSpec = baseSpec,
//      power = 0.0,
//      lastHit = now,
//      job = job
//    )
//  }
//
//  suspend fun fireStop(stopFire: StopFire) {
//    val tank = player.tank ?: throw Exception("No Tank")
//    val battle = player.battle
//
//    fireStarted = false
//
//    Command(CommandName.ClientStopFire, tank.id)
//      .send(battle.players.exclude(player).ready())
//  }
//}
package jp.assasans.protanki.server.battles.weapons

import jp.assasans.protanki.server.battles.*
import jp.assasans.protanki.server.battles.mode.TeamModeHandler
import jp.assasans.protanki.server.battles.sendTo
import jp.assasans.protanki.server.client.send
import jp.assasans.protanki.server.client.ChangeTankSpecificationData
import jp.assasans.protanki.server.client.toJson
import jp.assasans.protanki.server.client.weapons.freeze.FireTarget
import jp.assasans.protanki.server.client.weapons.freeze.StartFire
import jp.assasans.protanki.server.client.weapons.freeze.StopFire
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.garage.ServerGarageUserItemWeapon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FreezeWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {

  private var fireStarted: Boolean = false

  // === 冰冻 tick 配置 ===
  private val tickMs: Long = 500L                   // 每 0.5 秒一个 tick
  private val freezeIncreasePerTick = 0.25          // 每个 tick 命中 +25% 冰冻
  private val freezeRecoverPerTick = 0.20           // 每个 tick 未命中 -20% 冰冻
  private val freezeMax = 0.70                      // 冰冻值最大 0.7 -> 剩余 30% 速度

  private data class FreezeState(
    val baseSpec: ChangeTankSpecificationData,
    var power: Double,      // 当前冰冻值 [0.0, 0.7]
    var lastHit: Long,
    var job: Job
  )

  companion object {
    // 全局：tankId -> 冻结状态
    private val freezes: MutableMap<String, FreezeState> = mutableMapOf()

    /** 供火焰调用：若目标处于冰冻中，则立即解冻并恢复原始参数 */
    suspend fun cancelFreeze(target: BattleTank) {
      val state = freezes.remove(target.id) ?: return
      // 停掉协程
      state.job.cancel()
      // 恢复原始物理参数（sendTo 是 suspend，所以这里也要 suspend）
      Command(
        CommandName.ChangeTankSpecification,
        target.id,
        state.baseSpec.toJson()
      ).sendTo(target.battle)
    }

    fun isFrozen(tankId: String): Boolean = freezes.containsKey(tankId)
  }


  /** 只冻敌人，友军不受影响（但灭火不区分敌我） */
  private fun isEnemy(source: BattleTank, target: BattleTank): Boolean {
    if (source == target) return true // 自伤是否生效由 SelfDamageEnabled 控制

    val battle = source.battle
    return when (battle.modeHandler) {
      is TeamModeHandler -> source.player.team != target.player.team
      else -> true // 死斗模式：所有人互为敌人
    }
  }

  suspend fun fireStart(startFire: StartFire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    fireStarted = true

    Command(CommandName.ClientStartFire, tank.id)
      .send(battle.players.exclude(player).ready())
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    // 没在开火状态就忽略（安全兜底）
    if (!fireStarted) return

    // 先找出所有真正被命中的坦克（敌我都算）
    val hitTanks = battle.players
      .mapNotNull { p -> p.tank }
      .filter { t -> target.targets.contains(t.id) }
      .filter { t -> t.state == TankState.Active }

    // 1) 冰击中任何人：先解除火焰灼烧（敌/友都灭火）
    hitTanks.forEach { targetTank ->
      FlamethrowerWeaponHandler.cancelBurn(targetTank.id)
    }

    // 2) 只对敌方坦克结算伤害 + 冰冻减速
    hitTanks
      .filter { t -> isEnemy(sourceTank, t) }
      .forEach { targetTank ->
        // 正常结算伤害
        val result = damageCalculator.calculate(sourceTank, targetTank)
        battle.damageProcessor.dealDamage(
          sourceTank,
          targetTank,
          result.damage,
          result.isCritical
        )

        // 叠加冰冻减速
        applyFreezeHit(targetTank)
      }
  }

  /**
   * 被冰打中一次：
   * - 如果已经在冻结中，只更新 lastHit（后面的协程会每 0.5 秒叠加/恢复）
   * - 如果是第一次命中，记录原始参数并启动一个 tick 协程
   */
  private fun applyFreezeHit(targetTank: BattleTank) {
    val battle = targetTank.battle
    val tankId = targetTank.id
    val now = System.currentTimeMillis()

    val existing = freezes[tankId]
    if (existing != null) {
      // 如果协程还在跑，就只更新时间，继续沿用当前冻结层数
      if (existing.job.isActive) {
        existing.lastHit = now
        return
      } else {
        // 协程已经结束（异常 / 被取消），但 map 里还有旧数据，清掉重新开始一轮冻结
        freezes.remove(tankId)
      }
    }

    // 第一次被命中 或 上一次状态已失效：记录原始物理参数
    val baseSpec = ChangeTankSpecificationData.fromPhysics(
      targetTank.hull.modification.physics,
      targetTank.weapon.item.modification.physics
    )

    val job = targetTank.coroutineScope.launch {
      while (true) {
        delay(tickMs)

        val state = freezes[tankId] ?: break
        val currentTime = System.currentTimeMillis()
        val hitRecently = currentTime - state.lastHit <= tickMs

        if (hitRecently) {
          // 持续命中：每 tick 冻结值 +25%，最多 70%
          state.power = (state.power + freezeIncreasePerTick).coerceAtMost(freezeMax)
        } else {
          // 未命中：每 tick 冻结值 -20%，最少 0
          state.power = (state.power - freezeRecoverPerTick).coerceAtLeast(0.0)
        }

        // 冻结值已经清零并且最近没再命中：恢复原始参数并结束
        if (state.power <= 0.0 && !hitRecently) {
          Command(
            CommandName.ChangeTankSpecification,
            tankId,
            baseSpec.toJson()
          ).sendTo(battle)

          freezes.remove(tankId)
          break
        }

        // 当前速度系数：1 - 冻结值
        val factor = 1.0 - state.power

        // 按 factor 缩放底盘/炮塔相关所有移动 & 转向参数
        val slowSpec = baseSpec.copy().apply {
          // 底盘直线移动
          speed               = baseSpec.speed               * factor
          acceleration        = baseSpec.acceleration        * factor
          reverseAcceleration = baseSpec.reverseAcceleration * factor
          sideAcceleration    = baseSpec.sideAcceleration    * factor

          // 底盘转向
          turnSpeed               = baseSpec.turnSpeed               * factor
          turnAcceleration        = baseSpec.turnAcceleration        * factor
          reverseTurnAcceleration = baseSpec.reverseTurnAcceleration * factor

          // 炮塔转向
          turretRotationSpeed    = baseSpec.turretRotationSpeed    * factor
          turretTurnAcceleration = baseSpec.turretTurnAcceleration * factor
        }

        Command(
          CommandName.ChangeTankSpecification,
          tankId,
          slowSpec.toJson()
        ).sendTo(battle)
      }
    }

    freezes[tankId] = FreezeState(
      baseSpec = baseSpec,
      power = 0.0,
      lastHit = now,
      job = job
    )
  }


  suspend fun fireStop(stopFire: StopFire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    fireStarted = false

    Command(CommandName.ClientStopFire, tank.id)
      .send(battle.players.exclude(player).ready())
  }
}
