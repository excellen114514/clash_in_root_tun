# VERIFICATION.md — 运行验证报告

验证范围：**构建 → 安装 → 启动 → Root 授权 → 启动/停止代理 → TUN 与策略路由 → 订阅拉取/解析/切换
→ 日志控制台（含下载）→ 节点选择/测速 → 回滚清理 → 代理隐藏（目标应用视角探针）**。

两轮验证的宿主环境均为 MuMu Player 12（x86_64 / Android 12）；用户另外在 OnePlus PLR110
（Android 16 / arm64 / KernelSU）上做了实机验证，除「某个按 User-Agent 返回模板的机场订阅」外全部通过，
该问题已在第二轮定位并修复（详见 §4.8）。

结论：**通过**。APK 可构建、可安装、可启动；Root 可用时 mihomo TUN 成功接管流量，停止后
iptables/策略路由/TUN 设备全部回滚且网络正常；无 Root 时给出明确错误而不是崩溃；
第二轮修复的订阅兼容问题与新增的「节点选择」「下载日志」均已完成端到端验证。

---

## 1. 环境

| 项目 | 值 |
|---|---|
| 模拟器 | MuMu Player 12（`C:\Program Files\Netease\MuMuPlayer-12.0`），VM index 0 |
| ADB 端口 | `127.0.0.1:16384`（由 `MuMuManager.exe info -v 0` 读出：`adb_port: 16384`） |
| 客户机 | Xiaomi 2201123C，Android 12（API 32），`ro.product.cpu.abi=x86_64`，内核 5.4.32 |
| Root | MuMu 超级用户（`/system/bin/su`；`/system/xbin/su` 为 `4750 root:shell`，普通应用不可执行） |
| ADB | 仓库自带 `android-sdk\platform-tools\adb.exe`（1.0.41 / 36.0.0） |
| mihomo | `Mihomo Meta v1.19.34 android amd64 with go1.26.11`（`with_gvisor`） |
| APK | `app\build\outputs\apk\debug\app-debug.apk`，66,609,419 字节，SHA-256 `44723084E0882FC073EEBACFBEAEDE9EB6F8C76BA95DE8DFF97786130F25A03A` |

---

## 2. 使用的命令

```powershell
# 1) 启动 MuMu（已启动可跳过；用 Start-Process 让模拟器不随宿主命令结束而退出）
Start-Process "C:\Program Files\Netease\MuMuPlayer-12.0\nx_main\MuMuManager.exe" `
    -ArgumentList @("control","-v","0","launch") -WindowStyle Hidden

# 2) 读取 ADB 端口并连接
& "C:\Program Files\Netease\MuMuPlayer-12.0\nx_main\MuMuManager.exe" info -v 0
.\android-sdk\platform-tools\adb.exe connect 127.0.0.1:16384
.\android-sdk\platform-tools\adb.exe devices -l

# 3) 安装并启动
.\android-sdk\platform-tools\adb.exe install -r -t app\build\outputs\apk\debug\app-debug.apk
.\android-sdk\platform-tools\adb.exe shell am start -W -n com.roottun.clash/.MainActivity

# 4) 一键验收（脚本自带 7 组检查）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-device.ps1

# 5) 读取应用日志（需要 su）
.\android-sdk\platform-tools\adb.exe shell "su -c 'tail -n 60 /data/data/com.roottun.clash/files/clash/logs/app.log'"

# 6) 停止后的状态核对
.\android-sdk\platform-tools\adb.exe shell "su -c 'sh /data/local/tmp/statecheck.sh'"
```

`tools\verify-device.ps1` 实测输出（节选）：

```
== 1. 连接 MuMu ==
  [PASS] adb 已连接 127.0.0.1:16384
== 2. 设备信息 ==
  ABI=x86_64  SDK=32
  [PASS] 设备 ABI 受支持（x86_64）
== 3. 安装 APK ==
  [PASS] APK 存在：...\app-debug.apk
  [PASS] 安装成功
== 4. 启动主界面 ==
  [PASS] MainActivity 启动成功
== 5. 崩溃检查 ==
  [PASS] 没有 FATAL EXCEPTION
== 6. VPN 声明检查 ==
  [PASS] 未申请 BIND_VPN_SERVICE
  [PASS] 未声明 VpnService
结果：PASS=8 FAIL=0
```

---

## 3. 结果总览

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| 1 | Debug APK 构建 | PASS | `BUILD SUCCESSFUL`，产物见 [BUILD.md](BUILD.md) |
| 2 | 安装到 MuMu | PASS | `Performing Streamed Install / Success` |
| 3 | 主界面正常渲染，状态「已停止」 | PASS | [01-home-stopped.png](docs/screenshots/01-home-stopped.png) |
| 4 | 日志控制台可打开且有启动日志 | PASS | [04-log-console-all.png](docs/screenshots/04-log-console-all.png)，`docs/verification/app-run.log` |
| 5 | 无 Root 时给出明确错误、不崩溃 | PASS（见 §4.6） | 日志 `未获得 Root 权限：su 返回 uid=10020，未取得 Root` |
| 6 | Root 授权流程 | PASS | [02-root-request-dialog.png](docs/screenshots/02-root-request-dialog.png)，`Root 检查通过：uid=0` |
| 7 | mihomo 内核启动 | PASS | `Mihomo Meta v1.19.34 ...`，`内核就绪：pid=8067` |
| 8 | TUN 设备出现 | PASS | `ip link show mihomo0` → `UP,LOWER_UP`；[03-home-running.png](docs/screenshots/03-home-running.png) |
| 9 | 策略路由接管 | PASS | `ip rule` 含 9000/9001/9010 → `lookup 2022`；`ip route show table 2022` → `default dev mihomo0` |
| 10 | 流量确实进入内核 | PASS | mihomo 日志 `[TCP] 198.18.0.1 --> www.baidu.com:443 match GeoIP(cn) using DIRECT`；`curl` = HTTP 200 |
| 11 | 订阅：Clash YAML | PASS | `订阅「…sub_yaml.yaml」更新成功：2 个节点` |
| 12 | 订阅：base64 | PASS | `检测到 base64 包裹的 Clash YAML，已解码` → 2 个节点 |
| 13 | 订阅：分享链接列表 | PASS | `检测到明文分享链接列表` → 5 个节点（ss/trojan/vmess/vless/hysteria2） |
| 14 | 订阅：HTTP 错误可诊断 | PASS | `HTTP 状态码 404 File not found，响应：<!DOCTYPE HTML>` |
| 15 | 订阅切换后自动重启内核 | PASS | `活动订阅已切换…重启内核以应用新的活动订阅`，新 pid=8394，配置变为 `本地链路-A/B` |
| 16 | 停止代理：iptables/路由/TUN 全回滚 | PASS | 见 §4.5 与 [06-home-stopped-after-cleanup.png](docs/screenshots/06-home-stopped-after-cleanup.png) |
| 17 | 停止后网络正常 | PASS | `curl … baidu.com` → `HTTP=200` |
| 18 | 日志级别过滤 / 清空 / 自动滚动 | PASS | [05-log-console-error-filter.png](docs/screenshots/05-log-console-error-filter.png)，计数 `0/116`、`0/1` |
| 19 | 无 VpnService / 无 LSPosed | PASS | `aapt2 dump permissions` 无 VPN 权限；APK 无 `META-INF/xposed/*`；`grep` 仅命中说明性文字 |
| 20 | **第二轮**：机场按 UA 返回模板的订阅可正常导入 | PASS | 见 §4.8；`UA=ClashMetaForAndroid/2.11.7.Meta` → 35,114 字节 / **67 节点**，配置校验通过，内核 pid=9826 |
| 21 | **第二轮**：节点列表与自由选择 | PASS | 读取 8 个策略组 / 81 个节点项；`策略组「主代理」已切换为「DIRECT」`；[08-nodes.png](docs/screenshots/08-nodes.png)、[09-nodes-expanded.png](docs/screenshots/09-nodes-expanded.png) |
| 22 | **第二轮**：日志下载 | PASS | `Download/RootTunClash/RootTunClash-log-20261121-183040.txt`（2554 字节）；[10-log-download.png](docs/screenshots/10-log-download.png) |
| 23 | **第二轮**：订阅 UA 可在设置中修改 | PASS | 「设置 → 订阅 User-Agent」输入框 + 恢复默认；修改后下一次刷新立即生效 |
| 24 | **第三轮**：L1 VPN 状态不可见 | PASS | 探针 `RESULT_1_VPN_TRANSPORT_DETECTED=NO`；`dumpsys vpn_management` 只有 `[Legacy VPN]` |
| 25 | **第三轮**：mihomo 不开放任何本地 TCP/UDP 监听 | PASS | `ss` → `NO_TCP_LISTENERS` / `NO_UDP_LISTENERS`；控制器为 unix socket |
| 26 | **第三轮**：目标应用连不上 127.0.0.1 代理端口 | PASS | 探针 `RESULT_4_PROXY_PORT_REACHABLE=NO`（7890/9090/1053 均 ECONNREFUSED，UDP 1053 EPERM） |
| 27 | **第三轮**：节点选择在无 TCP 控制器下仍可用 | PASS | 经 unix socket 读取 2 策略组/10 节点，并成功 `策略组「PROXY」已切换为「DIRECT」` |
| 28 | **第三轮**：L2 原生接口隐藏 | **未达成**（如实记录） | 探针 `RESULT_2=YES` / `RESULT_3=YES`：`net0`（p2p、198.19.0.0）与路由可见；单 APK 无法拦截，方案见 [STEALTH.md](STEALTH.md) §5 |
| 29 | **第四轮**：MTU 9000 迁移与固定 | PASS | 模拟老用户 prefs：`TUN MTU 已从 9000 迁移为 1500` + `TUN 接口 net0 的 MTU 已固定为 1500`；`ip link` 实测 `mtu 1500` |
| 30 | **第四轮**：TUN 默认 IPv6 ULA 清理 | PASS | `ip -6 addr add fdfe:dcba:9876::1/126 dev net0` 后执行删除路径验证，删除后无全局 IPv6；mihomo 1.19 的 `inet6-address: []` 实测无效 |
| 31 | **第四轮**：探针区分「不可读」与「干净」 | PASS | Android 16 实机 `/proc/net/*` 为 EACCES；探针汇总改为 `UNKNOWN (UNREADABLE)`，并新增 `FINGERPRINTS` 行 |

---

## 4. 关键日志与证据

完整日志见 [docs/verification/app-run.log](docs/verification/app-run.log)（第一轮最终 APK 的完整运行日志）；
第二轮的订阅修复 / 节点选择 / 日志下载证据见
[docs/verification/app-run-round2.log](docs/verification/app-run-round2.log)
（其中订阅域名与 token 已替换为 `<机场域名>` / `<订阅token>`）。

### 4.1 启动：Root → 内核 → TUN

```text
18:02:58 INFO/CORE: 步骤 1/7：检查 Root 权限
18:02:58 INFO/ROOT: su 路径：/system/bin/su（exists=true, canExecute=true）
18:02:58 INFO/ROOT: Root 检查通过：uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0（su -c 通道）
18:02:58 INFO/ROOT: libsu Shell 已就绪（Shell.isRoot=true），优先使用 libsu 执行命令
18:02:58 INFO/CORE: 步骤 2/7：准备 mihomo 内核与地理数据库
18:02:58 INFO/MIHOMO: Mihomo Meta v1.19.34 android amd64 with go1.26.11 Wed Nov 11 06:51:48 UTC 2026
18:02:58 INFO/CORE: 配置已写入 /data/user/0/com.roottun.clash/files/clash/mihomo/config.yaml（1206 字符，2 个节点，stack=gvisor）
18:02:58 INFO/MIHOMO: configuration file .../config.yaml test is successful
18:02:58 INFO/CORE: mihomo 配置校验通过
18:02:58 INFO/TUN: /dev/net/tun 可用（节点已存在）
18:02:58 INFO/MIHOMO: [TUN] Tun adapter listening at: mihomo0([198.18.0.1/30],[]), mtu: 9000, auto route: true, ... ip stack: gVisor
18:02:58 INFO/MIHOMO: 内核就绪：pid=8394, stack=gvisor, controller=127.0.0.1:9090, tun=mihomo0
18:02:58 INFO/CORE: 检测到 mihomo auto-route 策略路由（fwmark 0x2023 / table 2022）
18:02:58 INFO/TUN: 已为应用自身（uid=10020）添加绕过 TUN 的策略路由：lookup wlan0（pref 8100）
18:02:58 INFO/CORE: 代理已启动：TUN=mihomo0, stack=gvisor, pid=8394, mixed-port=7890, controller=127.0.0.1:9090
```

（时间戳在本报告里做了截取；真实毫秒级时间见日志文件。）

运行中的系统状态（`adb shell su -c ...`）：

```text
13: mihomo0: <POINTOPOINT,MULTICAST,NOARP,UP,LOWER_UP> mtu 9000 ... qlen 500
9000: from all iif mihomo0 goto 9010
9001: not from all iif lo lookup 2022
9010: from all nop
8100: from all uidrange 10020-10020 lookup wlan0     # 应用自身流量绕过 TUN
table 2022: default dev mihomo0
pidof mihomo: 7455
```

### 4.2 流量确实被 TUN 捕获

```text
$ adb shell ping -c 2 223.5.5.5
2 packets transmitted, 2 received, 0% packet loss        # ICMP 经 TUN → mihomo → DIRECT

$ adb shell "curl -sS -o /dev/null -w '%{http_code}' https://www.baidu.com"
200
$ adb shell "curl -sS -o /dev/null -w '%{http_code}' http://connectivitycheck.gstatic.com/generate_204"
204

mihomo:
[ICMP] icmp 198.18.0.1:0 --> 223.5.5.5:0 using DIRECT
[TCP] 198.18.0.1:50450 --> www.baidu.com:443 match Match using DIRECT
[TCP] 198.18.0.1:60196 --> connectivitycheck.gstatic.com:80 match Match using DIRECT
```

使用带规则的订阅时，命中规则同样可在日志中确认：

```text
INFO/MIHOMO: Load MMDB file: .../clash/mihomo/geoip.metadb
INFO/MIHOMO: [TCP] 198.18.0.1:50606 --> www.baidu.com:443 match GeoIP(cn) using DIRECT
```

非中国域名走 PROXY 组（测试节点是本地假节点，连接失败属于预期）：

```text
WARN/MIHOMO: [TCP] dial PROXY (match Match/) 198.18.0.1:57166 --> www.google.com:443 error: dial tcp 104.244.42.197:443: i/o timeout
```

### 4.3 订阅解析与配置生成

三种格式均验证通过（测试订阅样例保存在 [docs/testdata/](docs/testdata/)；base64 解析在最终 APK 日志中，YAML 与分享链接解析在紧邻的前一次运行中验证，解析代码此后未再改动）：

```text
INFO/SUBSCRIPTION: 订阅「10.0.2.2/sub_yaml.yaml」更新成功：2 个节点
INFO/SUBSCRIPTION: 检测到 base64 包裹的 Clash YAML，已解码
INFO/SUBSCRIPTION: 订阅「10.0.2.2/sub-base64.txt」更新成功：2 个节点
INFO/SUBSCRIPTION: 检测到明文分享链接列表，开始逐条解析
INFO/SUBSCRIPTION: 订阅「10.0.2.2/sub_links.txt」更新成功：5 个节点
```

分享链接生成的 mihomo 配置（节选，`mihomo -t` 校验通过后才会启动）：

```yaml
proxies:
  - { name: "测试-SS节点", type: ss, server: 127.0.0.1, port: 18392, cipher: "aes-128-gcm", password: "test-pass", udp: true }
  - { name: "测试-Trojan节点", type: trojan, server: 127.0.0.1, port: 18443, password: "trojan-pass", udp: true, sni: example.com, skip-cert-verify: true }
  - { name: "测试-VMess节点", type: vmess, ..., network: ws, ws-opts: { path: /ws, headers: { Host: example.com } }, tls: true, servername: example.com }
  - { name: "测试-VLESS节点", type: vless, ..., tls: true, servername: example.com, network: ws, ws-opts: { path: /vless } }
  - { name: "测试-Hy2节点", type: hysteria2, server: 127.0.0.1, port: 18446, password: hy2pass, sni: example.com, skip-cert-verify: true }
```

订阅自带的顶层键（`port`、`socks-port`、`external-controller`、`tun`…）会被剥离，运行期键由应用覆盖：

设备侧逐键计数（`docs/verification/sectioncheck.sh`）确认运行期键只出现一次、订阅遗留的
`port` / `socks-port` 已被剥离：

```text
mixed-port=1        port=0             socks-port=0
external-controller=1  tun=1           dns=1
proxies=1           proxy-groups=1     rules=1        geodata-mode=1
```

### 4.4 切换活动订阅会自动重启内核

```text
INFO/SUBSCRIPTION: 活动订阅已切换为「10.0.2.2/sub-base64.txt」
INFO/CORE: 活动订阅已切换为「10.0.2.2/sub-base64.txt」
INFO/CORE: 代理正在运行，重启内核以应用新的活动订阅
INFO/CORE: 开始停止代理（切换活动订阅）
INFO/CORE: 配置已写入 .../config.yaml（1206 字符，2 个节点，stack=gvisor）
INFO/MIHOMO: 内核就绪：pid=8394, stack=gvisor, controller=127.0.0.1:9090, tun=mihomo0
```

切换后的配置已变成新订阅的节点（`本地链路-A` / `本地链路-B`），TUN 与新 pid 均正常。

### 4.5 停止：回滚与清理

```text
INFO/CORE: 开始停止代理（用户停止）
INFO/TUN: 开始回滚 iptables / 策略路由（tun=mihomo0）…
INFO/TUN: iptables / 策略路由回滚完成
INFO/MIHOMO: 终止进程 pid=8394
WARN/MIHOMO: mihomo 进程已退出（exit=0）
INFO/MIHOMO: 进程 pid=8394 已退出
INFO/MIHOMO: mihomo 内核已停止
DEBUG/SERVICE: 前台服务已销毁
INFO/CORE: 代理已停止，iptables / 策略路由已回滚
```

停止后用 [statecheck.sh](docs/verification/statecheck.sh) 逐项核对：

```text
== tun device ==            Device "mihomo0" does not exist.
== mihomo pid ==            （空）
== ip rules (8100 / 2022) == （空）
== table 2022 ==            （空）
== iptables chain TUNCLASH == iptables: No chain/target/match by that name.
```

停止后网络恢复正常：

```text
$ adb shell "curl -sS --max-time 10 -o /dev/null -w '%{http_code}' https://www.baidu.com"
200
```

### 4.6 没有 Root 时的行为（早期未授权状态）

首次安装、尚未在 MuMu 授权时（同一套 Root 校验逻辑）：

```text
INFO/ROOT: 10020
ERROR/ROOT: 未获得 Root 权限：su 返回 uid=10020，未取得 Root
ERROR/CORE: 启动失败
ERROR/CORE:   java.lang.IllegalStateException: 未获得 Root 权限，无法创建 TUN 设备。请在设备的 Root 管理器（MuMu：设置 → 开启 Root 权限）中授权后重试。
WARN/CORE: 启动流程未完成，开始回滚网络状态
```

应用没有崩溃，界面显示错误卡片，并提供「重新检测 Root 权限」入口；日志控制台同时记录了
libsu 只拿到非 Root shell 的情况：

```text
WARN/ROOT: libsu 只拿到非 Root shell（Shell.isRoot=false），已丢弃，避免特权命令降级执行
```

### 4.7 订阅错误可诊断

```
ERROR/SUBSCRIPTION: 订阅「10.0.2.2/does_not_exist.yaml」更新失败：HTTP 状态码 404 File not found，响应：<!DOCTYPE HTML>
ERROR/SUBSCRIPTION: 失败块之后日志继续输出响应片段（<h1>Error response</h1> ...）
```

订阅卡片同时显示「最近错误：…」，不会只显示「失败」两个词。

### 4.8 第二轮：机场按 User-Agent 返回模板导致订阅校验失败（已修复）

**现象**（用户实机截图）：导入某个机场订阅后启动代理，日志出现

```text
level=error msg="proxy group[0]: jp自动切换: '/日本/' not found"
configuration file /data/user/0/com.roottun.clash/files/clash/mihomo/config.yaml test failed
ERROR/CORE: mihomo 配置校验失败（exit=1）
```

**根因定位**：该机场按 User-Agent 返回不同内容的订阅。

| 请求 UA | 响应 |
|---|---|
| `mihomo/1.19 (RootTunClash/1.0)`（旧版本默认） | 3,944 字节「模板」：`proxies: []`，代理组写 `proxies: [/日本/]`、`/^(?!…).*$/` —— mihomo 不认识 `/正则/` 代理写法，报 `'/日本/' not found` |
| `ClashMetaForAndroid/2.11.7.Meta`（新默认） | 35,114 字节完整配置：54 个真实节点 + 明确写出的代理组成员 |
| `clash.meta/1.19.34` / `Clash Verge/2.0.0` | 同样是完整配置（35,114 字节） |
| `mihomo/1.19.34` | base64 分享链接列表（可被本项目的 Links 分支解析，但格式不同） |
| 无 UA | 15 字节（直接被拒绝） |

**修复**：

1. 默认 UA 改为 `ClashMetaForAndroid/2.11.7.Meta`（不再包含会被识别为旧版 Clash 的 `Clash/版本号`）；
2. 订阅拉取按「自定义 UA → CMFA → clash.meta → Clash Verge」依次重试；
3. 每份内容先过 `ConfigSanity`（空节点 / `/正则/` 代理组直接判为模板），不通过就换下一个 UA；
4. 设置页新增「订阅 User-Agent」，可手动指定并恢复默认。

**验证**（MuMu，使用用户提供的同一订阅，仅做短时间验证，标识已脱敏）：

```text
INFO/SUBSCRIPTION: 拉取订阅「<机场域名>/<订阅token>」：https://<机场域名>/s/<订阅token>（UA=ClashMetaForAndroid/2.11.7.Meta）
INFO/SUBSCRIPTION: HTTP 200 ← ...（UA=ClashMetaForAndroid/2.11.7.Meta，Content-Type=text/yaml; charset=UTF-8）
DEBUG/SUBSCRIPTION: 响应为 gzip 编码，自动解压
INFO/SUBSCRIPTION: 下载完成：35114 字节，字符集=UTF-8
INFO/SUBSCRIPTION: 订阅「…」更新成功：67 个节点（UA=ClashMetaForAndroid/2.11.7.Meta）
...
INFO/CORE: mihomo 配置校验通过
INFO/MIHOMO: 内核就绪：pid=9826, stack=gvisor, controller=127.0.0.1:9090, tun=mihomo0
INFO/CORE: 代理已启动：TUN=mihomo0, stack=gvisor, pid=9826, mixed-port=7890, controller=127.0.0.1:9090
```

同一份订阅用修复前的逻辑（`UA=…RootTunClash/1.0`）会走「模板」分支并被 `ConfigSanity` 标记为
「代理组使用了 mihomo 不支持的 /正则/ 代理写法」——修复后不再命中该分支。

### 4.9 第二轮：节点自由选择与测速

启动代理后打开「节点」页（数据来自 mihomo 控制器 `GET /proxies`）：

```text
INFO/NODES: 已读取 8 个策略组、81 个节点/分组
INFO/NODES: 策略组「主代理」已切换为「DIRECT」     # PUT /proxies/主代理 {"name":"DIRECT"}
```

- 8 个策略组按「手动选择（Selector）→ 自动测速（URLTest）→ 故障转移（Fallback）→ 负载均衡」排序；
- 展开「主代理」可看到成员及其类型（URLTest / LoadBalance / Fallback / Direct），单选按钮切换；
- 非 Selector 组会提示「由 mihomo 自动选择节点，不能手动指定」；
- 「测速」按组触发 `GET /proxies/{name}/delay?timeout=5000&url=http://www.gstatic.com/generate_204`，
  结果以 ms / 超时显示，颜色来自主色/三级色/错误色（无硬编码颜色）；
- 选择结果由 `profile.store-selected: true` 持久化，停止/重启后仍保留。

截图：[08-nodes.png](docs/screenshots/08-nodes.png)、[09-nodes-expanded.png](docs/screenshots/09-nodes-expanded.png)。

> 说明：验证时使用的是用户提供的真实订阅，为控制流量仅启动内核约 1 分钟并切换节点后立即停止；
> 真实出口连通性由用户自行验证。

### 4.10 第二轮：下载日志

日志页右上角「下载日志」：

```text
INFO/LOG: 日志已导出到 Download/RootTunClash/RootTunClash-log-20261121-183040.txt（2554 字节）
```

`adb shell ls -l /sdcard/Download/RootTunClash/` 确认文件存在：

```text
-rwxrwxrwx 1 root root 2554 2026-11-21 18:30 RootTunClash-log-20261121-183040.txt
```

导出内容 = 应用/设备信息头 + 最多 5000 行日志（时间戳、级别、来源标签、正文）；
Android 10+ 通过 MediaStore 写入公共「下载」目录，无需存储权限。截图：
[10-log-download.png](docs/screenshots/10-log-download.png)。

### 4.11 第三轮：代理隐藏——L1 与 L3（目标应用视角探针）

探针应用 `com.roottun.probe`（普通应用权限，非 root）在代理运行期间读取报告，
完整文件：[probe-running.txt](docs/verification/probe-running.txt)；停止后的基线：
[probe-stopped.txt](docs/verification/probe-stopped.txt)。

**L1：VPN 状态不可见**

```text
active.hasVpn=false hasWifi=true hasCellular=false hasEthernet=false
RESULT_1_VPN_TRANSPORT_DETECTED=NO
```

```text
$ adb shell dumpsys vpn_management
VPNs:
  0: [Legacy VPN]
```

**L3：没有任何本地代理端口可连**

mihomo 运行中 `ss` 结果（root）：

```text
== all mihomo listeners ==
NO_TCP_LISTENERS
NO_UDP_LISTENERS
srw-rw-rw- 1 root root 0 .../files/clash/mihomo/ctl.sock
```

探针的端口探测：

```text
tcp 127.0.0.1:7890 -> REFUSED/REJECTED (ECONNREFUSED)
tcp 127.0.0.1:9090 -> REFUSED/REJECTED (ECONNREFUSED)
tcp 127.0.0.1:1053 -> REFUSED/REJECTED (ECONNREFUSED)
udp 127.0.0.1:1053 -> ERROR IOException: sendto failed: EPERM
RESULT_4_PROXY_PORT_REACHABLE=NO
```

配置侧对应：

```yaml
mixed-port: 0
external-controller: ""
external-controller-unix: /data/user/0/com.roottun.clash/files/clash/mihomo/ctl.sock
# 不写 dns.listen（DNS 由 TUN dns-hijack 处理）
```

双保险的 iptables 回环封堵（不依赖 owner 匹配）：

```text
-N TUNCLASH_LOCAL
-A TUNCLASH_LOCAL -d 127.0.0.1/32 -p tcp --dport 7890 -j REJECT
-A TUNCLASH_LOCAL -d 127.0.0.1/32 -p udp --dport 7890 -j REJECT
-A TUNCLASH_LOCAL -d 127.0.0.1/32 -p tcp --dport 1053 -j REJECT
-A TUNCLASH_LOCAL -d 127.0.0.1/32 -p udp --dport 1053 -j REJECT
-A OUTPUT -j TUNCLASH_LOCAL
```

**节点选择仍可用**（控制器改为 unix socket 后）：

```text
INFO/NODES: 已读取 2 个策略组、10 个节点/分组
INFO/NODES: 策略组「PROXY」已切换为「DIRECT」
```

### 4.12 第三轮：L2 原生接口隐藏——未达成（如实记录）

同一份探针报告：

```text
iface=net0           up=true  p2p=true  mtu=1500  addrs=fe80::…%net0 198.19.0.0  <-- POINT-TO-POINT
iface=wlan0          up=true  p2p=false mtu=1500  addrs=fe80::…%wlan0 10.0.2.15
suspiciousInterfaces(name or p2p)=[net0]
RESULT_2_VPN_INTERFACE_DETECTED=YES

SUSPICIOUS ROUTE: net0  000013C6  00000000  0001 … FCFFFFFF …
RESULT_3_VPN_ROUTE_VISIBLE=YES
```

- 仅按 `tun|wg|ppp|vpn` 名称的幼稚检测已被中性名 `net0` 规避；
- 但点对点标志、`198.19.0.0/30` 地址、`/proc/net/route`、`/proc/net/if_inet6` 仍然可见；
- 这是单 APK 无法解决的层（需要 kmod/KPM 或 Zygisk 注入），本轮**不实现**，也**不通过增加
  用户态钩子来掩盖**。完整分析与下一轮方案（KPM 钩子点、Zygisk 风险、单 APK 约束冲突）见
  [STEALTH.md](STEALTH.md) §2、§5。

### 4.13 第三轮：停止后的隐藏状态回滚

```text
INFO/CORE: 代理已停止，iptables / 策略路由已回滚
```

```text
ip link show net0        -> Device "net0" does not exist.
pidof mihomo             -> （空）
iptables -S TUNCLASH_LOCAL -> No chain/target/match by that name.
ls .../mihomo/ctl.sock   -> No such file or directory
```

停止后再次运行探针（基线）：`RESULT_1..5` 全部为 `NO`，接口列表只剩 `wlan0`、`lo`，
见 [probe-stopped.txt](docs/verification/probe-stopped.txt)。

### 4.14 第四轮：实机探针（Android 16）的解读与修复

用户在其 OnePlus PLR110（Android 16 / API 36 / arm64）上运行探针，原始结果的要点：

```text
active.hasVpn=false … network=100 vpn=false ifaces=rmnet_data1; network=107 vpn=false ifaces=wlan0
iface=net0 up=true p2p=true mtu=9000 addrs=fe80::…%net0 fdfe:dcba:9876:… 198.19.0.0  <-- POINT-TO-POINT
/proc/net/route -> EACCES (Permission denied)
/proc/net/ipv6_route -> EACCES
/proc/net/if_inet6 -> EACCES
tcp 127.0.0.1:7890/9090/1053 -> ECONNREFUSED; udp 1053 -> EPERM
RESULT_1=NO  RESULT_2=YES  RESULT_3=NO  RESULT_4=NO  RESULT_5=NO
```

**逐项结论**

| 项 | 实机值 | 结论 |
|---|---|---|
| L1 VPN | `hasVpn=false`；`dumpsys vpn_management` 仅 `[Legacy VPN]` | ✅ 通过 |
| L2 接口 | `net0 p2p=true mtu=9000 addrs=fe80… 198.19.0.0` | ❌ 未达成（单 APK 无法隐藏；名字 `net0` 只规避幼稚字符串匹配） |
| L3 端口 | 7890/9090/1053 全部 `ECONNREFUSED`，UDP 1053 `EPERM` | ✅ 通过（内核零监听 + iptables 封堵） |
| R3/R5 | `/proc/net/*` 全部 `EACCES` | ⚠️ **不可读，不等于干净**；Android 16 收紧了 SELinux |
| 其他接口 | `rmnet_data* / dummy0 / ifb*` | 设备/系统自带接口，与本应用无关 |

**本轮修复（实机报告驱动的两个真问题）**

1. **MTU 仍是 9000**：旧版本默认 `tunMtu=9000` 已被写进 SharedPreferences，新默认值 1500 不会覆盖
   已存在的键。修复：`AppSettings` 对持久化的 9000 做强迁移；启动后再执行
   `ip link set dev net0 mtu 1500` 兜底。模拟老用户 prefs 实测：

   ```text
   INFO/SETTINGS: TUN MTU 已从 9000 迁移为 1500（避免 9000 指纹）
   INFO/TUN: TUN 接口 net0 的 MTU 已固定为 1500
   $ ip link show net0  →  mtu 1500
   ```

2. **TUN 默认 IPv6 ULA `fdfe:dcba:9876::/…`**：mihomo 1.19.34 实测**不支持**通过
   `tun.inet6-address: []` 关闭（`/configs` 中无该字段，`inet4-address` 也被忽略，地址始终由
   fake-ip-range 推导）。修复：启动后 `NetGuard.hardenTunInterface()` 删除
   `ip -6 addr show dev net0 scope global` 列出的全局 IPv6，仅保留 `fe80::/64`。在 MuMu 上
   注入 `fdfe:dcba:9876::1/126` 后执行删除路径，删除后无全局 IPv6。

**探针语义修复**

Android 16 上 `/proc/net/*` 读取失败时，旧探针会输出 `RESULT_3=NO`，容易被误读为“路由不可见”。
现在：

```text
RESULT_3_VPN_ROUTE_VISIBLE=UNKNOWN (UNREADABLE)
RESULT_5_IF_INET6_LEAK=UNKNOWN (UNREADABLE)
FINGERPRINTS=[net0:fake-ip-198.1x]
```

`FINGERPRINTS` 会标记 `mtu9000`、`fdfe:dcba:9876`、`198.18.x` 等已知特征；本轮 MuMu 报告见
[probe-running-round4.txt](docs/verification/probe-running-round4.txt)。

> 说明：`198.19.0.0/30` 仍落在经典 Clash fake-ip 段 `198.18.0.0/15` 内，因此探针会把它列为指纹。
> 这是 mihomo TUN 地址由 fake-ip-range 推导导致的残留特征；要彻底消除需要换掉 fake-ip 机制
> （会影响 DNS 行为），本轮不做。

---

## 5. 截图

| 截图 | 说明 |
|---|---|
| [01-home-stopped.png](docs/screenshots/01-home-stopped.png) | 干净安装后的首页，状态「已停止」 |
| [02-root-request-dialog.png](docs/screenshots/02-root-request-dialog.png) | MuMu 首次 Root 授权弹窗（已勾选「永久记住选择」） |
| [03-home-running.png](docs/screenshots/03-home-running.png) | 运行中：TUN mihomo0 / pid / Mixed 端口 / 内核版本 / 运行时长 |
| [04-log-console-all.png](docs/screenshots/04-log-console-all.png) | 日志控制台（全部级别），时间戳+级别+标签+正文 |
| [05-log-console-error-filter.png](docs/screenshots/05-log-console-error-filter.png) | 级别过滤（ERROR）与空态提示 |
| [06-home-stopped-after-cleanup.png](docs/screenshots/06-home-stopped-after-cleanup.png) | 停止并完成回滚后的首页 |
| [07-subscriptions.png](docs/screenshots/07-subscriptions.png) | 订阅页（第一轮测试订阅的中间态） |
| [08-nodes.png](docs/screenshots/08-nodes.png) | 「节点」页：策略组列表、当前节点、测速入口（第二轮） |
| [09-nodes-expanded.png](docs/screenshots/09-nodes-expanded.png) | 展开「主代理」：成员类型、单选切换、逐节点测速（第二轮） |
| [10-log-download.png](docs/screenshots/10-log-download.png) | 日志页「下载日志」与 Snackbar 路径提示（第二轮） |
| [11-nodes-unixsocket.png](docs/screenshots/11-nodes-unixsocket.png) | 控制器改为 unix socket 后，「节点」页仍可读取策略组（第三轮） |
| [12-probe-report.png](docs/screenshots/12-probe-report.png) | 目标应用视角探针报告：L1/L3 通过，L2 接口仍可见（第三轮） |
| [13-home-stealth.png](docs/screenshots/13-home-stealth.png) | 运行中首页：TUN `net0`、本地监听「无（仅 TUN + unix socket）」 |

---

## 6. 验证期间发现并修复的问题

1. **GEOIP 规则触发内核联网下载 `GeoIP.dat`，卡住 90 秒**
   现象：带 `GEOIP,CN,DIRECT` 的订阅在 `mihomo -t` 阶段卡住，日志出现
   `failed to decode geoip file: GeoIP.dat` → `can't download GeoIP.dat: context deadline exceeded`。
   原因：配置里写了 `geodata-mode: true`（该开关要求经典 `GeoIP.dat`），而随包数据是 `geoip.metadb`。
   修复：改为 `geodata-mode: false`，`mihomo -t` 立即通过并加载 `geoip.metadb`。

2. **libsu 静默降级成非 Root shell**
   现象：`id -u` 返回 `10020`，iptables 在 `u:r:untrusted_app` 域执行（实际没有 Root）。
   修复：每次 Root 检查重建 shell 并校验 `id -u`，非 Root shell 立即丢弃并回退 `su -c`；
   同时补充 `ACCESS_SUPERUSER` 权限声明，MuMu 授权框不再提示权限未定义。

3. **MuMu 上 `/system/xbin/su` 对普通应用不可执行**
   现象：直接使用 PATH 里的 `su` 可能命中 `4750 root:shell` 的二进制。
   修复：按候选列表选择当前 uid 真正 `canExecute()` 的 su（实测为 `/system/bin/su`）。

4. **TUN 接管后应用自身无法拉取订阅**
   现象：节点不可用时应用订阅请求被自己代理，报 `unexpected end of stream`。
   修复：为应用 uid 添加 `ip rule pref 8100 uidrange <uid> lookup <默认网卡>`，
   让管理流量绕过 TUN；停止时一并删除。

5. **切换活动订阅没有生效**
   现象：切换订阅后内核仍在跑旧配置。
   修复：切换活动订阅时若代理正在运行，自动重启内核（见 §4.4）。

6. **停止顺序**
   现象：原先在终止内核前就删除 TUN 设备，内核日志会出现设备消失。
   修复：改为「iptables/路由 → 终止内核 → 兜底删除 TUN 设备 → 停前台服务」。

7. **重复添加同一 URL 订阅会产生多条记录**
   修复：URL 相同则复用已有条目并改为刷新。

8. **（第二轮，用户实机反馈）机场按 User-Agent 返回模板订阅**
   现象：`proxy group[0]: jp自动切换: '/日本/' not found` → `mihomo 配置校验失败`。
   根因：UA `…RootTunClash/1.0` 被机场识别为旧版 Clash，返回 `proxies: []` +
   `proxies: [/日本/]` 模板；mihomo 不支持这种正则代理写法。
   修复：默认 UA 改为 `ClashMetaForAndroid/2.11.7.Meta`，增加多 UA 重试、`ConfigSanity`
   结构检查与「订阅 User-Agent」设置；同一订阅现可正常导入 67 个节点并通过配置校验（§4.8）。

9. **（第二轮，需求）新增功能**
   - 「节点」页：通过 mihomo 控制器读取策略组、自由切换 Selector 组节点、逐节点/整组测速（§4.9）；
   - 日志页「下载日志」：导出完整日志到 `Download/RootTunClash/`（§4.10）；
   - 设置页新增订阅 UA，并保持 MD3 / SPEC_2025 视觉不变。

10. **（第三轮）iptables `owner` 扩展缺失导致 UID 放行规则静默失败**
    现象：本机 iptables/ip6tables 报 `Couldn't find match 'owner'`，放行规则未生效，
    REJECT 规则把应用自身的控制器连接也拦掉了（`NODES 读取 /proxies 失败：ConnectException`）。
    处理：不叠加更多钩子掩盖，而是改架构——mihomo 不监听任何本地端口（`mixed-port: 0`、
    控制器改 unix socket、不设 `dns.listen`），iptables 只做无条件的端口封堵；
    owner 匹配不再被依赖（§4.11）。

11. **（第三轮）控制器 unix socket 的两处协议细节**
    - 健康检查仍按 TCP 9090 探测 → gvisor 启动被误判失败并降级 system；
      改为检测 unix socket 文件存在后恢复 gvisor（日志 `stack=gvisor`）。
    - mihomo 对 `/proxies` 使用 `Transfer-Encoding: chunked`，最初的极简 HTTP 客户端
      把 chunk 长度行当成 JSON → `JSONException`；已实现 chunked 解码（§4.11 节点读取成功）。

12. **（第三轮）mihomo 1.19 移除了 `global-client-fingerprint`**
    现象：内核日志报 `The global-client-fingerprint configuration is removed`。
    处理：删除该全局项，改为对分享链接生成的 vmess/vless/trojan 节点写入
    `client-fingerprint: chrome`（uTLS）；YAML 订阅节点保持订阅自身设置（[STEALTH.md](STEALTH.md) §4）。

---

## 7. 已知限制

- **L2 原生接口隐藏未达成（第三轮，如实记录）**：单 APK 无法拦截 netlink / ioctl / `/proc/net/*`，
  目标应用仍能看到 `net0`（p2p、`198.19.0.0/30`）。中性接口名只规避幼稚的字符串检测。
  下一轮的 KPM/kmod/Zygisk 方案与风险见 [STEALTH.md](STEALTH.md) §5；本轮不实现、也不以更多用户态
  钩子掩盖这一层。
- **Android 16 的 `/proc/net/*` 对普通应用返回 EACCES**：实机探针的 R3/R5 是「不可读」，
  不能当作隐藏成功；接口枚举（`NetworkInterface`）仍然能看到 `net0`。
- **残留指纹**：`198.19.0.0/30` 仍属于 `198.18.0.0/15` fake-ip 段，探针会将其列为已知指纹；
  TUN 全局 IPv6 已由启动后处理清除，但 MTU/接口枚举仍可暴露 p2p 性质。
- **L3 的边界**：mihomo 已不开放任何本地 TCP/UDP 监听，iptables 回环封堵只是双保险；
  如果未来把 `mixed-port` 改回非 0 或恢复 `dns.listen`，则需要重新验证端口暴露。
- **MuMu 之外的实机验证由用户完成**：APK 同时携带 arm64 内核；用户 OnePlus PLR110
  （Android 16 / KernelSU）上的验证除已修复的 UA 订阅问题外均通过。
- **SAF 本地文件**：已验证能拉起系统文件选择器（DocumentsUI），但自动化脚本没有完成「选中文件」这一步；
  导入代码与 URL 订阅共用同一解析/落盘路径。
- **内核随应用进程**：`force-stop` / `pm clear` 会结束内核进程；下次启动自动清理残留规则
  （实测重启后 `net0`、`TUNCLASH_LOCAL`、ip rule、`ctl.sock` 均被清理）。
- **节点选择范围**：手动切换仅对 Selector 组生效；URLTest / Fallback / LoadBalance 由内核自动决定。
  测速使用 `http://www.gstatic.com/generate_204`，节点若无法访问该地址会显示「超时」。
- **真实订阅验证范围**：第二轮用用户提供的真实订阅完成了「拉取 → 67 节点 → 配置校验 → 启动内核 →
  读取策略组 → 切换节点 → 停止」的短时间验证；未做持续流量 / 真实出口质量测试。
- **服务端识别是概率问题**：uTLS 指纹（仅分享链接生成的节点）与 fake-ip 段调整只能降低被识别概率，
  无法保证；YAML 订阅节点的 TLS 参数由订阅自身决定。
- **手工策略路由分支未在本次触发**：MuMu 上 mihomo `auto-route` 正常工作；且该设备 iptables 缺少
  `owner` 扩展，回退脚本若被触发会安全失败并在日志中说明。
- 功能边界内未包含：分应用代理、复杂分流编辑、WebUI、在线内核更新、模块打包。
