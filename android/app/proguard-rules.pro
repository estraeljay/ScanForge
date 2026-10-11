# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class app.scanforge.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.scanforge.**$$serializer { *; }
# ARCore
-keep class com.google.ar.** { *; }
