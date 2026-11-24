package com.visualzyzy.chuxingdazi.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.db.dao.PlaceInListRow
import com.visualzyzy.chuxingdazi.data.db.entity.CategoryEntity
import com.visualzyzy.chuxingdazi.data.db.entity.ListEntity
import com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity
import com.visualzyzy.chuxingdazi.data.repo.CategoryRepository
import com.visualzyzy.chuxingdazi.data.repo.ListRepository
import com.visualzyzy.chuxingdazi.data.repo.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * S28 清单详情（§6 S28，第七轮 2026-11-06 实现）。
 * 入口 = S27 点具体清单；出口 = 返回 / 返回键 → S27。
 * 功能（按用户本轮要求）：添加点位到清单（底部浮层多选 + 搜索选点）、移出清单；
 * 搜索逻辑与标记管理界面一致（名称/地址，含省份-城市）；点位数量级未知 → 全部走 DB 实时流。
 * 「移出清单」= 只删 place_list 关系，点位本体保留（§5.3 删除语义）。
 */
@HiltViewModel
class ListDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listRepository: ListRepository,
    placeRepository: PlaceRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val listId: String = savedStateHandle.get<String>("listId").orEmpty()

    /** 清单元信息（名称/颜色）。 */
    val listMeta: StateFlow<ListEntity?> = listRepository.observeAll()
        .map { lists -> lists.firstOrNull { it.id == listId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 清单内点位（最近添加在前）。 */
    val rows: StateFlow<List<PlaceInListRow>> = listRepository.observePlacesInList(listId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 全库点位 + 成员 id（添加浮层用：排除已在清单里的）。 */
    val allPlaces: StateFlow<List<PlaceEntity>> = placeRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memberIds: StateFlow<Set<String>> = listRepository.observePlaceIdsInList(listId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removePlace(placeId: String) {
        viewModelScope.launch { listRepository.removePlace(placeId, listId) }
    }

    fun addPlaces(placeIds: Collection<String>) {
        viewModelScope.launch { placeIds.forEach { listRepository.addPlace(it, listId) } }
    }
}

@Composable
fun ListDetailScreen(
    onClose: () -> Unit,
    viewModel: ListDetailViewModel = hiltViewModel(),
) {
    val listMeta by viewModel.listMeta.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val allPlaces by viewModel.allPlaces.collectAsStateWithLifecycle()
    val memberIds by viewModel.memberIds.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoriesById = remember(categories) { categories.associateBy { it.id } }

    // 第九轮（2026-11-08）：搜索改为与 S29 标记管理同一套多模式搜索（名称/地区/分类弹层），
    // 顶部筛选与「添加点位」浮层共用 ui/common/PlaceSearchBars。
    var searchBars by remember { mutableStateOf(listOf(com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBar())) }
    var showAddSheet by remember { mutableStateOf(false) }
    var addBars by remember { mutableStateOf(listOf(com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBar())) }
    var selectedForAdd by remember { mutableStateOf(setOf<String>()) }

    val markedProvinces = remember(allPlaces) {
        allPlaces.mapNotNull { it.province?.takeIf { p -> p.isNotBlank() } }.distinct()
    }

    val filteredRows = remember(rows, searchBars) {
        val filtered = com.visualzyzy.chuxingdazi.ui.common.filterPlacesBySearchBars(
            places = rows.map { it.place },
            bars = searchBars,
        )
        val ids = filtered.mapTo(HashSet()) { it.id }
        rows.filter { it.place.id in ids }
    }

    val addCandidates = remember(allPlaces, memberIds, addBars) {
        val matched = com.visualzyzy.chuxingdazi.ui.common.filterPlacesBySearchBars(
            places = allPlaces,
            bars = addBars,
        )
        matched.filter { it.id !in memberIds }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶栏：返回 / 清单名 + 色点 / 计数
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier
                        .size(26.dp)
                        .clickable(onClick = onClose),
                )
                Spacer(modifier = Modifier.width(10.dp))
                listMeta?.let { list ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                list.color?.let { c ->
                                    runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrDefault(Color(0xFFB9C2CE))
                                } ?: Color(0xFFB9C2CE),
                                CircleShape,
                            ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = listMeta?.name ?: "清单",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${rows.size} 个点位",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBarsSection(
                bars = searchBars,
                onBarsChange = { searchBars = it },
                categories = categories,
                markedProvinces = markedProvinces,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(filteredRows, key = { it.place.id }) { row ->
                    val place = row.place
                    val category = categoriesById[place.categoryId]
                    Surface(
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(parseListColor(category?.color)),
                                contentAlignment = Alignment.Center,
                            ) {
                                ListCategoryIcon(category?.iconKey, 20.dp)
                            }
                            Spacer(modifier = Modifier.width(11.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = place.name,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight(600),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    // 第十六轮：副标题改「地址/区域 · 分类名」，不再拼经纬度
                                    //（清单行此前「地址 · 经纬度」截成一行，坐标对用户没有可读价值）
                                    // 遗留 #9（2026-11-18）：区域回退改 regionLabelOf——直辖市省市同名不再「北京市-北京市」
                                    text = listOfNotNull(
                                        place.address
                                            ?: com.visualzyzy.chuxingdazi.ui.common.regionLabelOf(place.province, place.city)
                                            ?: "暂无地址",
                                        category?.name,
                                    ).joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "移出",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { viewModel.removePlace(place.id) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (filteredRows.isEmpty()) {
                    item {
                        Text(
                            text = when {
                                rows.isEmpty() -> "这个清单还没有点位，点下方「＋ 添加点位」"
                                else -> "没有匹配当前搜索条件的点位"
                            },
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.navigationBarsPadding())

            // 底部通栏：＋ 添加点位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding()
                    .height(46.dp)
                    .background(
                        com.visualzyzy.chuxingdazi.ui.theme.Primary,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable {
                        addBars = listOf(com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBar())
                        selectedForAdd = emptySet()
                        showAddSheet = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "＋ 添加点位", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // 添加浮层：scrim + 底部面板（多选 + 搜索选点，逻辑同标记管理）
        if (showAddSheet) {
            androidx.activity.compose.BackHandler(enabled = true) { showAddSheet = false }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x52000000))
                    .clickable(onClick = { showAddSheet = false }),
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) {},
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "添加点位到「${listMeta?.name.orEmpty()}」",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable { showAddSheet = false },
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        com.visualzyzy.chuxingdazi.ui.common.PlaceSearchBarsSection(
                            bars = addBars,
                            onBarsChange = { addBars = it },
                            categories = categories,
                            markedProvinces = markedProvinces,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Column(modifier = Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                            addCandidates.forEach { place ->
                                val selected = place.id in selectedForAdd
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedForAdd = if (selected) selectedForAdd - place.id else selectedForAdd + place.id
                                        }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .border(
                                                width = if (selected) 0.dp else 1.5.dp,
                                                color = if (selected) {
                                                    com.visualzyzy.chuxingdazi.ui.theme.Primary
                                                } else {
                                                    MaterialTheme.colorScheme.outline
                                                },
                                                shape = RoundedCornerShape(5.dp),
                                            )
                                            .background(
                                                if (selected) com.visualzyzy.chuxingdazi.ui.theme.Primary else Color.Transparent,
                                                RoundedCornerShape(5.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (selected) {
                                            Text(text = "✓", color = Color.White, fontSize = 13.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = place.name,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight(600),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            // 第十六轮：缺省「暂无地址」，不再回退经纬度
                                            // 遗留 #9（2026-11-18）：regionLabelOf 去重直辖市「北京市-北京市」
                                            text = listOfNotNull(
                                                categoriesById[place.categoryId]?.name,
                                                com.visualzyzy.chuxingdazi.ui.common.regionLabelOf(place.province, place.city),
                                            ).joinToString(" · ").ifBlank { "暂无地址" },
                                            fontSize = 10.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (addCandidates.isEmpty()) {
                                Text(
                                    text = if (allPlaces.isEmpty()) {
                                        "点位库还是空的，先回主地图添加点位"
                                    } else {
                                        "没有匹配的点位（或都已在清单里）"
                                    },
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 20.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .background(
                                    if (selectedForAdd.isEmpty()) MaterialTheme.colorScheme.surfaceVariant
                                    else com.visualzyzy.chuxingdazi.ui.theme.Primary,
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable(enabled = selectedForAdd.isNotEmpty()) {
                                    viewModel.addPlaces(selectedForAdd)
                                    showAddSheet = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (selectedForAdd.isEmpty()) "确认添加" else "确认添加 · ${selectedForAdd.size} 个",
                                color = if (selectedForAdd.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                }
            }
        }
    }
}

@Composable
private fun ListCategoryIcon(iconKey: String?, size: androidx.compose.ui.unit.Dp) {
    if (iconKey.isNullOrBlank()) return
    val context = LocalContext.current
    val id = remember(iconKey) {
        context.resources.getIdentifier("ic_cat_$iconKey", "drawable", context.packageName)
    }
    if (id != 0) {
        Icon(
            painter = painterResource(id),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    }
}

private fun parseListColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: "#64748B")) }.getOrDefault(Color(0xFF64748B))
