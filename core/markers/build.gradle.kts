// Printed marker board (ArUco DICT_4X4_250 layout, PDF), and camera pose / intrinsics from marker corners.
// Pure Kotlin: marker *detection* is done by OpenCV at the platform edge (Android, CLI) and passed in as corners.
plugins { kotlin("plugin.serialization") }
dependencies {
    api(project(":geometry"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Tests run the real OpenCV ArUco detector on synthetic renders of our boards.
    testImplementation("org.openpnp:opencv:4.9.0-0")
}
