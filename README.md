# Root TUN Clash

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
- 应用分流：系统应用列表、root 兜底列表、系统/用户标识与图标、白名单/黑名单、单应用三态
- 主页面流量卡片；可在通知栏显示累计流量与速率
- mihomo 官方稳定版检查、SHA-256 校验、下次启动切换与旧版回退
- 日志控制台、级别过滤与日志导出

TUN 接口（`net0`）仍可被系统枚举；本项目不承诺接口级完全隐藏。

## 环境

- Android 8.0+（minSdk 26），需要 root（`su`）
- JDK 17，Android SDK Platform 35 / Build-Tools 35.0.0
- 仓库内自带 `arm64-v8a` / `x86_64` 的 mihomo v1.19.34 与 geodata，仓库体积较大

## 构建

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。Windows 与详细说明见
[BUILD.md](BUILD.md)。

## CI 与 Release

- [`.github/workflows/android.yml`](.github/workflows/android.yml)：推送 `main` 或手动触发时构建并运行 JVM 单测。
- 构建成功后更新 `pre` 标签的 pre-release，附带 `RootTunClash-pre.apk`；该包使用 debug 签名，仅供测试。
- Pull Request 只跑构建，不发布。

## 已知限制

- 白名单模式固定使用订阅中首个受支持的静态远端节点，暂不跟随策略组选择。
- 设备验收以 TCP 为主；UDP / QUIC 尚未做完整端到端覆盖。
- 未实现 TUN 接口级隐藏，目标应用仍可枚举 `net0`。

## 文档

- [完整 README 备份](README.full.md)
- [1.0.1 验收报告](docs/verification/v101-report.md)
- [构建说明](BUILD.md)
- [STEALTH.md](STEALTH.md)

## 状态

当前版本 `1.0.1`（versionCode 3），开发中。欢迎通过 issue 反馈问题；提交 PR 前请确保
`:app:testDebugUnitTest` 通过。
