# BUILD.md — 构建说明与产物

## 1. 环境要求

| 组件 | 版本 / 路径（本机实测） |
|---|---|
| JDK | 17.0.11（`C:\Program Files\Java\jdk-17`） |
| Android SDK | `C:\newclash\android-sdk`（Platform 35、Build-Tools 35.0.0、Platform-Tools） |
| Gradle | Wrapper 8.14.3（仓库自带，无需本机安装） |
| AGP | 8.9.2 |
| Kotlin | 2.2.21（含 `org.jetbrains.kotlin.plugin.compose`） |
| compileSdk / minSdk / targetSdk | 35 / 26 / 35 |

依赖仓库：Google Maven、Maven Central、JitPack（libsu）、阿里云镜像（可选加速）。

## 2. 构建命令（仓库根目录）

```powershell
$env:JAVA_HOME   = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\newclash\android-sdk'
$env:ANDROID_SDK_ROOT = 'C:\newclash\android-sdk'

# 日常增量构建
.\gradlew.bat :app:assembleDebug

# 从零构建（清理中间产物）
.\gradlew.bat clean :app:assembleDebug

# 验证用探针应用（普通应用视角，单独 APK，不影响主 APK）
.\gradlew.bat :probe:assembleDebug
```

构建成功输出（本机实测）：

```
BUILD SUCCESSFUL
```

## 3. 产物

| 项目 | 值 |
|---|---|
| 路径 | `app\build\outputs\apk\debug\app-debug.apk` |
| 大小 | 67,525,530 字节（64.40 MiB） |
| SHA-256 | `D5901B4A3864F6893FF331AD550AB0D6909286EF2FA2E5941799D11DCB7F6A4E` |
| 版本 | versionCode 3 / versionName 1.0.1 |
| 签名 | debug keystore（可直接安装验收） |
| ABI | `arm64-v8a`、`x86_64`（各自内置一份 mihomo v1.19.34） |
| 内置资源 | `assets/geodata/geoip.metadb`（8.4 MB）、`geosite.dat`（4.3 MB） |

APK 内的内核与地理库：

```
lib/arm64-v8a/libmihomo.so   64,007,896 bytes
lib/x86_64/libmihomo.so      68,731,248 bytes
assets/geodata/geoip.metadb   8,434,612 bytes
assets/geodata/geosite.dat    4,349,121 bytes
```

> 没有做 ABI split，单个 APK 同时包含两种架构；MuMu（x86_64）与真机（arm64）共用同一个包。

验证探针（可选，单独安装）：

| 项目 | 值 |
|---|---|
| 路径 | `probe\build\outputs\apk\debug\probe-debug.apk` |
| 大小 | 20,697 字节 |
| SHA-256 | `EC12BB4553BFB467DECD941B97BBF3B4ED0D14FE99A3CDD6695B7C76BBED2D6D` |
| 包名 | `com.roottun.probe`（普通应用权限，不含 root） |

## 4. 关键构建配置

`app/build.gradle.kts` 中与本项目强相关的部分：

```kotlin
defaultConfig {
    applicationId = "com.roottun.clash"
    minSdk = 26
    targetSdk = 35
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
}
packaging {
    // mihomo 以可执行 ELF 形式随包，需保持“安装时解压到 nativeLibraryDir”
    jniLibs { useLegacyPackaging = true }
}
```

清单中 `android:extractNativeLibs="true"` 与 `useLegacyPackaging = true` 配合，
保证 `applicationInfo.nativeLibraryDir/libmihomo.so` 是一个可读取、可复制的真实文件，
应用首次启动时再复制到 `files/clash/mihomo/mihomo` 并 `chmod 700` 后通过 `su` 执行。

## 5. 常见问题

| 现象 | 处理 |
|---|---|
| `Could not find com.github.topjohnwu.libsu:core` | 依赖必须来自 JitPack，`settings.gradle.kts` 已声明 `maven("https://jitpack.io")` |
| `Android Gradle plugin requires Java 17` | 设置 `JAVA_HOME` 指向 JDK 17 |
| 依赖下载慢 | `settings.gradle.kts` 已内置阿里云镜像，可继续前置 |
| 想减小 APK | 只保留目标 ABI：修改 `ndk { abiFilters }` 与 `jniLibs` 目录 |
| Release 签名 | `release` 复用 debug 签名便于验收；正式分发请替换 `signingConfigs` |

## 6. 构建日志核对点

- `:app:compileDebugKotlin` 通过（无 `e:` 编译错误）；
- `:app:packageDebug` 通过；
- `stripDebugDebugSymbols` 会提示 `Unable to strip ... libmihomo.so`，这是**预期行为**
  （mihomo 是预编译 Go 二进制，不需要 strip，打包时按原样保留）；
- `processDebugMainManifest` 可能提示 `android:extractNativeLibs should not be specified ...`，
  属于 AGP 的建议性警告，不影响本项目的可执行内核策略。
