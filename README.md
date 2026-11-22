# Root TUN Clash

本项目的最初开发思想来自 [De4869/tunclash](https://github.com/De4869/tunclash)，感谢原作者的开源工作。

> [!WARNING]
> 本项目处于开发阶段。功能、配置格式和稳定性都可能变化，尚未经过完整安全审计。
> 请勿用于生产环境或重要网络场景；当前发布构建使用 debug 签名，仅供测试。

Root TUN Clash 是一个 Android 上的 root 级 TUN 透明代理客户端：通过 `su` 启动 mihomo
（Clash Meta）内核并创建 TUN 设备接管流量，不创建 `VpnService`，因此系统中不会出现
VPN 连接。

## 当前能力

- mihomo TUN 接管；root 检查、启动事务与失败回滚
- Clash YAML / base64 / 分享链接订阅；多 User-Agent 兼容
- 节点策略组读取、切换与测速
- 应用分流：系统应用列表、root / Shizuku 兜底列表、系统/用户标识与图标、白名单/黑名单、单应用三态
- Shizuku 实验：应用列表回退；TestNetworkManager 测试 TUN 创建/销毁（仅热点/网络共享测试，不接管本机应用）
- 主页面流量卡片；可在通知栏显示累计流量与速率
- mihomo 官方稳定版检查、SHA-256 校验、下次启动切换与旧版回退
- 日志控制台、级别过滤与日志导出

TUN 接口（`net0`）仍可被系统枚举；本项目不承诺接口级完全隐藏。

## 环境

- Android 8.0+（minSdk 26），需要 root（`su`）
- JDK 17，Android SDK Platform 35 / Build-Tools 35.0.0
- 可选：已安装并运行的 Shizuku（应用列表回退 + TestNetwork TUN 实验；不能替代 root 做本机透明代理）
- 仓库内自带 `arm64-v8a` / `x86_64` 的 mihomo v1.19.34 与 geodata；构建时按 ABI 拆分，每个 APK 只包含一个内核

## 构建

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleX86Debug :app:assembleArm64Debug
```

产物：

- `app/build/outputs/apk/x86/debug/app-x86-debug.apk`
- `app/build/outputs/apk/arm64/debug/app-arm64-debug.apk`

请安装与设备 ABI 匹配的 APK；两个包使用同一包名，不能同时安装。Windows 与详细说明见
[BUILD.md](BUILD.md)。

## CI 与 Release

- [`.github/workflows/android.yml`](.github/workflows/android.yml)：推送 `main` 或手动触发时构建 x86_64 / arm64-v8a 两个 APK，并运行 JVM 单测。
- 构建成功后更新 `pre` 标签的 pre-release，附带 `RootTunClash-x86_64-pre.apk` 与 `RootTunClash-arm64-v8a-pre.apk`；两个包均使用 debug 签名，仅供测试。
- Pull Request 只跑构建，不发布。

## 已知限制

- 白名单模式固定使用订阅中首个受支持的静态远端节点，暂不跟随策略组选择。
- 设备验收以 TCP 为主；UDP / QUIC 尚未做完整端到端覆盖。
- 未实现 TUN 接口级隐藏，目标应用仍可枚举 `net0`。
- Shizuku 可补充应用列表；其 `TestNetworkManager` 路径只能创建热点/网络共享测试 TUN，不接管本机应用流量。
  本机 TUN 透明代理仍需 root（或改用 VpnService，本项目不使用）；ADB shell 也无法直接执行 `ip rule`/`iptables`。
- MuMu Android 12 已实测可通过 Shizuku 创建/销毁 `testtun0`（`TestNetworkManager.createTunInterface`），但该镜像的
  `TetheringManager.setPreferTestNetworks` 不可用，因此热点上游链路未验证；不同 ROM 的 tethering 模块差异较大。

## 文档

- [完整 README 备份](README.full.md)
- [1.0.1 验收报告](docs/verification/v101-report.md)
- [构建说明](BUILD.md)
- [STEALTH.md](STEALTH.md)

## 状态

当前版本 `1.0.2-beta`（versionCode 5），开发中。欢迎通过 issue 反馈问题；提交 PR 前请确保
`:app:testX86DebugUnitTest` 通过。

## 许可证

本项目使用 [MIT License](LICENSE)。

