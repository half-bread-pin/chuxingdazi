package com.visualzyzy.chuxingdazi.data.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.visualzyzy.chuxingdazi.data.db.ChuxingdaziDatabase
import com.visualzyzy.chuxingdazi.data.db.dao.TripCountRow
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripEntity
import com.visualzyzy.chuxingdazi.data.db.entity.TripPlaceEntity
import com.visualzyzy.chuxingdazi.data.model.IconCatalog
import com.visualzyzy.chuxingdazi.data.repo.BuiltinCategories
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 备份 JSON（§5.5）。字段名与设计文档逐字对齐。
 * 第三十五轮（2026-11-15，用户需求）：version 改小数版本制，当前 0.5——为未来不同版本备份
 * 在导入时做版本间处理预留；version 当前只记录、不参与导入分支。解析容错：旧开发期整数
 * 版本（1–5）的 JSON 整数可直接解码为 Double，缺字段取默认值，旧备份导入不受影响。
 */
@Serializable
data class BackupFile(
    val format: String = "chuxingdazi-backup",
    val version: Double = 0.5,
    val exportedAt: Long,
    val categories: List<CategoryDto> = emptyList(),
    val lists: List<ListDto> = emptyList(),
    val places: List<PlaceDto> = emptyList(),
    val placeLists: List<PlaceListDto> = emptyList(),
    val trips: List<TripDto> = emptyList(),
    val tripPlaces: List<TripPlaceDto> = emptyList(),
    val schedules: List<ScheduleDto> = emptyList(),
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val iconKey: String,
    val color: String,
    val sortIndex: Int = 0,
    val isBuiltin: Int = 0,
    val fieldSchema: JsonElement = Json.parseToJsonElement("[]"),
)

@Serializable
data class ListDto(val id: String, val name: String, val color: String?, val sortIndex: Int = 0)

@Serializable
data class PlaceDto(
    val id: String,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val categoryId: String,
    val iconKey: String? = null,
    val color: String? = null,
    val note: String? = null,
    val fieldValues: JsonElement = Json.parseToJsonElement("{}"),
    // 字段模板升级（2026-11-21）：点位自定义字段定义，可选数组——旧备份缺省 null=无自定义字段，
    // version 保持 0.5 不做版本分支（§6.4 转换脚本统一清洗旧备份）
    val customSchema: JsonElement? = null,
    val source: String = "SEARCH",
    val poiUid: String? = null,
    val province: String? = null, // v4 新增；旧备份缺省 null → 导入后由 RegionHealer 自愈补齐
    val city: String? = null,
    val visited: Int = 0,
    val visitedAt: Long? = null, // v5 新增（第十六轮）：到访时间
)

@Serializable
data class PlaceListDto(val placeId: String, val listId: String, val addedAt: Long)

@Serializable
data class TripDto(val id: String, val name: String, val startDate: String?, val endDate: String?)

@Serializable
data class TripPlaceDto(val tripId: String, val placeId: String, val addedAt: Long)

@Serializable
data class ScheduleDto(
    val id: String,
    val tripId: String,
    val placeId: String,
    val planDate: String,
    val startMin: Int,
    val endMin: Int,
    val sortHint: Int = 0,
)

data class ImportReport(
    val places: Int,
    val lists: Int,
    val categoriesMerged: Int,
    val trips: Int,
    val schedules: Int,
    val skippedSchedules: Int,
)

/**
 * 导入冲突预览（第十六轮 2026-11-14，优化建议：导入只问「同名或同 ID」、做完才发现少点）。
 * 确认前列出备份内容、将新增、同 ID 冲突（覆盖模式=更新 / 保留两者模式=改存副本）、
 * 本地独有条数——导入只增改不删，本地独有数据不受影响（预览里写明，避免误解为会被删）。
 */
data class ImportPreview(
    val backupPlaces: Int,
    val backupLists: Int,
    val backupTrips: Int,
    val backupSchedules: Int,
    val newPlaces: Int,
    val conflictPlaces: Int,
    val newLists: Int,
    val conflictLists: Int,
    val newTrips: Int,
    val conflictTrips: Int,
    val newSchedules: Int,
    val conflictSchedules: Int,
    val localOnlyPlaces: Int,
    val localOnlyLists: Int,
    val localOnlyTrips: Int,
) {
    val hasConflicts: Boolean get() = conflictPlaces + conflictLists + conflictTrips + conflictSchedules > 0
}

data class ExportResult(val path: String, val count: Int)

/**
 * 备份/恢复（§5.5 + S13）：
 * - 导出 = 全表 → JSON → Download/我的出行搭子备份-YYYYMMDD-hhmmss.json（第三十三轮：加时分秒，
 *   用户可能一天内多次导出；历史列表/导入匹配按「我的出行搭子备份-」前缀，新旧文件名共存兼容）；
 * - 导入 = 冲突策略（覆盖 / 保留两者改id）全局选一次；内置分类只合并更新；
 *   换 id 必须先建映射表再统一改写引用；孤儿 schedule 跳过并计数；tripPlaces 反推补齐。
 */
@Singleton
class BackupManager @Inject constructor(
    private val db: ChuxingdaziDatabase,
    private val regionHealer: com.visualzyzy.chuxingdazi.data.repo.RegionHealer,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val categoryDao get() = db.categoryDao()
    private val listDao get() = db.listDao()
    private val placeDao get() = db.placeDao()
    private val placeListDao get() = db.placeListDao()
    private val tripDao get() = db.tripDao()
    private val tripPlaceDao get() = db.tripPlaceDao()
    private val scheduleDao get() = db.scheduleDao()
    private val trafficLegDao get() = db.trafficLegDao()

    // ---------- 导出 ----------

    suspend fun export(): ExportResult {
        val categories = categoryDao.getAll()
        val lists = listDao.getAll()
        val places = placeDao.getAll()
        val trips = tripDao.getAll()
        val dto = BackupFile(
            exportedAt = System.currentTimeMillis(),
            categories = categories.map {
                CategoryDto(it.id, it.name, it.iconKey, it.color, it.sortIndex, if (it.isBuiltin) 1 else 0,
                    Json.parseToJsonElement(it.fieldSchema.ifBlank { "[]" }))
            },
            lists = lists.map { ListDto(it.id, it.name, it.color, it.sortIndex) },
            places = places.map {
                PlaceDto(
                    it.id, it.name, it.address, it.lat, it.lng, it.categoryId, it.iconKey, it.color, it.note,
                    Json.parseToJsonElement(it.fieldValues.ifBlank { "{}" }),
                    Json.parseToJsonElement(it.customSchema.ifBlank { "[]" }), // 字段模板升级：自定义字段定义随备份带走
                    it.source, it.poiUid,
                    it.province, it.city, if (it.visited) 1 else 0, it.visitedAt,
                )
            },
            placeLists = exportPlaceLists(),
            trips = trips.map { TripDto(it.id, it.name, it.startDate, it.endDate) },
            tripPlaces = exportTripPlaces(),
            schedules = exportSchedules(),
        )
        val body = json.encodeToString(dto)
        // 第三十三轮（2026-11-15，用户需求）：文件名精确到秒——用户可能在一天内、一分钟内
        // 多次导出，同名靠 MediaStore 加 " (1)" 后缀不直观，秒级时间戳天然唯一、按名可排序
        val name = "我的出行搭子备份-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())}.json"
        val path = writeToDownloads(name, body)
        return ExportResult(path, places.size)
    }

    private suspend fun exportPlaceLists(): List<PlaceListDto> =
        placeListDao.allForBackup().map { PlaceListDto(it.placeId, it.listId, it.addedAt) }

    private suspend fun exportTripPlaces(): List<TripPlaceDto> =
        // 第十四轮（2026-11-12）修复：改为全表导出并携带真实 added_at——
        // 原实现硬编码 0L，而待安排池排序正是 ORDER BY tp.added_at（§5.3），恢复后顺序失真。
        tripPlaceDao.allForBackup().map { TripPlaceDto(it.tripId, it.placeId, it.addedAt) }

    private suspend fun exportSchedules(): List<ScheduleDto> =
        scheduleDao.allForBackup().map {
            ScheduleDto(it.id, it.tripId, it.placeId, it.planDate, it.startMin, it.endMin, it.sortHint)
        }

    private fun writeToDownloads(name: String, body: String): String {
        val safeBody = body
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = appResolver()
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入 Download")
            resolver.openOutputStream(uri)?.use { it.write(safeBody.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Download/$name"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            file.writeText(safeBody, Charsets.UTF_8)
            file.absolutePath
        }
    }

    /** 列出 Download 里本应用导出的备份文件（MediaStore 查询，自有贡献文件可直接读）。 */
    fun listBackups(): List<Pair<android.net.Uri, String>> {
        val resolver = appResolver()
        val result = mutableListOf<Pair<android.net.Uri, String>>()
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("我的出行搭子备份-%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1)
                        result.add(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id) to name)
                    }
                }
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir?.listFiles { f -> f.name.startsWith("我的出行搭子备份-") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { result.add(android.net.Uri.fromFile(it) to it.name) }
        }
        return result
    }

    private fun appResolver() = resolverProvider?.invoke()
        ?: throw IllegalStateException("Context 未注入")

    /** 由 App 层注入 ContentResolver（BackupManager 是纯 Kotlin 单例，不持有 Context）。 */
    var resolverProvider: (() -> android.content.ContentResolver)? = null

    /** 分享入口（S13：备份后给「分享」）。 */
    fun shareIntent(path: String): android.content.Intent {
        val ctx = appContextForShare ?: throw IllegalStateException("Context 未注入")
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), File(path).name)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx,
            ctx.packageName + ".fileprovider",
            file,
        )
        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    var appContextForShare: Context? = null

    // ---------- 导入 ----------

    /**
     * 导入前冲突预览（第十六轮）：只读不写，供 S13 确认对话框展示。
     * 分类不进预览（内置合并 / 自定义重名加后缀，不会丢）。
     */
    suspend fun previewImport(jsonText: String): ImportPreview {
        val parsed = json.decodeFromString<BackupFile>(jsonText)
        val existingPlaces = placeDao.getAll()
        val existingLists = listDao.getAll()
        val existingTrips = tripDao.getAll()
        val existingScheduleIds = scheduleDao.allForBackup().map { it.id }.toSet()
        val backupPlaceIds = parsed.places.map { it.id }.toSet()
        val backupListIds = parsed.lists.map { it.id }.toSet()
        val backupTripIds = parsed.trips.map { it.id }.toSet()
        return ImportPreview(
            backupPlaces = parsed.places.size,
            backupLists = parsed.lists.size,
            backupTrips = parsed.trips.size,
            backupSchedules = parsed.schedules.size,
            newPlaces = parsed.places.count { existingPlaces.none { e -> e.id == it.id } },
            conflictPlaces = parsed.places.count { existingPlaces.any { e -> e.id == it.id } },
            newLists = parsed.lists.count { existingLists.none { e -> e.id == it.id } },
            conflictLists = parsed.lists.count { existingLists.any { e -> e.id == it.id } },
            newTrips = parsed.trips.count { existingTrips.none { e -> e.id == it.id } },
            conflictTrips = parsed.trips.count { existingTrips.any { e -> e.id == it.id } },
            newSchedules = parsed.schedules.count { it.id !in existingScheduleIds },
            conflictSchedules = parsed.schedules.count { it.id in existingScheduleIds },
            localOnlyPlaces = existingPlaces.count { it.id !in backupPlaceIds },
            localOnlyLists = existingLists.count { it.id !in backupListIds },
            localOnlyTrips = existingTrips.count { it.id !in backupTripIds },
        )
    }

    /**
     * @param keepBoth true=同 id 冲突改新 id 保留两者（引用重映射）；false=同 id 覆盖。
     */
    /**
     * 第十一轮（2026-11-11）图标库全量替换后的导入守卫：
     * 旧备份里指向已删除图标的 key 不直接落库——内置分类回退到该分类的新默认图标，
     * 自定义分类统一回退定位针（第十二轮收紧：原「保留原值」会让分类选择区出现
     * 无图标的塌陷方块，与内置分类形态不一致，见用户反馈 111.jpg）。
     */
    private fun sanitizeCategoryIconKey(dto: CategoryDto): String =
        when {
            IconCatalog.contains(dto.iconKey) -> dto.iconKey
            dto.isBuiltin == 1 ->
                BuiltinCategories.specs.firstOrNull { it.first == dto.id }?.second?.second ?: "other_point"
            else -> "other_point"
        }

    /** 点位自定义图标 key 已删除（旧备份）→ 置空回「跟随分类」。 */
    private fun sanitizePlaceIconKey(dto: PlaceDto): String? =
        dto.iconKey?.takeIf { IconCatalog.contains(it) }

    suspend fun import(jsonText: String, keepBoth: Boolean): ImportReport {
        val parsed = json.decodeFromString<BackupFile>(jsonText)

        var mergedCategories = 0
        var importedPlaces = 0
        var importedLists = 0
        var importedTrips = 0
        var importedSchedules = 0
        var skippedSchedules = 0

        db.withTransaction {
            // 1) 分类：内置只合并更新；自定义按 id/名称处理
            val catIdMap = mutableMapOf<String, String>()
            val existingCats = categoryDao.getAll()
            for (dto in parsed.categories) {
                val builtin = dto.isBuiltin == 1
                val sameId = existingCats.firstOrNull { it.id == dto.id }
                if (builtin) {
                    if (sameId != null) {
                        categoryDao.update(
                            sameId.copy(
                                name = dto.name, iconKey = sanitizeCategoryIconKey(dto), color = dto.color,
                                sortIndex = dto.sortIndex, fieldSchema = dto.fieldSchema.toString(),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        mergedCategories++
                    } else {
                        // 内置分类本地缺失（如被手动清库）→ 照 builtin 补种
                        categoryDao.insert(
                            CategoryEntity(
                                id = dto.id, name = dto.name, iconKey = sanitizeCategoryIconKey(dto), color = dto.color,
                                sortIndex = dto.sortIndex, isBuiltin = true,
                                fieldSchema = dto.fieldSchema.toString(),
                                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        mergedCategories++
                    }
                    catIdMap[dto.id] = dto.id
                    continue
                }
                if (sameId != null && !keepBoth) {
                    categoryDao.update(
                        sameId.copy(
                            name = dto.name, iconKey = sanitizeCategoryIconKey(dto), color = dto.color, sortIndex = dto.sortIndex,
                            fieldSchema = dto.fieldSchema.toString(),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    catIdMap[dto.id] = dto.id
                    continue
                }
                if (sameId != null && keepBoth) {
                    val newId = UUID.randomUUID().toString()
                    val name = uniqueName(dto.name, existingCats.map { c -> c.name }.toSet())
                    categoryDao.insert(
                        CategoryEntity(
                            id = newId, name = name,
                            iconKey = sanitizeCategoryIconKey(dto), color = dto.color, sortIndex = dto.sortIndex,
                            fieldSchema = dto.fieldSchema.toString(),
                            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    catIdMap[dto.id] = newId
                    continue
                }
                // 同名不同 id（UNIQUE 拒绝）→ 加后缀
                val nameClash = categoryDao.findByName(dto.name)
                val finalName = if (nameClash != null) dto.name + "(2)" else dto.name
                categoryDao.insert(
                    CategoryEntity(
                        id = dto.id, name = finalName, iconKey = sanitizeCategoryIconKey(dto), color = dto.color,
                        sortIndex = dto.sortIndex, fieldSchema = dto.fieldSchema.toString(),
                        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                    ),
                )
                catIdMap[dto.id] = dto.id
            }

            // 2) 清单
            val listIdMap = mutableMapOf<String, String>()
            val existingLists = listDao.getAll().map { it.name }.toMutableSet()
            for (dto in parsed.lists) {
                val sameId = listDao.getById(dto.id)
                when {
                    sameId != null && !keepBoth -> {
                        listDao.update(sameId.copy(name = dto.name, color = dto.color, sortIndex = dto.sortIndex, updatedAt = System.currentTimeMillis()))
                        listIdMap[dto.id] = dto.id
                    }
                    sameId != null && keepBoth -> {
                        val newId = UUID.randomUUID().toString()
                        val name = if (dto.name in existingLists) dto.name + "(2)" else dto.name
                        listDao.insert(
                            ListEntity(newId, name, dto.color, dto.sortIndex, System.currentTimeMillis(), System.currentTimeMillis()),
                        )
                        existingLists.add(name)
                        listIdMap[dto.id] = newId
                    }
                    else -> {
                        val name = if (listDao.findByName(dto.name) != null) dto.name + "(2)" else dto.name
                        listDao.insert(
                            ListEntity(dto.id, name, dto.color, dto.sortIndex, System.currentTimeMillis(), System.currentTimeMillis()),
                        )
                        existingLists.add(name)
                        listIdMap[dto.id] = dto.id
                    }
                }
                importedLists++
            }

            // 3) 点位（覆盖同 id 时只改点位自身字段，不动关联数据 §5.5）
            val placeIdMap = mutableMapOf<String, String>()
            val existingPlaceNames = placeDao.getAll().map { it.name }.toMutableSet()
            for (dto in parsed.places) {
                val mappedCat = catIdMap[dto.categoryId] ?: dto.categoryId
                val entity = PlaceEntity(
                    id = dto.id, name = dto.name, address = dto.address, lat = dto.lat, lng = dto.lng,
                    categoryId = mappedCat, iconKey = sanitizePlaceIconKey(dto), color = dto.color, note = dto.note,
                    fieldValues = dto.fieldValues.toString(), source = dto.source, poiUid = dto.poiUid,
                    customSchema = dto.customSchema?.toString() ?: "[]", // 字段模板升级：缺省空数组
                    province = dto.province, city = dto.city,
                    visited = dto.visited == 1,
                    visitedAt = dto.visitedAt,
                    createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                )
                val sameId = placeDao.getById(dto.id)
                when {
                    sameId != null && !keepBoth -> placeDao.update(entity.copy(createdAt = sameId.createdAt))
                    sameId != null && keepBoth -> {
                        val newId = UUID.randomUUID().toString()
                        val name = if (dto.name in existingPlaceNames) dto.name + "(2)" else dto.name
                        placeDao.insert(entity.copy(id = newId, name = name))
                        existingPlaceNames.add(name)
                        placeIdMap[dto.id] = newId
                    }
                    else -> {
                        // 近重复（<5m 同名）不拦：备份导入语义优先
                        val name = if (dto.name in existingPlaceNames) dto.name + "(2)" else dto.name
                        placeDao.insert(entity.copy(name = name))
                        existingPlaceNames.add(name)
                        placeIdMap[dto.id] = dto.id
                    }
                }
                importedPlaces++
            }

            // 4) 行程
            val tripIdMap = mutableMapOf<String, String>()
            for (dto in parsed.trips) {
                val sameId = tripDao.getById(dto.id)
                val entity = TripEntity(
                    id = dto.id, name = dto.name, startDate = dto.startDate, endDate = dto.endDate,
                    createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                )
                when {
                    sameId != null && !keepBoth ->
                        // 第十四轮：覆盖保留原 createdAt（与点位/清单/分类的覆盖语义一致）
                        tripDao.update(entity.copy(createdAt = sameId.createdAt))
                    sameId != null && keepBoth -> {
                        val newId = UUID.randomUUID().toString()
                        tripDao.insert(entity.copy(id = newId, name = dto.name + "(2)"))
                        tripIdMap[dto.id] = newId
                    }
                    else -> {
                        tripDao.insert(entity)
                        tripIdMap[dto.id] = dto.id
                    }
                }
                importedTrips++
            }

            // 4b) tripPlace（行程成员全量导入，§5.5）。
            // 第十四轮（2026-11-12）修复严重遗漏：此前 parsed.tripPlaces 从未被导入，
            // 行程成员全靠第 6 步按 schedule 反推——「加入行程但还没安排时间」的
            // 待安排池点位在恢复后直接从行程里消失。§5.5：反推只是旧备份（v1–v2）
            // 缺 tripPlaces 时的兜底，不是唯一路径。
            val validTripIds = tripDao.getAll().map { it.id }.toSet()
            for (dto in parsed.tripPlaces) {
                val tid = tripIdMap[dto.tripId] ?: dto.tripId
                val pid = placeIdMap[dto.placeId] ?: dto.placeId
                if (tid in validTripIds && placeDao.getById(pid) != null) {
                    tripPlaceDao.insertIgnore(TripPlaceEntity(tid, pid, dto.addedAt))
                }
            }

            // 5) placeList（引用重映射后插入，IGNORE 挡重）
            for (dto in parsed.placeLists) {
                val pid = placeIdMap[dto.placeId] ?: dto.placeId
                val lid = listIdMap[dto.listId] ?: dto.listId
                if (placeDao.getById(pid) != null && listDao.getById(lid) != null) {
                    placeListDao.insertIgnore(PlaceListEntity(pid, lid, dto.addedAt))
                }
            }

            // 6) schedule（孤儿跳过计数）。行程成员已由 4b 全量导入；此处 insertIgnore 仅作
            //    旧备份（v1–v2 缺 tripPlaces）的 §5.5 反推兜底，复合主键天然挡重。
            // 第十四轮（2026-11-12）：schedule 同 id 冲突按全局冲突策略处理（§5.5「按 id 去重，
            // 冲突时覆盖或保留两者」）——此前直接 insert(ABORT)：同设备重导入备份时 schedule id
            // 与本地现存行冲突 → SQLiteConstraintException → 整个导入事务回滚（备份恢复失败）。
            // 多段点位 = 多行 schedule，这里逐行独立处理互不折叠；保留两者 = 改新 id 新建一段
            // （tid/pid 已经过映射表重映射，同一点位的多段全部跟随到新 id，不丢段）。
            for (dto in parsed.schedules) {
                val tid = tripIdMap[dto.tripId] ?: dto.tripId
                val pid = placeIdMap[dto.placeId] ?: dto.placeId
                if (tid !in validTripIds || placeDao.getById(pid) == null) {
                    skippedSchedules++
                    continue
                }
                if (dto.startMin !in 0..1439 || dto.endMin < dto.startMin || dto.endMin > dto.startMin + 43200) {
                    // 决策 #66：0 分钟段（endMin == startMin）合法，导入不再跳过
                    skippedSchedules++
                    continue
                }
                tripPlaceDao.insertIgnore(TripPlaceEntity(tid, pid, System.currentTimeMillis()))
                val now = System.currentTimeMillis()
                val sameId = scheduleDao.getById(dto.id)
                when {
                    sameId != null && !keepBoth ->
                        // 覆盖：只更新该行自身字段，保留原 createdAt（§5.5「覆盖时不动关联数据」）
                        scheduleDao.update(
                            ScheduleEntity(
                                id = dto.id, tripId = tid, placeId = pid, planDate = dto.planDate,
                                startMin = dto.startMin, endMin = dto.endMin, sortHint = dto.sortHint,
                                createdAt = sameId.createdAt, updatedAt = now,
                            ),
                        )
                    else ->
                        // 无冲突直接插入；保留两者撞 id → 改新 id 新建一段
                        scheduleDao.insert(
                            ScheduleEntity(
                                id = if (sameId != null) UUID.randomUUID().toString() else dto.id,
                                tripId = tid, placeId = pid, planDate = dto.planDate,
                                startMin = dto.startMin, endMin = dto.endMin, sortHint = dto.sortHint,
                                createdAt = now, updatedAt = now,
                            ),
                        )
                }
                importedSchedules++
            }

            // 第十六轮：traffic_leg_cache 无外键（P7），点位删除/覆盖换 id 后会留失效腿——
            // 导入收尾把 from/to 任一端已不存在的腿一并清掉
            trafficLegDao.deleteStaleLegs()
        }
        // 第七轮：导入的旧备份（v1–v3）或缺失 region 的点位 → 后台自愈补齐（无 AK 时静默，下次再试）
        // 第八轮：导入即点位数据变化 → healthy 标志先置 false（受 2 分钟限速约束，最多晚 2 分钟开补）
        regionHealer.markDirty()
        regionHealer.kick()
        return ImportReport(importedPlaces, importedLists, mergedCategories, importedTrips, importedSchedules, skippedSchedules)
    }

    private fun uniqueName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var i = 2
        while ("$base($i)" in taken) i++
        return "$base($i)"
    }

    // ---------- 清空 ----------

    /** 清空所有业务数据（内置分类保留 = 种子；§5.2 外键级联会带走 trip_place/schedule/place_list）。 */
    suspend fun clearAll() {
        db.withTransaction {
            placeDao.deleteAll()
            listDao.deleteAll()
            tripDao.deleteAll()
            placeListDao.deleteAll()
            tripPlaceDao.deleteAll()
            scheduleDao.deleteAll()
            trafficLegDao.deleteAll() // P7 交通腿缓存：非业务数据，清空时一并清
        }
    }
}
