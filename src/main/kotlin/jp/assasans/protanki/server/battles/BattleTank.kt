package jp.assasans.protanki.server.battles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.floor
import jp.assasans.protanki.server.ISocketServer
import jp.assasans.protanki.server.battles.effect.TankEffect
import jp.assasans.protanki.server.battles.mode.CaptureTheFlagModeHandler
import jp.assasans.protanki.server.battles.mode.FlagCarryingState
import jp.assasans.protanki.server.battles.mode.TeamDeathmatchModeHandler
import jp.assasans.protanki.server.battles.mode.TeamModeHandler
import jp.assasans.protanki.server.battles.weapons.WeaponHandler
import jp.assasans.protanki.server.client.*
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.garage.ServerGarageUserItemHull
import jp.assasans.protanki.server.garage.ServerGarageUserItemPaint
import jp.assasans.protanki.server.math.Quaternion
import jp.assasans.protanki.server.math.Vector3
import jp.assasans.protanki.server.math.distanceTo
import jp.assasans.protanki.server.quests.KillEnemyQuest
import jp.assasans.protanki.server.quests.questOf
import jp.assasans.protanki.server.toVector


object TankConstants {
  const val MAX_HEALTH: Double = 10000.0
}

class BattleTank(
  val id: String,
  val player: BattlePlayer,
  val incarnation: Int = 1,
  var state: TankState,
  var position: Vector3,
  var orientation: Quaternion,
  val hull: ServerGarageUserItemHull,
  val weapon: WeaponHandler,
  val coloring: ServerGarageUserItemPaint,
  // 从底盘配置中的 HULL_ARMOR 计算最大血量，如果没有就用一个兜底值
  var maxHealth: Double = hull.getHullArmorOrDefault(),
  var health: Double = maxHealth
  //var maxHealth: Double = 4000.0, // TODO(Assasans): Load from config
  //var health: Double = 4000.0
) : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val server: ISocketServer by inject()

  val socket: UserSocket
    get() = player.socket

  val battle: Battle
    get() = player.battle

  val coroutineScope = CoroutineScope(player.coroutineScope.coroutineContext + SupervisorJob())

  val effects: MutableList<TankEffect> = mutableListOf()

  var selfDestructing: Boolean = false

  val clientHealth: Int
    get() = floor((health / maxHealth) * TankConstants.MAX_HEALTH).toInt()

  suspend fun activate() {
    if(state == TankState.Active) return

    state = TankState.Active

    player.battle.players.users().forEach { player ->
      val tank = player.tank
      if(tank != null && tank != this) {
        Command(CommandName.ActivateTank, tank.id).send(socket)
      }
    }

    Command(CommandName.ActivateTank, id).sendTo(battle)
  }

  suspend fun deactivate(terminate: Boolean = false) {
    coroutineScope.cancel()

    if(!terminate) {
      effects.forEach { effect ->
        effect.deactivate()
      }
    }
    effects.clear()

    if(terminate || battle.properties[BattleProperty.DeactivateMinesOnDeath]) {
      battle.mineProcessor.deactivateAll(player)
    }
  }

  private suspend fun killSelf() {
    deactivate()
    state = TankState.Dead

    player.deaths++
    player.updateStats()

    (battle.modeHandler as? CaptureTheFlagModeHandler)?.let { handler ->
      val flag = handler.flags[player.team.opposite]
      if(flag is FlagCarryingState && flag.carrier == this) {
        handler.dropFlag(flag.team, this, position)
      }
    }

    Command(CommandName.KillLocalTank).send(socket)
  }

  suspend fun killBy(killer: BattleTank) {
    killSelf()

    Command(
      CommandName.KillTank,
      id,
      TankKillType.ByPlayer.key,
      killer.id
    ).sendTo(battle)

    // 个人击杀数
    killer.player.kills++
    killer.player.updateStats()

    // DM 模式：根据击杀数检查是否需要结算（最大摧毁数）
    battle.onPlayerKill(killer.player)

    Command(
      CommandName.UpdatePlayerKills,
      battle.id,
      killer.player.user.username,
      killer.player.kills.toString()
    ).let { command ->
      server.players
        .filter { player -> player.screen == Screen.BattleSelect }
        .filter { player -> player.active }
        .forEach { player -> command.send(player) }
    }

    // ====== 新增：团队比分（只给 TDM 用） ======
    val modeHandler = battle.modeHandler
    if(modeHandler is TeamDeathmatchModeHandler) {
      // 只有 TDM 模式击杀才给队伍加分
      modeHandler.addTeamScore(killer.player.team)
    }
    // =====================================

    // 任务逻辑（保持和你原来的一样）
    player.user.questOf<KillEnemyQuest> { quest ->
      quest.mode == null || quest.mode == battle.modeHandler.mode
    }?.let { quest ->
      quest.current++
      socket.updateQuests()
      quest.updateProgress()
    }
  }



  suspend fun selfDestruct(silent: Boolean = false) {
    killSelf()

    if(silent) {
      Command(CommandName.KillTankSilent, id).sendTo(battle)
    } else {
      Command(
        CommandName.KillTank,
        id,
        TankKillType.SelfDestruct.key,
        id
      ).sendTo(battle)
    }
  }

  fun updateSpawnPosition() {
    // TODO(Assasans): Special handling for CP: https://web.archive.org/web/20160310101712/http://ru.tankiwiki.com/%D0%9A%D0%BE%D0%BD%D1%82%D1%80%D0%BE%D0%BB%D1%8C_%D1%82%D0%BE%D1%87%D0%B5%D0%BA
    val point = battle.map.spawnPoints
      .filter { point -> point.mode == null || point.mode == battle.modeHandler.mode }
      .filter { point -> point.team == null || point.team == player.team }
      .random()
    position = point.position.toVector()
    position.z += 200
    orientation.fromEulerAngles(point.position.toVector())

    logger.debug { "Spawn point: $position, $orientation" }
  }

  suspend fun prepareToSpawn() {
    Command(
      CommandName.PrepareToSpawn,
      id,
      "${position.x}@${position.y}@${position.z}@${orientation.toEulerAngles().z}"
    ).send(this)
  }

  suspend fun initSelf() {
    Command(
      CommandName.InitTank,
      getInitTank().toJson()
    ).send(battle.players.ready())
  }

  suspend fun spawn() {
    state = TankState.SemiActive

    // TODO(Assasans): Add spawn event?
    if(player.equipmentChanged) {
      player.equipmentChanged = false
      player.changeEquipment()
    }

    updateHealth()

    Command(
      CommandName.SpawnTank,
      getSpawnTank().toJson()
    ).send(battle.players.ready())
  }

  suspend fun updateHealth() {
    logger.debug { "Updating health for tank $id (player: ${player.user.username}): $health HP / $maxHealth HP -> $clientHealth" }

    Command(
      CommandName.ChangeHealth,
      id,
      clientHealth.toString()
    ).apply {
      send(this@BattleTank)
      sendTo(battle, SendTarget.Spectators)
      if(battle.modeHandler is TeamModeHandler) {
        battle.players
          .filter { player -> player.team == this@BattleTank.player.team }
          .filter { player -> player != this@BattleTank.player }
          .forEach { player -> send(player.socket) }
      }
    }
  }
}

fun BattleTank.distanceTo(another: BattleTank): Double {
  return position.distanceTo(another.position)
}

fun BattleTank.getInitTank() = InitTankData(
  battleId = battle.id,
  hull_id = hull.mountName,
  turret_id = weapon.item.mountName,
  colormap_id = coloring.marketItem.coloring,
  hullResource = hull.modification.object3ds,
  turretResource = weapon.item.modification.object3ds,
  partsObject = TankSoundsData().toJson(),
  tank_id = id,
  nickname = player.user.username,
  team_type = player.team,
  state = state.tankInitKey,
  health = clientHealth,

  // Hull physics
  maxSpeed = hull.modification.physics.speed,
  maxTurnSpeed = hull.modification.physics.turnSpeed,
  acceleration = hull.modification.physics.acceleration,
  reverseAcceleration = hull.modification.physics.reverseAcceleration,
  sideAcceleration = hull.modification.physics.sideAcceleration,
  turnAcceleration = hull.modification.physics.turnAcceleration,
  reverseTurnAcceleration = hull.modification.physics.reverseTurnAcceleration,
  dampingCoeff = hull.modification.physics.damping,
  mass = hull.modification.physics.mass,
  power = hull.modification.physics.power,

  // Weapon physics
  turret_turn_speed = weapon.item.modification.physics.turretRotationSpeed,
  turretTurnAcceleration = weapon.item.modification.physics.turretTurnAcceleration,
  kickback = weapon.item.modification.physics.kickback,
  impact_force = weapon.item.modification.physics.impactForce,

  // Weapon visual
  sfxData = (weapon.item.modification.visual ?: weapon.item.marketItem.modifications[0]!!.visual)!!.toJson() // TODO(Assasans)
)

fun BattleTank.getSpawnTank() = SpawnTankData(
  tank_id = id,
  health = clientHealth,
  incration_id = player.incarnation,
  team_type = player.team,
  x = position.x,
  y = position.y,
  z = position.z,
  rot = orientation.toEulerAngles().z,

  // Hull physics
  speed = hull.modification.physics.speed,
  turn_speed = hull.modification.physics.turnSpeed,
  acceleration = hull.modification.physics.acceleration,
  reverseAcceleration = hull.modification.physics.reverseAcceleration,
  sideAcceleration = hull.modification.physics.sideAcceleration,
  turnAcceleration = hull.modification.physics.turnAcceleration,
  reverseTurnAcceleration = hull.modification.physics.reverseTurnAcceleration,

  // Weapon physics
  turret_rotation_speed = weapon.item.modification.physics.turretRotationSpeed,
  turretTurnAcceleration = weapon.item.modification.physics.turretTurnAcceleration
)

// 从底盘当前改装的 properties 里拿出 HULL_ARMOR 作为血量
private fun ServerGarageUserItemHull.getHullArmorOrDefault(default: Double = 400.0): Double {
  // 当前使用的改装
  val modification = this.modification

  // 找到 property == "HULL_ARMOR" 的那一项
  val armorProperty = modification.properties.firstOrNull { it.property == "HULL_ARMOR" }
  val rawValue = armorProperty?.value

  // value 在 JSON 里是数字，反序列化后一般是 Number（Int/Double），也可能是字符串
  val armor = when (rawValue) {
    is Number -> rawValue.toDouble()
    is String -> rawValue.toDoubleOrNull()
    else      -> null
  }

  return armor ?: default
}


// ==================== 涂装抗性相关 ====================

// 武器 ID -> 涂装属性名 的映射
private val paintResistancePropertyByWeaponId: Map<String, String> = mapOf(
  "smoky"       to "SMOKY_RESISTANCE",
  "twins"       to "TWINS_RESISTANCE",
  "ricochet"    to "RICOCHET_RESISTANCE",
  "thunder"     to "THUNDER_RESISTANCE",
  "railgun"     to "RAILGUN_RESISTANCE",
  "shaft"       to "SHAFT_RESISTANCE",
  "flamethrower" to "FIREBIRD_RESISTANCE",
  "freeze"      to "FREEZE_RESISTANCE",
  "isida"       to "ISIS_RESISTANCE",   // 旧名 ISIS，这里 JSON 用的是 ISIS_RESISTANCE
  "mine"        to "MINE_RESISTANCE"   // ★ 新增：地雷 -> MINE_RESISTANCE
)

/**
 * 计算【当前坦克】针对指定攻击者武器的涂装减伤系数。
 *
 * 返回值示例：
 * - 0.90f 表示受到 90% 伤害（10% 减伤）
 * - 0.75f 表示受到 75% 伤害（25% 减伤）
 * - 1.0 表示没有减伤
 */
fun BattleTank.getPaintResistanceMultiplier(
  attacker: BattleTank,
  damageSource: String? = null   // ★ 允许外面指定来源
): Double {
  // 如果外面传了 damageSource，就用它；否则按原来逻辑用炮塔 ID
  val weaponId = damageSource ?: attacker.weapon.item.id.itemName

  val propertyName = paintResistancePropertyByWeaponId[weaponId] ?: return 1.0

  val paintMarketItem = coloring.marketItem
  val property = paintMarketItem.properties
    .firstOrNull { it.property == propertyName } ?: return 1.0

  val rawValue = property.value

  val percent = when (rawValue) {
    is Number -> rawValue.toDouble()
    is String -> rawValue.toDoubleOrNull() ?: 0.0
    else      -> 0.0
  }

  val multiplier = 1.0 - percent / 100.0
  return multiplier.coerceIn(0.0, 1.0)
}

/*fun BattleTank.getPaintResistanceMultiplier(attacker: BattleTank): Double {
  // 攻击者使用的武器 ID，例如 "smoky" / "railgun" / "flamethrower" 等
  val weaponId = attacker.weapon.item.id.itemName

  val propertyName = paintResistancePropertyByWeaponId[weaponId] ?: return 1.0

  // 当前坦克所涂装的服务器物品
  val paintMarketItem = coloring.marketItem
  val property = paintMarketItem.properties.firstOrNull { it.property == propertyName } ?: return 1.0

  val rawValue = property.value

  // JSON 里是整数百分比，比如 25 表示 25% 抗性
  val percent = when (rawValue) {
    is Number -> rawValue.toDouble()
    is String -> rawValue.toDoubleOrNull() ?: 0.0
    else      -> 0.0
  }

  // 1 - 百分比/100 => 25% 抗性 => 0.75 倍伤害
  val multiplier = 1.0 - percent / 100.0

  // 不允许变成负数（不会被打一下回血…）
  return multiplier.coerceIn(0.0, 1.0)
}*/
