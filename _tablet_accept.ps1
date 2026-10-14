# 平板通用功能验收（无线路径）。用法：.\_tablet_accept.ps1 [-Seconds 60] [-Serial 5329c7f4]
# 目的：在 CS11 之前，用平板验证“通用功能”（与厂商专有 API 无关的部分）：
#   视频稳态、音频通道/抖动、缓冲音乐填充节流、TCP keepalive 上报、日志门控、
#   GC/线程占用、崩溃扫描。专有项（USB 读队列、流12 音量归属、麦克风独占）不在此脚本范围。
param(
    [int] $Seconds = 60,
    [string] $Serial = '5329c7f4',
    [string] $Package = 'com.shihab.diplay.hudtest'
)
$ErrorActionPreference = 'Continue'
$adb = 'd:\Dev Environment\platform-tools\adb.exe'
function Say($t) { Write-Host $t }
function Section($t) { Write-Host ""; Write-Host "===== $t =====" }

$pid0 = ((& $adb -s $Serial shell pidof $Package) -join '').Trim()
if (-not $pid0) { Say "App 未运行：先手动打开 DiPlay 并连上 CarPlay 再跑本脚本"; exit 1 }
Section "0) 环境"
Say ("   pid={0}" -f $pid0)
& $adb -s $Serial shell dumpsys package $Package | Select-String -Pattern 'versionName|versionCode' | Select-Object -First 2 | ForEach-Object { Say ("   " + $_.Line.Trim()) }

Say "   清空 logcat，计时 $Seconds s ..."
& $adb -s $Serial logcat -c
$t0 = Get-Date
Start-Sleep -Seconds $Seconds
$dump = & $adb -s $Serial logcat -d -v time
$span = ((Get-Date) - $t0).TotalSeconds
$mine = $dump | Where-Object { $_ -match "\(\s*$pid0\)" }

Section "1) 日志量（门控是否生效）"
Say ("   本进程行数 {0} / 跨度 {1:N1}s → {2:N1} 行/秒" -f $mine.Count, $span, ($mine.Count / $span))
$mine | ForEach-Object { if ($_ -match '\s([IWED])/([^\(]+)\(') { $matches[2].Trim() } } |
    Group-Object | Sort-Object Count -Descending | Select-Object -First 10 |
    ForEach-Object { Say ("     {0,6}  {1}" -f $_.Count, $_.Name) }

Section "2) 视频（通用：解码/上屏/抖动）"
$dump | Select-String -Pattern 'DiPlay-VideoStats' | Select-Object -Last 3 | ForEach-Object { Say ("   " + $_.Line) }

Section "3) 音频通道与抖动队列"
$dump | Select-String -Pattern 'Receive: audio type=' | Select-Object -Last 2 | ForEach-Object { Say ("   " + $_.Line) }
$dump | Select-String -Pattern 'audio stats audioType' | Select-Object -Last 4 | ForEach-Object { Say ("   " + $_.Line) }
$dump | Select-String -Pattern 'audio track prepared|buffered audio stream client' | Select-Object -Last 3 | ForEach-Object { Say ("   " + $_.Line) }

Section "4) 验收点 A：缓冲音乐填充节流（新改动，峰值应从 ~2001 降到 ~1000 kbit/s）"
$fill = $dump | Select-String -Pattern 'Buffered audio: queued (\d+) s, received (\d+) kbit/s'
if ($fill) {
    $rates = $fill | ForEach-Object { [int]($_.Line -replace '.*received (\d+) kbit\/s.*', '$1') }
    $queued = $fill | ForEach-Object { [int]($_.Line -replace '.*queued (\d+) s.*', '$1') }
    Say ("   样本 {0} 条；速率峰值 {1} kbit/s，中位 {2} kbit/s；queued 最大 {3} s" -f `
        $fill.Count, ($rates | Measure-Object -Maximum).Maximum, `
        ($rates | Sort-Object)[[int]($rates.Count / 2)], ($queued | Measure-Object -Maximum).Maximum)
    $first = $fill | Select-Object -First 1
    Say ("   首条: " + $first.Line)
    Say ("   判据: 峰值 <= ~1200 kbit/s 视为限速生效；达到 queued>=110 s 的耗时应在 20~40 s（旧值约 9 s）")
} else { Say "   未出现 Buffered audio 行 → 本次会话没走 103 缓冲通道（或没在放音乐）" }

Section "5) 验收点 B：TCP keepalive 上报（新改动）"
$ka = $dump | Select-String -Pattern 'TCP peer health'
if ($ka) { $ka | Select-Object -Last 3 | ForEach-Object { Say ("   " + $_.Line) }; Say "   判据: 平板(API35) 走 fromSocket 路径，应 keepalive=true userTimeout=true" }
else { Say "   未出现 → 本次会话未建立需要保活的 TCP（无线控制通道应在会话建立时打印）" }

Section "6) 验收点 C：音频路由诊断（新改动，仅通话/焦点变化时出现）"
$dump | Select-String -Pattern 'Audio: mode entered|Audio: mode restored|Audio: mode restore failed|Audio: focus change' | Select-Object -Last 6 | ForEach-Object { Say ("   " + $_.Line) }

Section "7) 稳定性：GC / 异常 / 崩溃"
$gc = $dump | Select-String -Pattern 'GC freed|WAIT_FOR_CONCURRENT_GC'
Say ("   GC 相关行: {0}" -f $gc.Count)
$gc | Select-Object -Last 3 | ForEach-Object { Say ("   " + $_.Line) }
$bad = $dump | Select-String -Pattern 'FATAL EXCEPTION|ANR in|IllegalStateException|NullPointerException|video frame pool|authFailures=[1-9]|seqGapEvents=[1-9]'
Say ("   异常/丢包扫描命中: {0}" -f $bad.Count)
$bad | Select-Object -Last 6 | ForEach-Object { Say ("   " + $_.Line) }

Section "8) 线程占用（6s 窗口，单核百分比）"
function Threads($p) {
    $d = @{}
    foreach ($l in (& $adb -s $Serial shell "for t in /proc/$p/task/*; do cat `$t/stat; done")) {
        if ($l -notmatch '^\d+ \(') { continue }
        $c = $l.LastIndexOf(')'); if ($c -lt 0) { continue }
        $tid = $l.Substring(0, $l.IndexOf(' ')); $comm = $l.Substring($l.IndexOf('(') + 1, $c - $l.IndexOf('(') - 1)
        $r = @(($l.Substring($c + 2) -split '\s+') | Where-Object { $_ -ne '' }); if ($r.Count -lt 13) { continue }
        $d[$tid] = [pscustomobject]@{ n = $comm; c = ([long]$r[11] + [long]$r[12]) }
    }
    $d
}
$a = Threads $pid0; Start-Sleep -Seconds 6; $b = Threads $pid0
$rows = @()
foreach ($k in $b.Keys) { if ($a.ContainsKey($k)) { $d2 = $b[$k].c - $a[$k].c; if ($d2 -gt 0) { $rows += [pscustomobject]@{ n = $b[$k].n; p = 100.0 * $d2 / 600.0 } } } }
Say ("   总计 {0:N1}%（单核）" -f (($rows | Measure-Object p -Sum).Sum))
$rows | Sort-Object p -Descending | Select-Object -First 8 | ForEach-Object { Say ("   {0,-24} {1,6:N1}%" -f $_.n, $_.p) }

Section "9) 渲染流畅度（gfxinfo）"
& $adb -s $Serial shell dumpsys gfxinfo $Package 2>$null | Select-String -Pattern 'Total frames|Janky frames|50th|90th|95th|99th' | ForEach-Object { Say ("   " + $_.Line.Trim()) }

Say ""
Say "完成。判据速查：视频 dropped=0/queueMax 小、音频 skippedMs≈0（103 通道）、填充峰值<=1200kbit/s、"
Say "keepalive=true userTimeout=true、无 FATAL/ANR、无 authFailures/seqGapEvents。"
