plugins {
    id("com.android.application")
    // 不 apply org.jetbrains.kotlin.android —— AGP 9 自带 Kotlin 支持，
    // 再显式 apply 一次会直接失败。core 仍然是纯 JVM 模块，所以 kotlin.jvm
    // 依然需要在根里声明着。
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "kapi.ledger"
    compileSdk = 36

    defaultConfig {
        applicationId = "kapi.ledger"
        minSdk = 26          // Android 8.0：自适应图标、通知渠道、前台服务都齐了
        targetSdk = 36
        // versionCode 按天递增，而不是写死 1。
        //
        // 写死的后果是**每个构建出来的包在系统看来都是同一个版本**：
        // 2026-09-30 用户看着手机上的 App 说"这不是最新包"，而我们从
        // `dumpsys package` 只能读到 versionName=0.1.0 / versionCode=1 / lastUpdateTime，
        // 三样都证明不了装的是哪一版——lastUpdateTime 只说明"什么时候装的"，
        // 不说明"装的是哪一版"。最后只能去 dex 里搜特征串，而那一步人做不了。
        //
        // Unix 天数（约 20xxx）远大于任何手动递增的版本号，所以
        // `adb install -r` 永远不会因为"版本降级"被拒，也不会出现同一天两次构建互相覆盖。
        versionCode = (System.currentTimeMillis() / 86_400_000L).toInt()
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig 从 AGP 8 起默认**不生成**，必须显式打开。
        // 设置页要显示版本号（判断手机上是不是最新包），少了这一行就是
        // `Unresolved reference 'BuildConfig'`，编译直接失败——
        // 而 g.ps1 的产物校验会拦住旧包，所以失败是"响亮"的，不会静默交出旧包。
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }
}

ksp {
    // Room 的 schema 导出目录。**必须在还没有真实用户数据之前就开着**——
    // 上线之后再想补迁移测试就来不及了，那时候每一版 schema 都已经在别人手机上了。
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // runner 必须显式加。ext:junit 只带 core/monitor/storage，不带 runner，
    // 少了它 instrumentation 在设备上 ClassNotFound，而 Gradle 报「成功、0 用例」。
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.work.testing)
}
