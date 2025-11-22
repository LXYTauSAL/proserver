//package jp.assasans.protanki.server.battles
//
//import java.math.RoundingMode
//import kotlin.random.Random
//import mu.KotlinLogging
//import jp.assasans.protanki.server.battles.weapons.WeaponHandler
//import jp.assasans.protanki.server.extensions.nextGaussianRange
//import jp.assasans.protanki.server.garage.WeaponDamage
//import jp.assasans.protanki.server.math.Vector3Constants
//
//interface IDamageCalculator {
//  fun calculate(weapon: WeaponHandler, distance: Double, splash: Boolean = false): DamageCalculateResult
//
//  fun getRandomDamage(range: WeaponDamage.Range): Double
//  fun getWeakeningMultiplier(weakening: WeaponDamage.Weakening, distance: Double): Double
//  fun getSplashMultiplier(splash: WeaponDamage.Splash, distance: Double): Double
//}
//
//fun IDamageCalculator.calculate(source: BattleTank, target: BattleTank): DamageCalculateResult {
//  val distance = source.distanceTo(target) * Vector3Constants.TO_METERS
//  return calculate(source.weapon, distance)
//}
//
//class DamageCalculateResult(
//  val damage: Double,
//  val weakening: Double,
//  val isCritical: Boolean
//)
//
//class DamageCalculator : IDamageCalculator {
//  private val logger = KotlinLogging.logger { }
//
//  override fun calculate(weapon: WeaponHandler, distance: Double, splash: Boolean): DamageCalculateResult {
//    val config = weapon.item.modification.damage
//
//    val baseDamage = config.range?.let { range -> getRandomDamage(range) }
//                     ?: config.fixed?.value
//                     ?: throw IllegalStateException("No base damage component found for ${weapon.item.mountName}")
//    val weakening = config.weakening?.let { getWeakeningMultiplier(it, distance) } ?: 1.0
//    val splashDamage = if(splash) config.splash?.let { getSplashMultiplier(it, distance) } ?: 1.0 else 1.0
//
//    var damage = baseDamage
//    damage *= weakening
//    damage *= splashDamage
//
//    logger.debug {
//      buildString {
//        append("${weapon.item.marketItem.name} M${weapon.item.modificationIndex} -> (base: ")
//        append(baseDamage.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toDouble())
//        append(") * (weakening: ")
//        append(weakening.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toDouble())
//        append(") = ")
//        append(damage.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toDouble())
//      }
//    }
//
//    return DamageCalculateResult(damage, weakening, isCritical = false)
//  }
//
//  override fun getRandomDamage(range: WeaponDamage.Range): Double {
//    return Random.nextGaussianRange(range.from, range.to)
//  }
//
//  override fun getWeakeningMultiplier(weakening: WeaponDamage.Weakening, distance: Double): Double {
//    val maximumDamageRadius = weakening.from
//    val minimumDamageRadius = weakening.to
//    val minimumDamageMultiplier = weakening.minimum
//
//    if(maximumDamageRadius >= minimumDamageRadius) throw IllegalArgumentException("maximumDamageRadius must be more than minimumDamageRadius")
//
//    return when {
//      distance <= maximumDamageRadius -> 1.0
//      distance >= minimumDamageRadius -> minimumDamageMultiplier
//      else                            -> minimumDamageMultiplier + (minimumDamageRadius - distance) * (1.0 - minimumDamageMultiplier) / (minimumDamageRadius - maximumDamageRadius)
//    }
//  }
//
//  // TODO(Assasans): Incorrect implementation
//  override fun getSplashMultiplier(splash: WeaponDamage.Splash, distance: Double): Double {
//    val minimumDamage = splash.from
//    val maximumDamage = splash.to
//    val radius = splash.radius
//
//    if(minimumDamage > maximumDamage) throw IllegalArgumentException("maximumDamage must be more than minimumDamage")
//
////    return when {
////      distance >= radius -> minimumDamage
////      else               -> (1.0 - distance / radius) * (maximumDamage - minimumDamage)
////    }
//    return when {
//      distance >= radius -> minimumDamage
//      else               -> minimumDamage + (1.0 - distance / radius) * (maximumDamage - minimumDamage)
//    }
//  }
//}

package jp.assasans.protanki.server.battles

import java.math.RoundingMode
import kotlin.random.Random
import mu.KotlinLogging
import jp.assasans.protanki.server.battles.weapons.WeaponHandler
import jp.assasans.protanki.server.extensions.nextGaussianRange
import jp.assasans.protanki.server.garage.WeaponDamage
import jp.assasans.protanki.server.math.Vector3Constants

interface IDamageCalculator {
  fun calculate(weapon: WeaponHandler, distance: Double, splash: Boolean = false): DamageCalculateResult

  fun getRandomDamage(range: WeaponDamage.Range): Double
  fun getWeakeningMultiplier(weakening: WeaponDamage.Weakening, distance: Double): Double
  fun getSplashMultiplier(splash: WeaponDamage.Splash, distance: Double): Double
}

fun IDamageCalculator.calculate(source: BattleTank, target: BattleTank): DamageCalculateResult {
  val distance = source.distanceTo(target) * Vector3Constants.TO_METERS
  return calculate(source.weapon, distance)
}

class DamageCalculateResult(
  val damage: Double,
  val weakening: Double,
  val isCritical: Boolean
)

class DamageCalculator : IDamageCalculator {
  private val logger = KotlinLogging.logger { }

  override fun calculate(weapon: WeaponHandler, distance: Double, splash: Boolean): DamageCalculateResult {
    val config = weapon.item.modification.damage

    val weaponId = weapon.item.marketItem.id
    val isLaserLike = weaponId == "railgun" || weaponId == "shaft"
    val isThunder = weaponId == "thunder"

    // =========================
    // 1. 雷暴溅射：单独逻辑
    // =========================
    if(splash && isThunder && config.range != null && config.splash != null) {
      val splashDamage = getThunderSplashDamage(
        range = config.range,
        splash = config.splash,
        distance = distance
      )

      logger.debug {
        "Thunder splash -> dist=${distance.toBigDecimal().setScale(2, RoundingMode.HALF_UP)} " +
                "dmg=${splashDamage.toBigDecimal().setScale(3, RoundingMode.HALF_UP)}"
      }

      // < 0 表示范围外，不造成伤害
      return DamageCalculateResult(
        damage = if(splashDamage < 0.0) 0.0 else splashDamage,
        weakening = 1.0,
        isCritical = false
      )
    }

    // 给客户端用的衰减（特效用），沿用 weakening 曲线
    val visualWeakening =
      config.weakening?.let { getWeakeningMultiplier(it, distance) } ?: 1.0

    // =========================
    // 2. 基础伤害（不含通用溅射）
    // =========================
    val baseDamage: Double = when {
      // 离散型武器：有 range
      config.range != null -> {
        when {
          isLaserLike -> {
            // railgun / shaft：始终在 [min,max] 随机，完全不看距离
            getRandomDamage(config.range)
          }

          else -> {
            // 除了激光 / 镭射的所有炮塔：
            // 正常距离内：[min,max] 随机；
            // 超出距离后，从 minDamage 线性衰减到 0。
            getRangedWeaponDamage(
              range = config.range,
              weakening = config.weakening,
              distance = distance
            )
          }
        }
      }

      // 固定伤害（火焰 / 冰冻 / Isida 之类）
      config.fixed != null -> config.fixed.value

      else -> throw IllegalStateException("No base damage component found for ${weapon.item.mountName}")
    }

    var damage = baseDamage

    // =========================
    // 3. 持续伤害类的 weakening 衰减
    // =========================
    // 对于 range 型炮塔，我们已经在 getRangedWeaponDamage 里处理了距离衰减，
    // 这里只给 fixed 型（火焰、冰冻、Isida）再乘 weakening。
    if(config.range == null && config.weakening != null) {
      damage *= visualWeakening
    }

    // =========================
    // 4. 通用溅射（目前雷暴溅射不用这个）
    // =========================
    val splashMultiplier =
      if(splash) config.splash?.let { getSplashMultiplier(it, distance) } ?: 1.0 else 1.0
    damage *= splashMultiplier

    logger.debug {
      buildString {
        append("${weapon.item.marketItem.name} M${weapon.item.modificationIndex} -> ")
        append("dist=")
        append(distance.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toDouble())
        append(" base=")
        append(baseDamage.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toDouble())
        append(" weakening=")
        append(visualWeakening.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toDouble())
        append(" splash=")
        append(splashMultiplier.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toDouble())
        append(" => dmg=")
        append(damage.toBigDecimal().setScale(3, RoundingMode.HALF_UP).toDouble())
      }
    }

    return DamageCalculateResult(damage, visualWeakening, isCritical = false)
  }

  /**
   * 非 rail/shaft 的所有炮塔（包括 Smoky/Thunder/Twins/Ricochet 等）：
   *
   * - weakening.to = 正常射击距离上限 normalRange
   *   - distance <= normalRange: 伤害在 [minDamage, maxDamage] 范围随机
   *
   * - 超出 normalRange 后，使用一段 falloff 距离从 minDamage 线性衰减到 0：
   *   - falloff = weakening.to - weakening.from
   *   - maxRange = normalRange + falloff
   *   - distance >= maxRange: 伤害 = 0
   */
  private fun getRangedWeaponDamage(
    range: WeaponDamage.Range,
    weakening: WeaponDamage.Weakening?,
    distance: Double
  ): Double {
    val minDamage = range.from
    val maxDamage = range.to

    // 没配 weakening 的话，就一直在 [min,max] 随机（理论上不会出现）
    if(weakening == null) {
      return Random.nextDouble(minDamage, maxDamage)
    }

    val normalRange = weakening.to

    if(distance <= normalRange) {
      // 正常射击距离内：[min,max] 随机
      return Random.nextDouble(minDamage, maxDamage)
    }

    // 超出正常距离后的衰减区间长度：用 (to - from) 这段
    val falloffDistance = (weakening.to - weakening.from).let {
      if(it <= 0.0) normalRange * 0.5 else it
    }
    val maxRange = normalRange + falloffDistance

    // 超出衰减区间：直接 0 伤害
    if(distance >= maxRange) return 0.0

    // t：normalRange 时为 1，maxRange 时为 0
    val t = 1.0 - (distance - normalRange) / falloffDistance
    val clampedT = t.coerceIn(0.0, 1.0)

    // 超出距离以后，只按最小伤害往下衰减
    val damage = minDamage * clampedT
    return damage.coerceAtLeast(0.0)
  }

  /**
   * 雷暴溅射伤害：
   * - 溅射最大伤害 = range.from（主炮伤害区间的最小值）
   * - 命中点为圆心，distance 越大，伤害越低
   * - 在贴近命中点的一小段范围内，始终是满伤
   * - 超出 radius 不造成伤害（返回 -1）
   */
  private fun getThunderSplashDamage(
    range: WeaponDamage.Range,
    splash: WeaponDamage.Splash,
    distance: Double
  ): Double {
    val maxSplashDamage = range.from
    val radius = splash.radius

    if(radius <= 0.0) return 0.0

    // 在范围外：直接认为没有伤害
    if(distance >= radius) return -1.0

    // 内圈比例（比如 0.3 表示半径 30% 内都吃满伤）
    val innerRadiusFactor = 0.3
    val innerRadius = radius * innerRadiusFactor

    return if(distance <= innerRadius) {
      maxSplashDamage
    } else {
      // 超出内圈，开始线性衰减到 0
      val t = 1.0 - (distance - innerRadius) / (radius - innerRadius)
      val clampedT = t.coerceIn(0.0, 1.0)
      (maxSplashDamage * clampedT).coerceAtLeast(0.0)
    }
  }

  override fun getRandomDamage(range: WeaponDamage.Range): Double {
    // 高斯分布：中间概率高，两边概率低
    return Random.nextGaussianRange(range.from, range.to)
  }

  override fun getWeakeningMultiplier(weakening: WeaponDamage.Weakening, distance: Double): Double {
    val maximumDamageRadius = weakening.from
    val minimumDamageRadius = weakening.to
    val minimumDamageMultiplier = weakening.minimum

    if(maximumDamageRadius >= minimumDamageRadius) {
      throw IllegalArgumentException("maximumDamageRadius must be less than minimumDamageRadius")
    }

    return when {
      distance <= maximumDamageRadius -> 1.0
      distance >= minimumDamageRadius -> minimumDamageMultiplier
      else -> {
        val t = (distance - maximumDamageRadius) / (minimumDamageRadius - maximumDamageRadius)
        1.0 - t * (1.0 - minimumDamageMultiplier)
      }
    }
  }

  override fun getSplashMultiplier(splash: WeaponDamage.Splash, distance: Double): Double {
    val minimumDamage = splash.from
    val maximumDamage = splash.to
    val radius = splash.radius

    if(minimumDamage > maximumDamage) {
      throw IllegalArgumentException("maximumDamage must be more than maximumDamage")
    }

    return when {
      distance >= radius -> minimumDamage
      else -> minimumDamage + (1.0 - distance / radius) * (maximumDamage - minimumDamage)
    }
  }
}
