# 实时采样：分核占用 / app 逐线程 CPU / 中断速率 / 网卡速率 / 视频统计
# 只打印，不写文件。
param(
  [string]$Adb = 'd:\Dev Environment\platform-tools\adb.exe',
  [string]$AppPid = '19791',
  [int]$Samples = 7,
  [int]$IntervalSeconds = 8
)

function Get-Cores {
  param($raw)
  $d = @{}
  foreach ($l in $raw) {
    if ($l -match '^cpu(\d*)\s+(.+)$') {
      $k = if ($matches[1] -eq '') { 'all' } else { 'c' + $matches[1] }
      $d[$k] = @(($matches[2] -split '\s+') | Where-Object { $_ -ne '' } | ForEach-Object { [long]$_ })
    }
  }
  return $d
}

function Get-Threads {
  param($raw)
  $d = @{}
  foreach ($l in $raw) {
    if ($l -notmatch '^\d+ \(') { continue }
    $close = $l.LastIndexOf(')')
    if ($close -lt 0) { continue }
    $tid = ($l.Substring(0, $l.IndexOf(' ')))
    $comm = $l.Substring($l.IndexOf('(') + 1, $close - $l.IndexOf('(') - 1)
    $rest = @(($l.Substring($close + 2) -split '\s+') | Where-Object { $_ -ne '' })
    if ($rest.Count -lt 13) { continue }
    $d[$tid] = [pscustomobject]@{ comm = $comm; utime = [long]$rest[11]; stime = [long]$rest[12] }
  }
  return $d
}

function Get-Irqs {
  param($raw)
  $d = @{}
  foreach ($l in $raw) {
    if ($l -match '^\s*(\d+):\s+([\d\s]+?)\s{2,}(.+)$') {
      $name = $matches[3].Trim()
      $nums = @(($matches[2] -split '\s+') | Where-Object { $_ -ne '' } | ForEach-Object { [long]$_ })
      $d["$($matches[1]) $name"] = ($nums | Measure-Object -Sum).Sum
    }
  }
  return $d
}

function Get-Net {
  param($raw)
  $d = @{}
  foreach ($l in $raw) {
    if ($l -match '^\s*([a-z0-9]+):\s+(.+)$') {
      $f = @(($matches[2] -split '\s+') | Where-Object { $_ -ne '' } | ForEach-Object { [long]$_ })
      if ($f.Count -ge 10) { $d[$matches[1]] = [pscustomobject]@{ rxB = $f[0]; rxP = $f[1]; txB = $f[8]; txP = $f[9] } }
    }
  }
  return $d
}

function Get-AppPid {
  $line = & $Adb shell ps | Select-String -Pattern 'com\.shihab\.diplay\.hudtest' | Where-Object { $_ -notmatch ':' } | Select-Object -First 1
  if (-not $line) { return $script:AppPid }
  $f = @(($line.ToString() -split '\s+') | Where-Object { $_ -ne '' })
  if ($f.Count -ge 2) { return $f[1] }
  return $script:AppPid
}

function Snapshot {
  $script:AppPid = Get-AppPid
  $s = [ordered]@{}
  $s.pid = $script:AppPid
  $s.stat = & $Adb shell "cat /proc/stat"
  $s.threads = & $Adb shell "for t in /proc/$($script:AppPid)/task/*; do cat `$t/stat; done"
  $s.irq = & $Adb shell "cat /proc/interrupts"
  $s.net = & $Adb shell "cat /proc/net/dev"
  $s.cpu = & $Adb shell "dumpsys cpuinfo"
  $s.vs = & $Adb shell "logcat -d -s DiPlay-VideoStats:V" | Select-Object -Last 1
  $s.t = Get-Date
  return $s
}

$prev = Snapshot
Write-Output ("### baseline at {0}" -f $prev.t.ToString('HH:mm:ss'))

for ($i = 1; $i -le $Samples; $i++) {
  Start-Sleep -Seconds $IntervalSeconds
  $cur = Snapshot
  $dt = ($cur.t - $prev.t).TotalSeconds
  Write-Output ""
  Write-Output ("===== sample {0}  t={1}  dt={2:N1}s =====" -f $i, $cur.t.ToString('HH:mm:ss'), $dt)

  # 分核
  $p = Get-Cores $prev.stat; $c = Get-Cores $cur.stat
  foreach ($k in @('c0', 'c1', 'c2', 'c3', 'all')) {
    if (-not $p.ContainsKey($k)) { continue }
    $a = $p[$k]; $b = $c[$k]
    $du = $b[0] - $a[0]; $dn = $b[1] - $a[1]; $ds = $b[2] - $a[2]; $di = $b[3] - $a[3]
    $dw = $b[4] - $a[4]; $dq = $b[5] - $a[5]; $dsq = $b[6] - $a[6]
    $tot = $du + $dn + $ds + $di + $dw + $dq + $dsq
    if ($tot -le 0) { continue }
    Write-Output ("CORE {0}: busy={1,5:N1}%  user={2,4:N1}% sys={3,4:N1}% irq={4,4:N1}% softirq={5,4:N1}% iowait={6,4:N1}% idle={7,4:N1}%" -f `
      $k, (100 * ($tot - $di) / $tot), (100 * $du / $tot), (100 * $ds / $tot), (100 * $dq / $tot), (100 * $dsq / $tot), (100 * $dw / $tot), (100 * $di / $tot))
  }

  # 逐线程
  $pt = Get-Threads $prev.threads; $ct = Get-Threads $cur.threads
  $rows = @()
  foreach ($tid in $ct.Keys) {
    if (-not $pt.ContainsKey($tid)) { continue }
    $ticks = ($ct[$tid].utime - $pt[$tid].utime) + ($ct[$tid].stime - $pt[$tid].stime)
    if ($ticks -le 0) { continue }
    $rows += [pscustomobject]@{ name = $ct[$tid].comm; pct = (100.0 * $ticks / (100.0 * $dt)) }
  }
  $appTotal = ($rows | Measure-Object -Property pct -Sum).Sum
  Write-Output ("APP pid={0}: total={1:N1}% of one core" -f $cur.pid, $appTotal)
  $rows | Sort-Object pct -Descending | Select-Object -First 12 | ForEach-Object { Write-Output ("   thread {0,-22} {1,6:N1}%" -f $_.name, $_.pct) }

  # 中断
  $pi = Get-Irqs $prev.irq; $ci = Get-Irqs $cur.irq
  $irows = @()
  foreach ($k in $ci.Keys) {
    if (-not $pi.ContainsKey($k)) { continue }
    $rate = ($ci[$k] - $pi[$k]) / $dt
    if ($rate -ge 5) { $irows += [pscustomobject]@{ name = $k; rate = $rate } }
  }
  Write-Output "IRQ (>=5/s):"
  $irows | Sort-Object rate -Descending | Select-Object -First 10 | ForEach-Object { Write-Output ("   irq {0,-34} {1,8:N0}/s" -f $_.name, $_.rate) }

  # 网卡
  $pn = Get-Net $prev.net; $cn = Get-Net $cur.net
  Write-Output "NET:"
  foreach ($k in @('wlan0', 'usb0', 'eth0')) {
    if (-not $cn.ContainsKey($k)) { continue }
    $rb = ($cn[$k].rxB - $pn[$k].rxB) / $dt; $rp = ($cn[$k].rxP - $pn[$k].rxP) / $dt
    $tb = ($cn[$k].txB - $pn[$k].txB) / $dt; $tp = ($cn[$k].txP - $pn[$k].txP) / $dt
    Write-Output ("   {0,-7} rx={1,8:N0} B/s ({2,6:N1} Mbps, {3,6:N0} pkt/s)  tx={4,7:N0} B/s ({5,6:N0} pkt/s)" -f `
      $k, $rb, (8 * $rb / 1e6), $rp, $tb, $tp)
  }

  # 系统进程占用
  Write-Output "PROCS:"
  $cur.cpu | Select-Object -First 10 | ForEach-Object { if ($_ -match '\S') { Write-Output ("   " + $_.Trim()) } }

  # 视频统计
  Write-Output "VIDEO:"
  if ($cur.vs) { $cur.vs | ForEach-Object { Write-Output ("   " + $_.Trim()) } }

  $prev = $cur
}
Write-Output ""
Write-Output "===== DONE ====="
