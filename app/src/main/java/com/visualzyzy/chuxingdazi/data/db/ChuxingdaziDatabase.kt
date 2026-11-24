package com.visualzyzy.chuxingdazi.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.visualzyzy.chuxingdazi.data.db.dao.CategoryDao
import com.visualzyzy.chuxingdazi.data.db.dao.ListDao
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceDao
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceListDao
import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleDao
import com.visualzyzy.chuxingdazi.data.db.dao.TrafficLegDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripDao
import com.visualzyzy.chuxingdazi.data.db.dao.DistrictDao
import com.visualzyzy.chuxingdazi.data.db.dao.TripPlaceDao
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.DistrictEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TrafficLegCacheEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity

/**
 * 单库 chuxingdazi.db（§5.1）。首次发版（release 包）后每改一次表就 +1 并写 Migration（§11.10），
 * schema 导出在 app/schemas/。
 */
@Database(
    entities = [
        CategoryEntity::class,
        ListEntity::class,
        PlaceEntity::class,
        PlaceListEntity::class,
        TripEntity::class,
        TripPlaceEntity::class,
        ScheduleEntity::class,
        TrafficLegCacheEntity::class,
        DistrictEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class ChuxingdaziDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun districtDao(): DistrictDao
    abstract fun listDao(): ListDao
    abstract fun placeDao(): PlaceDao
    abstract fun placeListDao(): PlaceListDao
    abstract fun tripDao(): TripDao
    abstract fun tripPlaceDao(): TripPlaceDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun trafficLegDao(): TrafficLegDao

    companion object {
        const val NAME = "chuxingdazi.db"

        /** v2：P7 交通腿结果缓存表（新增，独立于业务数据）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `traffic_leg_cache` (" +
                        "`id` TEXT NOT NULL, " +
                        "`from_place_id` TEXT NOT NULL, " +
                        "`to_place_id` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`minutes` INTEGER NOT NULL, " +
                        "`distance_m` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * v3：点位加省份/城市两列（第七轮 2026-11-06）。
         * 「省份表 / 省份-点位 / 省份-城市 / 城市-点位」四张记录表不物化——place 行本身就是
         * 事实源（province/city 列 + 覆盖索引），聚合记录用 GROUP BY 查询（见 PlaceDao），
         * 增删改联动因此天然正确（改点位即改记录，无第二份需要同步的表）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `place` ADD COLUMN `province` TEXT")
                db.execSQL("ALTER TABLE `place` ADD COLUMN `city` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_place_region` ON `place` (`province`, `city`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_place_city` ON `place` (`city`)")
            }
        }

        /**
         * v4：分类图标库全量替换（第十一轮 2026-11-11）。
         * 旧 39 个 ic_cat_* 已删除，内置分类的图标 key（=分类 id 旧写法）全部失效，
         * 无条件重置为用户指定的新默认图标；自定义分类的旧 key 保持原值（渲染自动跳过，
         * 用户在 S09 重选即恢复——见 designs/04 维护指南的既定行为）。
         * 表结构无变化，只是数据修正。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE category SET icon_key = 'travel_mountain' WHERE id = 'sight' AND is_builtin = 1")
                db.execSQL("UPDATE category SET icon_key = 'food_utensils' WHERE id = 'food' AND is_builtin = 1")
                db.execSQL("UPDATE category SET icon_key = 'place_bed' WHERE id = 'stay' AND is_builtin = 1")
                db.execSQL("UPDATE category SET icon_key = 'transport_train' WHERE id = 'transit' AND is_builtin = 1")
                db.execSQL("UPDATE category SET icon_key = 'place_shopping' WHERE id = 'shop' AND is_builtin = 1")
                db.execSQL("UPDATE category SET icon_key = 'event_ticket' WHERE id = 'event' AND is_builtin = 1")
                db.execSQL("UPDATE category SET icon_key = 'other_point' WHERE id = 'other' AND is_builtin = 1")
            }
        }

        /**
         * v5：自定义分类的旧图标 key 统一重置（第十二轮 2026-11-11）。
         * 旧库的自定义分类可能仍引用 40 个已删除的旧 key（内置 7 个已在 v3→v4 重置），
         * 渲染时方块塌陷成纯文字、与内置分类形态不一致（用户反馈 111.jpg 蓝圈）。
         * 统一重置为定位针 other_point；之后各渲染处对失效 key 也有回退兜底。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE category SET icon_key = 'other_point' WHERE is_builtin = 0 AND icon_key IN " +
                        "('sight','food','stay','transit','shop','event','other'," +
                        "'coffee','wine','beer','ice_cream_cone','pizza'," +
                        "'camera','trees','sunrise','waves','flower_2','landmark'," +
                        "'tent','caravan','key_round','house'," +
                        "'car_front','bus_front','bike','plane','ship','fuel'," +
                        "'store','shopping_cart','gift'," +
                        "'music','film','mic','dumbbell','party_popper'," +
                        "'star','heart','book_open','cross')",
                )
            }
        }

        /**
         * v6：点位加 visited_at（第十六轮 2026-11-14）。
         * 到访从「只有布尔」升级为可回答「哪次去的」；取消到访置 null。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `place` ADD COLUMN `visited_at` INTEGER DEFAULT NULL")
            }
        }

        /**
         * v7：新增 district 表（2026-11-17 腾讯版新需求）。
         * 省-市两级区划改由腾讯 getDistrictChildren 接口获取并本地持久化（2 个月更新一次），
         * 原内置静态表（ChinaRegions）废除。表数据可随时全量重建，升级无损。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `district` (" +
                        "`adcode` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`parentId` INTEGER, " +
                        "`sortIndex` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`adcode`)" +
                        ")",
                )
            }
        }

        /**
         * v8：存量点位坐标 BD09LL → GCJ02（遗留 #10，2026-11-18）。
         * 百度版库内坐标是 BD09LL，腾讯版底图按 GCJ02 解读会整体偏移数百米；v7→v8 升级时
         * 把「腾讯版重写（2026-11-17）之前创建」的点位批量换算（标准 bd09togcj02，
         * 公式与单测锚点见 ui/navi/NaviSheets.kt + Bd09ConversionTest，与
         * tools_seed/backup_bd09_to_gcj02.py 同源）。
         *
         * 判据 = created_at < 2026-11-17 00:00（Asia/Shanghai，epoch 1794844800000）：
         * 百度版最后一次发版是 2026-11-16（release-20261116-1），之前的行一定是百度版写入的
         * BD09LL；腾讯版新建/编辑不回写 created_at（RegionHealer 自愈也不刷 updated_at，第十六轮），
         * 腾讯版期间导入的「转 GCJ02 备份」虽有旧 created_at，但导入发生在 v8 之后、不经过本迁移。
         *
         * 不用纯 SQL：SQLite 的 sqrt/atan2/sin/cos 依赖编译期开关（Android 系统组件通常未启用），
         * Kotlin 循环逐行换算更稳。幂等：本迁移只在 7→v8 升级路径跑一次。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cutoff = 1794844800000L // 2026-11-17 00:00 +08:00
                val query = db.query("SELECT id, lat, lng FROM place WHERE created_at IS NOT NULL AND created_at < ?", arrayOf(cutoff))
                val updates = mutableListOf<Triple<String, Double, Double>>()
                query.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val lat = cursor.getDouble(1)
                        val lng = cursor.getDouble(2)
                        val (gLat, gLng) = com.visualzyzy.chuxingdazi.ui.navi.bd09ToGcJ02(lat, lng)
                        updates.add(Triple(id, gLat, gLng))
                    }
                }
                updates.forEach { (id, gLat, gLng) ->
                    db.execSQL("UPDATE place SET lat = ?, lng = ? WHERE id = ?", arrayOf(gLat, gLng, id))
                }
                android.util.Log.i(
                    "ChxDB",
                    "v7->v8 migration: converted ${updates.size} legacy place(s) BD09LL -> GCJ02",
                )
            }
        }

        /**
         * v9：点位加 custom_schema 列（字段模板升级 §3.1，2026-11-21）。
         * 纯加列默认空数组、无任何数据变换（对比 v8 坐标重算属「加个空抽屉」，零风险）。
         * 用户走「删除数据重装 + 转换脚本」时实际用不到，仍照项目规矩写迁移防忘清数据覆盖安装崩库。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `place` ADD COLUMN `custom_schema` TEXT NOT NULL DEFAULT '[]'")
            }
        }
    }
}
