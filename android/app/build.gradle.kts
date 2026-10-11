plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appName: String = providers.gradleProperty("scanforge.appName").getOrElse("ScanForge 3D")

android {
    namespace = "app.scanforge.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.scanforge.android"
        // Android 8.0: java.time, and the lowest level considered for ARCore Depth API devices in practice.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "app_name", appName)
        buildConfigField("String", "APP_NAME", "\"$appName\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // OpenCV ships native code for four ABIs (~150 MB); phones need only the ARM ones.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("app.scanforge.core:workflow")
    implementation("app.scanforge.core:transfer")
    implementation(libs.arcore)
    // Marker-board scanning without ARCore: CameraX for frames, OpenCV (Apache-2.0) for ArUco detection only.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.opencv)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
