#!/system/bin/sh
# cs11_lossprobe.sh — 丢包 × 逐核利用率 相关性探针（CS11 / i.MX6 / Android 4.4）
#
# 目的：回答"丢包瞬间，工作核是否满载"——录原始计数，PC 侧做差分与相关分析。
# 用法：
#   adb push tools/cs11_lossprobe.sh /data/.android/rootcmd.sh      # rootd 队列（120s）
#   adb shell "sh /data/.android/cs11_lossprobe.sh 120"             # 或直接跑（/proc 免 root 可读）
# 采样：每 0.5s 一行 CSV → /data/.android/lossprobe.log
#   seq, epoch秒, 4×(user,nice,sys,idle,iowait,irq,softirq), Udp6InErrors, Udp6RcvbufErrors, load1
# 分析：PC 侧 `python3 tools/lossprobe_summarize.py lossprobe.log`
# 提示：跑探针的同时执行滑动注入（见对话/文档中的 swipe 脚本），时长覆盖交互窗口。

SECS=${1:-120}
OUT=${2:-/data/.android/lossprobe.log}
TICKS=$((SECS * 2))

: > "$OUT"
echo "#seq,epoch,c0u,c0n,c0s,c0i,c0w,c0q,c0S,c1u,c1n,c1s,c1i,c1w,c1q,c1S,c2u,c2n,c2s,c2i,c2w,c2q,c2S,c3u,c3n,c3s,c3i,c3w,c3q,c3S,udperr,udprcv,load1" >> "$OUT"

i=0
while [ $i -lt $TICKS ]; do
  TS=$(date +%s)
  C=$(busybox awk '/^cpu[0-3] /{printf "%s,%s,%s,%s,%s,%s,%s,", $2,$3,$4,$5,$6,$7,$8}' /proc/stat)
  U=$(busybox awk '/^Udp6:/{n++; if(n==2) printf "%s,%s", $4, $6}' /proc/net/snmp)
  L=$(busybox awk 'NR==1{print $1; exit}' /proc/loadavg)
  echo "$i,$TS,$C$U,$L" >> "$OUT"
  busybox usleep 500000 2>/dev/null || busybox sleep 1
  i=$((i + 1))
done
echo "PROBE_DONE ticks=$i file=$OUT"
