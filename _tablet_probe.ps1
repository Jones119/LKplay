# 平板 bench 探针：会话期间一次性采集「日志量 / GC / 视频指标 / 逐线程 CPU / 卡顿统计」
# 用法: & '.\_tablet_probe.ps1' -Seconds 60
param(
  [string]$Adb = 'd:\Dev Environment\platform-tools\adb.exe',
  [string]$Serial = '5329c7f4',
  [string]$Package = 'com.shihab.diplay.hudtest',
  [int]$Seconds = 60,
  [int]$CpuWindowSeconds = 10
)

function Get-AppPid {
  $p = (& $Adb -s $Serial shell pidof $Package) -join ''
  return $p.Trim()
}

function Get-ThreadTicks([string]$appPid) {
  $d = @{}
  $raw = & $Adb -s $Serial shell "for t in /proc/$appPid/task/*; do cat `$t/stat; done"
  foreach ($l in $raw) {
    if ($l -notmatch '^\d+ \(') { continue }
    $close = $l.LastIndexOf(')')
    if ($close -lt 0) { continue }
    $tid = $l.Substring(0, $l.IndexOf(' '))
    $comm = $l.Substring($l.IndexOf('(') + 1, $close - $l.IndexOf('(') - 1)
    $rest = @(($l.Substring($close + 2) -split '\s+') | Where-Object { $_ -ne '' })
    if ($rest.Count -lt 13) { continue }
    $d[$tid] = [pscustomobject]@{ comm = $comm; cpu = ([long]$rest[11] + [long]$rest[12]) }
  }
  return $d
}

$appPid = Get-AppPid
if (-not $appPid) { Write-Output "app not running ($Package)"; exit 1 }
Write-Output "app pid=$appPid  采样窗口=$Seconds s"

# ---- 1) 日志量 ----
& $Adb -s $Serial logcat -c | Out-Null
Write-Output "logcat 已清空，开始计时…"

# ---- 2) 逐线程 CPU（窗口内） ----
$t1 = Get-ThreadTicks $appPid
Start-Sleep -Seconds $CpuWindowSeconds
$t2 = Get-ThreadTicks $appPid
$rows = @()
foreach ($tid in $t2.Keys) {
  if (-not $t1.ContainsKey($tid)) { continue }
  $delta = $t2[$tid].cpu - $t1[$tid].cpu
  if ($delta -le 0) { continue }
  $rows += [pscustomobject]@{ name = $t2[$tid].comm; pct = (100.0 * $delta / (100.0 * $CpuWindowSeconds)) }
}
Write-Output ("APP 线程占用（{0}s 窗口，单核百分比）总计={1:N1}%" -f $CpuWindowSeconds, (($rows | Measure-Object -Property pct -Sum).Sum))
$rows | Sort-Object pct -Descending | Select-Object -First 12 | ForEach-Object { "   {0,-24} {1,6:N1}%" -f $_.name, $_.pct }

Start-Sleep -Seconds ([Math]::Max(0, $Seconds - $CpuWindowSeconds))

# ---- 3) 日志统计 ----
$dump = & $Adb -s $Serial logcat -d -v time
$mine = $dump | Select-String -Pattern "\(\s*$appPid\)"
$ts = $mine | ForEach-Object { if ($_.Line -match '^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)') { $matches[1] } }
Write-Output ""
Write-Output "===== 日志量 ====="
Write-Output ("本进程日志行数: {0}" -f $mine.Count)
if ($ts.Count -gt 1) {
  $first = [datetime]::ParseExact($ts[0], 'MM-dd HH:mm:ss.fff', $null)
  $last = [datetime]::ParseExact($ts[-1], 'MM-dd HH:mm:ss.fff', $null)
  $span = ($last - $first).TotalSeconds
  if ($span -gt 0) { Write-Output ("时间跨度 {0:N1}s  →  {1:N1} 行/秒" -f $span, ($mine.Count / $span)) }
}
Write-Output "-- 按 tag（前 15）--"
$mine | ForEach-Object { if ($_.Line -match '[VDIWEF]/([A-Za-z0-9_.\-]+)\(') { $matches[1] } } |
  Group-Object | Sort-Object Count -Descending | Select-Object -First 15 |
  ForEach-Object { "   {0,6}  {1}" -f $_.Count, $_.Name }

# ---- 4) GC（ART 每条 GC 一行） ----
$gc = $dump | Select-String -Pattern 'Background concurrent copying GC|Background young concurrent|Explicit concurrent|WaitForGcToComplete'
Write-Output ""
Write-Output ("===== GC 行数（窗口内）: {0} =====" -f $gc.Count)
$gc | Select-Object -Last 6 | ForEach-Object { "   " + $_.Line }

# ---- 5) 我们的业务日志 ----
Write-Output ""
Write-Output "===== video/audio stats 与关键事件 ====="
$dump | Select-String -Pattern 'DiPlay-VideoStats|DiPlay-AudioStats|video decoder|first frame rendered|Receive:|recovery|decoder configured' |
  Select-Object -Last 25 | ForEach-Object { "   " + $_.Line }

# ---- 6) 卡顿统计 ----
Write-Output ""
Write-Output "===== gfxinfo（卡顿/帧时） ====="
& $Adb -s $Serial shell dumpsys gfxinfo $Package | Select-String -Pattern 'Total frames|Janky frames|50th|90th|95th|99th|Number Missed Vsync|Number Slow UI thread|Number Slow bitmap uploads|Number Slow issue draw commands' | ForEach-Object { "   " + $_.Line.Trim() }

# ---- 7) 异常扫描 ----
Write-Output ""
Write-Output "===== 我们代码的异常扫描 ====="
$bad = $mine | Select-String -Pattern 'IllegalStateException|NullPointerException|IndexOutOfBounds|video frame pool|recovery:'
if ($bad) { $bad | Select-Object -Last 10 | ForEach-Object { "   " + $_.Line } } else { Write-Output "   （无）" }
