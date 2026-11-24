package com.visualzyzy.chuxingdazi.data.db

import com.visualzyzy.chuxingdazi.data.db.dao.CategoryDao
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceDao
import com.visualzyzy.chuxingdazi.data.model.FieldDef
import com.visualzyzy.chuxingdazi.data.model.FieldType
import com.visualzyzy.chuxingdazi.data.repo.BuiltinCategories
import com.visualzyzy.chuxingdazi.data.repo.BuiltinIds
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepositoryImpl
import com.visualzyzy.chuxingdazi.data.repo.NameConflictException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * §5.4 内置分类种子 + §14.1 #33 名称唯一 + §5.3 删自定义分类语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CategoryRepositoryTest {

    private lateinit var db: ChuxingdaziDatabase
    private lateinit var categoryRepository: CategoryRepositoryImpl
    private lateinit var categoryDao: CategoryDao
    private lateinit var placeDao: PlaceDao

    @Before
    fun setUp() {
        db = TestDb.create()
        categoryRepository = CategoryRepositoryImpl(db, db.categoryDao(), db.placeDao())
        categoryDao = db.categoryDao()
        placeDao = db.placeDao()
    }

    @After
    fun tearDown() {
        TestDb.closeDb(db)
    }

    @Test
    fun `种子幂等且不覆盖用户已改的值`() = runTest {
        categoryRepository.seedBuiltins()
        categoryRepository.seedBuiltins()
        assertEquals(BuiltinCategories.specs.size, categoryDao.getAll().size)

        // 用户改了「美食」的颜色后再 seed，颜色不被覆盖（§5.4：不覆盖用户已改的值）
        categoryRepository.updateAppearance(BuiltinIds.FOOD, "food", "#123456")
        categoryRepository.seedBuiltins()
        assertEquals("#123456", categoryDao.getById(BuiltinIds.FOOD)?.color)
    }

    @Test
    fun `内置分类的默认字段模板正确且全部非必填`() = runTest {
        categoryRepository.seedBuiltins()
        val food = categoryDao.getById(BuiltinIds.FOOD)!!
        val schema = com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.decode(food.fieldSchema)
        assertEquals(2, schema.size)
        assertEquals("人均价格", schema[0].label)
        assertEquals(FieldType.MONEY, schema[0].type)
        assertTrue(schema.all { !it.required })

        val other = categoryDao.getById(BuiltinIds.OTHER)!!
        assertTrue(com.visualzyzy.chuxingdazi.data.model.FieldSchemaCodec.decode(other.fieldSchema).isEmpty())
    }

    @Test
    fun `分类名全局唯一，含内置分类名`() = runTest {
        categoryRepository.seedBuiltins()

        assertTrue(
            runCatching { categoryRepository.create("美食", "food", "#000000") }
                .exceptionOrNull() is NameConflictException,
        )
        // 与自定义分类冲突
        categoryRepository.create("咖啡馆", "coffee", "#000000")
        assertTrue(
            runCatching { categoryRepository.create("咖啡馆", "coffee", "#000000") }
                .exceptionOrNull() is NameConflictException,
        )
        // 改名撞别的分类
        val custom = categoryDao.getAll().first { !it.isBuiltin && it.name == "咖啡馆" }
        assertTrue(
            runCatching { categoryRepository.rename(custom.id, "景点") }
                .exceptionOrNull() is NameConflictException,
        )
        // 改成自己的名字不冲突
        categoryRepository.rename(custom.id, "咖啡馆")
    }

    @Test
    fun `删自定义分类时点位回落 other 并清理该模板的孤儿值`() = runTest {
        categoryRepository.seedBuiltins()
        val custom = categoryRepository.create("咖啡馆", "coffee", "#0EA5E9")
        categoryRepository.updateFieldSchema(
            custom.id,
            listOf(
                FieldDef("price", "人均价格", FieldType.MONEY),
                FieldDef("wifi", "有 WiFi", FieldType.TEXT),
            ),
        )

        // 一个点位只填了 price 和别的通用 key
        placeDao.insert(
            TestDb.place("p1", "楼上咖啡", custom.id, fieldValues = """{"price":32.0,"user_note":"记得带书"}"""),
        )
        placeDao.insert(TestDb.place("p2", "无字段值的店", custom.id))

        categoryRepository.deleteCustom(custom.id)

        assertNull(categoryDao.getById(custom.id))
        val migrated = placeDao.getById("p1")!!
        assertEquals(BuiltinIds.OTHER, migrated.categoryId)
        // 该分类模板的 key 被清理，其它 key 保留（§5.3 删除语义表）
        val values = com.visualzyzy.chuxingdazi.data.model.FieldValuesCodec.decode(migrated.fieldValues)
        assertTrue(!values.containsKey("price"))
        assertEquals("\"记得带书\"", values["user_note"].toString())
        // 无 field_values 的点位也正常迁移
        assertEquals(BuiltinIds.OTHER, placeDao.getById("p2")!!.categoryId)
        assertEquals(2, placeDao.countByCategory(BuiltinIds.OTHER))
    }

    @Test
    fun `内置分类不可删除`() = runTest {
        categoryRepository.seedBuiltins()
        val result = runCatching { categoryRepository.deleteCustom(BuiltinIds.FOOD) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertNotNull(categoryDao.getById(BuiltinIds.FOOD))
    }

    @Test
    fun `清单名唯一且删清单不删点位`() = runTest {
        val listRepository = com.visualzyzy.chuxingdazi.data.repo.ListRepositoryImpl(db.listDao(), db.placeListDao())
        val list = listRepository.create("杭州必吃", "#F97316")
        assertTrue(
            runCatching { listRepository.create("杭州必吃", null) }
                .exceptionOrNull() is NameConflictException,
        )

        placeDao.insert(TestDb.place("p1", "楼外楼"))
        listRepository.addPlace("p1", list.id)
        assertEquals(1, listRepository.placesInList(list.id).size)

        listRepository.delete(list.id)
        assertNull(listRepository.getById(list.id))
        // 点位本身还在（§5.3：删清单只删 place_list 关系）
        assertNotNull(placeDao.getById("p1"))
        assertEquals(0, db.placeListDao().countInList(list.id))
    }
}
