package com.visualzyzy.chuxingdazi.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.visualzyzy.chuxingdazi.data.tencent.SuggestionItem
import com.visualzyzy.chuxingdazi.data.tencent.SuggestionSearcher
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import com.visualzyzy.chuxingdazi.ui.theme.Primary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class PointSearchViewModel @Inject constructor(
    private val routeTransfer: RouteTransfer,
    placeRepository: com.visualzyzy.chuxingdazi.data.repo.PlaceRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    /** 第十六轮：点位库就在本地——空搜索时展示最近添加的库内点位，点选直接回填。 */
    val libraryPlaces = placeRepository.observeAll()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    val keyword = MutableStateFlow("")
    val results = MutableStateFlow<List<SuggestionItem>?>(null)
    val searching = MutableStateFlow(false)

    private val searcher = SuggestionSearcher(appContext)
    private var city: String = "全国"

    init {
        viewModelScope.launch {
            keyword.debounce(300).collect { kw ->
                if (kw.isBlank()) {
                    results.value = emptyList()
                    return@collect
                }
                searching.value = true
                val r = kotlinx.coroutines.suspendCancellableCoroutine<List<SuggestionItem>?> { cont ->
                    searcher.search(kw, city) { list -> cont.resumeIfActive(list) }
                }
                results.value = r
                searching.value = false
            }
        }
    }

    fun loadCity(context: android.content.Context) {
        viewModelScope.launch {
            Prefs.lastCity(context).collect { c -> if (!c.isNullOrBlank()) city = c }
        }
    }

    /** 选中 → 写入单例回传给 S10。 */
    fun commit(target: String, item: SuggestionItem) {
        val lat = item.lat ?: return
        val lng = item.lng ?: return
        val payload = StopPayload(name = item.name, lat = lat, lng = lng, placeId = null)
        if (target == "origin") routeTransfer.pendingOrigin = payload else routeTransfer.pendingDestination = payload
    }

    /** 第十六轮：从点位库直接选（带 placeId，S10 显示库内名称语义）。 */
    fun commitPlace(target: String, place: com.visualzyzy.chuxingdazi.data.db.entity.PlaceEntity) {
        val payload = StopPayload(name = place.name, lat = place.lat, lng = place.lng, placeId = place.id)
        if (target == "origin") routeTransfer.pendingOrigin = payload else routeTransfer.pendingDestination = payload
    }
}

private fun kotlinx.coroutines.CancellableContinuation<List<SuggestionItem>?>.resumeIfActive(value: List<SuggestionItem>?) {
    if (isActive) resume(value) { }
}

/**
 * S21 地图搜点（§6 S10 起点「地图搜点」/ 终点「更换→地图搜点」）：
 * 联想任意地点，选中即回传（不进点位库）。
 */
@Composable
fun PointSearchScreen(
    target: String, // "origin" | "destination"
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: PointSearchViewModel = hiltViewModel()
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val libraryPlaces by viewModel.libraryPlaces.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadCity(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier
                    .size(26.dp)
                    .clickable(onClick = onClose),
            )
            Text(
                text = if (target == "origin") "起点 · 地图搜点" else "终点 · 地图搜点",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.width(26.dp))
        }

        OutlinedTextField(
            value = keyword,
            onValueChange = { viewModel.keyword.value = it },
            singleLine = true,
            placeholder = { Text("搜索任意地点（不必在点位库）", fontSize = 13.sp) },
            shape = RoundedCornerShape(13.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))

        when {
            searching -> Text(
                text = "搜索中…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            results != null && results!!.isEmpty() && keyword.isNotBlank() -> Text(
                text = "没有匹配的地点",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 第十六轮：空搜索不再一整页空白——上方先列点位库（最近添加），点选直接回填
        val showLibrary = keyword.isBlank() && results?.isEmpty() != false
        if (showLibrary && libraryPlaces.isNotEmpty()) {
            Text(
                text = "从已有点位里选",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (showLibrary) {
                items(libraryPlaces, key = { "lib_${it.id}" }) { place ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable {
                                viewModel.commitPlace(target, place)
                                onClose()
                            },
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = place.name,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // 遗留 #9（2026-11-18）：regionLabelOf 去重直辖市「北京市-北京市」
                                text = listOfNotNull(
                                    place.address,
                                    com.visualzyzy.chuxingdazi.ui.common.regionLabelOf(place.province, place.city),
                                ).joinToString(" · ").ifBlank { "点位库" },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // 第二十一轮（2026-11-14）：联想行 key 必须带 index——百度地区引导行 uid 可为
            // 空串/同名同坐标的行也可能出现，仅按 uid/内容做 key 会撞重复 key "" 崩溃
            // （复现：S10 终点 → 地图搜点 → 输入「台湾」，测量期 IllegalArgumentException）。
            itemsIndexed(
                results ?: emptyList(),
                key = { index, item -> "sug_${index}_${item.uid ?: "nouid"}" },
            ) { _, item ->
                val lat = item.lat
                val lng = item.lng
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable(enabled = lat != null && lng != null) {
                            if (lat != null && lng != null) {
                                viewModel.commit(target, item)
                                onClose()
                            }
                        },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = item.name,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.address?.takeIf { it.isNotBlank() }?.let { addr ->
                            Text(
                                text = "$addr${item.district?.let { " · $it" } ?: ""}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
