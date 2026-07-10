import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * debug 版后端地址：从 local.properties 的 `huicui.devHost` 读（该文件不入库）。
 * 未配置时退回 10.0.2.2 —— Android 模拟器访问宿主机的固定地址。
 * 真机联调请在 local.properties 写 `huicui.devHost=<你的局域网IP>`。
 */
val devHost: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("huicui.devHost") ?: "10.0.2.2"

android {
    namespace = "com.youzheng.huicui.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.youzheng.huicui.app"
        minSdk = 26          // PRD OQ-APP-1：读系统录音目录 + java.time 需要 26+
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-M-A1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 契约 servers 的 basePath 是 /v1
            buildConfigField("String", "API_BASE_URL", "\"http://$devHost:9091/v1/\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"https://api.example.com/v1/\"")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":api-client"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.security.crypto)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
