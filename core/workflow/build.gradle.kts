// Glue between capture/reconstruction and project storage; shared by the Android app and the CLI.
dependencies {
    api(project(":reconstruction"))
    api(project(":project"))
}
