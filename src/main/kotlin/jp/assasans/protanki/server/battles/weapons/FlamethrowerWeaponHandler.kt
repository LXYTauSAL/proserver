//package jp.assasans.protanki.server.battles.weapons
//
//import jp.assasans.protanki.server.battles.*
//import jp.assasans.protanki.server.client.send
//import jp.assasans.protanki.server.client.weapons.flamethrower.FireTarget
//import jp.assasans.protanki.server.client.weapons.flamethrower.StartFire
//import jp.assasans.protanki.server.client.weapons.flamethrower.StopFire
//import jp.assasans.protanki.server.commands.Command
//import jp.assasans.protanki.server.commands.CommandName
//import jp.assasans.protanki.server.garage.ServerGarageUserItemWeapon
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//
//class FlamethrowerWeaponHandler(
//  player: BattlePlayer,
//  // 加上 val，让 weapon 变成这个类自己的属性
//  val weapon: ServerGarageUserItemWeapon
//) : WeaponHandler(player, weapon) {
//
//  private var fireStarted = false
//
//  // ================== 灼烧相关配置 ==================
//
//  // 每秒固定灼烧伤害（所有等级通用）
//  private val burnDamagePerSecond: Double = 100.0
//
//  // 灼烧结算间隔（毫秒），这里是 0.5 秒结算一次
//  private val burnIntervalMs: Long = 500L
//
//  // 每个目标一份灼烧 Job，key 用 BattleTank.id（String）
//  private val burnJobs: MutableMap<String, Job> = HashMap()
//
//  /**
//   * 从火焰炮 json 当前等级读取灼烧时长：
//   * modifications[n].properties -> FIRE_DAMAGE -> BURN_DURATION_SEC (单位：秒)
//   * 返回值：毫秒
//   */
//  private fun getBurnDurationMs(): Long {
//    val modification = weapon.modification
//
//    // 找到 FIRE_DAMAGE 这一组属性
//    val fireDamageGroup = modification.properties
//      .firstOrNull { prop -> prop.property == "FIRE_DAMAGE" }
//
//    // 在子属性里找 BURN_DURATION_SEC
//    val burnDurationProp = fireDamageGroup
//      ?.properties
//      ?.firstOrNull { prop -> prop.property == "BURN_DURATION_SEC" }
//
//    // value 可能是 Number / String，这里都兼容一下
//    val seconds: Double? = when(val v = burnDurationProp?.value) {
//      is Number -> v.toDouble()
//      is String -> v.toDoubleOrNull()
//      else -> null
//    }
//
//    val msFromJson = seconds?.let { (it * 1000.0).toLong() }
//
//    if(msFromJson != null && msFromJson > 0L) return msFromJson
//
//    // 兜底：如果 json 没配，按默认 M0~M3 时长
//    return when(weapon.modificationIndex) {
//      0 -> 1500L // 1.5s
//      1 -> 2000L // 2.0s
//      2 -> 2500L // 2.5s
//      else -> 3000L // 3.0s
//    }
//  }
//
//  // ================== 原有逻辑 + 灼烧 ==================
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
//    // TODO(Assasans): Damage timing is not checked on server, exploitation is possible
//    if(!fireStarted) return
//
//    val targetTanks = battle.players
//      .mapNotNull { player -> player.tank }
//      .filter { tank -> target.targets.contains(tank.id) }
//      .filter { tank -> tank.state == TankState.Active }
//
//    targetTanks.forEach { targetTank ->
//      val damage = damageCalculator.calculate(sourceTank, targetTank)
//
//      // 1. 立即伤害（原始逻辑）
//      battle.damageProcessor.dealDamage(
//        sourceTank,
//        targetTank,
//        damage.damage,
//        damage.isCritical
//      )
//
//      // 2. 灼烧：固定 50 DPS，时长从 json 读取
//      scheduleBurn(sourceTank, targetTank)
//    }
//
//    // TODO(Assasans): No response command?
//  }
//
//  /**
//   * 给目标挂上灼烧效果：
//   * - 总时长：json 里的 BURN_DURATION_SEC
//   * - 每 burnIntervalMs 掉一次血
//   * - 每秒固定灼烧：burnDamagePerSecond
//   * - 同一个玩家对同一目标只保留一层灼烧（重复命中刷新时间）
//   */
//  private fun scheduleBurn(
//    sourceTank: BattleTank,
//    targetTank: BattleTank
//  ) {
//    val battle = player.battle
//    val damageProcessor = battle.damageProcessor
//
//    val burnDurationMs = getBurnDurationMs()
//    if(burnDurationMs <= 0L) return
//
//    val ticks = (burnDurationMs / burnIntervalMs).toInt().coerceAtLeast(1)
//
//    // 50 DPS 按 tick 间隔拆成每次跳血值
//    val damagePerTick =
//      burnDamagePerSecond * (burnIntervalMs.toDouble() / 1000.0)
//
//    // 同一玩家的火焰对同一目标：如果已在灼烧，先取消旧的，再重新开始 = 刷新持续时间
//    burnJobs[targetTank.id]?.cancel()
//
//    val job = targetTank.coroutineScope.launch {
//      repeat(ticks) {
//        if(targetTank.state != TankState.Active || targetTank.health <= 0.0) {
//          burnJobs.remove(targetTank.id)
//          return@launch
//        }
//
//        delay(burnIntervalMs)
//
//        if(targetTank.state != TankState.Active || targetTank.health <= 0.0) {
//          burnJobs.remove(targetTank.id)
//          return@launch
//        }
//
//        // 灼烧不吃双倍伤害 BUFF，避免过强
//        damageProcessor.dealDamage(
//          sourceTank,
//          targetTank,
//          damagePerTick,
//          isCritical = false,
//          ignoreSourceEffects = true
//        )
//      }
//
//      burnJobs.remove(targetTank.id)
//    }
//
//    burnJobs[targetTank.id] = job
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
import jp.assasans.protanki.server.client.send
import jp.assasans.protanki.server.client.weapons.flamethrower.FireTarget
import jp.assasans.protanki.server.client.weapons.flamethrower.StartFire
import jp.assasans.protanki.server.client.weapons.flamethrower.StopFire
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.garage.ServerGarageUserItemWeapon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FlamethrowerWeaponHandler(
  player: BattlePlayer,
  // 保留 weapon 方便读 json
  val weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {

  private var fireStarted = false

  // ================== 灼烧相关配置 ==================

  // 每秒固定灼烧伤害（统一控制整体强度）
  private val burnDamagePerSecond: Double = 100.0

  // 灼烧结算间隔（毫秒），0.5 秒跳一次
  private val burnIntervalMs: Long = 500L

  companion object {
    // 全局：每辆坦克一个灼烧协程
    private val burnJobs: MutableMap<String, Job> = HashMap()

    /** 冰炮调用：如果目标处于灼烧状态，取消并移除 */
    fun cancelBurn(tankId: String) {
      burnJobs[tankId]?.cancel()
      burnJobs.remove(tankId)
    }

    fun isBurning(tankId: String): Boolean = burnJobs.containsKey(tankId)
  }

  /** 判断是否敌方（队友不能被灼烧） */
  private fun isEnemy(source: BattleTank, target: BattleTank): Boolean {
    if (source == target) return true // 自伤交给 SelfDamageEnabled 控制

    val battle = source.battle
    return when (battle.modeHandler) {
      is TeamModeHandler -> source.player.team != target.player.team
      else -> true
    }
  }

  /**
   * 从火焰炮 json 当前等级读取灼烧时长（秒 -> 毫秒）
   * modifications[n].properties -> FIRE_DAMAGE -> BURN_DURATION_SEC
   */
  private fun getBurnDurationMs(): Long {
    val modification = weapon.modification

    val fireDamageGroup = modification.properties
      .firstOrNull { prop -> prop.property == "FIRE_DAMAGE" }

    val burnDurationProp = fireDamageGroup
      ?.properties
      ?.firstOrNull { prop -> prop.property == "BURN_DURATION_SEC" }

    val seconds: Double? = when (val v = burnDurationProp?.value) {
      is Number -> v.toDouble()
      is String -> v.toDoubleOrNull()
      else -> null
    }

    val msFromJson = seconds?.let { (it * 1000.0).toLong() }
    if (msFromJson != null && msFromJson > 0L) return msFromJson

    // 兜底：json 没配置就按改装等级给个默认
    return when (weapon.modificationIndex) {
      0 -> 1500L // 1.5s
      1 -> 2000L // 2.0s
      2 -> 2500L // 2.5s
      else -> 3000L // 3.0s
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

    if (!fireStarted) return

    val targetTanks = battle.players
      .mapNotNull { p -> p.tank }
      .filter { t -> target.targets.contains(t.id) }
      .filter { t -> t.state == TankState.Active }

    targetTanks.forEach { targetTank ->
      // 1) 火焰打到任何人：先尝试解冻（敌/友都解冻）
      FreezeWeaponHandler.cancelFreeze(targetTank)

      // 2) 队友：只解冻，不灼烧、不伤害
      if (!isEnemy(sourceTank, targetTank)) {
        return@forEach
      }

      // 3) 敌人：正常即时伤害 + 灼烧
      val damage = damageCalculator.calculate(sourceTank, targetTank)
      battle.damageProcessor.dealDamage(
        sourceTank,
        targetTank,
        damage.damage,
        damage.isCritical
      )

      scheduleBurn(sourceTank, targetTank)
    }
  }

  /**
   * 给目标挂上灼烧效果：
   * - 总时长：json 里的 BURN_DURATION_SEC
   * - 每 burnIntervalMs 掉一次血
   * - 每秒固定 burnDamagePerSecond
   * - 同一时刻一辆坦克只保留一层灼烧（重复命中刷新持续时间）
   */
  private fun scheduleBurn(
    sourceTank: BattleTank,
    targetTank: BattleTank
  ) {
    val battle = player.battle
    val damageProcessor = battle.damageProcessor

    val burnDurationMs = getBurnDurationMs()
    if (burnDurationMs <= 0L) return

    val ticks = (burnDurationMs / burnIntervalMs).toInt().coerceAtLeast(1)
    val damagePerTick =
      burnDamagePerSecond * (burnIntervalMs.toDouble() / 1000.0)

    // 同一辆坦克：若已在灼烧中，重新开始 = 刷新持续时间
    burnJobs[targetTank.id]?.cancel()

    val job = targetTank.coroutineScope.launch {
      repeat(ticks) {
        if (targetTank.state != TankState.Active || targetTank.health <= 0.0) {
          burnJobs.remove(targetTank.id)
          return@launch
        }

        delay(burnIntervalMs)

        if (targetTank.state != TankState.Active || targetTank.health <= 0.0) {
          burnJobs.remove(targetTank.id)
          return@launch
        }

        // 灼烧不吃双倍攻击 BUFF，避免过强
        damageProcessor.dealDamage(
          sourceTank,
          targetTank,
          damagePerTick,
          isCritical = false,
          ignoreSourceEffects = true
        )
      }

      burnJobs.remove(targetTank.id)
    }

    burnJobs[targetTank.id] = job
  }

  suspend fun fireStop(stopFire: StopFire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    fireStarted = false

    Command(CommandName.ClientStopFire, tank.id)
      .send(battle.players.exclude(player).ready())
  }
}
