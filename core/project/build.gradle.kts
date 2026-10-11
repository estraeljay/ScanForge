// Versioned project schema, on-disk project store, measurement reports and package archives.
plugins { kotlin("plugin.serialization") }
dependencies {
    api(project(":geometry"))
    api(project(":export"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
