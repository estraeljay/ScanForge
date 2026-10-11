// Partial compile check for environments without the Android SDK / Google Maven.
// Type-checks the Android sources that depend only on the Android framework (no ARCore, no AndroidX) against the real
// Android 15 framework classes published by Robolectric on Maven Central. It does NOT replace an Android Studio build:
// ARCore- and Compose-dependent files are not checked here. OpenCV's Java API is taken from the official Android AAR
// (classes.jar only; native libraries are not needed to type-check).
plugins { kotlin("jvm") version "2.2.21" }

val src = "../../android/app/src/main/java/app/scanforge/android"
val checked = listOf(
    "ScanForgeApp.kt", "SystemServices.kt", "ar/GlUtil.kt", "ar/OverlayRenderer.kt", "capture/ArImages.kt", "settings/UserSettings.kt",
    "transfer/AndroidTls.kt", "transfer/NetworkInfo.kt", "transfer/TransferManager.kt", "transfer/TransferService.kt",
    "viewer/MeshRenderer.kt", "viewer/MeshViewerView.kt", "viewer/OrbitCamera.kt",
    "capture/CameraFrames.kt", "capture/MarkerCaptureEngine.kt", "markers/OpenCvMarkerDetector.kt",
)
val opencvAar by configurations.creating { isTransitive = false }
val opencvClasses by tasks.registering(Copy::class) {
    from({ opencvAar.map { zipTree(it) } }) { include("classes.jar") }
    into(layout.buildDirectory.dir("opencv"))
}
val syncSources by tasks.registering(Sync::class) {
    from(src) { include(checked) }
    into(layout.buildDirectory.dir("checked-src"))
}
sourceSets.main { kotlin.setSrcDirs(listOf("stub", layout.buildDirectory.dir("checked-src"))) }
tasks.named("compileKotlin") { dependsOn(syncSources, opencvClasses) }

dependencies {
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")
    opencvAar("org.opencv:opencv:4.14.0@aar")
    compileOnly(files(layout.buildDirectory.file("opencv/classes.jar")))
    implementation("app.scanforge.core:workflow")
    implementation("app.scanforge.core:transfer")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
