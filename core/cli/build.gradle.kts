// Desktop developer tool: synthetic captures, offline reconstruction, packaging and a
// transfer server that runs the same protocol code as the Android app.
plugins { application }
dependencies {
    implementation(project(":workflow"))
    implementation(project(":transfer"))
}
application {
    mainClass.set("app.scanforge.cli.MainKt")
    applicationName = "scanforge-cli"
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}
