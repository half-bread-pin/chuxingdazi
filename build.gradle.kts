// 我的出行搭子 · 根构建脚本
// 版本钉死（设计文档 §8.1）：AGP 8.2.2 + Gradle 8.x + Kotlin 2.0，compileSdk/targetSdk 34
// 🔴 AGP 上限 8.2.2（2026-11-20 深夜定案）：8.3~8.5 生成 R 类丢 nav AAR 的 37 个
// 「R.txt 已声明/values 无实体」id → 步行/骑行引导视图 inflate 崩；等腾讯修 AAR 后才可升。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
