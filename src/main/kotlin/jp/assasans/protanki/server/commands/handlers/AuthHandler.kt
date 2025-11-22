package jp.assasans.protanki.server.commands.handlers

import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import jp.assasans.protanki.server.client.*
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandHandler
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.commands.ICommandHandler
import jp.assasans.protanki.server.invite.IInviteService

object AuthHandlerConstants {
  const val InviteRequired = "Invite code is required to log in"

  fun getInviteInvalidUsername(username: String) = "This invite can only be used with the username \"$username\""
}

class AuthHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val userRepository: IUserRepository by inject()
  private val userSubscriptionManager: IUserSubscriptionManager by inject()
  private val inviteService: IInviteService by inject()

  /*@CommandHandler(CommandName.Login)
  suspend fun login(socket: UserSocket, captcha: String, rememberMe: Boolean, username: String, password: String) {
    val invite = socket.invite
    if(inviteService.enabled) {
      // TODO(Assasans): AuthDenied shows unnecessary "Password incorrect" modal
      if(invite == null) {
        Command(CommandName.ShowAlert, AuthHandlerConstants.InviteRequired).send(socket)
        Command(CommandName.AuthDenied).send(socket)
        return
      }

      invite.username?.let { inviteUsername ->
        if(username == inviteUsername || username.startsWith("${inviteUsername}_")) return@let

        Command(CommandName.ShowAlert, AuthHandlerConstants.getInviteInvalidUsername(inviteUsername)).send(socket)
        Command(CommandName.AuthDenied).send(socket)
        return
      }
    }

    logger.debug { "User login: [ Invite = '${socket.invite?.code}', Username = '$username', Password = '$password', Captcha = ${if(captcha.isEmpty()) "*none*" else "'${captcha}'"}, Remember = $rememberMe ]" }

    val user = userRepository.getUser(username)
               ?: userRepository.createUser(username, password)
               ?: TODO("Race condition")
    logger.debug { "Got user from database: ${user.username}" }

    if(inviteService.enabled && invite != null) {
      invite.username = user.username
      invite.updateUsername()
    }

    // if(user.password == password) {
    logger.debug { "User login allowed" }

    userSubscriptionManager.add(user)
    socket.user = user
    Command(CommandName.AuthAccept).send(socket)
    socket.loadLobby()
    // } else {
    //   logger.debug { "User login rejected: incorrect password" }
    //
    //   Command(CommandName.AuthDenied).send(socket)
    // }
  }*/
/*  @CommandHandler(CommandName.Login)
  suspend fun login(socket: UserSocket, captcha: String, rememberMe: Boolean, username: String, password: String) {
    val invite = socket.invite
    if(inviteService.enabled) {
      // TODO(Assasans): AuthDenied shows unnecessary "Password incorrect" modal
      if(invite == null) {
        Command(CommandName.ShowAlert, AuthHandlerConstants.InviteRequired).send(socket)
        Command(CommandName.AuthDenied).send(socket)
        return
      }

      invite.username?.let { inviteUsername ->
        if(username == inviteUsername || username.startsWith("${inviteUsername}_")) return@let

        Command(CommandName.ShowAlert, AuthHandlerConstants.getInviteInvalidUsername(inviteUsername)).send(socket)
        Command(CommandName.AuthDenied).send(socket)
        return
      }
    }

    logger.debug {
      "User login: [ Invite = '${socket.invite}', Username = '$username', " +
              "Captcha = ${if(captcha.isEmpty()) "*none*" else "'$captcha'"}, Remember = $rememberMe ]"
    }

    // 1. 先查用户
    var user = userRepository.getUser(username)

    // 2. 允许“登陆即注册”的旧逻辑，但处理并发竞争
    if(user == null) {
      val created = userRepository.createUser(username, password)
      user = if(created != null) {
        created
      } else {
        // 说明在 getUser 和 createUser 之间，另一个请求创建了同名用户
        logger.warn { "Race while creating user '$username': user already exists" }
        userRepository.getUser(username)
      }

      if(user == null) {
        logger.warn { "User '$username' could not be created due to race condition" }
        Command(CommandName.AuthDenied).send(socket)
        return
      }
    }

    logger.debug { "Got user from database: ${user.username}" }

    if(inviteService.enabled && invite != null) {
      invite.username = user.username
      invite.updateUsername()
    }

    // 3. 这里做密码校验（如果你以后改成哈希密码，在这里改）
    if(user.password != password) {
      logger.debug { "User login rejected: incorrect password for '$username'" }
      Command(CommandName.AuthDenied).send(socket)
      return
    }

    logger.debug { "User login allowed" }

    userSubscriptionManager.add(user)
    socket.user = user
    Command(CommandName.AuthAccept).send(socket)
    socket.loadLobby()
  }*/
  @CommandHandler(CommandName.Login)
  suspend fun login(socket: UserSocket, captcha: String, rememberMe: Boolean, username: String, password: String) {
    val invite = socket.invite
    // 邀请码验证（如果启用了邀请系统）
    if(inviteService.enabled) {
      if(invite == null) {
        Command(CommandName.ShowAlert, AuthHandlerConstants.InviteRequired).send(socket)
        Command(CommandName.AuthDenied).send(socket)
        return
      }
      // 验证邀请码绑定的用户名
      invite.username?.let { inviteUsername ->
        if(username != inviteUsername && !username.startsWith("${inviteUsername}_")) {
          Command(CommandName.ShowAlert, AuthHandlerConstants.getInviteInvalidUsername(inviteUsername)).send(socket)
          Command(CommandName.AuthDenied).send(socket)
          return
        }
      }
    }

    // 查询用户，不存在则创建（支持"登录即注册"）
    var user = userRepository.getUser(username)
    if(user == null) {
      val created = userRepository.createUser(username, password)
      user = created ?: userRepository.getUser(username) // 处理并发创建的情况
      if(user == null) {
        Command(CommandName.AuthDenied).send(socket)
        return
      }
    }

    // 密码校验
    if(user.password != password) {
      logger.debug { "User login rejected: incorrect password for '$username'" }
      Command(CommandName.AuthDenied).send(socket)
      return
    }

    // 登录成功处理
    userSubscriptionManager.add(user)
    socket.user = user
    Command(CommandName.AuthAccept).send(socket)
    socket.loadLobby()
  }

  @CommandHandler(CommandName.LoginByHash)
  suspend fun loginByHash(socket: UserSocket, hash: String) {
    if(inviteService.enabled && socket.invite == null) {
      Command(CommandName.ShowAlert, AuthHandlerConstants.InviteRequired).send(socket)
      Command(CommandName.AuthDenied).send(socket)
      return

      // TODO(Assasans): Check username
    }

    logger.debug { "User login by hash: $hash" }

    Command(CommandName.LoginByHashFailed).send(socket)
  }

  @CommandHandler(CommandName.ActivateInvite)
  suspend fun activateInvite(socket: UserSocket, code: String) {
    logger.debug { "Fetching invite: $code" }

    val invite = inviteService.getInvite(code)
    if(invite != null) {
      Command(CommandName.InviteValid).send(socket)
    } else {
      Command(CommandName.InviteInvalid).send(socket)
    }

    socket.invite = invite
  }

/*  @CommandHandler(CommandName.CheckUsernameRegistration)
  suspend fun checkUsernameRegistration(socket: UserSocket, username: String) {
    if(userRepository.getUser(username) != null) {
      // TODO(Assasans): Use "nickname_exist"
      Command(CommandName.CheckUsernameRegistrationClient, "incorrect").send(socket)
      return
    }

    // Pass-through
    Command(CommandName.CheckUsernameRegistrationClient, "not_exist").send(socket)
  }*/
@CommandHandler(CommandName.CheckUsernameRegistration)
suspend fun checkUsernameRegistration(socket: UserSocket, username: String) {
  if(userRepository.getUser(username) != null) {
    Command(CommandName.CheckUsernameRegistrationClient, "incorrect").send(socket)
    return
  }

  Command(CommandName.CheckUsernameRegistrationClient, "not_exist").send(socket)
}


  /*@CommandHandler(CommandName.RegisterUser)
  suspend fun registerUser(socket: UserSocket, username: String, password: String, captcha: String) {
    val invite = socket.invite
    if(inviteService.enabled) {
      // TODO(Assasans): "Reigster" button is not disabled after error
      if(invite == null) {
        Command(CommandName.ShowAlert, AuthHandlerConstants.InviteRequired).send(socket)
        return
      }

      invite.username?.let { inviteUsername ->
        if(username == inviteUsername || username.startsWith("${inviteUsername}_")) return@let

        Command(CommandName.ShowAlert, AuthHandlerConstants.getInviteInvalidUsername(inviteUsername)).send(socket)
        return
      }
    }



    logger.debug { "Register user: [ Invite = '${socket.invite?.code}', Username = '$username', Password = '$password', Captcha = ${if(captcha.isEmpty()) "*none*" else "'${captcha}'"} ]" }

    val user = userRepository.createUser(username, password)
               ?: TODO("User exists")

    if(inviteService.enabled && invite != null) {
      invite.username = user.username
      invite.updateUsername()
    }

    userSubscriptionManager.add(user)
    socket.user = user
    Command(CommandName.AuthAccept).send(socket)
    socket.loadLobby()


    logger.debug {
      "Register user: [ Invite = '${socket.invite}', Username = '$username', " +
              "Captcha = ${if(captcha.isEmpty()) "*none*" else "'$captcha'"} ]"
    }

    val user = userRepository.createUser(username, password)
    if(user == null) {
      logger.debug { "User registration rejected: username '$username' already exists" }
      // 提示可以自行调整文案
      Command(CommandName.ShowAlert, "This nickname is already in use").send(socket)
      Command(CommandName.AuthDenied).send(socket)
      return
    }

    if(inviteService.enabled && invite != null) {
      invite.username = user.username
      invite.updateUsername()
    }

    userSubscriptionManager.add(user)
    socket.user = user
    Command(CommandName.AuthAccept).send(socket)
    socket.loadLobby()


  }*/
  @CommandHandler(CommandName.RegisterUser)
  suspend fun registerUser(socket: UserSocket, username: String, password: String, captcha: String) {
    val invite = socket.invite
    // 邀请码验证（如果启用了邀请系统）
    if(inviteService.enabled) {
      if(invite == null) {
        Command(CommandName.ShowAlert, AuthHandlerConstants.InviteRequired).send(socket)
        return
      }
      // 验证邀请码绑定的用户名
      invite.username?.let { inviteUsername ->
        if(username != inviteUsername && !username.startsWith("${inviteUsername}_")) {
          Command(CommandName.ShowAlert, AuthHandlerConstants.getInviteInvalidUsername(inviteUsername)).send(socket)
          return
        }
      }
    }

    // 创建用户，若用户名已存在则返回错误
    val user = userRepository.createUser(username, password)
    if(user == null) {
      logger.debug { "User registration rejected: username '$username' already exists" }
      Command(CommandName.ShowAlert, "This nickname is already in use").send(socket)
      Command(CommandName.AuthDenied).send(socket)
      return
    }

    // 注册成功处理
    if(inviteService.enabled && invite != null) {
      invite.username = user.username
      invite.updateUsername()
    }
    userSubscriptionManager.add(user)
    socket.user = user
    Command(CommandName.AuthAccept).send(socket)
    socket.loadLobby()
  }
}
