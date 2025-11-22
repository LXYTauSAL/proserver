package jp.assasans.protanki.server.battles.weapons

import jp.assasans.protanki.server.battles.*
import jp.assasans.protanki.server.battles.mode.TeamModeHandler
import jp.assasans.protanki.server.client.send
import jp.assasans.protanki.server.client.weapons.railgun.FireTarget
import jp.assasans.protanki.server.client.toJson
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.garage.ServerGarageUserItemWeapon

class RailgunWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {

  /** 当前改装等级的穿透伤害比例（每穿 1 个敌人就再乘一次） */
  private fun penetrationFactor(): Double = when (item.modificationIndex) {
    0 -> 0.10  // M0：每多穿 1 个敌人，伤害 *0.10
    1 -> 0.25  // M1：*0.25
    2 -> 0.50  // M2：*0.50
    else -> 1.0 // M3：*1.00（无衰减）
  }

  /** 只把敌方坦克计入穿透序列，友军不参与穿透、不扣血 */
  private fun isEnemy(source: BattleTank, target: BattleTank): Boolean {
    if (source == target) return true // 自伤仍由 DamageProcessor 里的 SelfDamageEnabled 控制

    val battle = source.battle
    return when (battle.modeHandler) {
      is TeamModeHandler -> source.player.team != target.player.team
      else -> true // 死斗模式：所有人互为敌人
    }
  }

  suspend fun fireStart() {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.StartFire, tank.id)
      .send(battle.players.exclude(player).ready())
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    // 按原来的方式保持顺序
    val targetTanks = target.targets
      .mapNotNull { username ->
        battle.players.singleOrNull { p -> p.user.username == username }
      }
      .mapNotNull { p -> p.tank }
      .filter { t -> target.targets.contains(t.id) }
      .filter { t -> t.state == TankState.Active }

    // 只保留敌方坦克作为穿透序列
    val enemyTanksInOrder = targetTanks.filter { t -> isEnemy(sourceTank, t) }

    if (enemyTanksInOrder.isEmpty()) {
      // 没有敌人命中，仍广播一次 ShotTarget，让客户端显示射击效果
      Command(CommandName.ShotTarget, sourceTank.id, target.toJson())
        .send(battle.players.exclude(player).ready())
      return
    }

    val factor = penetrationFactor()
    var currentDamage: Double? = null

    enemyTanksInOrder.forEachIndexed { index, targetTank ->
      if (index == 0) {
        // 第一个敌人：正常算一次伤害，记作当前伤害
        val result = damageCalculator.calculate(sourceTank, targetTank)
        currentDamage = result.damage
      } else {
        // 后续每个敌人：在上一个敌人基础上再乘一次比例
        currentDamage = (currentDamage ?: return@forEachIndexed) * factor
      }

      val dmg = currentDamage ?: return@forEachIndexed
      if (dmg <= 0.0) return@forEachIndexed

      battle.damageProcessor.dealDamage(
        sourceTank,
        targetTank,
        dmg,
        isCritical = false // 如果以后要开暴击可以改成 result.isCritical（需要保存）
      )
    }

    Command(CommandName.ShotTarget, sourceTank.id, target.toJson())
      .send(battle.players.exclude(player).ready())
  }
}
