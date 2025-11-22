//package jp.assasans.protanki.server.battles.effect
//
//import kotlin.time.Duration.Companion.seconds
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//import kotlinx.datetime.Clock
//import jp.assasans.protanki.server.battles.BattleTank
//
//class RepairKitEffect(
//  tank: BattleTank
//) : TankEffect(
//  tank,
//  duration = 3.seconds,
//  cooldown = 20.seconds
//) {
//  override val info: EffectInfo
//    get() = EffectInfo(
//      id = 1,
//      name = "health"
//    )
//
//  override suspend fun activate() {
//    val battle = tank.battle
//    val damageProcessor = battle.damageProcessor
//
//    // TODO(Assasans): More complicated logic
//    damageProcessor.heal(tank, 1500.0)
//
//    if(duration == null) return
//    tank.coroutineScope.launch {
//      val startTime = Clock.System.now()
//      val endTime = startTime + duration
//      while(Clock.System.now() < endTime) {
//        delay(500)
//        if(tank.health < tank.maxHealth) {
//          damageProcessor.heal(tank, 300.0)
//        }
//      }
//    }
//  }
//}
package jp.assasans.protanki.server.battles.effect

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import jp.assasans.protanki.server.battles.BattleTank
import jp.assasans.protanki.server.battles.weapons.FreezeWeaponHandler
import jp.assasans.protanki.server.battles.weapons.FlamethrowerWeaponHandler

class RepairKitEffect(
  tank: BattleTank
) : TankEffect(
  tank,
  duration = 10.seconds,   // ✅ 持续 10 秒
  cooldown = 20.seconds
) {
  override val info: EffectInfo
    get() = EffectInfo(
      id = 1,
      name = "health"
    )

  override suspend fun activate() {
    val battle = tank.battle
    val damageProcessor = battle.damageProcessor

    // ======================
    // 1. 使用时立刻解除冰冻 / 烧伤
    // ======================
    try {
      // 冰冻在 FreezeWeaponHandler 的 companion object 里，
      // 是 suspend fun，所以这里可以直接调用
      FreezeWeaponHandler.cancelFreeze(tank)
    } catch (_: Throwable) {
      // 防御式：就算没实现 / 抛异常，也不要让血包崩掉
    }

    try {
      // 火焰灼烧只需要 tankId 即可
      FlamethrowerWeaponHandler.cancelBurn(tank.id)
    } catch (_: Throwable) {
      // ignore
    }

    // ======================
    // 2. 持续 10 秒的百分比回复
    //    前 5 秒：每 0.5 秒 +10% maxHP
    //    后 5 秒：每 0.5 秒 +5% maxHP
    // ======================

    val maxHp = tank.maxHealth

    // 一共 20 个 tick
    val totalTicks  = 20
    val tickMillis  = 500L

    val healPerTickFirst5s = maxHp * 0.10  // 前 10 tick
    val healPerTickLast5s  = maxHp * 0.05  // 后 10 tick

    if(duration == null) return

    tank.coroutineScope.launch {
      val startTime = Clock.System.now()
      val endTime   = startTime + duration

      var tick = 0
      while(Clock.System.now() < endTime && tick < totalTicks) {
        delay(tickMillis)

        // 坦克死了就不用加血了
        if(tank.health <= 0.0) break

        // 复活后也能继续吃后面的 tick
        val healAmount = if(tick < 10) {
          healPerTickFirst5s
        } else {
          healPerTickLast5s
        }

        if(tank.health < tank.maxHealth) {
          damageProcessor.heal(tank, healAmount)
        }

        tick++
      }
    }
  }
}
