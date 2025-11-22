package jp.assasans.protanki.server.battles.weapons

import jp.assasans.protanki.server.battles.*
import jp.assasans.protanki.server.battles.mode.TeamModeHandler
import jp.assasans.protanki.server.client.send
import jp.assasans.protanki.server.client.weapons.isida.IsidaFireMode
import jp.assasans.protanki.server.client.weapons.isida.ResetTarget
import jp.assasans.protanki.server.client.weapons.isida.SetTarget
import jp.assasans.protanki.server.client.weapons.isida.StartFire
import jp.assasans.protanki.server.client.toJson
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.garage.ServerGarageItemProperty
import jp.assasans.protanki.server.garage.ServerGarageUserItemWeapon

class IsidaWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  private var fireStarted = false

  /**
   * 从 JSON 读取数值，做到“配置驱动”的自奶 / 奶队友：
   *
   * - 自己回血百分比：modification.properties -> ISIS_SELF_HEALING_PERCENT
   * - 队友回血比例：damage.heal.fixed.value / damage.fixed.value
   *
   * 以后只改 isida.json 就能调数值，无需改 Kotlin 代码。
   */

  private val modification
    get() = item.modification   // ServerGarageItemWeaponModification

  /** 递归搜索某个 property 名称 */
  private fun List<ServerGarageItemProperty>.findProperty(name: String): ServerGarageItemProperty? {
    fun ServerGarageItemProperty.find(name: String): ServerGarageItemProperty? {
      if(property == name) return this
      properties?.forEach { child ->
        val found = child.find(name)
        if(found != null) return found
      }
      return null
    }

    for(prop in this) {
      val found = prop.find(name)
      if(found != null) return found
    }
    return null
  }

  /** 从 JSON 里取当前改装的 ISIS_SELF_HEALING_PERCENT（例如 35.97） */
  private fun selfHealPercent(): Double {
    val prop = modification.properties.findProperty("ISIS_SELF_HEALING_PERCENT")
      ?: return 0.0

    return when(val raw = prop.value) {
      is Number -> raw.toDouble()
      is String -> raw.toDoubleOrNull() ?: 0.0
      else      -> 0.0
    }
  }

  /** 自奶系数：百分比 / 100 */
  private fun selfHealFactor(): Double = selfHealPercent() / 100.0

  /**
   * 队友回血系数：
   * 来自 JSON 中的 damage.heal.fixed.value / damage.fixed.value
   * 这两个字段都在 isida.json -> damage 里定义。
   */
  private fun allyHealFactor(): Double {
    val damageConfig = modification.damage

    val healFixed = damageConfig.heal?.fixed?.value
    val dmgFixed = damageConfig.fixed?.value

    if(healFixed == null || dmgFixed == null || dmgFixed == 0.0) return 1.0
    return healFixed / dmgFixed
  }

  suspend fun setTarget(setTarget: SetTarget) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ClientSetTarget, tank.id, setTarget.toJson())
      .send(battle.players.exclude(player).ready())
  }

  suspend fun resetTarget(resetTarget: ResetTarget) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ClientResetTarget, tank.id, resetTarget.toJson())
      .send(battle.players.exclude(player).ready())
  }

  suspend fun fireStart(startFire: StartFire) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val targetTank = battle.players
      .mapNotNull { it.tank }
      .single { it.id == startFire.target }

    if(targetTank.state != TankState.Active) return

    val fireMode = when(battle.modeHandler) {
      is TeamModeHandler ->
        if(targetTank.player.team == sourceTank.player.team) IsidaFireMode.Heal
        else IsidaFireMode.Damage

      else -> IsidaFireMode.Damage
    }

    // TODO(Assasans): Damage timing is not checked on server, exploitation is possible
    if(fireStarted) {
      when(fireMode) {
        IsidaFireMode.Damage -> {
          val result = damageCalculator.calculate(sourceTank, targetTank)

          // 敌人掉血：完整走伤害计算（距离削弱 / 溅射等）
          battle.damageProcessor.dealDamage(
            sourceTank,
            targetTank,
            result.damage,
            isCritical = result.isCritical
          )

          // 自己回血：按 ISIS_SELF_HEALING_PERCENT 百分比
          val selfHeal = result.damage * selfHealFactor()
          // 自奶不吃双倍攻击，用单参数的 heal
          battle.damageProcessor.heal(sourceTank, selfHeal)
        }

        IsidaFireMode.Heal -> {
          val result = damageCalculator.calculate(sourceTank, targetTank)

          // 队友回血：按 JSON 中 heal.fixed / fixed 的比例
          val allyHeal = result.damage * allyHealFactor()
          // 用带 source 的 heal，让 DamageProcessor 去应用双倍攻击加成
          battle.damageProcessor.heal(sourceTank, targetTank, allyHeal)
        }
      }
      return
    }

    fireStarted = true

    val setTarget = SetTarget(
      physTime = startFire.physTime,
      target = startFire.target,
      incarnation = startFire.incarnation,
      localHitPoint = startFire.localHitPoint,
      actionType = fireMode
    )

    Command(CommandName.ClientSetTarget, sourceTank.id, setTarget.toJson())
      .send(battle.players.exclude(player).ready())
  }

  suspend fun fireStop() {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    fireStarted = false

    Command(CommandName.ClientStopFire, tank.id)
      .send(battle.players.exclude(player).ready())
  }
}
