package jp.assasans.protanki.server.battles.weapons

import jp.assasans.protanki.server.battles.*
import jp.assasans.protanki.server.client.send
import jp.assasans.protanki.server.client.weapons.shaft.FireTarget
import jp.assasans.protanki.server.client.weapons.shaft.ShotTarget
import jp.assasans.protanki.server.client.toJson
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.garage.ServerGarageUserItemWeapon
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ShaftWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {

  // ====== 蓄力相关状态 ======
  // 开始蓄力时客户端的 physTime（StartEnergyDrain 传进来的 time）
  private var aimingStartPhysTime: Int? = null

  suspend fun startEnergyDrain(time: Int) {
    val tank = player.tank ?: throw Exception("No Tank")

    // ✅ 正确含义：time 是当前 physTime，不是“蓄满时间”
    aimingStartPhysTime = time

    // 原来这里没别的逻辑，先保持
    // TODO(Assasans)
  }

  suspend fun enterSnipingMode() {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ClientEnterSnipingMode, tank.id)
      .send(battle.players.exclude(player).ready())
  }

  suspend fun exitSnipingMode() {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ClientExitSnipingMode, tank.id)
      .send(battle.players.exclude(player).ready())
  }

  /**
   * 普通模式开火：
   * 不用 DamageCalculator，直接从 shaft.json 当前等级 damage.range 随机一个伤害。
   */
  suspend fun fireArcade(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    if(target.target != null) {
      val targetTank = battle.players
        .mapNotNull { player -> player.tank }
        .single { tank -> tank.id == target.target }
      if(targetTank.state != TankState.Active) return

      val damage = getArcadeDamage()

      battle.damageProcessor.dealDamage(
        sourceTank,
        targetTank,
        damage,
        false // 原版就是非暴击固定伤害，这里保持 false
      )
    }

    val shot = ShotTarget(target, 5.0)
    Command(CommandName.ShotTarget, sourceTank.id, shot.toJson())
      .send(battle.players.exclude(player).ready())
  }

  /**
   * 狙击模式开火（蓄力伤害）：
   * finalDamage = SHAFT_AIMING_MODE_MAX_DAMAGE * chargeFactor
   * chargeFactor 由「FireTarget.physTime - StartEnergyDrain.time」和 JSON 里的充能时间算出。
   */
  suspend fun fireSniping(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    if(target.target != null) {
      val targetTank = battle.players
        .mapNotNull { player -> player.tank }
        .single { tank -> tank.id == target.target }
      if(targetTank.state != TankState.Active) return

      // 1. 计算蓄力比例（0 ~ 1）
      val chargeFactor = getChargeFactor(target.physTime)

      // 2. 从 shaft.json 当前级别拿 SHAFT_AIMING_MODE_MAX_DAMAGE
      val maxAimingDamage = getAimingModeMaxDamage()

      // 3. 蓄力伤害：正常 = maxAimingDamage * chargeFactor
      //    如果 JSON 没配（maxAimingDamage<=0）就兜底 150
      val finalDamage =
        if(maxAimingDamage > 0.0) {
          maxAimingDamage * chargeFactor
        } else {
          150.0
        }

      battle.damageProcessor.dealDamage(
        sourceTank,
        targetTank,
        finalDamage,
        false // 原版 sniping 也是非暴击固定伤害
      )
    }

    // 每次开完一枪，清空这次蓄力的起点
    aimingStartPhysTime = null

    val shot = ShotTarget(target, 5.0)
    Command(CommandName.ShotTarget, sourceTank.id, shot.toJson())
      .send(battle.players.exclude(player).ready())
  }

  // ==================== 读取 JSON & 伤害计算 ====================

  /**
   * 普通模式的随机伤害：
   * 来自 shaft.json 当前等级 damage.range.from ~ to
   * json 有问题就退回 75（你原来的固定值）
   */
  private fun getArcadeDamage(): Double {
    val range = item.modification.damage.range
      ?: return 75.0 // 没配就用原来的 75

    val from = min(range.from, range.to).toDouble()
    val to = max(range.from, range.to).toDouble()

    if(to <= 0.0) return 75.0
    if(to == from) return to

    val rnd = Math.random() // 0.0 ~ 1.0
    return from + rnd * (to - from)
  }

  /**
   * 从 shaft.json 当前等级读取 SHAFT_AIMING_MODE_MAX_DAMAGE
   * modifications[n].properties -> AIMING_MODE_DAMAGE -> SHAFT_AIMING_MODE_MAX_DAMAGE
   */
  private fun getAimingModeMaxDamage(): Double {
    val modification = item.modification

    val aimingGroup = modification.properties
      .firstOrNull { it.property == "AIMING_MODE_DAMAGE" }

    val maxDamageProp = aimingGroup
      ?.properties
      ?.firstOrNull { it.property == "SHAFT_AIMING_MODE_MAX_DAMAGE" }

    return when(val v = maxDamageProp?.value) {
      is Number -> v.toDouble()
      is String -> v.toDoubleOrNull() ?: 0.0
      else -> 0.0
    }
  }

  /**
   * 从 JSON 里拿“蓄满需要多久”（秒），转成毫秒：
   * WEAPON_CHARGE_RATE -> WEAPON_RELOAD_TIME
   *
   * 你 shaft.json 里：
   *  M0: 3.59s
   *  M1: 3.35s
   *  M2: 3.10s
   *  M3: 3.00s
   */
  private fun getFullChargeTimeMs(): Int {
    val modification = item.modification

    val chargeGroup = modification.properties
      .firstOrNull { it.property == "WEAPON_CHARGE_RATE" }

    val reloadProp = chargeGroup
      ?.properties
      ?.firstOrNull { it.property == "WEAPON_RELOAD_TIME" }

    val seconds = when(val v = reloadProp?.value) {
      is Number -> v.toDouble()
      is String -> v.toDoubleOrNull()
      else -> null
    } ?: 3.0 // 没配就按 3 秒算

    return (seconds * 1000.0).roundToInt()
  }

  /**
   * 计算当前蓄力比例 0.0 ~ 1.0
   *
   * - StartEnergyDrain(time) 里，time = 开始蓄力时的 physTime（单位和 FireTarget.physTime 一样）
   * - FireSniping(target) 时，target.physTime = 开枪时的 physTime
   * - 已蓄力时间 = target.physTime - StartEnergyDrain.time
   * - 蓄力比例 = 已蓄力时间 / 完整蓄力时间（从 JSON 里拿 WEAPON_RELOAD_TIME）
   */
  private fun getChargeFactor(shotPhysTime: Int): Double {
    val start = aimingStartPhysTime ?: return 0.0
    val fullTimeMs = getFullChargeTimeMs()
    if(fullTimeMs <= 0) return 0.0

    val elapsed = (shotPhysTime - start).coerceAtLeast(0)
    return (elapsed.toDouble() / fullTimeMs.toDouble())
      .coerceIn(0.0, 1.0)
  }
}