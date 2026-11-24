# 2026-11-17 腾讯版重写：百度混淆规则已随百度版作废删除。
# 腾讯三件套混淆规则（vector AAR 自带 consumer rules；导航/定位按官方 quick_integrate 补齐）：

# 导航 SDK（官方 quick_integrate 混淆规则）
-keep class com.tencent.navix.** { *; }
-keep class com.tencent.gaya.** { *; }
-keep class com.tencent.map.** { *; }
-keep class com.tencent.mapsdk.** { *; }
-keep class com.tencent.tencentmap.** { *; }
-keep class com.tencent.bugly.** { *; }
-keep class com.tencent.feedback.** { *; }
-keep class com.tencent.tmsbeacon.** { *; }
-keep class com.tencent.tmsqmsp.** { *; }
-keep class com.tencent.lbssearch.** { *; }
-keep class com.tencent.moduleSDK.** { *; }
-keep class com.tencent.pangu.** { *; }
-keep class com.qq.taf.** { *; }
-keep class com.qq.jce.** { *; }
-keep class c.t.** { *; }

# 腾讯检索（lbssearch，随 vector AAR 打包；同导航官方规则族）
-keep class com.tencent.lbssearch.object.param.** { *; }
-keep class com.tencent.lbssearch.object.result.** { *; }
-keep class com.tencent.lbssearch.httpresponse.** { *; }

# R8 缺类告警（OEM 扩展与内部可选日志组件，运行时不触达；官方 missing_rules.txt 生成）
-dontwarn com.oplus.os.OplusBuild
-dontwarn com.tencent.pangu.plog.LoggerConfig

# 保留 so 引用与反射入口（marker 渲染回调等）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 2026-11-19 定位修复：libnavicore.so JNI_OnLoad 反射查找的类（com.tencent.lbs.lib.jni.**，
# 如 NetworkJNI）只有 native 侧引用、Java 无引用——R8 shrink 会删掉它们，
# JNI_OnLoad 里 FindClass 失败抛 ClassNotFoundException（真机 loadLibrary 实锤；
# 不阻碍定位主链路，但防未来版本变严格，一并 keep）
-keep class com.tencent.lbs.lib.jni.** { *; }

# 2026-11-20 晚补齐（官方定位 SDK androidGeoCreat 文档要求；E5 已证混淆非 #20 根因，
# 此为配置债清理防患）：定位栈 service 类 + 「带 native 方法的类名」通用防线
# （R8 混淆类名会破坏 JNI 静态注册的类名匹配——keepclasseswithmembernames 是定位 SDK
# 文档的标准防线；c.t.**/geolocation.** 已由上面覆盖）
-keep class com.tencent.tencentmap.lbssdk.service.** { *; }
-keep class com.tencent.tencentmap.lbssdk.officialservice.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 2026-11-20 深夜补齐（AGP 8.2.2 构建侧自救轮实锤）：tencent-map-nav-sdk-tts AAR 的
# 语音组件链——com.tencent.ai(tvs 语音)/com.tencent.btts/kael.tools/qrom.component 四个包
# 只被引擎反射触达、Java 侧无直接引用，R8 shrink 删掉后 non-motor（步行/骑行）引擎
# startNavigation 主线程 tick 解引用 null 必现 SIGSEGV（0x2390bc0 三帧链，与缺 tts AAR
# 同签名）。8.5.2 时代步行流程死在 inflate 层从未跑到 tick 故未暴露；AGP 8.2.2 修复
# R$id 后 tick 首次真跑、即崩即实锤。demo（minify=false）不受影响即此因。
-keep class com.tencent.ai.** { *; }
-keep class com.tencent.btts.** { *; }
-keep class kael.tools.** { *; }
-keep class qrom.component.** { *; }
