package jp.assasans.protanki.server.battles.weapons

import jp.assasans.protanki.server.battles.*
import kotlin.random.Random
import jp.assasans.protanki.server.client.send
import jp.assasans.protanki.server.client.weapons.smoky.Fire
import jp.assasans.protanki.server.client.weapons.smoky.FireStatic
import jp.assasans.protanki.server.client.weapons.smoky.FireTarget
import jp.assasans.protanki.server.client.weapons.smoky.ShotTarget
import jp.assasans.protanki.server.client.toJson
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.garage.ServerGarageUserItemWeapon

class SmokyWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {

  suspend fun fire(fire: Fire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.Shot, tank.id, fire.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun fireStatic(static: FireStatic) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ShotStatic, tank.id, static.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val targetTank = battle.players
      .mapNotNull { player -> player.tank }
      .single { tank -> tank.id == target.target }
    if(targetTank.state != TankState.Active) return

    // 先用通用伤害计算器算出基础伤害（考虑距离削弱等）
    val baseDamage = damageCalculator.calculate(sourceTank, targetTank)

    // 从 smoky.json 当前等级读取暴击几率（百分比）和暴击伤害（固定值）
    val critChancePercent = getCriticalChance()
    val critDamage = getCriticalDamage()

    // 暴击判定：0~100 的随机数，小于暴击几率则触发
    val roll = Random.nextDouble(0.0, 100.0)
    val isCriticalHit = critChancePercent > 0.0 && critDamage > 0.0 && roll < critChancePercent

    // 暴击时用固定暴击伤害；否则用普通随机伤害
    val finalDamage = if(isCriticalHit) critDamage else baseDamage.damage

    battle.damageProcessor.dealDamage(
      sourceTank,
      targetTank,
      finalDamage,
      isCriticalHit
    )

    // weakening 用基础伤害算出来的削弱系数；critical 告诉客户端这是暴击
    val shot = ShotTarget(target, baseDamage.weakening, isCriticalHit)
    Command(CommandName.ShotTarget, sourceTank.id, shot.toJson()).send(battle.players.exclude(player).ready())
  }

  // 从当前 smoky 等级的 json 里拿暴击几率（百分制，比如 15 表示 15%）
  private fun getCriticalChance(): Double {
    val modification = item.modification

    val critChanceProp = modification.properties
      .firstOrNull { it.property == "CRITICAL_HIT_CHANCE" }

    return when(val v = critChanceProp?.value) {
      is Number -> v.toDouble()
      is String -> v.toDoubleOrNull() ?: 0.0
      else -> 0.0
    }
  }

  // 从当前 smoky 等级的 json 里拿固定暴击伤害
  private fun getCriticalDamage(): Double {
    val modification = item.modification

    val critDamageProp = modification.properties
      .firstOrNull { it.property == "CRITICAL_HIT_DAMAGE" }

    return when(val v = critDamageProp?.value) {
      is Number -> v.toDouble()
      is String -> v.toDoubleOrNull() ?: 0.0
      else -> 0.0
    }
  }
}
