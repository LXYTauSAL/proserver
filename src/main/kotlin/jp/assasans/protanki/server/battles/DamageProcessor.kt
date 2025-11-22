package jp.assasans.protanki.server.battles

import jp.assasans.protanki.server.battles.effect.DoubleArmorEffect
import jp.assasans.protanki.server.battles.effect.DoubleDamageEffect
import jp.assasans.protanki.server.battles.effect.TankEffect
import jp.assasans.protanki.server.battles.mode.TeamModeHandler
import jp.assasans.protanki.server.client.send
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.extensions.singleOrNullOf

enum class DamageType(val id: Int, val key: String) {
  Normal(0, "NORMAL"),
  Critical(1, "CRITICAL"),
  Kill(2, "FATAL"),
  Heal(3, "HEAL");

  companion object {
    private val map = values().associateBy(DamageType::key)

    fun get(key: String) = map[key]
  }
}

interface IDamageProcessor {
  val battle: Battle

  suspend fun dealDamage(source: BattleTank, target: BattleTank, damage: Double, isCritical: Boolean, ignoreSourceEffects: Boolean = false, damageSource: String? = null )
  suspend fun dealDamage(target: BattleTank, damage: Double, isCritical: Boolean): DamageType

  suspend fun heal(source: BattleTank, target: BattleTank, heal: Double)
  suspend fun heal(target: BattleTank, heal: Double)
}

class DamageProcessor(
  override val battle: Battle
) : IDamageProcessor {
  override suspend fun dealDamage(
    source: BattleTank,
    target: BattleTank,
    damage: Double,
    isCritical: Boolean,
    ignoreSourceEffects: Boolean,
    damageSource: String?          // ★ 接上新参数
  ) {
    var totalDamage = damage

    if(!battle.properties[BattleProperty.DamageEnabled]) return

    var dealDamage = true
    if(battle.modeHandler is TeamModeHandler) {
      if(source.player.team == target.player.team &&
        !battle.properties[BattleProperty.FriendlyFireEnabled]) {
        dealDamage = false
      }
    }
    if(source == target &&
      battle.properties[BattleProperty.SelfDamageEnabled]) {
      dealDamage = true // TODO: 按武器细分
    }
    if(!dealDamage) return

    if(!ignoreSourceEffects) {
      source.effects.singleOrNullOf<TankEffect, DoubleDamageEffect>()?.let { effect ->
        totalDamage *= effect.multiplier
      }
    }

    target.effects.singleOrNullOf<TankEffect, DoubleArmorEffect>()?.let { effect ->
      totalDamage /= effect.multiplier
    }

    // === 涂装抗性：支持通过 damageSource 指定来源（比如 "mine"） ===
    val paintMultiplier = target.getPaintResistanceMultiplier(source, damageSource)
    totalDamage *= paintMultiplier
    // === 涂装抗性结束 ===

    val damageType = dealDamage(target, totalDamage, isCritical)
    if(damageType == DamageType.Kill) {
      target.killBy(source)
    }

    Command(
      CommandName.DamageTank,
      target.id,
      totalDamage.toString(),
      damageType.key
    ).send(source)
  }

  override suspend fun dealDamage(target: BattleTank, damage: Double, isCritical: Boolean): DamageType {
    var damageType = if(isCritical) DamageType.Critical else DamageType.Normal

    target.health = (target.health - damage).coerceIn(0.0, target.maxHealth)
    target.updateHealth()
    if(target.health <= 0.0) {
      damageType = DamageType.Kill
    }

    return damageType
  }

/*  override suspend fun heal(source: BattleTank, target: BattleTank, heal: Double) {

    var totalHeal = heal

    // 双倍攻击加成到“给队友治疗”
    source.effects.singleOrNullOf<TankEffect, DoubleDamageEffect>()?.let { effect ->
      totalHeal *= effect.multiplier
    }

    heal(target, heal)

    Command(CommandName.DamageTank, target.id, heal.toString(), DamageType.Heal.key).send(source)
  }*/

  override suspend fun heal(source: BattleTank, target: BattleTank, heal: Double) {
    var totalHeal = heal

    // 双倍攻击加成到“给队友治疗”
    source.effects.singleOrNullOf<TankEffect, DoubleDamageEffect>()?.let { effect ->
      totalHeal *= effect.multiplier
    }

    // ✅ 这里要用 totalHeal，而不是原来的 heal
    heal(target, totalHeal)

    // ✅ 发给客户端的数值也要是 totalHeal
    Command(
      CommandName.DamageTank,
      target.id,
      totalHeal.toString(),
      DamageType.Heal.key
    ).send(source)
  }

  override suspend fun heal(target: BattleTank, heal: Double) {
    target.health = (target.health + heal).coerceIn(0.0, target.maxHealth)
    target.updateHealth()
  }
}
