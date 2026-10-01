// 本地预取的 Maven 仓库。
//
// 为什么需要它：Gradle 下载 kotlin-compiler-embeddable(58 MB)时稳定失败，而同样的
// URL 用 PowerShell 下只要 201 秒。网络是通的，是 Gradle 的 HTTP 栈在这个体积上
// 有问题。更坑的是 Gradle 报的错永远指向**最后**尝试的仓库——Kotlin 插件自带的
// GitHub release，那儿连不上——于是错误信息完全指错了方向，看起来像"仓库不可达"。
//
// 仓库按顺序查找，命中即止，所以本地仓库放最前面。
//
// 注意：路径必须写在每个块内部。pluginManagement 是 settings 里最先被单独求值的
// 块，脚本其他位置的 val 在它眼里不存在。

pluginManagement {
    repositories {
        maven { url = uri(rootDir.resolve("../tools/m2")) }
        maven("https://repo1.maven.org/maven2/")
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(rootDir.resolve("../tools/m2")) }
        maven("https://repo1.maven.org/maven2/")
        google()
    }
}

rootProject.name = "kapi-ledger"

include(":core")
include(":app")
