# 1.0.1 验证报告（MuMu / Android 12）

## 构建

| 项目 | 值 |
|---|---|
| APK | `dist/RootTunClash-1.0.1-debug.apk` |
| 大小 | 67,525,530 字节（64.40 MiB） |
| SHA-256 | `D5901B4A3864F6893FF331AD550AB0D6909286EF2FA2E5941799D11DCB7F6A4E` |
| 版本 | versionCode 3 / versionName 1.0.1 |
| 签名 | debug keystore |
| 设备 | MuMu Player 12，Android 12（API 32），x86_64，`127.0.0.1:16385` |

构建：`.\gradlew.bat --offline :app:assembleDebug :app:testDebugUnitTest` → `BUILD SUCCESSFUL`。
JVM 单测 19 项全部通过：`AppRoutingTest` 12、`MihomoKernelSlotsTest` 3、`MihomoReleaseTest` 4。

## 功能验收

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 订阅卡片删除“启用订阅”四个字，保留顶部操作提示与开关 | PASS | [v101 订阅页截图](screenshots/v101-subscriptions.png) |
| 2 | 应用列表来自系统 PackageManager，展示图标、名称、包名 | PASS | [应用列表截图](screenshots/v101-app-list.png)；列表 45 个应用 |
| 3 | 系统应用与用户应用区分 | PASS | 列表含“系统”徽标与“全部 / 用户应用 / 系统应用”筛选；40 个系统应用 |
| 4 | root 应用列表开关默认关，可手动开启 | PASS | 初始 `checked=false`；开启后日志 `root 应用列表：发现 57 个包，补充 0 个不可见应用`，界面提示“root 列表已核对” |
| 5 | 白名单模式：仅勾选应用经代理，其余直连 | PASS | 选择探针 UID 10044；运行配置含 `UID,10044,FixtureHTTP`、`UID,10044,REJECT`、TCP/UDP 直连兜底；[白名单设备回执](v101-whitelist-uid-proxy.txt) |
| 6 | 黑名单模式：勾选应用直连，其余按订阅规则 | PASS | 运行配置含 `UID,10044,DIRECT`；[黑名单设备回执](v101-blacklist-uid-direct.txt) |
| 7 | 关闭模式仍按单应用三态 / 订阅规则 | PASS | 配置中无应用分流规则；[订阅规则回执](v101-rules.txt) |
| 8 | 双向传输 | PASS | 每个模式 4 次传输：4 MiB 上传 ×2、128 KiB 下载 ×2；接收端校验字节数与 SHA-256 |
| 9 | 主页面流量卡片 | PASS | 两次 4 MiB 上传后显示上传 8.0 MB、下载 257.1 KB；[流量卡片截图](screenshots/v101-traffic.png) |
| 10 | 通知栏显示流量开关 | PASS | 开启后 `dumpsys notification`：`android.text=↑ 8.0 MB  ↓ 257.1 KB`，大文本含上传/下载和速率 |
| 11 | 关于页动态版本与开发者信息 | PASS | 版本 `1.0.1`；开发者 `ExceSama、GPT、DeepSeek`；VpnService 说明已从主页移入关于页；[关于页截图](screenshots/v101-about.png) |
| 12 | 停止后回滚 | PASS | `pidof mihomo` 为空、`net0` 不存在、pref 8940 UID 规则清空 |
| 13 | mihomo 规则语法 | PASS | 设备上用 `mihomo -t` 校验 UID / AND(NETWORK,TUN) 规则：`test is successful` |

## 设备回执摘要

白名单（探针 UID 10044 强制代理）：

```text
[TCP] 198.19.0.0:45632(com.roottun.probe, uid=10044) --> 10.0.2.2:38881
      match Uid(10044) using FixtureHTTP
```

黑名单（探针 UID 10044 强制直连）：

```text
[TCP] 198.19.0.0:47224(com.roottun.probe, uid=10044) --> 10.0.2.2:38881
      match Uid(10044) using DIRECT
```

关闭模式（订阅规则）：

```text
[TCP] 198.19.0.0:42594 --> 10.0.2.2:38881
      match AND(((IPCIDR,10.0.2.2/32) && (DstPort,38881) && (SrcPort,41000-44999))) using PROXY[FixtureHTTP]
```

## 未覆盖 / 限制

- **root 补充列表的图标路径未做端到端验证**：MuMu 的系统列表已覆盖全部 45 个可选应用，root 列表没有“系统列表不可见”的包，因此 `loadRootAppInfo`（复制 APK 读取图标）未触发；root 命令、合并和提示已实际运行。
- **UDP 只做了规则级验证**：应用规则已改为 UID 级，理论上覆盖 TCP/UDP；设备传输用例只发送 TCP。
- **白名单代理目标固定为订阅中首个静态远端节点**：不跟随订阅的 Selector 选择，也不套用订阅的域名分流规则；没有可用静态节点时勾选应用被 REJECT。
- **流量统计每 2 秒轮询 `/connections`**：传输结束后速率为 0 B/s 属正常；大量连接时该接口响应会变大。
- **未验证真实跨版本内核升级**：官方检查返回 v1.19.34（与内置相同），只验证了查询和版本判断；升级/回退状态由单测覆盖。
- **未做隐藏对抗**：本轮不关注 net0 可见性，也未承诺对其他应用隐藏代理。

## 清理

- 测试用夹具订阅已删除，保留原有订阅。
- 应用分流 prefs 已清除，恢复为默认“关闭 / root 列表关”。
- 本地测试端点已停止。
