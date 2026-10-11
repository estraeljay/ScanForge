pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ScanForge3D"

// Platform-independent core (reconstruction, measurements, export, project store, transfer protocol).
// Dependencies on "app.scanforge.core:<module>" are substituted with these projects.
includeBuild("../core")

include(":app")
