# Keep line numbers for crash diagnosis.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.tetraploid.joyforold.**$$serializer { *; }
-keepclassmembers class com.tetraploid.joyforold.** {
    *** Companion;
}
-keepclasseswithmembers class com.tetraploid.joyforold.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ONNX Runtime (JNI + reflection)
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# sherpa-onnx JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Koin
-keep class org.koin.** { *; }
-keep class kotlin.Metadata { *; }

# Accessibility / system services referenced from manifest
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.inputmethodservice.InputMethodService { *; }
-keep class * extends android.app.Service { *; }
