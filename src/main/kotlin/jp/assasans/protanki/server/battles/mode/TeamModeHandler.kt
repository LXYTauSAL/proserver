package jp.assasans.protanki.server.battles.mode

import jp.assasans.protanki.server.battles.*
import jp.assasans.protanki.server.client.*
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName

abstract class TeamModeHandler(battle: Battle) : BattleModeHandler(battle) {
  val teamScores: MutableMap<BattleTeam, Int> = mutableMapOf(
    BattleTeam.Red to 0,
    BattleTeam.Blue to 0
  )

  private val clientTeamScores: MutableMap<BattleTeam, Int> = teamScores.toMutableMap()

  override val mode: BattleMode
    get() = when(this) {
      // 这个如果你原来没有就删掉；如果各自子类自己 override 了 mode，那这里可以不要
      else -> throw NotImplementedError("Mode is implemented in subclasses")
    }

  override suspend fun playerJoin(player: BattlePlayer) {
    // 统计当前各队玩家信息，给新加入的玩家
    val players = battle.players
      .users()
      .filter { battlePlayer -> battlePlayer.team == player.team }
      .toStatisticsUsers()

    val redPlayers = battle.players
      .users()
      .filter { battlePlayer -> battlePlayer.team == BattleTeam.Red }
      .toStatisticsUsers()

    val bluePlayers = battle.players
      .users()
      .filter { battlePlayer -> battlePlayer.team == BattleTeam.Blue }
      .toStatisticsUsers()

    // 给新加入玩家初始化队伍统计面板
    Command(
      CommandName.InitTeamStatistics,
      InitTeamStatisticsData(
        reds = redPlayers,
        blues = bluePlayers,
        redScore = teamScores[BattleTeam.Red] ?: 0,
        blueScore = teamScores[BattleTeam.Blue] ?: 0
      ).toJson()
    ).send(player)

    if(player.isSpectator) return

    // 通知其它玩家：某队有新玩家加入
    Command(
      CommandName.BattlePlayerJoinTeam,
      BattlePlayerJoinTeamData(
        id = player.user.username,
        team = player.team,
        players = players
      ).toJson()
    ).send(battle.players.exclude(player).ready())
  }

  override suspend fun playerLeave(player: BattlePlayer) {
    if(player.isSpectator) return

    Command(
      CommandName.BattlePlayerLeaveTeam,
      player.user.username
    ).send(battle.players.exclude(player).ready())
  }

  /** 新增：统一的团队加分入口（TDM / CTF 等共用） */
  suspend fun addTeamScore(team: BattleTeam, delta: Int = 1) {
    if(team == BattleTeam.None) return

    val newScore = teamScores.merge(team, delta, Int::plus) ?: return
    battle.onTeamScoreChanged(team, newScore)
    updateScores()
  }

  /** 把 server 端 teamScores 推给所有玩家 */
  suspend fun updateScores() {
    teamScores
      .filter { (team, score) -> clientTeamScores[team] != score } // 只发变动的比分
      .forEach { (team, score) ->
        clientTeamScores[team] = score

        Command(
          CommandName.ChangeTeamScore,
          team.key,
          score.toString()
        ).sendTo(battle)
      }
  }

  override suspend fun dump(builder: StringBuilder) {
    builder.appendLine("    Scores:")
    teamScores.forEach { (team, score) ->
      builder.appendLine("        ${team.name}: $score")
    }
  }
}
