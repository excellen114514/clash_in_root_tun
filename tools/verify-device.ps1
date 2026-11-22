# Root TUN Clash - MuMu 模拟器快速验收脚本
#
# 用法（在仓库根目录）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-device.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\verify-device.ps1 -Serial 127.0.0.1:16384
#
# 依赖：本仓库自带的 android-sdk\platform-tools\adb.exe，以及已在 MuMu 中开启的 Root 权限。
param(
    [string]$Serial = "127.0.0.1:16384",
    [string]$Adb = "",
    [string]$Apk = ""
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Adb)) { $Adb = Join-Path $root "android-sdk\platform-tools\adb.exe" }
if ([string]::IsNullOrWhiteSpace($Apk)) { $Apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk" }

$pkg = "com.roottun.clash"
$activity = "$pkg/.MainActivity"

$script:pass = 0
$script:fail = 0

function Check([bool]$Condition, [string]$Message) {
    if ($Condition) {
        $script:pass++
        Write-Host "  [PASS] $Message" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  [FAIL] $Message" -ForegroundColor Red
    }
}

Write-Host "== 1. 连接 MuMu ==" -ForegroundColor Cyan
& $Adb connect $Serial | Out-Null
Start-Sleep -Seconds 2
$devices = (& $Adb devices | Out-String)
Check ($devices -match ([regex]::Escape($Serial) + "\s+device")) "adb 已连接 $Serial"

Write-Host "== 2. 设备信息 ==" -ForegroundColor Cyan
$abi = (& $Adb -s $Serial shell getprop ro.product.cpu.abi) -join ""
$sdk = (& $Adb -s $Serial shell getprop ro.build.version.sdk) -join ""
Write-Host "  ABI=$abi  SDK=$sdk"
Check ($abi -match "x86_64|arm64") "设备 ABI 受支持（$abi）"

Write-Host "== 3. 安装 APK ==" -ForegroundColor Cyan
Check (Test-Path $Apk) "APK 存在：$Apk"
if (-not (Test-Path $Apk)) { exit 1 }
$install = (& $Adb -s $Serial install -r -t $Apk 2>&1 | Out-String)
Check ($install -match "Success") "安装成功"

Write-Host "== 4. 启动主界面 ==" -ForegroundColor Cyan
& $Adb -s $Serial shell am force-stop $pkg | Out-Null
& $Adb -s $Serial logcat -c | Out-Null
$start = (& $Adb -s $Serial shell am start -W -n $activity 2>&1 | Out-String)
Check ($start -match "Status: ok") "MainActivity 启动成功"
Start-Sleep -Seconds 5

Write-Host "== 5. 崩溃检查 ==" -ForegroundColor Cyan
$crash = & $Adb -s $Serial logcat -d -b crash 2>&1 | Out-String
Check ($crash -notmatch "FATAL EXCEPTION") "没有 FATAL EXCEPTION"

Write-Host "== 6. VPN 声明检查 ==" -ForegroundColor Cyan
$perms = & $Adb -s $Serial shell dumpsys package $pkg 2>&1 | Out-String
Check ($perms -notmatch "BIND_VPN_SERVICE") "未申请 BIND_VPN_SERVICE"
Check ($perms -notmatch "android.net.VpnService") "未声明 VpnService"

Write-Host "== 7. 应用日志（需要 su 授权） ==" -ForegroundColor Cyan
$logPath = "/data/data/$pkg/files/clash/logs/app.log"
$log = & $Adb -s $Serial shell "su -c 'tail -n 25 $logPath'" 2>&1 | Out-String
if ($log -match "RootTun Clash|应用启动") {
    Write-Host $log
} else {
    Write-Host "  未能读取应用日志（可能尚未授权 Root）：" -ForegroundColor Yellow
    Write-Host $log
}

Write-Host "== 8. 代理隐藏检查（可选：需已安装 probe 且代理正在运行） ==" -ForegroundColor Cyan
$probePkg = "com.roottun.probe"
$probeInstalled = ((& $Adb -s $Serial shell pm list packages 2>&1 | Out-String) -match [regex]::Escape($probePkg))
if (-not $probeInstalled) {
    Write-Host "  [SKIP] 未安装探针应用（probe-debug.apk）" -ForegroundColor Yellow
} else {
    & $Adb -s $Serial shell am start -W -n "$probePkg/.ProbeActivity" | Out-Null
    Start-Sleep -Seconds 5
    $probeReport = (& $Adb -s $Serial shell "run-as $probePkg cat /data/data/$probePkg/files/probe-report.txt" 2>&1 | Out-String)
    if ($probeReport -match "RESULT_1_VPN_TRANSPORT_DETECTED") {
        Check ($probeReport -match "RESULT_1_VPN_TRANSPORT_DETECTED=NO") "目标应用未检测到 TRANSPORT_VPN"
        Check ($probeReport -match "RESULT_4_PROXY_PORT_REACHABLE=NO") "目标应用连不上本机代理端口"
        if ($probeReport -match "RESULT_2_VPN_INTERFACE_DETECTED=YES") {
            Write-Host "  [INFO] L2：代理接口对目标应用可见（预期，见 STEALTH.md 第 2 节）" -ForegroundColor Yellow
        } else {
            Write-Host "  [INFO] L2：未发现可疑接口（代理可能未运行）" -ForegroundColor Yellow
        }
    } else {
        Write-Host "  [SKIP] 未读到探针报告（可能代理未运行）" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "结果：PASS=$script:pass FAIL=$script:fail" -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }
