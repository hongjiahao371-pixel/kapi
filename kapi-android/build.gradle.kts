// 所有插件的**版本只在这里声明一次**，子模块一律不带版本号。
//
// 为什么不用 version catalog 的 alias 来 apply 插件：`libs.plugins.kotlin.jvm`
// 和 `libs.plugins.kotlin.android` 是同一个 Kotlin Gradle Plugin 的两个 ID，
// alias 自带版本，于是子模块再声明一次版本时 Gradle 会报
// "already on the classpath with an unknown version"。core 用 JVM 插件、
// app 用 Android 插件，两者共存是必然的，所以只能用「根声明带版本 +
// 子模块 `id(...)` 不带版本」这个写法。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    // Kotlin 2.0 起 Compose 编译器从 Kotlin 仓库里拆出来了，必须单独声明，
    // 版本必须和 Kotlin 严格一致。
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

allprojects {
    group = "kapi"
    version = "0.1.0"
}
