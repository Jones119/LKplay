# 受控实验：用 adb 自己注入滑动，制造可复现负载；滑动中途 SIGQUIT 抓 Java 栈。
param(
  [string]$Adb = 'd:\Dev Environment\platform-tools\adb.exe',
  [int]$Rounds = 18,
  [int]$IntervalMs = 1500
)

function Get-AppPid {
  $line = & $Adb shell ps | Select-String -Pattern 'com\.shihab\.diplay\.hudtest' | Where-Object { $_ -notmatch ':' } | Select-Object -First 1
  if (-not $line) { return $null }
  $f = @(($line.ToString() -split '\s+') | Where-Object { $_ -ne '' })
  if ($f.Count -ge 2) { return $f[1] }
  return $null
}

function Get-ThreadTicks {
  param($pid)
  $d = @{}
  $raw = & $Adb shell "for t in /proc/$pid/task/*; do cat `$t/stat; done"
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

$pid = Get-AppPid
if (-not $pid) { Write-Output "app not running"; exit 1 }
Write-Output "app pid=$pid  rounds=$Rounds  interval=${IntervalMs}ms (swipe injected each round)"
$dumpIndex = 0

for ($i = 1; $i -le $Rounds; $i++) {
  # 注入一次纵向滑动（逻辑屏 1280x720）
  & $Adb shell "input swipe 640 560 640 220 180" | Out-Null

  $t1 = Get-ThreadTicks $pid
  $c1 = & $Adb shell "cat /proc/stat"
  Start-Sleep -Milliseconds 1400
  $t2 = Get-ThreadTicks $pid
  $c2 = & $Adb shell "cat /proc/stat"

  $busy = @{}
  for ($k = 0; $k -lt 4; $k++) {
    $a = @((($c1 | Select-String "^cpu$k\s").Line -split '\s+') | Where-Object { $_ -ne '' } | Select-Object -Skip 1 | ForEach-Object { [long]$_ })
    $b = @((($c2 | Select-String "^cpu$k\s").Line -split '\s+') | Where-Object { $_ -ne '' } | Select-Object -Skip 1 | ForEach-Object { [long]$_ })
    if ($a.Count -lt 7) { continue }
    $tot = 0; for ($j = 0; $j -lt 7; $j++) { $tot += ($b[$j] - $a[$j]) }
    if ($tot -gt 0) { $busy["c$k"] = 100.0 * ($tot - ($b[3] - $a[3])) / $tot }
  }

  $rows = @()
  foreach ($tid in $t2.Keys) {
    if (-not $t1.ContainsKey($tid)) { continue }
    $d = $t2[$tid].cpu - $t1[$tid].cpu
    if ($d -le 0) { continue }
    $rows += [pscustomobject]@{ name = $t2[$tid].comm; pct = (100.0 * $d / 140.0) }
  }
  $total = ($rows | Measure-Object -Property pct -Sum).Sum
  $hot = ($rows | Sort-Object pct -Descending | Select-Object -First 6 | ForEach-Object { "{0}={1:N1}%" -f $_.name, $_.pct }) -join ' '
  $cores = ($busy.Keys | Sort-Object | ForEach-Object { "{0}={1:N0}%" -f $_, $busy[$_] }) -join ' '
  Write-Output ("r{0,2} cores[{1}] appTotal={2:N0}%  hot: {3}" -f $i, $cores, $total, $hot)

  # 滑动中途抓两次 Java 栈
  if ($i -eq 5 -or $i -eq 12) {
    $dumpIndex++
    & $Adb shell "run-as com.shihab.diplay.hudtest kill -3 $pid" | Out-Null
    Start-Sleep -Milliseconds 2500
    $all = & $Adb shell "cat /data/anr/traces.txt"
    $idx = ($all | Select-String -Pattern "Cmd line: com.shihab.diplay.hudtest" | Select-Object -Last 1).LineNumber
    if ($idx) {
      $all[($idx - 2)..($all.Count - 1)] | Set-Content -Path "D:\Development\DiAuto-DiPlay\_trace_load$dumpIndex.txt"
      Write-Output ("   -> stack dump {0} saved ({1} lines)" -f $dumpIndex, ($all.Count - $idx + 3))
    } else {
      Write-Output "   -> stack dump $dumpIndex NOT found"
    }
  }
}
Write-Output "===== PROBE DONE ====="
