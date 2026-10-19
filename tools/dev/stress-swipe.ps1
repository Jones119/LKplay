#Requires -Version 7.0
<#
.SYNOPSIS
    快速滑动压测：在已连上 CarPlay 的设备上连滑 N 次，判定崩溃 / 断连 / 正常。

.DESCRIPTION
    目标故障（doc 92 §5.1、doc 93 §3）：CS11 上快速滑动 CarPlay 屏幕会崩溃或断连。
    这个脚本把"连滑十几下"变成可重复的测量：

      1. 先确认会话是活的（NCM rxFrames 在增长），否则直接退出；
      2. 清空 logcat，在设备内用一条 shell 循环连滑 N 次（不走 adb 往返，才能真正"快"）；
      3. 回读判定：
         - `Fatal signal` / `FATAL EXCEPTION` / crash 缓冲有内容 ⇒ CRASH
         - NCM rxFrames 冻结                                        ⇒ DISCONNECT
         - 都不满足                                                 ⇒ OK
      4. 非 OK 且带 -Capture 时自动跑 capture-crash.ps1。

    坐标按屏幕尺寸现算（横向扫过屏幕中部），不依赖分辨率。

.EXAMPLE
    .\stress-swipe.ps1 -Serial 192.168.6.106:38241
    .\stress-swipe.ps1 -Serial 0123456789ABCDEF -Swipes 300 -Capture
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [int]$Swipes = 200,
    [int]$SwipeMillis = 60,
    [switch]$Capture,
    [string]$Note = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "找不到 adb: $adb（检查 ANDROID_HOME）" }
$pkg = 'com.shihab.diplay.hudtest'

if (-not $Serial) {
    $devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
    if ($devices.Count -eq 0) { throw '没有已授权的设备' }
    if ($devices.Count -gt 1) { throw "有多台设备，请用 -Serial 指定：$($devices -join ', ')" }
    $Serial = $devices[0]
}
$adbArgs = @('-s', $Serial)
"设备: $Serial"

function Get-LogTail {
    & $adb @adbArgs shell run-as $pkg cat files/logs/diplay.log 2>&1
}

function Get-RxFrames {
    $line = (Get-LogTail | Select-String 'userspace NCM link rxFrames=' | Select-Object -Last 1).Line
    if (-not $line) { return -1 }
    $m = [regex]::Match($line, 'rxFrames=(\d+)')
    if ($m.Success) { return [int]$m.Groups[1].Value } else { return -1 }
}

# --- 1. 会话必须是活的 -------------------------------------------------------
$rx0 = Get-RxFrames
Start-Sleep -Seconds 2
$rx1 = Get-RxFrames
"起始 rxFrames=$rx0 -> $rx1"
if ($rx0 -lt 0 -or $rx1 -le $rx0) {
    throw '会话不像活的（rxFrames 没有增长）。先把 CarPlay 连上：拔插数据线 → 点「连接手机」。'
}

# --- 2. 屏幕尺寸，算一条横向滑动线 -------------------------------------------
$size = (& $adb @adbArgs shell wm size) -join ''
$m = [regex]::Match($size, '(\d+)x(\d+)')
if (-not $m.Success) { throw "解析不了屏幕尺寸: $size" }
$w = [int]$m.Groups[1].Value
$h = [int]$m.Groups[2].Value
$x1 = [int]($w * 0.80); $x2 = [int]($w * 0.20); $y = [int]($h * 0.50)
"屏幕 ${w}x${h}，滑动线 y=$y x=$x1<->$x2"

# --- 3. 压测：一条设备内循环，单次滑动 + 反向滑动 ---------------------------
$interval = if ($Swipes -gt 0) { [int](1000 / $Swipes) } else { 0 }
"连滑 $Swipes 组（每组左右各一次，单次 ${SwipeMillis}ms）"

& $adb @adbArgs logcat -c 2>&1 | Out-Null
$loop = "for i in `$(seq 1 $Swipes); do " +
    "input swipe $x1 $y $x2 $y $SwipeMillis; " +
    "input swipe $x2 $y $x1 $y $SwipeMillis; done"
& $adb @adbArgs shell $loop 2>&1 | Out-Null
"滑动结束"

# --- 4. 判定 ----------------------------------------------------------------
Start-Sleep -Seconds 3
$crash = (& $adb @adbArgs logcat -d 2>&1) |
    Select-String 'Fatal signal|FATAL EXCEPTION|signal 11 \(SIGSEGV|SIGABRT'
$crashBuffer = (& $adb @adbArgs logcat -d -b crash 2>&1) | Select-Object -Last 5
$rx2 = Get-RxFrames
$video = Get-LogTail | Select-String 'video stats|first frame' | Select-Object -Last 1
"结束 rxFrames=$rx2"
if ($video) { "最后视频行: $($video.Line)" }

$verdict = 'OK'
if ($crash) { $verdict = 'CRASH' }
elseif ($rx2 -le $rx1) { $verdict = 'DISCONNECT' }

"`nVERDICT: $verdict"
if ($crash) {
    $crash | Select-Object -Last 5 | ForEach-Object { "  $($_.Line)" }
    $crashBuffer | ForEach-Object { "  [crash] $_" }
}

if ($verdict -ne 'OK' -and $Capture) {
    $text = if ($Note) { "$verdict · $Note" } else { "$verdict · swipe storm $Swipes" }
    '取证中…'
    & (Join-Path $PSScriptRoot 'capture-crash.ps1') -Serial $Serial -Note $text
}

if ($verdict -eq 'DISCONNECT') {
    "`n提示：断连后需要拔插一次数据线才能重连（doc 93 §4.5）。"
}
