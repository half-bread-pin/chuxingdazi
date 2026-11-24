package com.visualzyzy.chuxingdazi.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.visualzyzy.chuxingdazi.data.tencent.MapController
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMapOptions
import com.visualzyzy.chuxingdazi.data.prefs.Prefs
import kotlinx.coroutines.flow.first

/**
 * 组合期创建腾讯 MapView（记得住，每个 MapView 新建时注入当前 DataStore 里的 key）。
 * 保存新 key 后由 PhoenixActivity 重启进程，重建即生效。
 */
@Composable
fun rememberTencentMapView(context: android.content.Context): MapView? = remember {
    runCatching {
        val key = kotlinx.coroutines.runBlocking { Prefs.sdkKey(context).first() }
        val options = TencentMapOptions()
        if (!key.isNullOrBlank()) options.setMapKey(key)
        MapView(context, options)
    }.getOrNull()
}

/**
 * Compose 中承载腾讯 MapView（设计文档 §8.3）。
 * 生命周期转发必须做全：onResume / onPause / onDestroy，否则泄漏或崩溃。
 * 2026-11-17 腾讯版：MapView 构造带 TencentMapOptions().setMapKey(...) 运行时注入
 * 用户在 S02 填写的 key（地图 SDK 的 key 运行时入口）。腾讯 SDK 自带全部 ABI（含 x86_64），
 * 模拟器可用；MapView 创建失败时仍兜底占位提示。
 */
@Composable
fun MapHost(
    controller: MapController,
    modifier: Modifier = Modifier,
    onMapCreated: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val mapView = rememberTencentMapView(context)
    if (mapView == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "地图视图创建失败\n请检查地图密钥配置后重启应用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle

    AndroidView(
        modifier = modifier,
        factory = {
            // P45（2026-11-03）问题2：关掉 SDK 自带的 +/- 缩放控件（在 MapController.attach 里统一做，
            // 腾讯走 uiSettings.setZoomControlsEnabled(false)）；只保留 App 自己的那一层
            controller.attach(mapView)
            onMapCreated?.invoke()
            mapView
        },
    )

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> runCatching { mapView.onStart() }
                Lifecycle.Event.ON_RESUME -> runCatching { mapView.onResume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { mapView.onPause() }
                Lifecycle.Event.ON_STOP -> runCatching { mapView.onStop() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.detach()
            runCatching { mapView.onDestroy() }
        }
    }
}
