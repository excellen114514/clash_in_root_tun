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

# 分别构建 x86_64 与 arm64-v8a
.\gradlew.bat :app:assembleX86Debug :app:assembleArm64Debug

# 从零构建（清理中间产物）
.\gradlew.bat clean :app:assembleX86Debug :app:assembleArm64Debug

# JVM 单测（两个 flavor 代码相同，跑一个即可）
.\gradlew.bat :app:testX86DebugUnitTest

# 验证用探针应用（普通应用视角，单独 APK，不影响主 APK）
.\gradlew.bat :probe:assembleDebug
```

构建成功输出（本机实测）：

```
BUILD SUCCESSFUL
```

## 3. 产物

已按 ABI 拆分，每个 APK 只含一个 mihomo 内核：

| 项目 | x86_64 | arm64-v8a |
|---|---|---|
| 路径 | `app\build\outputs\apk\x86\debug\app-x86-debug.apk` | `app\build\outputs\apk\arm64\debug\app-arm64-debug.apk` |
| 大小 | 42,718,196 字节（40.74 MiB） | 40,755,882 字节（38.87 MiB） |
| SHA-256 | `FA9E78A11C186C504C645ED899EF4F6A8747330D48267D4F16180605444FF6B0` | `9FF097F2323C7AD761C73C7424979FBA8C8DA3A2049602087B21989DE53C613D` |
| 版本 | versionCode 5 / versionName 1.0.2-beta | versionCode 5 / versionName 1.0.2-beta |
| 签名 | debug keystore | debug keystore |
| ABI | `x86_64` | `arm64-v8a` |
| 内置资源 | `geoip.metadb` / `geosite.dat` | `geoip.metadb` / `geosite.dat` |

APK 内的内核（`aapt dump badging` 的 `native-code` 也分别只有对应 ABI）：

```
x86_64 APK:
  lib/x86_64/libmihomo.so              68,731,248 bytes
  lib/x86_64/libandroidx.graphics.path.so

arm64-v8a APK:
  lib/arm64-v8a/libmihomo.so           64,007,896 bytes
  lib/arm64-v8a/libandroidx.graphics.path.so
```

> MuMu 使用 x86_64 包，arm64 真机使用 arm64-v8a 包。两个 APK 使用同一包名，不能同时安装；
> 覆盖安装时请确认签名一致。

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
}

flavorDimensions += "abi"
productFlavors {
    create("x86") {
        dimension = "abi"
        ndk { abiFilters += "x86_64" }
    }
    create("arm64") {
        dimension = "abi"
        ndk { abiFilters += "arm64-v8a" }
    }
}

packaging {
    // mihomo 以可执行 ELF 形式随包，需保持“安装时解压到 nativeLibraryDir”
    jniLibs { useLegacyPackaging = true }
}
```

每个 flavor 的 `abiFilters` 只有一个值，因此最终 APK 只携带对应 ABI 的 `libmihomo.so`。

清单中 `android:extractNativeLibs="true"` 与 `useLegacyPackaging = true` 配合，
保证 `applicationInfo.nativeLibraryDir/libmihomo.so` 是一个可读取、可复制的真实文件，
应用首次启动时再复制到 `files/clash/mihomo/mihomo` 并 `chmod 700` 后通过 `su` 执行。

## 5. 常见问题

| 现象 | 处理 |
|---|---|
| `Could not find com.github.topjohnwu.libsu:core` | 依赖必须来自 JitPack，`settings.gradle.kts` 已声明 `maven("https://jitpack.io")` |
| `Android Gradle plugin requires Java 17` | 设置 `JAVA_HOME` 指向 JDK 17 |
| 依赖下载慢 | `settings.gradle.kts` 已内置阿里云镜像，可继续前置 |
| 想减小 APK | 已按 ABI 拆分；不要再把两个内核放回同一 APK，按需构建 `assembleX86Debug` 或 `assembleArm64Debug` |
| Release 签名 | `release` 复用 debug 签名便于验收；正式分发请替换 `signingConfigs` |

## 6. 构建日志核对点

- `:app:compileX86DebugKotlin` / `:app:compileArm64DebugKotlin` 通过（无 `e:` 编译错误）；
- `:app:packageX86Debug` / `:app:packageArm64Debug` 通过；
- `stripX86DebugDebugSymbols` / `stripArm64DebugDebugSymbols` 会提示 `Unable to strip ... libmihomo.so`，这是**预期行为**
  （mihomo 是预编译 Go 二进制，不需要 strip，打包时按原样保留）；
- `processX86DebugMainManifest` / `processArm64DebugMainManifest` 可能提示
  `android:extractNativeLibs should not be specified ...`，属于 AGP 的建议性警告，不影响本项目的可执行内核策略。

## 7. Shizuku（可选）

- 依赖：`dev.rikka.shizuku:api:13.1.5`、`dev.rikka.shizuku:provider:13.1.5`。
- Manifest 注册 `rikka.shizuku.ShizukuProvider`，authority 为 `${applicationId}.shizuku`。
- 通过 `ShizukuUserService`（AIDL）在 Shizuku 的 root/shell 进程里执行 `pm list packages`，用于包可见性受限时补充应用列表。
- **TUN 仍必须 root**：ADB shell 身份没有 `CAP_NET_ADMIN`，且 `/dev/tun` 是 `system:vpn` 0660；
  实测 `ip rule add` 为 `Operation not permitted`，`ip tuntap add` 为 `Permission denied`。
- 未安装 Shizuku 或不想使用 Shizuku 时，保持 root 应用列表/系统列表即可，不影响代理启动。
