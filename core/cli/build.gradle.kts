// Desktop developer tool: synthetic captures, offline reconstruction, packaging and a
// transfer server that runs the same protocol code as the Android app.
plugins { application }
dependencies {
    implementation(project(":reconstruction"))
    implementation(project(":project"))
    implementation(project(":transfer"))
}
application {
    mainClass.set("app.scanforge.cli.MainKt")
    applicationName = "scanforge-cli"
}
