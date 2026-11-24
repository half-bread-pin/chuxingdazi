import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 签名配置（设计文档 §11.3）：keystore.properties 只在本地，缺失时跳过 release 签名
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

// 2026-11-17 腾讯版重写：三个 SDK（地图 vector / 定位 / 导航 navix）全部走 Gradle maven
// 集成（官方文档 configuration / androidGeoCreat / quick_integrate），百度 AAR + jniLibs 已删除。
// Key 不走 manifest 写死：地图 TencentMapOptions.setMapKey / 定位 TencentLocationManagerOptions.setKey /
// 导航 NavigatorConfig.Builder.setDeveloperKey / 检索 TencentSearch(context, key) 全部运行时注入
// 用户在 S02 填的 key（设计文档 §9 腾讯集成要点）。

android {
    namespace = "com.visualzyzy.chuxingdazi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.visualzyzy.chuxingdazi"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        multiDexEnabled = true
        // 腾讯 SDK 默认 universal（地图 5 ABI 含 x86_64 可模拟器、导航 3 ABI：arm64/v7a/armeabi）。
        // 若需按 ABI 拆分减体积，参照设计文档 §11.5 加 ndk.abiFilters / splits.abi。
        // 🔴 决策 #114（2026-11-18）：manifest 绝不携带 key。lbssearch 请求 key 全部走运行时
        // 注入——TencentSearch 必须用 3 参构造器（context, apiKey, secretKey）把 key 落 apiKey 位
        // （2 参构造器 key 进 secretKey 位、mApiKey=null，请求 key 会回退 readMetaData）。
        // 决策 #109 的「构建期 manifestPlaceholders 注入」方案已废除，meta-data TencentMapSDK
        // 已从 AndroidManifest.xml 删除；tencent_map_sdk_key.txt 仅作本地参考不再参与构建。
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file("keystore/${keystoreProps["storeFile"]}")
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // 2026-11-20 深夜 #20 定案修订：R$id（AGP 8.2.2）与 native tick 两层已分治——
            // T1 二分实锤 minify=true/false 同点崩（R8 非死因）；真根因 = non-motor 引擎
            // tick 依赖内置 TTS 组件链、宿主 setTTSPlayer(外部桥) 破坏之 → 仅驾车挂系统
            // TTS 桥，步行/骑行走引擎内置组件（NavGuideActivity 门控）。tts AAR 四包
            // keep 补齐（R8 下组件链防剥）。维持生产配置。
            isMinifyEnabled = true
            // 腾讯导航 UI 通过 XML/反射取自有资源；官方混淆规则覆盖 com.tencent.** 全保留后
            // 资源压缩风险面未知，与百度版同口径先关（体积略增可接受，真机验收后再评估）
            isShrinkResources = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            // 兜底：方案 A 下全 APK 仅 nav AAR 一份 libtxmapvis.so（无重复），
            // 保留 pickFirst 防未来依赖变动
            pickFirsts += "**/libtxmapvis.so"
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric 需要资源
            isIncludeAndroidResources = true
        }
    }
}

// Room schema 导出：发版后每次表结构变更必须配 Migration（设计文档 §11.10）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ---- 腾讯位置服务（官方导航包形态，方案 A）----
    // 🔴 2026-11-21 方案 A 落地（#20 收口）：全 App 只留导航包自带地图库（官方形态——
    // 官方文档「新版导航SDK已内含定位与地图库，外部不可再重复引入」）。删 vector 地图
    // AAR，nav-core 直接用官方原版（未去重）；主地图 S03/S06/S26/S30 与导航页共用
    // navix 内置 mapsdk 类（VectorNavi 内核 libnavicore）。可行性与真机全链路验证见
    // docs/导航SDK自建地图-MapView共存备选方案.md §九：vector 公开门面 132 类在 nav AAR
    // 内零缺失、lbssearch 160 类原样捆绑、foundation/tts 与 nav 全路径零重叠（零重复类
    // 冲突）；步行/骑行走导航包自带 MapView → world_init 建图 → 引擎「地图登记表」填充
    // → 0x58 崩溃根因链消除（真机 T2 彩线渲染实证）。体积净减约 38MiB。
    // strip_nav.py 去重工具链退役（tools_seed 留档）。
    // nav 版本 = 官方 changelog 最高 7.10.0（mavenCentral 8.0.0 非官方发布弃用；
    // 另有 7.11.0 未核对官方 changelog，默认不跟）。
    // 🔴 2026-11-22：nav-core 由本地 AAR 切回官方 maven（对齐官方文档集成方式）。
    // 同一发布三方实锤：本机 Gradle 缓存 / mavenCentral 下载 / 原 libs 留档 AAR
    // SHA-256 全等（23f61bc2…55bd），切换无二进制差异。app/libs/ 三份 AAR 仅为
    // 排查留档（已 gitignore 不入仓库）；升级 nav 版本 = 改下行版本号。
    implementation("com.tencent.map:tencent-map-nav-sdk-core:7.10.0")
    // 地图/导航公共基础库（POM 无传递依赖，必须显式引入；内含 LatLng/LatLngBounds/JsonModel）
    implementation("com.tencent.openmap:foundation:0.9.1.6875646")
    // E8 排查 #20（2026-11-20 晚，官方 demo 终极对照实锤）：官方内置 TTS AAR——
    // 官方文档标注「可选」，但 navix 7.10.0 的 non-motor（步行/骑行）引擎实际依赖
    // 其组件链：缺失时 startNavigation 后引擎主线程 tick 解引用 null → 必现 native
    // SIGSEGV（双机复现）；引入后引擎不再崩（真机实锤）。驾车引擎不依赖故此前正常。
    implementation("com.tencent.map:tencent-map-nav-sdk-tts:7.10.0")
    // 定位：App 定位 API（TencentLocationManager 等）由 nav-core 自带定位栈提供
    //（官方快速集成页无定位 SDK 依赖）。2026-11-23 对照试验（移除→真机全链路回归：
    // 主地图/蓝点/单次取位/算路/S16 驾车导航全过、crash buffer 零）：旧 jniLibs 的
    // libtencentloc.so 在纯 nav 形态下零引用（Java 层无 "tencentloc" 字面量、
    // libnavicore/libtxmapvis 无 dlopen 或链接依赖、其 JNI 宿主类 walkBikeDr.dr.
    // TencentDrJni 不在 nav AAR——它是 #113 移除的 openplatform 独立定位 SDK 7.6.1.12
    // 的融合定位引擎），已移除不再打包；定位原生支撑由 libnavicore 承担。
    // 留档 tmp_verify/libtencentloc_backup/（gitignore 外的临时区）。
    // navix 引导 UI 的 androidx 依赖（NavigatorLayerViewDrive 继承 ConstraintLayout，
    // ETA 列表用 RecyclerView；AAR 不带、缺失时运行时 inflate 失败）
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.appcompat:appcompat:1.6.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose（BOM 统一版本）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room + DataStore
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    // JSON（字段模板 field_schema / field_values 编解码，§5.4）
    implementation(libs.kotlinx.serialization.json)

    // java.time 脱糖（minSdk 24 < 26，行程日期用 LocalDate）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    debugImplementation(libs.androidx.compose.ui.tooling)

    // 单元测试（Robolectric 提供 Android 环境跑 Room）
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
}
