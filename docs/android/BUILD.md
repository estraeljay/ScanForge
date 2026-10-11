# Building the Android app

> Status: the Android project has **not been compiled** in the environment it was written in (Google Maven and the
> Android SDK download host were blocked). Expect to fix a small number of compile errors in the ARCore/Compose files
> on first build. The framework-only files type-check (`tools/android-framework-check`).

Requirements: Android Studio (recent stable), Android SDK Platform 35, JDK 17, a Depth API ARCore device for scanning.

```
cd android
gradle wrapper --gradle-version 8.14.3      # or open the folder in Android Studio, which creates the wrapper
./gradlew :app:assembleDebug                # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug
```
The build includes `../core` via `includeBuild`, so core changes are picked up automatically.
Version pins live in `android/gradle/libs.versions.toml`; run Android Studio's upgrade assistant to move to the latest
AGP/Compose/ARCore. Product name: `scanforge.appName` in `android/gradle.properties`.

Release: configure signing in `app/build.gradle.kts`, `./gradlew :app:bundleRelease`; R8 rules in `proguard-rules.pro`
keep kotlinx.serialization serializers and ARCore classes.
