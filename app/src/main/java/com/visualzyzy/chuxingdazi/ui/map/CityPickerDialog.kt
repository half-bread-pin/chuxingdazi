package com.visualzyzy.chuxingdazi.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visualzyzy.chuxingdazi.data.region.DistrictRepository

/**
 * P10 城市选择（2026-11-02）：省级 → 市级两级选择框。
 * 第一步列全部省级行政区；点省进该省城市列表（顶部可返回）；点市回调并关闭。
 *
 * 2026-11-17 腾讯版：数据源从内置静态表（ChinaRegions，已废除）改为 district 表
 * （腾讯 getDistrictChildren 接口获取、2 个月更新一次，DistrictRepository）。
 */
@Composable
fun CityPickerDialog(
    currentCity: String?,
    repository: DistrictRepository,
    onSelect: (province: String, city: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedProvince by remember { mutableStateOf<String?>(null) }
    val provinces by repository.observeProvinces().collectAsStateWithLifecycle(initialValue = emptyList())
    var cities by remember { mutableStateOf<List<String>>(emptyList()) }
    // 选中省后查该省辖市（Room suspend 查询，结果随选择刷新）
    LaunchedEffect(selectedProvince, provinces) {
        val province = selectedProvince
        cities = if (province == null) {
            emptyList()
        } else {
            provinces.firstOrNull { it.name == province }?.let { entity ->
                repository.citiesOf(entity.adcode).map { it.name }
            } ?: emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedProvince != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回省份列表",
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .clickable { selectedProvince = null },
                    )
                }
                Text(
                    text = when {
                        selectedProvince == null -> "选择城市"
                        else -> selectedProvince!!
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column {
                if (currentCity != null && selectedProvince == null) {
                    Text(
                        text = "当前：$currentCity",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (provinces.isEmpty()) {
                    // 库空（首装未更新 / 更新失败）：引导文案，不出空列表
                    Text(
                        text = "暂无行政区划数据，可在「设置 → 地图与定位」中更新",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(360.dp)) {
                        if (selectedProvince == null) {
                            items(provinces) { province ->
                                RegionRow(
                                    text = province.name,
                                    highlight = province.name == currentCity,
                                    onClick = { selectedProvince = province.name },
                                )
                            }
                        } else {
                            items(cities) { city ->
                                RegionRow(
                                    text = city,
                                    highlight = city == currentCity,
                                    onClick = { onSelect(selectedProvince!!, city) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Text(
                text = "取消",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(8.dp),
            )
        },
        shape = RoundedCornerShape(18.dp),
    )
}

@Composable
private fun RegionRow(text: String, highlight: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 11.dp),
    ) {
        Text(
            text = text,
            fontSize = 14.5.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
