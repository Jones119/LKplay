#!/system/bin/sh
# CS11 CarPlay link tuning — re-apply after every reboot (all settings are volatile).
# Run via the rootd queue: adb push cs11_tune.sh /data/.android/rootcmd.sh
# 1) USB controller IRQs off CPU0 (the busiest core) — jitter there drops NCM packets.
for n in $(busybox awk '/usb/{gsub(":","",$1); print $1}' /proc/interrupts 2>/dev/null); do
  echo e > /proc/irq/$n/smp_affinity 2>/dev/null
done
# 2) NCM receive softirq on CPU1 only (the app is pinned off CPU1).
echo 2 > /sys/class/net/usb1/queues/rx-0/rps_cpus 2>/dev/null
# 3) Kernel network backlogs / buffers.
echo 4000 > /proc/sys/net/core/netdev_max_backlog
echo 2097152 > /proc/sys/net/core/rmem_max
echo 524288 > /proc/sys/net/core/rmem_default
# 4) Raise the deferred network softirq threads above the app threads.
for t in $(busybox ps | busybox awk '/ksoftirqd/{print $1}'); do
  busybox renice -5 $t 2>/dev/null
done
# 5) Pin the app to CPU0/2/3, leaving CPU1 to the USB IRQ and the NCM softirq.
APP_PID=$(busybox pidof com.shihab.diplay.hudtest 2>/dev/null | busybox awk '{print $1}')
if [ -n "$APP_PID" ]; then
  busybox taskset -p 0xd $APP_PID 2>/dev/null
fi
echo CS11_TUNE_DONE
