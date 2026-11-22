pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    // PREFER_SETTINGS keeps the repositories declared here authoritative even when an
    // environment-level init script injects project repositories.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "RootTunClash"
include(":app")

// 验证用探针应用（单独 APK，不参与 app 的构建与发布）：
// 以「普通应用」视角检查 VPN/接口/路由/本机端口是否暴露。
include(":probe")
