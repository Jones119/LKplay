#!/system/bin/sh
# CS11 wireless CarPlay system tuning — volatile, re-apply after every reboot.
# Apply via the rootd queue: adb push tools/cs11_wireless_tune.sh /data/.android/rootcmd.sh
#
# Why this shape (measured 2026-10-07, see docs/_digest/29):
#   * IRQ 79 (ssi@0202c000, the DIRANA/SSI2 audio link) fires ~48,100 times/s — one
#     interrupt per 48 kHz sample — and is 100% pinned to CPU0. Nothing in userspace
#     can stop it, so this script moves everything else off CPU0 instead:
#       CPU0 = audio IRQ storm + misc peripherals (leave it alone)
#       CPU1 = Wi-Fi SDIO IRQ (mmc1) + its receive softirq (RPS)
#       CPU2+CPU3 = the CarPlay app
#   * Affinity masks are hex: 2 = CPU1, 4 = CPU2, 8 = CPU3, c = CPU2+CPU3.

echo "=== CS11 wireless tune ==="

echo "--- 1) Wi-Fi SDIO IRQ (mmc1) and USB off CPU0 -> CPU1"
for n in 55 72 75; do
  if [ -w /proc/irq/$n/smp_affinity ]; then
    echo 2 > /proc/irq/$n/smp_affinity
    echo -n "irq$n="; cat /proc/irq/$n/smp_affinity
  else
    echo "irq$n not writable/present"
  fi
done

echo "--- 2) receive softirq (RPS) on the same core as the Wi-Fi IRQ"
for q in wlan0 usb1; do
  if [ -w /sys/class/net/$q/queues/rx-0/rps_cpus ]; then
    echo 2 > /sys/class/net/$q/queues/rx-0/rps_cpus
    echo -n "$q rps_cpus="; cat /sys/class/net/$q/queues/rx-0/rps_cpus
  fi
done

echo "--- 3) kernel network buffers"
echo 4000    > /proc/sys/net/core/netdev_max_backlog
echo 2097152 > /proc/sys/net/core/rmem_max
echo 524288  > /proc/sys/net/core/rmem_default
for k in net/core/netdev_max_backlog net/core/rmem_max net/core/rmem_default; do
  echo -n "$k="; cat /proc/sys/$k
done

echo "--- 4) eMMC read-ahead (UI/app IO)"
if [ -w /sys/block/mmcblk2/queue/read_ahead_kb ]; then
  echo 512 > /sys/block/mmcblk2/queue/read_ahead_kb
  echo -n "read_ahead_kb="; cat /sys/block/mmcblk2/queue/read_ahead_kb
fi

echo "--- 5) network softirq threads above the app threads"
for t in $(busybox ps | busybox awk '/ksoftirqd/{print $1}'); do busybox renice -5 $t 2>/dev/null; done
busybox ps | busybox grep "[k]softirqd" | busybox head -4

echo "--- 6) pin the CarPlay app to CPU2+CPU3 (away from the storm and the Wi-Fi IRQ)"
APP=$(busybox pidof com.shihab.diplay.hudtest 2>/dev/null)
if [ -n "$APP" ]; then
  for p in $APP; do busybox taskset -p 0xc $p 2>/dev/null; done
  for p in $APP; do echo -n "pid$p "; busybox grep Cpus_allowed_list /proc/$p/status; done
else
  echo "app not running — re-run this script after it starts (or add it to the bootstrap)"
fi

echo "=== CS11_WIRELESS_TUNE_DONE ==="
