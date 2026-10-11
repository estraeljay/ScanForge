# Building the Android app

> Status: `:app:assembleDebug` succeeds in CI (GitHub Actions, AGP 8.7.3, compileSdk 35); the debug APK is uploaded as
> the `app-debug-apk` artifact of every CI run. The app has **not been run on a device yet**. The first CI build found
> 9 compile errors (name shadowing of the `app.scanforge` package, a nullable smart cast, and an operator-precedence
> bug in the support-plane cut), all fixed in commit a058d73.

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
