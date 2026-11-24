pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        // 腾讯位置服务官方仓库（2026-11-17 腾讯版重写；SDK 实测 mavenCentral 亦有全量版本）
        maven("https://mirrors.tencent.com/repository/maven/tencent_public/")
        maven("https://oss.sonatype.org/content/groups/public/")
    }
}

rootProject.name = "chuxingdazi"
include(":app")
