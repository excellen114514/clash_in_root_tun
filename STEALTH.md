# STEALTH.md — 代理隐藏的分层设计与实测结论

目标：让目标应用**不知道自己处在代理之下**，并让远端服务器**更难判定这是代理流量**。
按「每一层独立可验证」的原则，本文件记录每层做了什么、实测结果、以及**明确没做到**的部分。

> 单 APK 约束：本项目不使用 LSPosed / Zygisk 模块。内核级原生过滤（kmod / KPM）不在本轮实现范围内，
> 方案与代价见 §5，建议下一轮单独评估。

---

## 0. 结论速览

| 层 | 目标 | 本轮状态 | 说明 |
|---|---|---|---|
| L1 Java/API | `NetworkCapabilities` 不报告 VPN；`dumpsys vpn_management` 不暴露进程 | ✅ 达成 | 不创建 `VpnService`，系统里根本没有 VPN 网络 |
| L2 原生接口 | `getifaddrs` / `NetworkInterface` / `/proc/net/route` 看不到代理接口 | ❌ **未达成** | 单 APK 无法拦截内核可见性；接口名已改为中性 `net0`，但 p2p 标志、`198.19.0.0/30` 地址仍可见。Android 16 起应用读 `/proc/net/*` 被 EACCES 拒绝，但 `NetworkInterface` 枚举仍能看到 `net0` |
| L3 本机端口 | 目标应用连不上 `127.0.0.1` 上的 mihomo 端口 | ✅ 达成（最强形态） | mihomo **不监听任何 TCP/UDP 端口**：`mixed-port: 0`、控制器走 unix socket、不设 `dns.listen`；另有 iptables 回环封堵作双保险 |
| L4 远端识别 | 降低服务器判定“这是代理”的概率 | 🟡 部分达成 | 分享链接节点使用 `client-fingerprint: chrome`（uTLS）；fake-ip 段改 `198.19.0.0/16`；MTU 1500；`strict-route`。YAML 订阅节点的 TLS 指纹仍由订阅自身配置决定 |

实测环境：MuMu Player 12 / Android 12（API 32）/ x86_64，mihomo v1.19.34；探针应用 `com.roottun.probe`
（普通应用权限）逐项检查，输出见 [docs/verification/probe-running.txt](docs/verification/probe-running.txt) 与
[docs/verification/probe-stopped.txt](docs/verification/probe-stopped.txt)。

---

## 1. L1：Java / 框架层

- 全工程没有 `VpnService`、没有 `BIND_VPN_SERVICE`、没有 VPN 网络；
- 探针实测：

```text
active.hasVpn=false hasWifi=true hasCellular=false hasEthernet=false
network=100 vpn=false ifaces=wlan0
RESULT_1_VPN_TRANSPORT_DETECTED=NO
```

- `adb shell dumpsys vpn_management` 只有系统占位项，没有本应用进程：

```text
VPNs:
  0: [Legacy VPN]
```

结论：L1 不依赖任何隐藏手段即成立；这也说明后续所有暴露都来自 **原生层与本机端口**。

---

## 2. L2：原生接口过滤（本轮未达成，已定位到具体原因）

### 2.1 目标应用实际能看到什么（代理运行中）

```text
iface=net0           up=true  p2p=true  mtu=1500  addrs=fe80::…%net0 198.19.0.0  <-- POINT-TO-POINT
iface=wlan0          up=true  p2p=false mtu=1500  addrs=fe80::…%wlan0 10.0.2.15
iface=lo             up=true  p2p=false mtu=65536 addrs=::1 127.0.0.1
vpnLikeInterfaces=[]
suspiciousInterfaces(name or p2p)=[net0]

/proc/net/route:
net0  000013C6  00000000  0001  …  FCFFFFFF  …
```

- 仅做「`tun|wg|ppp|vpn` 子串匹配」的幼稚检测会被中性名 `net0` 绕过；
- 但**真正有效的检测**仍成立：点对点接口、`198.19.0.0/30` 地址、`/proc/net/route` 条目、
  `/proc/net/if_inet6` 链路本地地址都能暴露代理的存在。

### 2.2 为什么单 APK 做不到

应用读取这些信息全部走内核路径：`ioctl(SIOCGIFCONF/SIOCGIFFLAGS)`、netlink
（`RTM_GETLINK/RTM_GETADDR/RTM_GETROUTE`）、`getifaddrs`、`/proc/net/*`。它们不经过应用进程里任何
可被 Hook 的函数——除非：

1. 在**内核层**过滤（kmod / KPM）；或
2. 在目标进程内注入并 Hook libc（Zygisk / LSPosed），且能被直接系统调用绕过，还可能被检测到注入。

本工程是「单 APK + Root」交付，且明确不使用 LSPosed/Zygisk 模块，因此 **L2 本轮不实现**。
不做「加更多用户态钩子」来掩盖一个失效层的事——这也是本轮把重心放到 L3/L4 的原因。

### 2.3 本轮已做的有限缓解

- TUN 设备名从 `mihomo0` 改为中性 `net0`（旧设置自动迁移）；
- fake-ip 段改为不常见的 `198.19.0.0/16`（见 L4），地址不再是 `198.18.x.x`；
- 明确文档化：这些只降低“字符串匹配”类检测的成功率，**不构成原生层隐藏**。

---

## 3. L3：本机端口封堵（本轮最强的一项）

### 3.1 设计：不监听 > 封堵

mihomo v1.19.34 实测支持：

- `mixed-port: 0` → 不启动 HTTP/SOCKS 监听；
- `external-controller: ""` + `external-controller-unix: <files/clash/mihomo/ctl.sock>` → 控制器走 unix socket；
- 不写 `dns.listen` → 不启动 1053 端口（DNS 由 TUN 的 `dns-hijack` 在栈内处理）。

运行中实测（`ss`）：

```text
== all mihomo listeners ==
NO_TCP_LISTENERS
NO_UDP_LISTENERS
srw-rw-rw- 1 root root 0 ctl.sock
```

因此目标应用扫描 `127.0.0.1` / `::1` 时**没有任何端口可连**：

```text
tcp 127.0.0.1:7890 -> REFUSED/REJECTED (ECONNREFUSED)
tcp 127.0.0.1:9090 -> REFUSED/REJECTED (ECONNREFUSED)
tcp 127.0.0.1:1053 -> REFUSED/REJECTED (ECONNREFUSED)
udp 127.0.0.1:1053 -> ERROR IOException: sendto failed: EPERM
RESULT_4_PROXY_PORT_REACHABLE=NO
```

### 3.2 应用如何访问控制器

- 控制器不再监听 TCP，改为 `unix domain socket`；
- 应用用 `android.net.LocalSocket` 直接实现最小 HTTP/1.1 客户端
  （`GET /proxies`、`PUT /proxies/{group}`、`GET /proxies/{node}/delay`），支持 `Content-Length` 与
  `Transfer-Encoding: chunked`（mihomo 对部分接口使用 chunked；实测第一版未处理导致 JSON 解析失败，已修复）；
- socket 路径：`/data/user/0/com.roottun.clash/files/clash/mihomo/ctl.sock`，权限 `srw-rw-rw-`，
  root 创建、应用自身可连接；停止时删除，下次启动前再兜底删除。

### 3.3 iptables 双保险（并记录一次失败的重构）

初版实现试图用 `iptables -m owner --uid-owner` 放行 root 与本应用、拦截其它 UID，
但 MuMu（Android 12）的 iptables 缺少 `owner` 扩展：

```text
ip6tables v1.8.7 (legacy): Couldn't find match `owner'
```

结果是放行规则静默失败、REJECT 规则生效，**连自己的控制器都连不上**（节点页报 `ConnectException`）。
这说明“UID 匹配”这一层在该设备上根本不可用，继续加规则只会掩盖问题。最终改为：

- 内核不监听任何端口（主手段，见 3.1）；
- iptables 仅对 `mixed-port` / DNS 端口做**无条件回环封堵**作为防御纵深（不依赖 owner 匹配）：

```text
-N TUNCLASH_LOCAL
-A TUNCLASH_LOCAL -d 127.0.0.1/32 -p tcp --dport 7890 -j REJECT
-A TUNCLASH_LOCAL -d 127.0.0.1/32 -p udp --dport 7890 -j REJECT
-A TUNCLASH_LOCAL -d 127.0.0.1/32 -p tcp --dport 1053 -j REJECT
-A TUNCLASH_LOCAL -d 127.0.0.1/32 -p udp --dport 1053 -j REJECT
-A OUTPUT -j TUNCLASH_LOCAL
```

停止时删除跳转/链，`net0`、控制器 socket 一并清理（见 VERIFICATION §4.12）。

---

## 4. L4：让流量更像普通浏览器流量

| 配置 | 值 | 目的 |
|---|---|---|
| `global-client-fingerprint` | **已移除** | mihomo 1.19 明确报错：该全局项已废弃，需在节点上设置 |
| 节点级 `client-fingerprint` | `chrome`（分享链接生成的 vmess/vless/trojan 节点） | uTLS 模拟 Chrome 握手；YAML 订阅节点保持订阅自身设置 |
| `dns.fake-ip-range` | `198.19.0.0/16` | 不再是常见的 `198.18.0.0/15`，降低以 fake-ip 段反查代理的成功率 |
| `tun.mtu` | `1500` | `9000` 是不自然的取值，易被指纹化 |
| `tun.strict-route` | `true`（可关） | 减少未命中路由导致的真实 IP 泄漏 |
| `tcp-concurrent` / `unified-delay` | `true` | 流量时序更接近普通客户端 |
| `external-controller` | unix socket | 不暴露管理端口（同时服务 L3） |

限制：

- 服务端识别是概率问题，不能“保证不被识别”；IP 信誉、ASN、行为特征不在本应用控制范围内；
- 仅对 TLS 类节点生效；YAML 订阅里若节点写死了自己的指纹/传输参数，本应用不会改写订阅的 proxies 内容；
- `sniffer`、`dns` 解析链等保持 mihomo 默认，未做更激进的伪装。

---

## 5. 下一轮方案（需要确认范围后再实现）

要在 L2 真正达标，必须在**内核层**过滤，且不能破坏系统组件（netd、ConnectivityService）自身的枚举：

### 方案 A：KPM / KernelPatch（推荐）

- 通过 kpatch 加载模块，在 `rtnl_fill_ifinfo` 出口过滤 `net0`、在 `dev_ioctl`
  的 `SIOCGIFCONF/SIOCGIFFLAGS` 路径过滤、并在 `getifaddrs` 使用的 netlink 转储中同样剪除；
- `/proc/net/{route,ipv6_route,if_inet6}` 用 kprobe 在读取时过滤对应行；
- `SO_BINDTODEVICE` / `SO_BINDTOIFINDEX` 对目标接口返回 `ENODEV`；
- 只对「非系统进程」生效（按 uid 白名单放行 netd/system_server），避免破坏系统网络栈；
- 目标进程零足迹（无注入库、无内存修改），`/proc/self/maps` 无异常。

### 方案 B：kmod（GKI 允许时）

钩子点与 A 相同，只是以 `.ko` 形式加载；受 GKI 变体与内核版本限制。

### 方案 C：Zygisk（最后备选）

在目标进程内 inline hook libc 的 `getifaddrs` / `ioctl` / `open`，风险：

- 直接 `syscall()` 可绕过；
- Zygisk 注入本身可能被检测；
- **需要额外的模块 APK，违背“单 APK”约束**，需要单独批准。

> 本轮不实现 A/B/C。若下一轮要做，建议先确认：目标设备内核类型（GKI/KPM 可用性）、
> 是否接受模块化交付、以及“只对指定应用隐藏接口”的白名单策略。

---

## 6. 验证方法（可复现）

1. 安装 `app-debug.apk` 与探针 `probe-debug.apk`；
2. 启动代理；
3. 运行探针：`adb shell am start -W -n com.roottun.probe/.ProbeActivity`；
4. 读取报告：`adb shell run-as com.roottun.probe cat /data/data/com.roottun.probe/files/probe-report.txt`；
5. 系统侧抽查：
   ```bash
   adb shell su -c 'ss -ltnp | grep mihomo; ss -lunp | grep mihomo'
   adb shell dumpsys vpn_management
   ```
6. 停止代理后再次运行探针，作为基线对照。

对比结果（运行中）：`RESULT_1=NO`、`RESULT_2=YES`（L2 未达成）、`RESULT_3=YES`（L2 未达成）、
`RESULT_4=NO`、`RESULT_5=NO`。

## 7. 第四轮：实机探针发现的问题与修复

用户在 OnePlus PLR110（Android 16 / API 36 / arm64）上用探针得到的报告，暴露了两个真实问题：

### 7.1 MTU 仍是 9000（设置迁移遗漏）

```text
iface=net0  up=true p2p=true mtu=9000
```

原因：旧版本默认 `tunMtu = 9000`，只要用户改过任意设置，`AppSettings.update()` 就会把当时所有键
（包括 9000）写进 SharedPreferences；升级后新的默认值 1500 不会覆盖已存在的键。

修复：

- `AppSettings.init()` 对持久化的 `tunMtu == 9000` 做强迁移（该版本没有修改 MTU 的 UI，
  所以 9000 一定来自旧默认值）；
- 启动内核后再执行一次 `ip link set dev net0 mtu 1500` 兜底，确保无论配置从哪来，
  接口的最终 MTU 都是 1500；
- 实测（MuMu，先把 prefs 改成 9000 模拟老用户）：

```text
INFO/SETTINGS: TUN MTU 已从 9000 迁移为 1500（避免 9000 指纹）
INFO/TUN: TUN 接口 net0 的 MTU 已固定为 1500
$ ip link show net0
26: net0: <POINTOPOINT,…> mtu 1500 …
```

### 7.2 TUN 上出现 mihomo 默认 IPv6 ULA 指纹

实机报告里 net0 除了 `fe80::…` 还有一段 `fdfe:dcba:9876:…` 的全局 IPv6（mihomo 的默认
`inet6-address`）。这是可指纹化的固定 ULA。

验证 mihomo 1.19.34 的行为：

- `tun.inet6-address: []` **无效**：`/configs` 返回里根本没有该字段；
- `tun.inet4-address` 同样被忽略：TUN 地址始终由 `fake-ip-range` 推导（`198.19.0.0/30`）。

因此改为**启动后处理**（`NetGuard.hardenTunInterface`）：

1. `ip link set dev <tun> mtu <mtu>` 固定 MTU；
2. 列出 `ip -6 addr show dev <tun> scope global`，逐个 `ip addr del <cidr> dev <tun>`，
   只保留 `fe80::/64` 链路本地地址。

在 MuMu 上验证删除路径有效：

```text
$ ip -6 addr add fdfe:dcba:9876::1/126 dev net0   # 人为注入
    inet6 fdfe:dcba:9876::1/126 scope global
$ ip addr del fdfe:dcba:9876::1/126 dev net0
AFTER_DEL
（无输出，全局 IPv6 已删除）
```

### 7.3 Android 16 的 `/proc/net/*` 语义：不可读 ≠ 干净

实机探针：

```text
--- /proc/net/route ---
cannot read: java.io.FileNotFoundException: /proc/net/route: open failed: EACCES (Permission denied)
--- /proc/net/ipv6_route ---  同样 EACCES
--- /proc/net/if_inet6 ---    同样 EACCES
```

Android 16 对普通应用收紧了 `/proc/net` 的 SELinux 访问。此时探针原来会输出
`RESULT_3_VPN_ROUTE_VISIBLE=NO`，容易被误读为「路由不可见」；实际是「无法检查」。

修复探针语义：

- 读取失败时汇总改为 `RESULT_3_VPN_ROUTE_VISIBLE=UNKNOWN (UNREADABLE)` /
  `RESULT_5_IF_INET6_LEAK=UNKNOWN (UNREADABLE)`；
- 新增 `FINGERPRINTS=[…]` 行：点对点接口 + `mtu9000`、`fdfe:dcba:9876`、`198.18.x` 地址
  会被标为已知指纹；
- 实机结论因此调整为：L1 通过、L3 通过、L2 仍未达成；R3/R5 在 Android 16 上**未知**。

### 7.4 实机报告的逐项解读

| 探针项 | 实机值 | 结论 |
|---|---|---|
| `active.hasVpn` | `false`；全部 network `vpn=false` | L1 通过：系统层面没有 VPN 网络 |
| `NetworkInterface` | `net0 up p2p=true mtu=9000 addrs=fe80… 198.19.0.0` | L2 暴露：p2p 接口 + fake-ip 段地址可见（MTU 已在本轮修复为 1500） |
| `/proc/net/route` | `EACCES` | 应用无法读取；此项**不能**作为隐藏成功的证据 |
| `127.0.0.1:7890/9090/1053` | 全部 `ECONNREFUSED`；UDP 1053 `EPERM` | L3 通过：内核无监听 + iptables 封堵生效 |
| `dumpsys vpn_management` | 仅 `[Legacy VPN]` | L1 通过 |
| 其他接口 | `rmnet_data* / dummy0 / ifb*` | 设备/系统自带接口，不属于本应用 |

结论：这份报告说明 **L1/L3 已经达标**，**L2 仍是缺口**；同时抓出了 MTU 迁移和 IPv6 ULA 两个
可被指纹化的细节，均已在第四轮修复。L2 的根治仍需 [§5](#5-下一轮方案需要确认范围后再实现) 的
KPM/kmod/Zygisk 后端。