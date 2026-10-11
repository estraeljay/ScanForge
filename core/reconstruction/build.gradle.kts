// Capture dataset format, TSDF depth fusion, surface extraction and coverage.
plugins { kotlin("plugin.serialization") }
dependencies {
    api(project(":geometry"))
    api(project(":markers"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
