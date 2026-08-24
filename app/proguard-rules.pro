# ProGuard and R8 Obfuscation & Hardening Rules

# Keep JavascriptInterfaces for WebViews
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep native methods and type annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Hardening / Obfuscation Optimization settings
-dontusemixedcaseclassnames
-allowaccessmodification
-repackageclasses ''

# Keep MediaNotificationService and components
-keep class com.example.service.** { *; }
-keep class com.example.ui.components.** { *; }

