# Jsoup uses reflection-free parsing; keep its public API.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
