# ---- ADB-over-Wi-Fi installer (libadb-android, Conscrypt, spake2, sun-security) ----
# libadb loads Conscrypt reflectively (Class.forName("org.conscrypt.OpenSSLProvider"),
# Conscrypt.exportKeyingMaterial) and X509CertInfo looks attributes up by name.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.** { *; }
-keep class android.sun.** { *; }
-dontwarn org.conscrypt.**
-dontwarn android.sun.**

# JNI: native method owners must keep their names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep stack traces readable in crash reports
-keepattributes SourceFile,LineNumberTable
