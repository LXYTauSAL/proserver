package jp.assasans.protanki.server.invite

import jakarta.persistence.EntityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import jp.assasans.protanki.server.HibernateUtils
import jp.assasans.protanki.server.extensions.putIfAbsent

interface IInviteRepository {
    suspend fun getInvite(id: Int): Invite?
    suspend fun getInvite(code: String): Invite?
    suspend fun getInvites(): List<Invite>
    suspend fun getInviteCount(): Long

    suspend fun createInvite(code: String): Invite?
    suspend fun deleteInvite(code: String): Boolean
}

class InviteRepository : IInviteRepository {
    //private val _entityManagers = ThreadLocal<EntityManager>()
    private suspend fun <T> withEntityManager(block: (EntityManager) -> T): T =
        withContext(Dispatchers.IO) {
            val entityManager = HibernateUtils.createEntityManager()
            try {
                block(entityManager)
            } finally {
                entityManager.close()
            }
        }

    //  private val entityManager: EntityManager
//    get() = _entityManagers.putIfAbsent { HibernateUtils.createEntityManager() }
    private suspend fun <T> withTransaction(block: (EntityManager) -> T): T =
        withEntityManager { entityManager ->
            val transaction = entityManager.transaction
            transaction.begin()
            try {
                val result = block(entityManager)
                transaction.commit()
                result
            } catch (exception: Exception) {
                if (transaction.isActive) transaction.rollback()
                throw exception
            }
        }

    //override suspend fun getInvite(id: Int): Invite? = withContext(Dispatchers.IO) {
    override suspend fun getInvite(id: Int): Invite? = withEntityManager { entityManager ->
        entityManager.find(Invite::class.java, id)
    }

    //override suspend fun getInvite(code: String): Invite? = withContext(Dispatchers.IO) {
    override suspend fun getInvite(code: String): Invite? = withEntityManager { entityManager ->
        entityManager
            .createQuery("FROM Invite WHERE code = :code", Invite::class.java)
            .setParameter("code", code)
            .resultList
            .singleOrNull()
    }

    //override suspend fun getInvites(): List<Invite> = withContext(Dispatchers.IO) {
    override suspend fun getInvites(): List<Invite> = withEntityManager { entityManager ->
        entityManager
            .createQuery("FROM Invite", Invite::class.java)
            .resultList
            .toList()
    }

    //override suspend fun getInviteCount(): Long = withContext(Dispatchers.IO) {
    override suspend fun getInviteCount(): Long = withEntityManager { entityManager ->
        entityManager
            .createQuery("SELECT COUNT(1) FROM Invite", Long::class.java)
            .singleResult
    }

    //  override suspend fun createInvite(code: String): Invite? = withContext(Dispatchers.IO) {
//    getInvite(code)?.let { return@withContext null }
    override suspend fun createInvite(code: String): Invite? {
        val existing = getInvite(code)
        if (existing != null) return null
        val invite = Invite(
            id = 0,
            code = code,
            username = null
        )

//    entityManager.transaction.begin()
//    entityManager.persist(invite)
//    entityManager.transaction.commit()
//
//    invite
        return withTransaction { entityManager ->
            entityManager.persist(invite)
            invite
        }
    }

    //  override suspend fun deleteInvite(code: String): Boolean = withContext(Dispatchers.IO) {
//    entityManager.transaction.begin()
    override suspend fun deleteInvite(code: String): Boolean = withTransaction { entityManager ->
        val updates = entityManager
            .createQuery("DELETE FROM Invite WHERE code = :code")
            .setParameter("code", code)
            .executeUpdate()
//    entityManager.transaction.commit()

        updates > 0
    }
}
