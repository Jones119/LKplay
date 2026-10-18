#Requires -Version 7.0
<#
.SYNOPSIS
    装 APK 到一台设备并启动 DiPlay。

.DESCRIPTION
    默认装到唯一连接的设备；有多台时用 -Serial 指定。
    步骤：安装 → 停掉旧进程 → 启动 → 等前台窗口 → 打印结果。

.EXAMPLE
    .\install-and-run.ps1
    .\install-and-run.ps1 -Serial 0123456789ABCDEF
    .\install-and-run.ps1 -Activity CarPlayHostActivity
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Apk = 'mobile\build\outputs\apk\debug\mobile-debug.apk',
    [ValidateSet('DiPlayActivity', 'CarPlayHostActivity')]
    [string]$Activity = 'DiPlayActivity',
    [int]$WaitSeconds = 8
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "找不到 adb: $adb（检查 ANDROID_HOME）" }

$apkPath = if ([System.IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $repo $Apk }
if (-not (Test-Path $apkPath)) { throw "APK 不存在: $apkPath（先跑 assembleStandaloneDebug）" }

# 选设备
if (-not $Serial) {
    $devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
    if ($devices.Count -eq 0) { throw '没有已授权的设备（adb devices 为空；平板需点允许调试）' }
    if ($devices.Count -gt 1) { throw "有多台设备，请用 -Serial 指定：$($devices -join ', ')" }
    $Serial = $devices[0]
}
"设备: $Serial"
"APK : $apkPath  ($([math]::Round((Get-Item $apkPath).Length / 1MB, 2)) MB)"

$adbArgs = @('-s', $Serial)
$pkg = 'com.shihab.diplay.hudtest'
$component = "$pkg/com.shilapi.xcertplay.$Activity"

"`n[1/4] 安装"
$install = & $adb @adbArgs install -r $apkPath 2>&1
$install | Where-Object { $_ -match 'Success|Failure|INSTALL_' } | ForEach-Object { "  $_" }
# -notmatch on an array filters elements and returns the non-matching ones, so a successful
# "Performing Streamed Install" line would make the old form throw even when Success was present.
if (($install -join "`n") -notmatch 'Success') { throw "安装失败：$($install -join ' ')" }

"`n[2/4] 停掉旧进程"
& $adb @adbArgs shell am force-stop $pkg 2>&1 | Out-Null
Start-Sleep -Milliseconds 800

"`n[3/4] 启动 $Activity"
& $adb @adbArgs shell am start -n $component 2>&1 |
    Where-Object { $_ -match 'Starting|Error|Warning' } | ForEach-Object { "  $_" }

"`n[4/4] 等待前台（最多 ${WaitSeconds}s）"
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$foreground = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 700
    $focus = (& $adb @adbArgs shell dumpsys window 2>&1) | Select-String 'mCurrentFocus' | Select-Object -First 1
    if ($focus -and $focus.Line -match 'diplay') {
        $foreground = ($focus.Line -replace '.*mCurrentFocus=', '').Trim()
        break
    }
}

if ($foreground) {
    "  前台: $foreground"
} else {
    "  ⚠ ${WaitSeconds}s 内没等到前台窗口；查崩溃:"
    & $adb @adbArgs logcat -d 2>&1 |
        Select-String 'FATAL EXCEPTION' -Context 0, 12 |
        Select-Object -First 1 | ForEach-Object { $_.Line; $_.Context.PostContext }
}

# 顺带报一下版本，确认装的确实是刚构建的那个
$ver = (& $adb @adbArgs shell dumpsys package $pkg 2>&1) |
    Select-String 'versionName|lastUpdateTime' | ForEach-Object { $_.Line.Trim() }
if ($ver) { "`n版本: $($ver -join '  |  ')" }
