@echo off
chcp 65001 >nul
title CS11 车机 adb 连接
set "ADB=D:\Dev Environment\platform-tools\adb.exe"

echo.
echo  [CS11] 正在连接车机（Wi-Fi: 192.168.6.105:5555）...
"%ADB%" connect 192.168.6.105:5555
echo.
"%ADB%" devices -l
echo.
echo  列表里出现 192.168.6.105:5555 = 连接成功（Wi-Fi 通道）
echo  如果什么设备都没有，可能是车机重启过，先插 USB 线再执行：
echo      "%ADB%" tcpip 5555
echo.
pause
