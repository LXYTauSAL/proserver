package jp.assasans.protanki.server.commands.handlers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import jp.assasans.protanki.server.HibernateUtils
import jp.assasans.protanki.server.battles.BattleProperty
import jp.assasans.protanki.server.client.*
import jp.assasans.protanki.server.commands.Command
import jp.assasans.protanki.server.commands.CommandHandler
import jp.assasans.protanki.server.commands.CommandName
import jp.assasans.protanki.server.commands.ICommandHandler
import jp.assasans.protanki.server.garage.*
import jakarta.persistence.EntityManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/*
Switch to garage from battle:
-> switch_garage
<- change_layout_state [GARAGE]
<- unload_battle
-> i_exit_from_battle
<- init_messages
* load garage resources *
<- init_garage_items [{"items":[...]}]
-> get_garage_data
<- init_market [{"items":[...]}]
<- end_layout_switch [garage, garage]
<- init_mounted_item [hunter_m0, 227169]
<- init_mounted_item [railgun_m0, 906685]
<- init_mounted_item [green_m0, 966681]
*/

class GarageHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val marketRegistry by inject<IGarageMarketRegistry>()
  private val userRepository by inject<IUserRepository>()

  // 每个 userId 对应一个 Mutex，同一玩家换装串行化
  private val userEquipmentLocks = ConcurrentHashMap<Int, Mutex>()

  private fun lockForUser(userId: Int): Mutex =
    userEquipmentLocks.computeIfAbsent(userId) { Mutex() }

  @CommandHandler(CommandName.TryMountPreviewItem)
  suspend fun tryMountPreviewItem(socket: UserSocket, item: String) {
    Command(CommandName.MountItem, item, false.toString()).send(socket)
  }

 /* @CommandHandler(CommandName.TryMountItem)
  suspend fun tryMountItem(socket: UserSocket, rawItem: String) {
    val user = socket.user ?: throw Exception("No User")

    val item = rawItem.substringBeforeLast("_")
    val marketItem = marketRegistry.get(item)
    val currentItem = user.items.singleOrNull { userItem -> userItem.marketItem.id == item }

    logger.debug { "Trying to mount ${marketItem.id}..." }

    if(currentItem == null) {
      logger.debug { "Player ${user.username} (${user.id}) tried to mount not owned item: ${marketItem.id}" }
      return
    }

    val entityManager = HibernateUtils.createEntityManager()
    entityManager.transaction.begin()

    when(currentItem) {
      is ServerGarageUserItemWeapon -> user.equipment.weapon = currentItem
      is ServerGarageUserItemHull   -> user.equipment.hull = currentItem
      is ServerGarageUserItemPaint  -> user.equipment.paint = currentItem

      else                          -> {
        logger.debug { "Player ${user.username} (${user.id}) tried to mount invalid item: ${marketItem.id} (${currentItem::class.simpleName})" }
        return
      }
    }

    withContext(Dispatchers.IO) {
      entityManager
        .createQuery("UPDATE User SET equipment = :equipment WHERE id = :id")
        .setParameter("equipment", user.equipment)
        .setParameter("id", user.id)
        .executeUpdate()
    }
    entityManager.transaction.commit()
    entityManager.close()

    val player = socket.battlePlayer
    if(player != null) {
      if(!player.battle.properties[BattleProperty.RearmingEnabled]) {
        logger.warn { "Player ${player.user.username} attempted to change equipment in battle with disabled rearming" }
        return
      }

      player.equipmentChanged = true
    }

    Command(CommandName.MountItem, currentItem.mountName, true.toString()).send(socket)
  }*/
 /*@CommandHandler(CommandName.TryMountItem)
 suspend fun tryMountItem(socket: UserSocket, rawItem: String) {
   val user = socket.user ?: throw Exception("No User")

   val itemId = rawItem.substringBeforeLast("_")
   val marketItem = marketRegistry.get(itemId)
   val currentItem = user.items.singleOrNull { userItem -> userItem.marketItem.id == itemId }

   logger.debug { "Trying to mount ${marketItem.id}..." }

   if(currentItem == null) {
     logger.debug { "Player ${user.username} (${user.id}) tried to mount not owned item: ${marketItem.id}" }
     return
   }

   val equipmentChanged = when(currentItem) {
     is ServerGarageUserItemWeapon -> {
       user.equipment.weapon = currentItem
       true
     }
     is ServerGarageUserItemHull   -> {
       user.equipment.hull = currentItem
       true
     }
     is ServerGarageUserItemPaint  -> {
       user.equipment.paint = currentItem
       true
     }
     else                          -> {
       logger.debug {
         "Player ${user.username} (${user.id}) tried to mount invalid item: ${marketItem.id} (${currentItem::class.simpleName})"
       }
       false
     }
   }

   if(!equipmentChanged) return

   // DB 更新放进事务模板中执行
   withTransaction { entityManager ->
     entityManager
       .createQuery("UPDATE User SET equipment = :equipment WHERE id = :id")
       .setParameter("equipment", user.equipment)
       .setParameter("id", user.id)
       .executeUpdate()
   }

   val player = socket.battlePlayer
   if(player != null) {
     if(!player.battle.properties[BattleProperty.RearmingEnabled]) {
       logger.warn { "Player ${player.user.username} attempted to change equipment in battle with disabled rearming" }
       return
     }
     player.equipmentChanged = true
   }

   Command(CommandName.MountItem, currentItem.mountName, true.toString()).send(socket)
 }*/
 @CommandHandler(CommandName.TryMountItem)
 suspend fun tryMountItem(socket: UserSocket, rawItem: String) {
   val user = socket.user ?: throw Exception("No User")

   // 先拿到 battlePlayer，看是否在战局中
   val player = socket.battlePlayer
   if(player != null && !player.battle.properties[BattleProperty.RearmingEnabled]) {
     // 战斗不允许换装：直接拒绝，不改内存、不写 DB
     logger.warn {
       "Player ${player.user.username} attempted to change equipment in battle '${player.battle.id}' " +
               "with disabled rearming"
     }
     return
   }

   val itemId = rawItem.substringBeforeLast("_")
   val marketItem = marketRegistry.get(itemId)

   // 同一玩家的 TryMountItem 串行执行，避免同一行并发 UPDATE
   val mutex = lockForUser(user.id)

   mutex.withLock {
     val currentItem = user.items.singleOrNull { userItem -> userItem.marketItem.id == itemId }

     logger.debug { "Trying to mount ${marketItem.id} for user ${user.username} (${user.id})..." }

     if(currentItem == null) {
       logger.debug {
         "Player ${user.username} (${user.id}) tried to mount not owned item: ${marketItem.id}"
       }
       return@withLock
     }

     val equipmentChanged = when(currentItem) {
       is ServerGarageUserItemWeapon -> {
         user.equipment.weapon = currentItem
         true
       }
       is ServerGarageUserItemHull   -> {
         user.equipment.hull = currentItem
         true
       }
       is ServerGarageUserItemPaint  -> {
         user.equipment.paint = currentItem
         true
       }
       else                          -> {
         logger.debug {
           "Player ${user.username} (${user.id}) tried to mount invalid item: " +
                   "${marketItem.id} (${currentItem::class.simpleName})"
         }
         false
       }
     }

     if(!equipmentChanged) return@withLock

     // DB 更新放进事务模板中执行，事务内只做短 UPDATE
     withTransaction { entityManager ->
       entityManager
         .createQuery("UPDATE User SET equipment = :equipment WHERE id = :id")
         .setParameter("equipment", user.equipment)
         .setParameter("id", user.id)
         .executeUpdate()
     }

     // 如果在战斗里，标记装备已改变，方便后续在死亡/复活时重建 Tank
     if(player != null) {
       player.equipmentChanged = true
     }

     // 通知客户端挂载成功
     Command(CommandName.MountItem, currentItem.mountName, true.toString()).send(socket)
   }
 }



  // TODO(Assasans): Code repeating
  /*@CommandHandler(CommandName.TryBuyItem)
  suspend fun tryBuyItem(socket: UserSocket, rawItem: String, count: Int) {
    val user = socket.user ?: throw Exception("No User")

    if(count < 1) {
      logger.debug { "Player ${user.username} (${user.id}) tried to buy invalid count of items: $count" }
      return
    }

    val entityManager = HibernateUtils.createEntityManager()

    val item = rawItem.substringBeforeLast("_")
    val marketItem = marketRegistry.get(item)
    var currentItem = user.items.singleOrNull { userItem -> userItem.marketItem.id == item }

    var isNewItem = false

    entityManager.transaction.begin()

    when(marketItem) {
      is ServerGarageItemWeapon -> {
        if(currentItem == null) {
          currentItem = ServerGarageUserItemWeapon(user, marketItem.id, 0)
          user.items.add(currentItem)
          isNewItem = true

          val price = currentItem.modification.price
          if(user.crystals < price) {
            logger.debug { "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)" }
            return
          }
          user.crystals -= price

          logger.debug { "Bought weapon ${marketItem.id} ($price crystals)" }
        }
      }

      is ServerGarageItemHull   -> {
        if(currentItem == null) {
          currentItem = ServerGarageUserItemHull(user, marketItem.id, 0)
          user.items.add(currentItem)
          isNewItem = true

          val price = currentItem.modification.price
          if(user.crystals < price) {
            logger.debug { "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)" }
            return
          }
          user.crystals -= price

          logger.debug { "Bought hull ${marketItem.id} ($price crystals)" }
        }
      }

      is ServerGarageItemPaint  -> {
        if(currentItem == null) {
          currentItem = ServerGarageUserItemPaint(user, marketItem.id)
          user.items.add(currentItem)
          isNewItem = true

          val price = currentItem.marketItem.price
          if(user.crystals < price) {
            logger.debug { "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)" }
            return
          }
          user.crystals -= price

          logger.debug { "Bought paint ${marketItem.id} ($price crystals)" }
        }
      }

      is ServerGarageItemSupply -> {
        when(marketItem.id) {
          "1000_scores" -> {
            user.score += 1000 * count
            socket.updateScore()

            val price = marketItem.price * count
            if(user.crystals < price) {
              logger.debug { "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)" }
              return
            }
            user.crystals -= price

            logger.debug { "Bought ${marketItem.id} (count: $count, ${count * 1000} XP, $price crystals)" }
          }

          else          -> {
            if(currentItem == null) {
              currentItem = ServerGarageUserItemSupply(user, marketItem.id, count)
              user.items.add(currentItem)
              isNewItem = true
            } else {
              val supplyItem = currentItem as ServerGarageUserItemSupply
              supplyItem.count += count

              withContext(Dispatchers.IO) {
                entityManager
                  .createQuery("UPDATE ServerGarageUserItemSupply SET count = :count WHERE id = :id")
                  .setParameter("count", supplyItem.count)
                  .setParameter("id", supplyItem.id)
                  .executeUpdate()
              }
            }

            val price = currentItem.marketItem.price * count
            if(user.crystals < price) {
              logger.debug { "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)" }
              return
            }
            user.crystals -= price

            socket.battlePlayer?.let { battlePlayer ->
              Command(CommandName.SetItemCount, marketItem.id, currentItem.count.toString()).send(battlePlayer)
            }

            logger.debug { "Bought supply ${marketItem.id} (count: $count, $price crystals)" }
          }
        }
      }

      else                      -> {
        logger.warn { "Buying item ${marketItem::class.simpleName} is not implemented" }
      }
    }

    if(isNewItem) {
      entityManager.persist(currentItem)
    }

    if(!isNewItem && currentItem is ServerGarageUserItemWithModification) {
      if(currentItem.modificationIndex < 3) {
        val oldModification = currentItem.modificationIndex
        currentItem.modificationIndex++

        withContext(Dispatchers.IO) {
          entityManager
            .createQuery("UPDATE ServerGarageUserItemWithModification SET modificationIndex = :modificationIndex WHERE id = :id")
            .setParameter("modificationIndex", currentItem.modificationIndex)
            .setParameter("id", currentItem.id)
            .executeUpdate()
        }

        val price = currentItem.modification.price
        if(user.crystals < price) {
          logger.debug { "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)" }
          return
        }
        user.crystals -= price

        logger.debug { "Upgraded ${marketItem.id} modification: M${oldModification} -> M${currentItem.modificationIndex} ($price crystals)" }
      }
    }

    entityManager.transaction.commit()
    entityManager.close()

    userRepository.updateUser(user)

    Command(
      CommandName.BuyItem,
      BuyItemResponseData(
        itemId = marketItem.id,
        count = if(marketItem is ServerGarageItemSupply) count else 1
      ).toJson()
    ).send(socket)

    socket.updateCrystals()
  }*/
  @CommandHandler(CommandName.TryBuyItem)
  suspend fun tryBuyItem(socket: UserSocket, rawItem: String, count: Int) {
    val user = socket.user ?: throw Exception("No User")

    if(count < 1) {
      logger.debug { "Player ${user.username} (${user.id}) tried to buy invalid count of items: $count" }
      return
    }

    val itemId = rawItem.substringBeforeLast("_")
    val marketItem = marketRegistry.get(itemId)

    // 这些标记只在事务外用，事务里只做纯 DB + 内存修改
    var shouldUpdateScore = false
    var supplyCountForBattle: Int? = null
    var supplyItemIdForBattle: String? = null

    val success = withTransaction { entityManager ->
      // currentItem / isNewItem 全部放到事务内部，避免 “changing closure” 问题
      var currentItem = user.items.singleOrNull { userItem -> userItem.marketItem.id == itemId }
      var isNewItem = false

      when(marketItem) {
        is ServerGarageItemWeapon -> {
          if(currentItem == null) {
            val newItem = ServerGarageUserItemWeapon(user, marketItem.id, 0)
            val price = newItem.modification.price
            if(user.crystals < price) {
              logger.debug {
                "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), " +
                        "but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)"
              }
              return@withTransaction false
            }

            currentItem = newItem
            user.items.add(newItem)
            isNewItem = true

            user.crystals -= price
            logger.debug { "Bought weapon ${marketItem.id} ($price crystals)" }
          }
        }

        is ServerGarageItemHull   -> {
          if(currentItem == null) {
            val newItem = ServerGarageUserItemHull(user, marketItem.id, 0)
            val price = newItem.modification.price
            if(user.crystals < price) {
              logger.debug {
                "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), " +
                        "but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)"
              }
              return@withTransaction false
            }

            currentItem = newItem
            user.items.add(newItem)
            isNewItem = true

            user.crystals -= price
            logger.debug { "Bought hull ${marketItem.id} ($price crystals)" }
          }
        }

        is ServerGarageItemPaint  -> {
          if(currentItem == null) {
            val newItem = ServerGarageUserItemPaint(user, marketItem.id)
            val price = newItem.marketItem.price
            if(user.crystals < price) {
              logger.debug {
                "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), " +
                        "but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)"
              }
              return@withTransaction false
            }

            currentItem = newItem
            user.items.add(newItem)
            isNewItem = true

            user.crystals -= price
            logger.debug { "Bought paint ${marketItem.id} ($price crystals)" }
          }
        }

        is ServerGarageItemSupply -> {
          when(marketItem.id) {
            // 经验包
            "1000_scores" -> {
              val price = marketItem.price * count
              if(user.crystals < price) {
                logger.debug {
                  "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), " +
                          "but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)"
                }
                return@withTransaction false
              }

              user.score += 1000 * count
              user.crystals -= price
              shouldUpdateScore = true

              logger.debug { "Bought ${marketItem.id} (count: $count, ${count * 1000} XP, $price crystals)" }
            }

            // 普通补给
            else -> {
              // 先算价格，再检查余额
              val price = marketItem.price * count
              if(user.crystals < price) {
                logger.debug {
                  "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), " +
                          "but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)"
                }
                return@withTransaction false
              }

              // 再修改内存状态
              if(currentItem == null) {
                val newItem = ServerGarageUserItemSupply(user, marketItem.id, count)
                currentItem = newItem
                user.items.add(newItem)
                isNewItem = true
              } else {
                val supplyItem = currentItem as ServerGarageUserItemSupply
                supplyItem.count += count
              }

              user.crystals -= price

              val supplyItem = currentItem as ServerGarageUserItemSupply
              // 只有老物品需要立即 UPDATE，新增的走 persist
              if(!isNewItem) {
                entityManager
                  .createQuery("UPDATE ServerGarageUserItemSupply SET count = :count WHERE id = :id")
                  .setParameter("count", supplyItem.count)
                  .setParameter("id", supplyItem.id)
                  .executeUpdate()
              }

              supplyItemIdForBattle = marketItem.id
              supplyCountForBattle = supplyItem.count

              logger.debug { "Bought supply ${marketItem.id} (count: $count, $price crystals)" }
            }

          }
        }

        else                      -> {
          logger.warn { "Buying item ${marketItem?.javaClass?.simpleName} is not implemented" }
        }
      }

      // 新买的物品统一 persist
      if(isNewItem && currentItem != null) {
        entityManager.persist(currentItem)
      }

      // 已有武器 / 车体的升级逻辑：不再依赖 currentItem 的 smart cast
      val modItem = currentItem as? ServerGarageUserItemWithModification
      if(!isNewItem && modItem != null && modItem.modificationIndex < 3) {
        val oldModification = modItem.modificationIndex
        val newModificationIndex = oldModification + 1

        // 先改内存里的等级，算价格
        modItem.modificationIndex = newModificationIndex
        val price = modItem.modification.price
        if(user.crystals < price) {
          // 钱不够，恢复原等级
          modItem.modificationIndex = oldModification
          logger.debug {
            "Player ${user.username} (${user.id}) tried to buy item: ${marketItem.id} ($price crystals), " +
                    "but does not has enough crystals (user: ${user.crystals} crystals, delta: ${user.crystals - price} crystals)"
          }
          return@withTransaction false
        }

        // DB UPDATE
        entityManager
          .createQuery("UPDATE ServerGarageUserItemWithModification SET modificationIndex = :modificationIndex WHERE id = :id")
          .setParameter("modificationIndex", modItem.modificationIndex)
          .setParameter("id", modItem.id)
          .executeUpdate()

        user.crystals -= price

        logger.debug {
          "Upgraded ${marketItem.id} modification: M$oldModification -> M${modItem.modificationIndex} ($price crystals)"
        }
      }

      true
    }

    // 如果事务里返回 false，说明业务校验失败（钱不够之类），直接结束
    if(!success) return

    if(shouldUpdateScore) {
      socket.updateScore()
    }

    userRepository.updateUser(user)

    Command(
      CommandName.BuyItem,
      BuyItemResponseData(
        itemId = marketItem.id,
        count = if(marketItem is ServerGarageItemSupply) count else 1
      ).toJson()
    ).send(socket)

    // 补给数量变更需要通知战场里的玩家
    if(supplyItemIdForBattle != null && supplyCountForBattle != null) {
      socket.battlePlayer?.let { battlePlayer ->
        Command(
          CommandName.SetItemCount,
          supplyItemIdForBattle!!,
          supplyCountForBattle!!.toString()
        ).send(battlePlayer)
      }
    }

    socket.updateCrystals()
  }


  /*
  SENT    : garage;kitBought;universal_soldier_m0
  SENT    : garage;try_mount_item;railgun_m0
  SENT    : garage;try_mount_item;twins_m0
  SENT    : garage;try_mount_item;flamethrower_m0
  SENT    : garage;try_mount_item;hornet_m0
  RECEIVED: LOBBY;add_crystall;179
  RECEIVED: GARAGE;showCategory;armor
  RECEIVED: GARAGE;select;hornet_m0
  RECEIVED: GARAGE;mount_item;railgun_m0;true
  RECEIVED: GARAGE;mount_item;twins_m0;true
  RECEIVED: GARAGE;mount_item;flamethrower_m0;true
  RECEIVED: GARAGE;mount_item;hornet_m0;true
  */
  /*@CommandHandler(CommandName.TryBuyKit)
  suspend fun tryBuyKit(socket: UserSocket, rawItem: String) {
    val user = socket.user ?: throw Exception("No User")

    val item = rawItem.substringBeforeLast("_")
    val marketItem = marketRegistry.get(item)
    if(marketItem !is ServerGarageItemKit) return

    logger.debug { "Trying to buy kit ${marketItem.id}..." }

    marketItem.kit.items.forEach { kitItem ->
      val kitMarketItem = marketRegistry.get(kitItem.id.substringBeforeLast("_"))
      TODO()
    }
  }*/
  /*@CommandHandler(CommandName.TryBuyKit)
  suspend fun tryBuyKit(socket: UserSocket, rawItem: String) {
    val user = socket.user ?: throw Exception("No User")

    val itemId = rawItem.substringBeforeLast("_")
    val marketItem = marketRegistry.get(itemId)
    if(marketItem !is ServerGarageItemKit) {
      logger.warn { "Item $itemId is not a kit (${marketItem?.javaClass?.simpleName})" }
      return
    }

    logger.debug { "Trying to buy kit ${marketItem.id}..." }

    val price = marketItem.price
    if(user.crystals < price) {
      logger.debug {
        "Player ${user.username} (${user.id}) tried to buy kit ${marketItem.id} " +
                "but doesn't have enough crystals: has=${user.crystals}, price=$price, delta=${user.crystals - price}"
      }
      return
    }

    val entityManager = HibernateUtils.createEntityManager()
    entityManager.transaction.begin()

    // 1. 全套一次性扣费
    user.crystals -= price

    // 2. 逐个发放 kit 内的物品
    marketItem.kit.items.forEach { kitItem ->
      val baseId = kitItem.id.substringBeforeLast("_")
      val kitMarketItem = marketRegistry.get(baseId) ?: run {
        logger.warn { "Unknown kit item id=${kitItem.id} in kit ${marketItem.id}" }
        return@forEach
      }

      // 解析 m 等级：xxx_m0 / xxx_m1 / ...
      val modificationIndex = kitItem.id
        .substringAfterLast("_", "")
        .removePrefix("m")
        .toIntOrNull() ?: 0

      var currentItem = user.items.singleOrNull { userItem -> userItem.marketItem.id == baseId }

      when(kitMarketItem) {
        is ServerGarageItemWeapon -> {
          if(currentItem == null) {
            val newItem = ServerGarageUserItemWeapon(user, kitMarketItem.id, modificationIndex)
            user.items.add(newItem)
            currentItem = newItem
            entityManager.persist(newItem)
          } else if(currentItem is ServerGarageUserItemWeapon &&
            currentItem.modificationIndex < modificationIndex) {
            currentItem.modificationIndex = modificationIndex
            entityManager
              .createQuery(
                "UPDATE ServerGarageUserItemWithModification " +
                        "SET modificationIndex = :modificationIndex WHERE id = :id"
              )
              .setParameter("modificationIndex", currentItem.modificationIndex)
              .setParameter("id", currentItem.id)
              .executeUpdate()
          }
        }

        is ServerGarageItemHull -> {
          if(currentItem == null) {
            val newItem = ServerGarageUserItemHull(user, kitMarketItem.id, modificationIndex)
            user.items.add(newItem)
            currentItem = newItem
            entityManager.persist(newItem)
          } else if(currentItem is ServerGarageUserItemHull &&
            currentItem.modificationIndex < modificationIndex) {
            currentItem.modificationIndex = modificationIndex
            entityManager
              .createQuery(
                "UPDATE ServerGarageUserItemWithModification " +
                        "SET modificationIndex = :modificationIndex WHERE id = :id"
              )
              .setParameter("modificationIndex", currentItem.modificationIndex)
              .setParameter("id", currentItem.id)
              .executeUpdate()
          }
        }

        is ServerGarageItemPaint -> {
          if(currentItem == null) {
            val newItem = ServerGarageUserItemPaint(user, kitMarketItem.id)
            user.items.add(newItem)
            entityManager.persist(newItem)
          }
        }

        is ServerGarageItemSupply -> {
          if(currentItem == null) {
            val newItem = ServerGarageUserItemSupply(user, kitMarketItem.id, kitItem.count)
            user.items.add(newItem)
            entityManager.persist(newItem)
          } else if(currentItem is ServerGarageUserItemSupply) {
            currentItem.count += kitItem.count
            entityManager
              .createQuery("UPDATE ServerGarageUserItemSupply SET count = :count WHERE id = :id")
              .setParameter("count", currentItem.count)
              .setParameter("id", currentItem.id)
              .executeUpdate()
          }
        }

        else -> {
          logger.warn {
            "Buying kit item ${kitMarketItem.id} (${kitMarketItem::class.simpleName}) " +
                    "from kit ${marketItem.id} is not implemented"
          }
        }
      }
    }

    entityManager.transaction.commit()
    entityManager.close()

    userRepository.updateUser(user)
    socket.updateCrystals()

    logger.debug { "Kit ${marketItem.id} bought successfully" }
  }*/
  @CommandHandler(CommandName.TryBuyKit)
  suspend fun tryBuyKit(socket: UserSocket, rawItem: String) {
    val user = socket.user ?: throw Exception("No User")

    val itemId = rawItem.substringBeforeLast("_")
    val marketItem = marketRegistry.get(itemId)
    if(marketItem !is ServerGarageItemKit) {
      logger.warn { "Item $itemId is not a kit (${marketItem?.javaClass?.simpleName})" }
      return
    }

    logger.debug { "Trying to buy kit ${marketItem.id}..." }

    val price = marketItem.price
    if(user.crystals < price) {
      logger.debug {
        "Player ${user.username} (${user.id}) tried to buy kit ${marketItem.id} " +
                "but doesn't have enough crystals: has=${user.crystals}, price=$price, delta=${user.crystals - price}"
      }
      return
    }

    withTransaction { entityManager ->
      // 1. 一次性扣费
      user.crystals -= price

      // 2. 逐个发放 kit 内的物品
      marketItem.kit.items.forEach { kitItem ->
        val baseId = kitItem.id.substringBeforeLast("_")
        val kitMarketItem = marketRegistry.get(baseId) ?: run {
          logger.warn { "Unknown kit item id=${kitItem.id} in kit ${marketItem.id}" }
          return@forEach
        }

        val modificationIndex = kitItem.id
          .substringAfterLast("_", "")
          .removePrefix("m")
          .toIntOrNull() ?: 0

        var userItem = user.items.singleOrNull { userItem -> userItem.marketItem.id == baseId }

        when(kitMarketItem) {
          is ServerGarageItemWeapon -> {
            if(userItem == null) {
              val newItem = ServerGarageUserItemWeapon(user, kitMarketItem.id, modificationIndex)
              user.items.add(newItem)
              userItem = newItem
              entityManager.persist(newItem)
            } else if(userItem is ServerGarageUserItemWeapon &&
              userItem.modificationIndex < modificationIndex) {
              userItem.modificationIndex = modificationIndex
              entityManager
                .createQuery(
                  "UPDATE ServerGarageUserItemWithModification " +
                          "SET modificationIndex = :modificationIndex WHERE id = :id"
                )
                .setParameter("modificationIndex", userItem.modificationIndex)
                .setParameter("id", userItem.id)
                .executeUpdate()
            }
          }

          is ServerGarageItemHull -> {
            if(userItem == null) {
              val newItem = ServerGarageUserItemHull(user, kitMarketItem.id, modificationIndex)
              user.items.add(newItem)
              userItem = newItem
              entityManager.persist(newItem)
            } else if(userItem is ServerGarageUserItemHull &&
              userItem.modificationIndex < modificationIndex) {
              userItem.modificationIndex = modificationIndex
              entityManager
                .createQuery(
                  "UPDATE ServerGarageUserItemWithModification " +
                          "SET modificationIndex = :modificationIndex WHERE id = :id"
                )
                .setParameter("modificationIndex", userItem.modificationIndex)
                .setParameter("id", userItem.id)
                .executeUpdate()
            }
          }

          is ServerGarageItemPaint -> {
            if(userItem == null) {
              val newItem = ServerGarageUserItemPaint(user, kitMarketItem.id)
              user.items.add(newItem)
              entityManager.persist(newItem)
            }
          }

          is ServerGarageItemSupply -> {
            if(userItem == null) {
              val newItem = ServerGarageUserItemSupply(user, kitMarketItem.id, kitItem.count)
              user.items.add(newItem)
              entityManager.persist(newItem)
            } else if(userItem is ServerGarageUserItemSupply) {
              userItem.count += kitItem.count
              entityManager
                .createQuery("UPDATE ServerGarageUserItemSupply SET count = :count WHERE id = :id")
                .setParameter("count", userItem.count)
                .setParameter("id", userItem.id)
                .executeUpdate()
            }
          }

          else -> {
            logger.warn {
              "Buying kit item ${kitMarketItem.id} (${kitMarketItem::class.simpleName}) from kit ${marketItem.id} is not implemented"
            }
          }
        }
      }

      Unit
    }

    userRepository.updateUser(user)
    socket.updateCrystals()

    logger.debug { "Kit ${marketItem.id} bought successfully" }
  }



  // === 事务模板抽取 ===
  private suspend fun <T> withEntityManager(block: (EntityManager) -> T): T =
    withContext(Dispatchers.IO) {
      val entityManager = HibernateUtils.createEntityManager()
      try {
        block(entityManager)
      } finally {
        entityManager.close()
      }
    }

  private suspend fun <T> withTransaction(block: (EntityManager) -> T): T =
    withEntityManager { entityManager ->
      val transaction = entityManager.transaction
      transaction.begin()
      try {
        val result = block(entityManager)
        transaction.commit()
        result
      } catch(exception: Exception) {
        if(transaction.isActive) transaction.rollback()
        throw exception
      }
    }

}
