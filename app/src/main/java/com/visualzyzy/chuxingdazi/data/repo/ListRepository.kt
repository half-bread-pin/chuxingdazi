package com.visualzyzy.chuxingdazi.data.repo

import android.database.sqlite.SQLiteConstraintException
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceListDao
import com.visualzyzy.chuxingdazi.data.db.dao.ListDao
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceListEntity
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceInListRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface ListRepository {
    fun observeAll(): Flow<List<ListEntity>>
    suspend fun getById(id: String): ListEntity?

    /** @throws NameConflictException 名称重复（S27 实时校验 + 保存置灰） */
    suspend fun create(name: String, color: String?): ListEntity

    /** @throws NameConflictException */
    suspend fun rename(id: String, newName: String)
    suspend fun setColor(id: String, color: String?)

    /**
     * 第十六轮：S27 清单排序——direction=-1 上移 / +1 下移。
     * 实现按当前显示序整体重新编号 0..n-1 后交换两者（存量数据 sort_index 恒 0 也能正确交换）。
     */
    suspend fun move(id: String, direction: Int)

    /**
     * 删清单（§5.3 删除语义）：只删 place_list 关系，点位、行程都不受影响。
     */
    suspend fun delete(listId: String)

    suspend fun addPlace(placeId: String, listId: String)
    suspend fun removePlace(placeId: String, listId: String)
    suspend fun placesInList(listId: String): List<PlaceInListRow>
    suspend fun countInListSync(listId: String): Int

    /** S28 清单详情（第七轮）：清单内点位实时流（place/place_list 任一变化即刷新）。 */
    fun observePlacesInList(listId: String): Flow<List<PlaceInListRow>>

    /** S28 添加浮层：清单成员 id 集合实时流。 */
    fun observePlaceIdsInList(listId: String): Flow<Set<String>>

    /** S04 清单筛选（2026-11-24 多选）：多个清单点位 id 的并集（清单数量小，逐清单查再并集）。 */
    suspend fun placeIdsInLists(listIds: Collection<String>): Set<String>
}

@Singleton
class ListRepositoryImpl @Inject constructor(
    private val listDao: ListDao,
    private val placeListDao: PlaceListDao,
) : ListRepository {

    override fun observeAll(): Flow<List<ListEntity>> = listDao.observeAll()

    override suspend fun getById(id: String): ListEntity? = listDao.getById(id)

    override suspend fun create(name: String, color: String?): ListEntity {
        val now = System.currentTimeMillis()
        val entity = ListEntity(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            color = color,
            sortIndex = (listDao.getMinSortIndex() ?: 0) - 1, // 第十六轮：新建排最前
            createdAt = now,
            updatedAt = now,
        )
        return try {
            listDao.insert(entity)
            entity
        } catch (e: SQLiteConstraintException) {
            throw NameConflictException("已有同名清单：${entity.name}")
        }
    }

    override suspend fun rename(id: String, newName: String) {
        val current = listDao.getById(id) ?: return
        val conflict = listDao.findByName(newName.trim())
        if (conflict != null && conflict.id != id) {
            throw NameConflictException("已有同名清单：${conflict.name}")
        }
        listDao.update(current.copy(name = newName.trim(), updatedAt = System.currentTimeMillis()))
    }

    override suspend fun setColor(id: String, color: String?) {
        val current = listDao.getById(id) ?: return
        listDao.update(current.copy(color = color, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun move(id: String, direction: Int) {
        val lists = listDao.getAll() // 已按显示序
        val idx = lists.indexOfFirst { it.id == id }
        val target = idx + direction
        if (idx < 0 || target !in lists.indices) return
        val now = System.currentTimeMillis()
        // 全表重编号消化旧的恒 0 sort_index，再交换两个位置
        lists.forEachIndexed { i, list ->
            val newIdx = when (list.id) {
                lists[idx].id -> target
                lists[target].id -> idx
                else -> i
            }
            if (newIdx != list.sortIndex) {
                listDao.update(list.copy(sortIndex = newIdx, updatedAt = now))
            }
        }
    }

    override suspend fun delete(listId: String) {
        listDao.getById(listId)?.let { listDao.delete(it) }
    }

    override suspend fun addPlace(placeId: String, listId: String) {
        placeListDao.insertIgnore(PlaceListEntity(placeId, listId, System.currentTimeMillis()))
    }

    override suspend fun removePlace(placeId: String, listId: String) {
        placeListDao.remove(placeId, listId)
    }

    override suspend fun placesInList(listId: String): List<PlaceInListRow> = placeListDao.placesInList(listId)

    override fun observePlacesInList(listId: String): Flow<List<PlaceInListRow>> =
        placeListDao.observePlacesInList(listId)

    override fun observePlaceIdsInList(listId: String): Flow<Set<String>> =
        placeListDao.observePlaceIdsInList(listId)
            .map { rows -> rows.toSet() } // kotlinx Flow.map

    override suspend fun countInListSync(listId: String): Int = placeListDao.countInList(listId)

    override suspend fun placeIdsInLists(listIds: Collection<String>): Set<String> =
        listIds.flatMap { observePlaceIdsInList(it).first() }.toSet()
}
