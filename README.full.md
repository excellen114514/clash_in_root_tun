# Root TUN Clash

**Root 级 TUN 透明代理客户端**：通过 `su` 拉起 mihomo（Clash Meta）内核并创建 TUN 设备接管流量，
**不创建任何 `VpnService`**，因此系统里不会出现 VPN 连接。TUN 接口本身仍可被系统枚举，
本项目不承诺接口级完全隐藏（详见 [STEALTH.md](STEALTH.md)）。

- 包名：`com.roottun.clash`
- 版本：`1.0.1`（versionCode 3）
- UI：Jetpack Compose + Material 3，色彩规范 **SPEC_2025**
- 内核：mihomo **v1.19.34**，以 `libmihomo.so` 打进 `jniLibs`（arm64-v8a / x86_64）
- 依赖：**无 LSPosed / Xposed / Zygisk / libxposed**，**无 VpnService 权限或服务**

> 1.0.1 的改动与 MuMu 验收见 [docs/verification/v101-report.md](docs/verification/v101-report.md)。
> 下方架构图保留 1.0.0 的历史描述；1.0.1 实际使用 TUN 名 `net0`、不开放本地 TCP 监听
> （控制器走 unix socket），并新增应用分流、流量卡片/通知和内核自动更新。

> 本工程是在旧 LSPosed 模块（`com.lspclash`）的基础上**整体重写**的：旧模块的全部 Java 源码、
> `META-INF/xposed/*` 声明、libxposed 依赖都已删除，只保留 `su` + mihomo + TUN 这一条链路。

---

## 1. 功能范围

| 能力 | 说明 |
|---|---|
| 代理启停 | 「启动代理 / 停止代理」，启用时 7 步事务化流程，失败自动回滚 |
| 订阅管理 | 添加 / 删除 / 启用禁用 / 切换活动订阅；HTTP(S) URL 与本地 YAML（SAF） |
| 订阅格式 | Clash YAML、base64 编码的 YAML、明文分享链接列表（ss / vmess / vless / trojan / hysteria2 / tuic） |
| 订阅兼容 | 多 User-Agent 自动重试 + 结构校验，规避机场按 UA 返回 `proxies: []` 模板导致内核校验失败 |
| 节点选择 | 「节点」页读取 mihomo 控制器（unix socket）：策略组列表、当前节点、点击切换、逐节点/整组测速 |
| 应用分流 | 系统应用列表（图标/名称/包名/系统标识）；root 兜底列表开关；白名单/黑名单模式；单应用跟随/直连/代理 |
| 流量统计 | 主页面上传/下载累计与速率卡片；可开关在前台通知中显示 |
| 内核更新 | 官方稳定版检查、SHA-256 校验、下次启动切换与旧版回退 |
| 代理隐藏 | 不开放任何本地 TCP/UDP 监听（控制器走 unix socket）+ iptables 回环封堵；接口名中性化；TLS 指纹清洗；TUN 接口仍可被枚举，不承诺完全隐藏（详见 [STEALTH.md](STEALTH.md)） |
| 日志控制台 | 时间戳 + 级别 + 来源标签；级别过滤、清空、自动滚动；一键下载为 `.txt` 文件 |
| 状态展示 | 运行状态、Root 状态、TUN 设备、内核 PID/版本、本地监听、运行时长、当前订阅 |
| 设置 | 动态取色（Material You）、深色模式、主题基准色、应用分流、TUN 协议栈/MTU、手工策略路由、自身流量绕过 TUN、订阅 UA、内核更新、关于 |

不在本工程范围内：复杂分流规则编辑器、WebUI、KernelSU/Magisk 模块打包。

---

## 2. 架构

```
┌──────────────────── UI（Compose M3 / SPEC_2025） ────────────────────┐
│ 状态页（Home）        订阅页（Subscriptions）      日志控制台（Logs） │
└───────────────┬───────────────────────┬─────────────────────────────┘
                │ StateFlow             │ StateFlow
┌───────────────▼───────────────────────▼─────────────────────────────┐
│                          ProxyCore（编排器）                          │
│   start() 7 步事务：Root → 内核释放 → 订阅 → 写配置 → 校验 → TUN      │
│                     → 启动内核 → 策略路由；失败/异常自动回滚           │
└───────┬───────────────┬───────────────┬───────────────┬─────────────┘
        │               │               │               │
 ┌──────▼─────┐  ┌──────▼──────┐  ┌─────▼──────┐  ┌─────▼────────┐
 │ RootShell  │  │  Mihomo     │  │  NetGuard  │  │ KernelManager│
 │ libsu/     │  │  Installer  │  │ /dev/net/  │  │ 进程 + 健康  │
 │ su -c      │  │ + geodata   │  │ tun、iptables、│  │ 检查 + 退出  │
 │            │  │             │  │ ip rule     │  │ 监听         │
 └──────┬─────┘  └─────────────┘  └────────────┘  └──────┬───────┘
        │ su                                             │ su -c exec
┌───────▼────────────────────────────────────────────────▼───────────┐
│ mihomo v1.19.34（root）: TUN mihomo0 + fake-ip DNS:1053              │
│ mixed-port 127.0.0.1:7890 · external-controller 127.0.0.1:9090       │
│ auto-route: fwmark 0x2023 → table 2022 → default dev mihomo0         │
└────────────────────────────────────────────────────────────────────┘
```

### 启动流程（ProxyCore.startInternal）

1. **Root 检查**（必须先于任何 TUN 操作）：`su -c id` 校验 `uid=0`；失败时日志写明原因
   （没有 su / 授权超时 / uid 不是 0 / 非 Root shell），UI 显示可读错误并给出「重新检测」入口。
2. **释放内核**：把 `nativeLibraryDir/libmihomo.so` 复制到 `files/clash/mihomo/mihomo`，
   `chmod 700`，`mihomo -v` 自检 ABI 是否匹配；同时释放内置 `geoip.metadb` / `geosite.dat`。
3. **准备订阅**：读取活动订阅缓存；无订阅时使用内置 DIRECT 自检配置（日志 WARN 提示）。
4. **写配置 + 校验**：订阅内容原样保留，只覆盖运行期顶层键（TUN/DNS/端口/控制器），
   然后执行 `mihomo -t`，**校验失败绝不启动内核**。
5. **TUN 节点**：`/dev/net/tun` 不存在且 `/dev/tun` 存在时建立软链接（Android 的控制设备是
   `/dev/tun`），并打印 `ls -l` 结果。
6. **启动内核**：`su -c "echo $$ > pid; cd ...; exec mihomo -d ... -f ..."`，实时捕获 stdout/stderr；
   健康检查为「进程存活 + 控制器端口可连 + TUN 设备出现在 `ip link`」，15 秒超时后打印内核 stderr
   并自动降级 `system` 栈重试一次。
7. **策略路由**：优先使用 mihomo `auto-route`（检测 `fwmark 0x2023` / `table 2022`）；
   检测不到且设置允许时，才安装手工规则（iptables MARK + `ip rule` + 独立路由表）。
   额外为**应用自身 uid** 添加 `ip rule ... uidrange <uid> lookup <默认网卡>`，避免
   「订阅节点不可用 → 无法再拉取订阅」的自我锁死。

### 停止流程（逆序回滚）

`iptables 跳转/链清理 → 手工 ip rule/路由表清理 → 自身 uid 绕过规则清理 → 终止 mihomo →
兜底删除 TUN 设备 → 停止前台服务`。清理函数是幂等的，应用崩溃后再启动也会先把上次的残留清掉。

---

## 3. 关键实现说明

### 3.1 Root 通道（libsu + `su -c` 双通道）

- **libsu 5.2.2**（`com.github.topjohnwu.libsu:core`，JitPack）作为根命令通道；当
  `Shell.isRoot == true` 时优先使用 `shell.newJob().add(cmd).enqueue()`。
- 实测发现：**libsu 在 su 被拒绝/超时时会静默降级成非 Root shell**（`Shell.isRoot == false`），
  参考项目正是因此出现「以为有 root，实际命令以 uid=10020 执行」。本实现每次 Root 检查都会
  重新建 shell 并校验 `id -u`，非 Root shell 直接丢弃；必要时退回 `su -c` 直连通道。
- 会自动挑选**当前应用 uid 真正可执行**的 su 路径：MuMu 的 `/system/xbin/su` 权限为
  `4750 root:shell`（普通应用无法执行），必须使用 `/system/bin/su`。
- 首次提权会弹出 Root 授权框；`AndroidManifest.xml` 声明了 `android.permission.ACCESS_SUPERUSER`，
  兼容 SuperSU/MuMu 的超级用户识别。

### 3.2 订阅解析

`SubscriptionParser` 独立分支处理三类输入，任何失败都带**原始响应片段**写日志：

1. Clash/mihomo YAML（按顶层键判定：`proxies` / `proxy-groups` / `rules` / `mixed-port` …）；
2. base64 包裹（解码后可能是 YAML，也可能是分享链接列表）；
3. 明文分享链接列表（逐条解析，单条失败只告警不中断，并给出失败原因）。

`ConfigComposer` 用「轻量顶层块编辑器」保留订阅的 `proxies/proxy-groups/rules` 原文，
只剥离并重写运行期键（`tun`/`dns`/端口/控制器/日志级别等），订阅缺少 `rules` 或
`proxy-groups` 时按最小可用规则补齐并记录 WARN。

### 3.3 订阅 User-Agent 兼容（真实故障修复）

很多机场会**按 User-Agent 返回不同内容的订阅**。真实案例：某订阅对
`UA=…Clash/1.0`（旧版 Clash 客户端）返回的是「模板配置」：

```yaml
proxies: []
proxy-groups:
  - { name: jp自动切换, type: fallback, proxies: [/日本/] }
  - { name: 主代理, type: select, proxies: [..., '/^(?!.*(剩余流量|…)).*$/'] }
```

`proxies: [/正则/]` 不是 mihomo 支持的写法，内核会直接报
`proxy group[0]: jp自动切换: '/日本/' not found`，配置校验失败。

本项目的处理：

1. 默认 UA 改为 `ClashMetaForAndroid/2.11.7.Meta`（与 ClashMetaForAndroid 一致），
   避免 UA 中出现 `Clash/版本号` 被机场识别成旧客户端；
2. 拉取时按「自定义 UA → CMFA → clash.meta → Clash Verge」依次尝试；
3. 每份内容先过 `ConfigSanity`：节点数为 0 且无 `proxy-providers`、或代理组里出现
   `/正则/` 代理条目，就判定为模板并自动换下一个 UA；
4. 全部失败时，日志与订阅卡片会列出**每种 UA 的具体失败原因**；
5. 「设置 → 订阅 User-Agent」允许用户自行指定 UA（例如机场要求特定客户端标识）。

### 3.4 mihomo 配置要点

- `geodata-mode: false`：使用内置 `geoip.metadb`。**若设为 true，mihomo 会去找经典格式的
  `GeoIP.dat`，缺失时尝试联网下载并卡住 90 秒**（本项目在 MuMu 上实测踩到并已修复）。
- `tun.stack: gvisor`（失败自动降级 `system`）、`auto-route: true`、`auto-detect-interface: true`、
  `dns-hijack: any:53`、fake-ip `198.18.0.1/16`。
- 所有路径都来自 `context.filesDir`，**没有 `/data/adb/...` 之类的硬编码路径**。

### 3.5 节点自由选择（mihomo unix socket 控制器）

- 「节点」页通过 `android.net.LocalSocket` 直接与 `files/clash/mihomo/ctl.sock` 上的 HTTP/1.1 控制器通信
  （`GET /proxies`、`PUT /proxies/{group}`、`GET /proxies/{node}/delay`），**不经过任何 TCP 端口**；
- 列表按「手动选择 → 自动测速 → 故障转移 → 负载均衡 → 链式」排序，展示每组当前节点；
- 展开后列出成员节点（名称 + 类型 + 延迟），**仅 Selector 组允许手动切换**；
  URLTest / Fallback / LoadBalance 组会提示由内核自动选择；
- 「测速」按钮可整组测试（并发上限 8），结果以 ms / 超时显示；
- 选择结果由 mihomo 的 `profile.store-selected: true` 持久化到内核缓存，重启后保留；
- 控制器不监听 TCP，配合应用自身流量绕过 TUN，控制通道既不会经过代理，也不会被目标应用扫描到。

### 3.6 日志（约束 C5）

- 每条 Root 命令执行前打印 `$ <cmd>`，执行后分别记录 stdout（INFO）与 stderr（WARN）以及退出码；
- mihomo 的 stdout/stderr 实时转发到日志控制台；
- 所有 `catch` 都至少把异常消息写入日志，未捕获异常会打印最多 20 行堆栈；
- 日志同时写入 `files/clash/logs/app.log`，便于离线排障；
- 日志页右上角「下载日志」把设备信息头 + 完整日志导出为 `.txt`：
  Android 10+ 写入公共 `Download/RootTunClash/`（MediaStore，无需存储权限），
  Android 8/9 写入应用专属外部目录；导出后会以 Snackbar 显示具体路径与字节数。

### 3.7 UI（MD3 SPEC_2025）

- `ColorScheme` 由 **MaterialKolor 的动态取色引擎**生成：`SchemeTonalSpot` +
  `ColorSpec.SpecVersion.SPEC_2025`（2025 版规范，不是 2021 默认），所有角色通过
  `MaterialTheme.colorScheme.*` 访问，Composable 中无硬编码颜色。
- 支持可选的动态取色（Android 12+ Material You）与 6 个 SPEC_2025 基准色预设。
- 页面结构：`Scaffold` + `NavigationBar`（状态 / 订阅 / 节点 / 日志），主操作使用
  `FilledTonalButton`，状态展示使用 `Card`，订阅项使用 `Card` + `RadioButton`/`Switch`，
  节点列表使用 `Card` + `RadioButton` + 延迟徽标；「下载 / 节点」图标按 Material 24dp 路径自绘。

> **关于 MaterialKolor 版本**：任务书写的是「MaterialKolor 6.0.0+」，但 Maven Central 上
> `com.materialkolor:material-kolor` 的最新版本是 **5.0.1**，且其 `PaletteStyle` 枚举中并没有
> `SPEC_2025`（只有 9 个 2021 风格的取值）；5.0.1 的 Compose 集成还依赖 Compose Multiplatform
> `1.12.0-alpha03` + Kotlin `2.4.10`，与本工程锁定的 AGP 8.9.2 / compileSdk 35 工具链不兼容。
> 因此本工程直接使用**同一 MaterialKolor 项目发布的 `material-color-utilities` 4.0.5** 引擎，
> 通过 `ColorSpec.SpecVersion.SPEC_2025` 生成色板并映射为 Compose `ColorScheme`——
> 视觉结果与 `PaletteStyle.SPEC_2025` 等价，同时保持工具链稳定。

---

## 4. 构建

详见 [BUILD.md](BUILD.md)。最短路径：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME='C:\newclash\android-sdk'
.\gradlew.bat :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（含 arm64-v8a 与 x86_64 两个 ABI）。

验证用探针应用（普通应用视角，单独 APK，不随主 APK 发布）：

```powershell
.\gradlew.bat :probe:assembleDebug
# probe\build\outputs\apk\debug\probe-debug.apk
```

---

## 5. 在 MuMu 上运行

1. 启动模拟器（已在运行可跳过）：

   ```powershell
   Start-Process "C:\Program Files\Netease\MuMuPlayer-12.0\nx_main\MuMuManager.exe" `
       -ArgumentList @("control","-v","0","launch") -WindowStyle Hidden
   ```

2. 连接与安装：

   ```powershell
   .\android-sdk\platform-tools\adb.exe connect 127.0.0.1:16384
   .\android-sdk\platform-tools\adb.exe install -r -t app\build\outputs\apk\debug\app-debug.apk
   .\android-sdk\platform-tools\adb.exe shell am start -W -n com.roottun.clash/.MainActivity
   ```

3. 首次启动会弹出 Root 授权框：选择 **「永久记住选择」→ 允许**。
   （MuMu：设置 → 其他设置 → 打开 Root 权限；未开启时应用会显示明确的「未获得 Root 权限」。）

4. 打开「订阅」页添加订阅 → 回到「状态」页点击「启动代理」。
   日志控制台会逐条显示 Root 检查、内核释放、配置校验、TUN 创建与策略路由结果。

一键验收脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-device.ps1
```

---

## 6. 参考项目（De4869/tunclash）问题审查与修复对照

| # | 参考项目的问题 | 本项目处理 |
|---|---|---|
| 1 | `su` 检查不保证在 TUN 之前 | 启动第 1 步强制 `su -c id` 校验 `uid=0`，失败立即中止并给可读错误 |
| 2 | `/dev/net/tun` 可能不存在 | 启动前 `ln -sf /dev/tun /dev/net/tun`（仅当缺失且 `/dev/tun` 存在），并打印 `ls -l` 结果 |
| 3 | iptables 规则缺少统一清理 | 统一托管链 `TUNCLASH` + 幂等清理函数；停止/失败/崩溃重启都会回滚规则、路由表与 TUN 设备 |
| 4 | 订阅解析边界情况崩溃 | YAML / base64 / 分享链接三路独立解析，失败带 HTTP 状态码、异常类型与原始片段 |
| 5 | 仅 `Runtime.exec()` 就认为启动成功 | 进程存活 + 控制器端口 + TUN 设备三重健康检查，超时打印内核 stderr，并自动降级协议栈重试 |
| 6 | 残留 `VpnService` 引用 | 全工程无 `VpnService` 类/权限/服务；`aapt dump permissions` 与 `dumpsys package` 均已验证 |
| 7 | 硬编码 `/data/adb/...` | 全部路径来自 `context.filesDir`（`files/clash/...`） |
| 8 | libsu 静默降级非 Root shell | 每次 Root 检查重建并校验 shell，非 Root shell 直接丢弃，回退 `su -c` 直连 |
| 9 | `geodata-mode` 与内置数据不匹配 | 内置 `geoip.metadb` + `geodata-mode: false`，避免内核联网下载 GeoIP.dat 卡死 90 秒 |

---

## 7. 目录结构

```
app/src/main/java/com/roottun/clash/
├── MainActivity.kt / RootTunClashApp.kt
├── core/
│   ├── RootShell.kt            # libsu + su -c 双通道、Root 校验
│   ├── MihomoInstaller.kt      # 释放内核与地理库、ABI 自检
│   ├── ConfigComposer.kt       # 订阅 → mihomo 配置（运行期覆盖）
│   ├── ClashYaml.kt            # 顶层块编辑器（保留订阅原文）
│   ├── ConfigSanity.kt         # 订阅结构检查（空节点 / 正则代理组）
│   ├── ShareLinks.kt / YamlWrite.kt
│   ├── Subscription*.kt        # 拉取（多 UA 重试）、解析、缓存、活动订阅
│   ├── KernelManager.kt        # 进程 + 健康检查 + 退出监听
│   ├── NetGuard.kt             # TUN 节点、策略路由、iptables 回滚
│   ├── MihomoApi.kt            # 控制器 REST 客户端（proxies/select/delay）
│   ├── NodeSelector.kt         # 「节点」页状态机：组列表、切换、测速
│   ├── LogExporter.kt          # 日志导出（MediaStore / 应用目录）
│   ├── ProxyCore.kt            # 启停事务与状态机
│   ├── ProxyForegroundService.kt
│   ├── LogBus.kt / AppSettings.kt / Workspace.kt / Subscription.kt
└── ui/                         # Compose M3（SPEC_2025）
    ├── theme/Theme.kt / AppIcons.kt
    └── screens/Home / Subscriptions / Nodes / LogConsole
```

仓库根目录另有：

- [STEALTH.md](STEALTH.md)：代理隐藏的分层设计、实测结论与下一轮 KPM/Zygisk 方案；
- `probe/`：目标应用视角探针（VPN 状态 / 接口枚举 / 路由 / 本机端口）；
- `docs/`：验证截图、脱敏运行日志与测试订阅样例。

---

## 8. 已知限制

- **原生接口隐藏（L2）未达成**：单 APK 无法拦截 netlink / ioctl / `/proc/net/*`，目标应用仍能看到
  中性命名的 `net0` 及其 p2p 标志、`198.19.0.0/30` 地址与路由。kmod/KPM/Zygisk 方案与风险见
  [STEALTH.md](STEALTH.md) §5，建议下一轮单独评估。
- **实机验证由用户完成**：本仓库的自动化验证在 MuMu（x86_64 / Android 12）上完成；
  用户在 OnePlus PLR110（Android 16 / arm64 / KernelSU）上的实机验证除「某个按 UA 返回模板的机场订阅」
  外均通过，该问题本轮已修复（见 3.3 与 [VERIFICATION.md](VERIFICATION.md)）。
- **SAF 本地文件**：文件选择器已能正常拉起（DocumentsUI），但自动化脚本未完成「选中文件」这一步；
  导入逻辑与 URL 订阅共用同一套解析/落盘代码。
- **内核随应用进程**：应用被 `force-stop` / `pm clear` 时内核进程会一起结束；下次启动会自动清理
  残留规则。正常退到后台由前台服务保活。
- **节点选择的范围**：手动切换只对 Selector 组生效；URLTest / Fallback / LoadBalance 由内核决定。
  测速使用 `http://www.gstatic.com/generate_204`，部分节点可能因该测试地址不可达而显示超时。
- **仅基本功能**：无分应用代理、无规则编辑器、无 WebUI、无在线内核更新。

---

## 9. 变更记录

| 轮次 | 内容 |
|---|---|
| 第一轮 | Root TUN 启停事务、订阅三格式解析、日志控制台、MD3 SPEC_2025 界面、MuMu 全链路验证 |
| 第二轮 | 修复机场按 User-Agent 返回模板导致的配置校验失败（多 UA 重试 + 结构校验 + 自定义 UA）；新增「节点」页（策略组 / 自由选择 / 测速）；日志控制台新增「下载日志」 |
| 第三轮 | 代理隐藏：零本地 TCP/UDP 监听（控制器改 unix socket）、iptables 回环封堵、TUN 中性命名与 fake-ip 段/MTU/strict-route 调整、TLS 指纹清洗；新增目标应用视角探针与 [STEALTH.md](STEALTH.md)；L2 原生接口隐藏未达成并给出下一轮方案 |
| 第四轮 | 实机探针（Android 16）驱动：修复持久化 MTU=9000 的迁移、启动后固定 MTU=1500、清除 TUN 默认 IPv6 ULA（`inet6-address: []` 在 mihomo 1.19 实测无效，改为启动后 `ip addr del`）；探针区分 `/proc/net/*` 不可读与干净，并输出已知指纹 |

验证细节、日志片段与截图见 [VERIFICATION.md](VERIFICATION.md)。
