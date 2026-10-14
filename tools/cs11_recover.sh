#!/usr/bin/env bash
# CS11 CarPlay 一键恢复（重启后运行）
#   1) root 链点火：rootd 队列 + usb1 复活（走 cs11_root_fire.sh）
#   2) 拉起 app（若未在跑）→ 等它完成 NCM 引导（含 USB IRQ 亲和）
#   3) 重放链路/CPU 调优（RPS/taskset/renice/内核旋钮 → cs11_tune.sh）
#   4) 状态核验
# 用法：./tools/cs11_recover.sh [SERIAL]
set -u
SERIAL="${1:-0123456789ABCDEF}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
# adb.exe 是原生 Windows 程序且本环境不做 MSYS 路径转换（2026-10-07 修复）
HERE_WIN="$(cygpath -m "$HERE" 2>/dev/null || echo "$HERE")"
A="adb -s $SERIAL"
APP="com.shihab.diplay.hudtest"
ACT="$APP/com.shilapi.xcertplay.CarPlayHostActivity"

echo "== 1/4 root 链点火 =="
"$HERE/tools/cs11_root_fire.sh" "$SERIAL" | tail -12

echo
echo "== 2/4 拉起 app 并等 NCM 引导 =="
RUNNING=$($A shell "busybox pidof $APP 2>/dev/null" | tr -d '\r' | busybox awk '{print $1}')
if [ -z "$RUNNING" ]; then
  $A shell "am start -n $ACT" >/dev/null 2>&1
  echo "已拉起，等待 18s（NCM 绑定 + fe80::2 + DAD）..."
  sleep 18
else
  echo "app 已在运行 (pid=$RUNNING)，等待 5s 稳定..."
  sleep 5
fi

echo "== 3/4 调优重放 =="
$A push "$HERE_WIN/tools/cs11_tune.sh" /data/.android/rootcmd.sh >/dev/null
sleep 4
$A shell "busybox cat /data/.android/rootout.txt"

echo
echo "== 4/4 状态核验 =="
$A shell "busybox ifconfig usb1 2>&1 | busybox head -3; echo ---; busybox grep Udp6InErrors /proc/net/snmp6; echo ---; busybox ps | busybox grep '[c]om.shihab.diplay'"
echo
echo "恢复完成。若屏幕未见 CarPlay 画面，检查 iPhone 是否插好 USB2 口。"
