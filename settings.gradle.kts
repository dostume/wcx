pluginManagement {
    repositories {
        maven { url = uri("file:///root/maven-mirror") }
                maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("file:///root/maven-mirror") }
        // JitPack 必须排在通用镜像之前, 且通用镜像要显式排除这些组:
        // 部分镜像会残留这些组的元数据却没有构件文件, Gradle 一旦在镜像处
        // 命中模块描述符就不会再尝试其他仓库, 导致 CI 端解析失败
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.Ujhhgtg")
                includeGroup("com.github.Ujhhgtg.rhino")
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") {
            content {
                excludeGroup("com.github.Ujhhgtg")
                excludeGroup("com.github.Ujhhgtg.rhino")
                excludeGroup("com.github.topjohnwu.libsu")
            }
        }
        maven("https://api.xposed.info/") {
            content {
                includeGroup("de.robv.android.xposed")
            }
        }
        mavenCentral()
    }

    versionCatalogs {
        create("libs")
    }
}

rootProject.name = "wekit"

include(
    ":app",
    ":libs:common:annotation-scanner",
    ":libs:common:stubs",
    ":libs:common:bsh",
    ":libs:common:reflekt"
)
