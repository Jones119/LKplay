#!/bin/bash
# CS11 Root 侦察脚本 —— 车机通电 + USB 调试开启 + adb 可连时运行
# 用法: bash tools/cs11_root_recon.sh   （输出同时保存到 _analysis/root_recon_<ts>.txt）
# 特性：除 `su -c id` 试探外全部只读；su 试探带 15s 超时保护。
set -u
cd "$(dirname "$0")/.." || exit 1
ADB="adb -s 0123456789ABCDEF"
TS=$(date +%Y%m%d_%H%M%S)
OUT="_analysis/root_recon_${TS}.txt"
mkdir -p _analysis

run() { $ADB shell "$1" 2>&1; }   # 注意：车机 shell 无 head/grep，过滤一律放主机侧

{
echo "########## CS11 root 侦察 @ ${TS} ##########"
if ! $ADB get-state >/dev/null 2>&1; then
  echo "!! 车机不在线（检查：打火通电 / USB 调试 / adb devices）"; exit 1
fi

echo; echo "===== 0. 身份与版本 ====="
run "id; getprop ro.build.version.incremental; getprop ro.build.kernel.version; getprop ro.debuggable; getprop ro.secure"

echo; echo "===== 1. su 试探（最优先）====="
run "which su; echo '--- files:'; ls -la /system/xbin/su /system/bin/su /system/xbin/daemonsu /sbin/su /su 2>/dev/null; echo '--- done'"
echo "[su -c id 试探]："
if command -v timeout >/dev/null 2>&1; then
  timeout 15 $ADB shell "su -c id" 2>&1 | sed 's/^/[su] /'
  echo "[su] exit=$? (124=超时/等输入；uid=0=成功)"
else
  echo "(无 timeout 命令，手动执行: adb shell 'su -c id')"
fi

echo; echo "===== 2. 内核与模块 ====="
run "cat /proc/version; echo '--- cmdline:'; cat /proc/cmdline; echo '--- /system/lib/modules:'; ls /system/lib/modules/ 2>&1; echo '--- /lib/modules:'; ls /lib/modules/ 2>&1; echo '--- /proc/modules:'; cat /proc/modules 2>&1"

echo; echo "===== 3. 内核安全开关 ====="
run "echo -n 'kptr_restrict='; cat /proc/sys/kernel/kptr_restrict 2>&1; echo -n 'dmesg_restrict='; cat /proc/sys/kernel/dmesg_restrict 2>&1; echo -n 'randomize_va_space='; cat /proc/sys/kernel/randomize_va_space 2>&1; echo; getenforce 2>&1; echo '--- dmesg(head):'; dmesg 2>&1" | head -60

echo; echo "===== 4. 系统格局 ====="
run "mount" | head -40
echo "--- /system/xbin 清单:"
run "ls -la /system/xbin/" | head -60
echo "--- 相关 settings:"
run "settings get global adb_enabled; settings get system FACTORY_USED_AUTHORITY; settings get global development_settings_enabled"

echo; echo "===== 5. 网络现状（对照）====="
run "netcfg" | grep -iE 'usb|wlan|sit|lo|eth'
echo "--- /dev/tun 检查:"
run "ls -la /dev/tun /dev/net/tun 2>&1"
echo "--- 相关属性:"
run "getprop" | grep -iE 'ro.boot|selinux|eng|factory'

echo; echo "########## 完 ##########"
} 2>&1 | tee "$OUT"
echo; echo "已保存: $OUT"
