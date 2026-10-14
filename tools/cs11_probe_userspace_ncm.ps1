<#
  CS11 用户态 NCM 桥「USB 侧探针」一键脚本（2026-10-12 立，配合 docs/_digest/28 第 5 条与 doc 58 §5）

  做什么：装上当前 APK → 置 `debug.diplay.ncm_legacy force` → 清日志 → 等插线重连 → 抓 60 秒日志 →
          按判据打印"成功/失败/未触发"结论，并把原始日志存到工作区 work\ 下供归档。

  为什么只做 USB 侧：AirPlay 还需要一套用户态 TCP/IP 栈（本 ROM 无 TUN）。本轮只验证
  「描述符解析 → claim → 原生 setInterface → NCM 协商」四步在车机上是否成立——这是整条
  用户态路线唯一的**关键未知项**（原生改 altsetting 是否真生效）。

  用法：
    pwsh -File tools\cs11_probe_userspace_ncm.ps1                 # 装包 + 探针 + 复位属性
    pwsh -File tools\cs11_probe_userspace_ncm.ps1 -SkipInstall    # 用车上已有的包
    pwsh -File tools\cs11_probe_userspace_ncm.ps1 -Seconds 90     # 延长抓取时间
    pwsh -File tools\cs11_probe_userspace_ncm.ps1 -SelfTest       # 离线自检判据（不需要车机）
#>
param(
    [string]$Serial = '0123456789ABCDEF',
    [string]$Adb = 'd:\Dev Environment\platform-tools\adb.exe',
    [string]$Package = 'com.shihab.diplay.hudtest',
    [string]$Apk = 'mobile\build\outputs\apk\debug\mobile-debug.apk',
    [int]$Seconds = 60,
    [switch]$SkipInstall,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Continue'
$workspace = Split-Path -Parent $PSScriptRoot
function Sh([string]$Command) { (& $Adb -s $Serial shell $Command 2>&1) }

# ── 判据（每一条都对应源码里真实存在的字符串，改代码时必须同步这里）────────────────────
# 来源：NcmNegotiation.kt:147-186、NcmUsbBridge.kt:466,524、CarPlayController.kt:1677,1692,1705,1707
$script:Markers = @(
    @{ Key = 'parameters'; Regex = 'NCM legacy stage=parameters';                  Note = 'GET_NTB_PARAMETERS 成功，并给出协商值' }
    @{ Key = 'format16';   Regex = 'NCM legacy stage=format16';                    Note = '选了 NTB16' }
    @{ Key = 'crc';        Regex = 'NCM legacy stage=(crcOff|packetFilterFixed)';  Note = 'CRC 关（或设备不支持 CRC）' }
    @{ Key = 'inputSize';  Regex = 'NCM legacy stage=inputSize';                   Note = 'SET_NTB_INPUT_SIZE 成功' }
    @{ Key = 'filter';     Regex = 'NCM legacy stage=packetFilter';                Note = '包过滤已设' }
    @{ Key = 'ready';      Regex = 'NCM legacy stage=ready';                       Note = 'USB 侧四步全过（含原生 setInterface）' }
    @{ Key = 'probeOk';    Regex = 'userspace NCM probe ok';                       Note = '探针自证成功' }
    @{ Key = 'probeFail';  Regex = 'userspace NCM probe failed';                   Note = '探针失败（看冒号后的类名与消息）' }
    @{ Key = 'probeSkip';  Regex = 'userspace NCM probe skipped';                  Note = '压根没打开 USB 连接（权限/时序）' }
    @{ Key = 'noNative';   Regex = 'needs the legacy_usb native library';          Note = '该 APK 没带原生库（未装 NDK 构建）' }
    @{ Key = 'decision';   Regex = 'userspace NCM probe enabled';                  Note = '策略已按 force 走到用户态探针' }
)

function Get-ProbeReadings([string]$Log) {
    $rows = foreach ($m in $script:Markers) {
        [pscustomobject]@{ Key = $m.Key; Note = $m.Note; Hits = [regex]::Matches($Log, $m.Regex).Count }
    }
    # 结论只看"探针自己那一行"，其余标记只作证据：探针的 ok/failed/skipped 是唯一权威。
    $conclusion = if ($Log -match 'userspace NCM probe ok') { 'ok' }
        elseif ($Log -match 'userspace NCM probe failed') { 'failed' }
        elseif ($Log -match 'userspace NCM probe skipped') { 'skipped' }
        elseif ($Log -match 'NCM legacy stage=parameters') { 'partial' }
        else { 'notrigger' }
    [pscustomobject]@{
        Rows         = $rows
        Conclusion   = $conclusion
        FailedReason = [regex]::Match($Log, 'userspace NCM probe failed: [^\r\n]*').Value
    }
}

function Show-ProbeReadings($Readings) {
    foreach ($row in $Readings.Rows) {
        $mark = if ($row.Hits -gt 0) { '  [x]' } else { '  [ ]' }
        Write-Output ("{0} {1,-10} x{2,-3} {3}" -f $mark, $row.Key, $row.Hits, $row.Note)
    }
    Write-Output ("  → 结论: " + $Readings.Conclusion)
}

# ── 自检：用真实字符串的样本跑判据，防止脚本与代码漂移（判错一次=浪费一个点火窗口）──────────
if ($SelfTest) {
    $samples = @(
        @{ Name = 'ok'; Expected = 'ok'; Log = @'
NCM legacy stage=parameters input=16384 output=16384 divisor=4 remainder=0 alignment=4
NCM legacy stage=format16
NCM legacy stage=crcOff
NCM legacy stage=inputSize bytes=16384
NCM legacy stage=packetFilter flags=14
NCM legacy stage=ready iface=4/1 input=16384 output=16384 status=0x82
userspace NCM probe ok configuration=6: parsed, claimed, switched, negotiated
'@ },
        @{ Name = 'failed-claim'; Expected = 'failed'; Log = 'userspace NCM probe failed: DeviceUnavailable: Android could not claim the NCM data interface 4' },
        @{ Name = 'failed-negotiation'; Expected = 'failed'; Log = 'userspace NCM probe failed: Protocol: NCM NTB alignment parameters are invalid' },
        @{ Name = 'failed-no-native'; Expected = 'failed'; MustHit = 'noNative'; Log = 'userspace NCM probe failed: DeviceUnavailable: the userspace NCM bridge needs the legacy_usb native library' },
        @{ Name = 'skipped'; Expected = 'skipped'; Log = 'userspace NCM probe skipped: the iPhone connection could not be opened' },
        @{ Name = 'partial-parameters-only'; Expected = 'partial'; Log = 'NCM legacy stage=parameters input=16384 output=16384 divisor=4 remainder=0 alignment=4' },
        @{ Name = 'not-triggered'; Expected = 'notrigger'; Log = 'CS11: kernel netdev transport selected; skipping raw NCM bridge' }
    )
    $failures = 0
    Write-Output "=== 自检：判据 vs 样本 ==="
    foreach ($sample in $samples) {
        $readings = Get-ProbeReadings $sample.Log
        $ok = $readings.Conclusion -eq $sample.Expected
        $why = if ($ok) { '' } else { "结论不符（期望 $($sample.Expected)）" }
        if ($sample.ContainsKey('MustHit')) {
            $hit = ($readings.Rows | Where-Object { $_.Key -eq $sample.MustHit }).Hits
            if ($hit -lt 1) { $ok = $false; $why = "标记 '$($sample.MustHit)' 未命中：判据与源码字符串漂移了" }
        }
        if (-not $ok) { $failures++ }
        Write-Output ("  {0} {1,-24} 期望={2,-10} 实得={3,-10} {4}" -f $(if ($ok) { '[PASS]' } else { '[FAIL]' }), $sample.Name, $sample.Expected, $readings.Conclusion, $why)
    }
    Write-Output ""
    Write-Output "=== 判据表（对 ok 样本）==="
    Show-ProbeReadings (Get-ProbeReadings $samples[0].Log)
    Write-Output ""
    if ($failures -gt 0) { Write-Output "自检失败 $failures 项：判据与代码漂移了，先修脚本再上车。"; exit 1 }
    Write-Output "自检全部通过（$($samples.Count) 个样本）。"
    exit 0
}

# ── 实机流程 ──────────────────────────────────────────────────────────────────────
Write-Output "=== 0. 设备 ==="
$devices = & $Adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice' }
if (-not ($devices -match [regex]::Escape($Serial))) {
    Write-Output "  车机未连接（期待 $Serial）。插上 USB 调好 adb 后重跑本脚本。"
    exit 1
}
Write-Output "  已连接：$Serial"

if (-not $SkipInstall) {
    Write-Output "=== 1. 安装当前包（:mobile:clean 后 assemble 出来的那个）==="
    $apkPath = Join-Path $workspace $Apk
    if (-not (Test-Path $apkPath)) { Write-Output "  找不到 $apkPath，先构建或用 -SkipInstall"; exit 1 }
    $hash = (Get-FileHash $apkPath -Algorithm MD5).Hash
    Write-Output ("  {0}  {1} bytes  md5={2}" -f (Split-Path $apkPath -Leaf), (Get-Item $apkPath).Length, $hash)
    & $Adb -s $Serial install -r -d $apkPath 2>&1 | Select-Object -Last 2
}

Write-Output "=== 2. 置探针开关并重启应用 ==="
Sh "setprop debug.diplay.ncm_legacy force" | Out-Null
Write-Output ("  debug.diplay.ncm_legacy = " + ((Sh "getprop debug.diplay.ncm_legacy") -join ''))
Sh "am force-stop $Package" | Out-Null
Sh "logcat -c" | Out-Null

Write-Output "=== 3. 现在请拔掉 iPhone 再插上（应用会在 USB 接入时自动启动）==="
Write-Output "  抓取 $Seconds 秒日志…"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $workspace 'work'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$logFile = Join-Path $outDir "ncm-probe-$stamp.log"
$job = Start-Job -ScriptBlock {
    param($adb, $serial, $file)
    & $adb -s $serial logcat -v time 2>&1 | Out-File -Encoding utf8 $file
} -ArgumentList $Adb, $Serial, $logFile
Start-Sleep -Seconds $Seconds
Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue
$log = if (Test-Path $logFile) { Get-Content $logFile -Raw } else { '' }
Write-Output ("  原始日志: $logFile  ({0} 行)" -f (($log -split "`n").Count))

Write-Output "=== 4. 判读 ==="
$readings = Get-ProbeReadings $log
Show-ProbeReadings $readings
Write-Output ""
Write-Output "--- 关键行原文 ---"
$log -split "`n" | Where-Object { $_ -match 'NCM legacy stage=|userspace NCM probe|claim iface=|ncm descriptor|setInterface iface=' } |
    Select-Object -Last 18 | ForEach-Object { "  " + $_.Trim() }

Write-Output ""
Write-Output "=== 5. 结论与下一步 ==="
switch ($readings.Conclusion) {
    'ok' {
        Write-Output "  [OK] 关键未知项已证实：应用层能 claim NCM 接口、用原生 ioctl 改 altsetting、完成 NCM 协商。"
        Write-Output "       下一步（阶段 1）：装 NDK → lwIP（doc 58 §7）→ 让用户态栈真正承载 AirPlay。"
    }
    'failed' {
        Write-Output ("  [FAIL] " + $readings.FailedReason)
        Write-Output "       按冒号后的类名定位：DeviceUnavailable=claim/altsetting 被拒；Protocol=描述符解析或协商被拒。"
    }
    'skipped' {
        Write-Output "  [SKIP] 连接没打开：多为权限未授予或时序问题，重插一次再跑。"
    }
    'partial' {
        Write-Output "  [PARTIAL] 协商开始了但没走到 ready：多半卡在原生 setInterface 或 SET_NTB_INPUT_SIZE。"
        Write-Output "       若连 parameters 都缺 ⇒ 需要 dump GET_NTB_PARAMETERS 的 28 字节原始响应（doc 58 §5 待确认点 1）。"
    }
    default {
        Write-Output "  [NONE] 未触发：确认已插线、应用被拉起（USB_DEVICE_ATTACHED 自动启动），或改 -SkipInstall 复跑。"
    }
}

Write-Output "=== 6. 复位属性（避免影响后续点火）==="
Sh "setprop debug.diplay.ncm_legacy ''" | Out-Null
Write-Output ("  debug.diplay.ncm_legacy = '" + ((Sh "getprop debug.diplay.ncm_legacy") -join '') + "'")
Write-Output "  提示：把结论与日志路径追加到 docs/_digest/28 的「下次点火窗口」小节。"
