# RootTun Clash keeps everything in Kotlin; no reflection-hostile code is used.
# libsu ships an optional initializer registry through reflection on the Shell class.
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# MaterialKolor color engine: models are plain Kotlin data holders.
-keep class com.materialkolor.** { *; }
-dontwarn com.materialkolor.**
