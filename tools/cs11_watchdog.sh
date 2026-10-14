#!/usr/bin/env bash
# CS11 CarPlay 守护脚本：设备上线即自愈。
#   - 检测 CS11 是否在线（USB adb 串口；可选 WiFi adb 回连）
#   - 健康检查：rootd 守护 / usb1 网卡 / app 进程 / 链路调优（rps=2）
#   - 按需自愈：root 链点火 → 拉起 app → 重放调优（自带冷却，不会反复触发）
# 用法：
#   ./tools/cs11_watchdog.sh                 # 常驻守护（30s 周期）
#   ./tools/cs11_watchdog.sh --once          # 单次体检（不常驻）
#   ./tools/cs11_watchdog.sh --interval 15   # 自定义周期
#   ./tools/cs11_watchdog.sh --wifi 192.168.43.1:5555   # 设备不出现时尝试 WiFi 回连
# 日志：_analysis/watchdog.log
set -u
SERIAL="0123456789ABCDEF"
WIFI_ADDR=""
INTERVAL=30
ONCE=0
FIRE_COOLDOWN=180   # 两次 root 点火最小间隔（秒）
APP_START_COOLDOWN=60

HERE="$(cd "$(dirname "$0")/.." && pwd)"
# adb.exe 是原生 Windows 程序且本环境不做 MSYS 路径转换（2026-10-07 修复）
HERE_WIN="$(cygpath -m "$HERE" 2>/dev/null || echo "$HERE")"
LOG="$HERE/_analysis/watchdog.log"
A="adb -s $SERIAL"

while [ $# -gt 0 ]; do
  case "$1" in
    --once) ONCE=1 ;;
    --interval) shift; INTERVAL="${1:-30}" ;;
    --wifi) shift; WIFI_ADDR="${1:-}" ;;
    --serial) shift; SERIAL="${1:-$SERIAL}"; A="adb -s $SERIAL" ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
  shift
done

log() { echo "$(date '+%F %T')  $*" | tee -a "$LOG"; }

device_state() {
  adb -s "$SERIAL" get-state 2>/dev/null | tr -d '\r'
}

# 聚合一次 shell 往返做全部体检（便宜）
health() {
  $A shell '
    echo "rootd=$(busybox ps 2>/dev/null | busybox grep -c "[r]ootd.sh")"
    echo "usb1=$(busybox cat /sys/class/net/usb1/operstate 2>/dev/null || echo absent)"
    echo "app=$(busybox pidof com.shihab.diplay.hudtest 2>/dev/null | busybox awk "{print \$1}")"
    echo "rps=$(busybox cat /sys/class/net/usb1/queues/rx-0/rps_cpus 2>/dev/null || echo na)"
  ' 2>/dev/null | tr -d '\r'
}

last_fire=0
last_appstart=0

heal_cycle() {
  local h="$1" rootd usb1 app rps now
  rootd=$(echo "$h" | sed -n 's/^rootd=//p')
  usb1=$(echo "$h"  | sed -n 's/^usb1=//p')
  app=$(echo "$h"   | sed -n 's/^app=//p')
  rps=$(echo "$h"   | sed -n 's/^rps=//p')
  now=$(date +%s)

  if [ "${rootd:-0}" = "0" ]; then
    if [ $((now - last_fire)) -ge $FIRE_COOLDOWN ]; then
      log "rootd 不在 → 执行 root 链点火"
      "$HERE/tools/cs11_root_fire.sh" "$SERIAL" >/dev/null 2>&1 || log "点火脚本返回非零（继续观察）"
      last_fire=$now
      sleep 5
      h=$(health); rootd=$(echo "$h" | sed -n 's/^rootd=//p'); usb1=$(echo "$h" | sed -n 's/^usb1=//p')
      log "点火后: rootd=${rootd:-0} usb1=${usb1:-?}"
    else
      log "rootd 不在，但冷却中（$((FIRE_COOLDOWN - now + last_fire))s 后可重试）"
    fi
  fi

  if [ "${rootd:-0}" != "0" ] && [ -z "${app:-}" ]; then
    if [ $((now - last_appstart)) -ge $APP_START_COOLDOWN ]; then
      log "app 不在 → am start"
      $A shell "am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.CarPlayHostActivity" >/dev/null 2>&1
      last_appstart=$now
    fi
  fi

  if [ "${rootd:-0}" != "0" ] && [ "${rps:-x}" != "2" ] && [ "${usb1:-absent}" != "absent" ]; then
    log "调优未生效（rps=${rps:-?}）→ 重放 cs11_tune.sh"
    $A push "$HERE_WIN/tools/cs11_tune.sh" /data/.android/rootcmd.sh >/dev/null 2>&1
  fi

  log "体检: rootd=${rootd:-0} usb1=${usb1:-?} app=${app:-无} rps=${rps:-?}"
}

trap 'log "守护退出"; exit 0' INT TERM
adb start-server >/dev/null 2>&1
log "==== 守护启动（serial=$SERIAL interval=${INTERVAL}s once=$ONCE wifi=${WIFI_ADDR:-off}）===="

while true; do
  state=$(device_state)
  if [ "$state" = "device" ]; then
    heal_cycle "$(health)"
  else
    log "设备未就绪（state=${state:-未连接}）"
    if [ -n "$WIFI_ADDR" ]; then
      adb connect "$WIFI_ADDR" >/dev/null 2>&1 && log "已尝试 WiFi 回连 $WIFI_ADDR"
    fi
  fi
  [ "$ONCE" = "1" ] && break
  sleep "$INTERVAL"
done
[ "$ONCE" = "1" ] && log "单次体检完成"
