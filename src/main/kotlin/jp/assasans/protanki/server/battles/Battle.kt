package jp.assasans.protanki.server.battles

import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextULong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import jp.assasans.protanki.server.ServerMapInfo
import jp.assasans.protanki.server.battles.bonus.BonusProcessor
import jp.assasans.protanki.server.battles.mode.*
import jp.assasans.protanki.server.client.*
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import jp.assasans.protanki.server.client.InitGuiModelData
import jp.assasans.protanki.server.client.GuiUserData
import java.awt.SystemColor.info


enum class TankState {
    Dead,
    Respawn,
    SemiActive,
    Active
}

val TankState.tankInitKey: String
    get() = when (this) {
        TankState.Dead -> "suicide"
        TankState.Respawn -> "suicide"
        TankState.SemiActive -> "newcome"
        TankState.Active -> "active"
    }

enum class BattleTeam(val id: Int, val key: String) {
    Red(0, "RED"),
    Blue(1, "BLUE"),

    None(2, "NONE");

    companion object {
        private val map = values().associateBy(BattleTeam::key)

        fun get(key: String) = map[key]
    }
}

val BattleTeam.opposite: BattleTeam
    get() {
        return when (this) {
            BattleTeam.None -> BattleTeam.None
            BattleTeam.Red -> BattleTeam.Blue
            BattleTeam.Blue -> BattleTeam.Red
        }
    }

enum class BattleMode(val key: String, val id: Int) {
    Deathmatch("DM", 1),
    TeamDeathmatch("TDM", 2),
    CaptureTheFlag("CTF", 3),
    ControlPoints("CP", 4);

    companion object {
        private val map = values().associateBy(BattleMode::key)
        private val mapById = values().associateBy(BattleMode::id)

        fun get(key: String) = map[key]
        fun getById(id: Int) = mapById[id]
    }
}

enum class SendTarget {
    Players,
    Spectators
}

suspend fun Command.sendTo(
    battle: Battle,
    vararg targets: SendTarget = arrayOf(SendTarget.Players, SendTarget.Spectators),
    exclude: BattlePlayer? = null
): Int = battle.sendTo(this, *targets, exclude = exclude)

fun List<BattlePlayer>.users() = filter { player -> !player.isSpectator }
fun List<BattlePlayer>.spectators() = filter { player -> player.isSpectator }
fun List<BattlePlayer>.ready() = filter { player -> player.ready }
fun List<BattlePlayer>.exclude(player: BattlePlayer) = filter { it != player }

class Battle(
    coroutineContext: CoroutineContext,
    val id: String,
    val title: String,
    var map: ServerMapInfo,
    modeHandlerBuilder: BattleModeHandlerBuilder
) {

    var restarting: Boolean = false
        private set

    companion object {
        fun generateId(): String = Random.nextULong().toString(16)
    }

    private val logger = KotlinLogging.logger { }

    val coroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

    val properties: BattleProperties = BattleProperties()
    val modeHandler: BattleModeHandler = modeHandlerBuilder(this)
    val players: MutableList<BattlePlayer> = mutableListOf()

    val damageProcessor = DamageProcessor(this)
    val bonusProcessor = BonusProcessor(this)

    val minSpawnZ: Double = map.spawnPoints.minOf { it.position.z.toDouble() }

    /**
     * 坦克高度低于这个值时，认为已经从地图掉下去了。
     *
     * 这里用「最低出生点高度 - 2000」做阈值：
     * - 出生点 z 一般在 0~几千之间
     * - 正常在地图上开不会低到这么多
     * - 真正掉下去时会一路往负数掉，肯定会穿过这个高度
     *
     * 如果某些图掉得太久才死，可以把 2000 再调小一点；如果误杀就调大一点。
     */
    val fallDeathZ: Double = minSpawnZ - 600.0

    val mineProcessor = MineProcessor(this)
    val fundProcessor = FundProcessor(this)

    // 当前这一局的开始时间（只在有时间限制的房间里有意义）
    var startTime: Instant? = null

    // DM 时间限制用的协程 Job
    private var timeLimitJob: Job? = null

    // 当前这一局剩余时间（没有时间限制时返回 null）
    val timeLeft: Duration?
        get() {
            val startTime = startTime ?: return null
            val limit = properties[BattleProperty.TimeLimit]
            if (limit == 0) return null

            val endAt = startTime + limit.seconds
            val left = endAt - Clock.System.now()

            // 不允许返回负数，避免 HUD 出现奇怪的时间
            return if (left.inWholeMilliseconds < 0) Duration.ZERO else left
        }
    private val timeLimitModes = setOf(
        BattleMode.Deathmatch,
        BattleMode.TeamDeathmatch,
        BattleMode.CaptureTheFlag,
        BattleMode.ControlPoints
    )

    // Battle.kt
    fun startTimeLimitJobIfNeeded() {
        // 目前只在 DM / TDM 里做时间结算
        if (modeHandler.mode !in timeLimitModes) {
            timeLimitJob?.cancel()
            timeLimitJob = null
            return
        }

        val timeLimitInSec = properties[BattleProperty.TimeLimit]
        if (timeLimitInSec <= 0) {
            timeLimitJob?.cancel()
            timeLimitJob = null
            return
        }

        // 重启之前先把旧 Job 干掉，避免一局触发多次 restart()
        timeLimitJob?.cancel()

        // 如果这一局还没设置开始时间，就从现在开始算
        if (startTime == null) {
            startTime = Clock.System.now()
        }
        val localStart = startTime!!

        timeLimitJob = coroutineScope.launch {
            val endAt = localStart + timeLimitInSec.seconds
            val remaining = endAt - Clock.System.now()
            val delayMillis = remaining.inWholeMilliseconds

            if (delayMillis > 0) {
                delay(delayMillis)
            }

            // 这段时间内房间可能被关 / 改成无限时间 / 换成别的模式
            if (properties[BattleProperty.TimeLimit] <= 0) return@launch
            if (modeHandler.mode !in timeLimitModes) return@launch

            logger.debug { "Battle $id: time limit reached ($timeLimitInSec s), finish by time [${modeHandler.mode}]" }

            // 这里是“时间到”的结算
            restart()
        }
    }


    fun onPlayerKill(killer: BattlePlayer) {
        val scoreLimit = properties[BattleProperty.ScoreLimit]
        val timeLimit = properties[BattleProperty.TimeLimit]

        val useScoreLimit = scoreLimit > 0
        val useTimeLimit = timeLimit > 0

        // 两个都没设置：不自动结算
        if (!useScoreLimit && !useTimeLimit) return

        if (modeHandler.mode == BattleMode.Deathmatch && useScoreLimit && killer.kills >= scoreLimit) {
            logger.debug { "Battle $id: DM score limit reached for ${killer.user.username} (${killer.kills}/$scoreLimit)" }
            timeLimitJob?.cancel()
            timeLimitJob = null
            coroutineScope.launch {
                restart()
            }
        }
    }

    private val teamScoreModes = setOf(
        BattleMode.TeamDeathmatch,
        BattleMode.CaptureTheFlag,
        BattleMode.ControlPoints
    )

    fun onTeamScoreChanged(team: BattleTeam, score: Int) {
        val scoreLimit = properties[BattleProperty.ScoreLimit]
        if (scoreLimit <= 0) return
        if (modeHandler.mode !in teamScoreModes) return
        if (team == BattleTeam.None) return
        if (score >= scoreLimit) {
            logger.debug { "Battle $id: ${modeHandler.mode} score limit reached for team ${team.name} ($score/$scoreLimit)" }
            timeLimitJob?.cancel()
            timeLimitJob = null
            coroutineScope.launch {
                restart()
            }
        }
    }


    fun toBattleData(): BattleData {
        // TODO(Assasans)
        return when (modeHandler) {
            is DeathmatchModeHandler -> DmBattleData(
                battleId = id,
                battleMode = modeHandler.mode,
                map = map.name,
                name = title,
                maxPeople = 8,
                minRank = properties[BattleProperty.MinRank],
                maxRank = properties[BattleProperty.MaxRank],
                preview = map.preview,
                parkourMode = properties[BattleProperty.ParkourMode],
                users = players.users().map { player -> player.user.username },
            )
            is TeamModeHandler -> TeamBattleData(
                battleId = id,
                battleMode = modeHandler.mode,
                map = map.name,
                name = title,
                maxPeople = 8,
                minRank = properties[BattleProperty.MinRank],
                maxRank = properties[BattleProperty.MaxRank],
                preview = map.preview,
                parkourMode = properties[BattleProperty.ParkourMode],
                usersRed = players
                    .users()
                    .filter { player -> player.team == BattleTeam.Red }
                    .map { player -> player.user.username },
                usersBlue = players
                    .users()
                    .filter { player -> player.team == BattleTeam.Blue }
                    .map { player -> player.user.username }
            )
            else -> throw IllegalStateException("Unknown battle mode: ${modeHandler::class}")
        }
    }

    suspend fun selectFor(socket: UserSocket) {
        Command(CommandName.ClientSelectBattle, id).send(socket)
    }

    suspend fun showInfoFor(socket: UserSocket) {
        val info = when (modeHandler) {
            is DeathmatchModeHandler -> ShowDmBattleInfoData(
                itemId = id,
                battleMode = modeHandler.mode,
                scoreLimit = properties[BattleProperty.ScoreLimit],
                timeLimitInSec = properties[BattleProperty.TimeLimit],
                timeLeftInSec = timeLeft?.inWholeSeconds?.toInt() ?: 0,
                preview = map.preview,
                maxPeopleCount = 8,
                name = title,
                minRank = properties[BattleProperty.MinRank],
                maxRank = properties[BattleProperty.MaxRank],
                spectator = true,
                withoutBonuses = false,
                withoutCrystals = false,
                withoutSupplies = false,
                reArmorEnabled = properties[BattleProperty.RearmingEnabled],
                parkourMode = properties[BattleProperty.ParkourMode],
                users = players.users().map { player ->
                    BattleUser(
                        user = player.user.username,
                        kills = player.kills,
                        score = player.score
                    )
                },
            ).toJson()
            is TeamModeHandler -> ShowTeamBattleInfoData(
                itemId = id,
                battleMode = modeHandler.mode,
                scoreLimit = properties[BattleProperty.ScoreLimit],
                timeLimitInSec = properties[BattleProperty.TimeLimit],
                timeLeftInSec = timeLeft?.inWholeSeconds?.toInt() ?: 0,
                preview = map.preview,
                maxPeopleCount = 8,
                name = title,
                minRank = properties[BattleProperty.MinRank],
                maxRank = properties[BattleProperty.MaxRank],
                spectator = true,
                withoutBonuses = false,
                withoutCrystals = false,
                withoutSupplies = false,
                reArmorEnabled = properties[BattleProperty.RearmingEnabled],
                parkourMode = properties[BattleProperty.ParkourMode],
                usersRed = players
                    .users()
                    .filter { player -> player.team == BattleTeam.Red }
                    .map { player ->
                        BattleUser(
                            user = player.user.username,
                            kills = player.kills,
                            score = player.score
                        )
                    },
                usersBlue = players
                    .users()
                    .filter { player -> player.team == BattleTeam.Blue }
                    .map { player ->
                        BattleUser(
                            user = player.user.username,
                            kills = player.kills,
                            score = player.score
                        )
                    },
                scoreRed = modeHandler.teamScores[BattleTeam.Red] ?: 0,
                scoreBlue = modeHandler.teamScores[BattleTeam.Blue] ?: 0,
                autoBalance = false,
                friendlyFire = properties[BattleProperty.FriendlyFireEnabled]
            ).toJson()
            else -> throw IllegalStateException("Unknown battle mode: ${modeHandler.mode}")
        }

        Command(CommandName.ShowBattleInfo, info).send(socket)
    }

    suspend fun restart() {
        if (restarting) return
        restarting = true

        val restartTime = 10.seconds

        try {
            // 重启前先清掉旧的时间限制任务，避免在重启过程中再次触发。
            // 如果当前就是时间限制任务在调用 restart，则不能直接 cancel 自己，否则下面的 delay 会收到 CancellationException。
            val currentJob = coroutineContext[Job]
            timeLimitJob?.let { job ->
                if(job != currentJob) job.cancel()
            }
            timeLimitJob = null

            // 1. 发送结算信息（所有模式共用）
            Command(
                CommandName.FinishBattle,
                FinishBattleData(
                    time_to_restart = restartTime.inWholeMilliseconds,
                    users = players.users().map { player ->
                        FinishBattleUserData(
                            username = player.user.username,
                            rank = player.user.rank.value,
                            team = player.team,
                            score = player.score,
                            kills = player.kills,
                            deaths = player.deaths,
                            prize = 21,
                            bonus_prize = 12
                        )
                    }
                ).toJson()
            ).sendTo(this)
            logger.debug { "Finished battle $id" }

            // 2. 结算阶段（客户端会显示 10 秒倒计时）
            delay(restartTime.inWholeMilliseconds)

            // 3. 新一局开始：重置这一局的开始时间，并重启时间限制任务（DM / TDM 都会用到）
            startTime = Clock.System.now()
            startTimeLimitJobIfNeeded()

            // 4. 重置玩家统计并复活（对所有模式重置个人分数）
            players.users().forEach { player ->
                player.score = 0
                player.kills = 0
                player.deaths = 0
                player.respawn()
            }

            // 4.1 重置队伍比分（所有团队模式都清零）
            (modeHandler as? TeamModeHandler)?.let { handler ->
                handler.teamScores.keys.forEach { team -> handler.teamScores[team] = 0 }
                handler.updateScores()
            }

            // 4.2 模式内状态重置（CTF 旗帜、CP 占点进度等）
            when (val handler = modeHandler) {
                is CaptureTheFlagModeHandler -> handler.resetFlags()
                is ControlPointsModeHandler -> handler.resetPoints()
                else -> Unit
            }

            // 5. 通知客户端“战斗重启”，客户端收到后会把结算面板关掉
            Command(CommandName.RestartBattle, 0.toString()).sendTo(this)
            // 6. ★ 关键：对所有玩家重新初始化本地战斗模型（包括 GUI / 统计 / 模式等）
            for (player in players) {
                player.initLocal()
            }

            logger.debug { "Restarted battle $id" }
        } finally {
            restarting = false
        }

    }


    suspend fun sendTo(
        command: Command,
        vararg targets: SendTarget = arrayOf(SendTarget.Players, SendTarget.Spectators),
        exclude: BattlePlayer? = null
    ): Int {
        var count = 0
        if (targets.contains(SendTarget.Players)) {
            players
                .users()
                .filter { player -> player.socket.active }
                .filter { player -> exclude == null || player != exclude }
                .filter { player -> player.ready }
                .forEach { player ->
                    command.send(player)
                    count++
                }
        }
        if (targets.contains(SendTarget.Spectators)) {
            players
                .spectators()
                .filter { player -> player.socket.active }
                .filter { player -> exclude == null || player != exclude }
                .filter { player -> player.ready }
                .forEach { player ->
                    command.send(player)
                    count++
                }
        }

        return count
    }
}

interface IBattleProcessor {
    val battles: MutableList<Battle>

    fun getBattle(id: String): Battle?
}

class BattleProcessor : IBattleProcessor {
    private val logger = KotlinLogging.logger { }

    override val battles: MutableList<Battle> = mutableListOf()

    override fun getBattle(id: String): Battle? = battles.singleOrNull { battle -> battle.id == id }


}