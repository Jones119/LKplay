#!/system/bin/sh
# CS11 car-side tuning for 1080p @ 60Hz output (2026-10-10)
# Applied through the /data/.android rootd queue (full-capability root).
# Display parameters themselves are already persistent (settings global display_density_forced=240,
# no display_size override = native 1920x1080); the knobs below live in /sys and /proc and therefore
# must be re-applied after every reboot.
umask 000
export PATH=/system/bin:/system/xbin:/sbin

echo "=== 1080p60 TUNE START ==="
echo "-- before --"
echo "gpuMin3D=$(cat /sys/module/galcore/parameters/initgpu3DMinClock 2>&1)"
echo "swappiness=$(cat /proc/sys/vm/swappiness 2>&1)"
echo "sched=$(cat /sys/block/mmcblk2/queue/scheduler 2>&1) readahead=$(cat /sys/block/mmcblk2/queue/read_ahead_kb 2>&1)"

# GPU: never let the 3D clock drop to its lowest level, which shows up as UI/composition jank.
echo 3 > /sys/module/galcore/parameters/initgpu3DMinClock 2>&1

# Streaming workload: keep dirty writeback small so eMMC flushing cannot stall a frame.
echo 10 > /proc/sys/vm/swappiness 2>&1
echo 10 > /proc/sys/vm/dirty_ratio 2>&1
echo 3  > /proc/sys/vm/dirty_background_ratio 2>&1

# I/O: deadline for predictable latency, larger readahead for the media writes.
echo deadline > /sys/block/mmcblk2/queue/scheduler 2>&1
echo 2048 > /sys/block/mmcblk2/queue/read_ahead_kb 2>&1

# All four cores online and pinned at the top step (the ROM already defaults to performance).
for c in 0 1 2 3; do
  [ -f /sys/devices/system/cpu/cpu$c/online ] && echo 1 > /sys/devices/system/cpu/cpu$c/online 2>&1
  echo performance > /sys/devices/system/cpu/cpu$c/cpufreq/scaling_governor 2>&1
done

echo "-- after --"
echo "gpuMin3D=$(cat /sys/module/galcore/parameters/initgpu3DMinClock 2>&1)"
echo "swappiness=$(cat /proc/sys/vm/swappiness 2>&1) dirty=$(cat /proc/sys/vm/dirty_ratio 2>&1)/$(cat /proc/sys/vm/dirty_background_ratio 2>&1)"
echo "sched=$(cat /sys/block/mmcblk2/queue/scheduler 2>&1) readahead=$(cat /sys/block/mmcblk2/queue/read_ahead_kb 2>&1)"
for c in 0 1 2 3; do echo "cpu$c=$(cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq 2>&1) $(cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_governor 2>&1)"; done
echo "thermal=$(cat /sys/class/thermal/thermal_zone0/temp 2>&1)"
echo "=== 1080p60 TUNE END ==="
