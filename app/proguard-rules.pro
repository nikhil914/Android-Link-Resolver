# Link Resolver release shrinking rules.

# Jsoup's optional re2j regex engine is not bundled.
-dontwarn com.google.re2j.**

# OkHttp / MockWebServer optional platforms (only referenced reflectively).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep line numbers for readable crash traces in debug logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# TDLib: classes, fields and constructors are looked up by name from native code.
-keep class org.drinkless.tdlib.** { *; }
