#Requires -Version 7.0
<#
.SYNOPSIS
    一次性抓齐崩溃/卡死的取证材料，落到 _artifacts\crash-<时间>\。

.DESCRIPTION
    抓的东西：
      logcat.txt       全量 logcat（含 FATAL / Fatal signal / 原生崩溃信号）
      tombstones.txt   dumpsys dropbox 的 SYSTEM_TOMBSTONE（含完整回溯；shell 读不了 /data/tombstones）
      diplay.log       应用自己的日志（STEP 链、连接诊断、NCM 统计）
      meminfo.txt      内存概况（排查 pbuf 泄漏 / OOM 时用）
      meta.txt         设备、构建 sha、应用版本、时间——**符号化时要靠它找对应的 .so**
      crash-heads.txt  从 logcat 里摘出的崩溃头（pid/tid/线程名），方便直接定位

.EXAMPLE
    .\capture-crash.ps1
    .\capture-crash.ps1 -Serial 0123456789ABCDEF -Note "滑动后卡死"
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Note = '',
    [string]$OutRoot = '_artifacts\crash'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "找不到 adb: $adb（检查 ANDROID_HOME）" }

if (-not $Serial) {
    $devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
    if ($devices.Count -eq 0) { throw '没有已授权的设备' }
    if ($devices.Count -gt 1) { throw "有多台设备，请用 -Serial 指定：$($devices -join ', ')" }
    $Serial = $devices[0]
}

$rootDir = if ([System.IO.Path]::IsPathRooted($OutRoot)) { $OutRoot } else { Join-Path $repo $OutRoot }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dir = Join-Path $rootDir $stamp
New-Item -ItemType Directory -Path $dir -Force | Out-Null

$adbArgs = @('-s', $Serial)
$pkg = 'com.shihab.diplay.hudtest'

function Save-Adb([string]$Name, [string[]]$AdbShellArgs) {
    $target = Join-Path $dir $Name
    try {
        $out = & $adb @adbArgs @AdbShellArgs 2>&1
        $out | Out-File -FilePath $target -Encoding utf8
        "  $Name  ($((Get-Content $target | Measure-Object -Line).Lines) 行)"
    } catch {
        "  $Name  ❌ $($_.Exception.Message)" | Out-File -FilePath $target -Encoding utf8
        "  $Name  ❌"
    }
}

# 设备端能跑的构建 sha / 版本，作为符号化依据
$sha = (& git -C $repo rev-parse --short HEAD 2>&1) -join ''
$dirty = (& git -C $repo status --porcelain 2>&1 | Measure-Object).Count -gt 0
$pkgInfo = (& $adb @adbArgs shell dumpsys package $pkg 2>&1) |
    Select-String 'versionName|versionCode|lastUpdateTime' | ForEach-Object { $_.Line.Trim() }

@(
    "time       = $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
    "serial     = $Serial"
    "pc sha     = $sha$(if ($dirty) { ' (dirty)' } else { '' })"
    "note       = $Note"
    "product    = $((& $adb @adbArgs shell getprop ro.build.product 2>&1) -join '')"
    "release    = $((& $adb @adbArgs shell getprop ro.build.version.release 2>&1) -join '') / API $((& $adb @adbArgs shell getprop ro.build.version.sdk 2>&1) -join '')"
    "app        = $pkg"
    "version    = $($pkgInfo -join '  |  ')"
    "symbols    = _artifacts\symbols\$sha\  （用这一份 .so 符号化；换了构建地址就变了）"
) | Out-File -FilePath (Join-Path $dir 'meta.txt') -Encoding utf8

"取证目录: $dir"
"`n抓取中:"

Save-Adb 'logcat.txt' @('logcat', '-d')
Save-Adb 'tombstones.txt' @('dumpsys', 'dropbox', '--print', 'SYSTEM_TOMBSTONE')
Save-Adb 'diplay.log' @('shell', 'run-as', $pkg, 'cat', 'files/logs/diplay.log')
Save-Adb 'meminfo.txt' @('shell', 'dumpsys', 'meminfo', $pkg)

# 崩溃头摘出来，省得每次手工 grep
$logcat = Join-Path $dir 'logcat.txt'
$heads = Join-Path $dir 'crash-heads.txt'
if (Test-Path $logcat) {
    $patterns = 'FATAL EXCEPTION', 'Fatal signal', 'signal \d+ \(SIG', 'name: \w+.*>>>', 'pid: \d+, tid: \d+'
    $hits = Get-Content $logcat -Encoding utf8 |
        Select-String -Pattern $patterns |
        Select-Object -Last 60
    if ($hits) {
        $hits | ForEach-Object { $_.Line.Trim() } | Out-File -FilePath $heads -Encoding utf8
        "`n崩溃头（最后 60 条）:"
        $hits | Select-Object -Last 12 | ForEach-Object { "  $($_.Line.Trim())" }
    } else {
        '（logcat 里没有崩溃痕迹——可能是卡死而非崩溃，看 diplay.log 的 NCM 统计）' |
            Out-File -FilePath $heads -Encoding utf8
        "`n没有崩溃痕迹；若是卡死，下一步看 diplay.log 的 'userspace NCM link' 统计行"
    }
}

"`n完成。下一步："
"  1. 若是原生崩溃，用 _artifacts\symbols\$sha\ 里对应的 .so 符号化（见 docs\DEV-ENVIRONMENT-CS11.md §6）"
"  2. 若是卡死，看 diplay.log 里 rxFrames / injected / written 是否停止增长"
