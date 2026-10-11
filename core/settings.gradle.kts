// Pure Kotlin/JVM core of ScanForge 3D. Contains everything that does not
// depend on the Android framework so it can be built and unit-tested on any
// JDK, and is consumed by the Android app through an included build.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "scanforge-core"

include(":geometry", ":markers", ":reconstruction", ":export", ":project", ":transfer", ":workflow", ":cli")
