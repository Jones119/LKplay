# 16 - CarPlay 媒体攻坚：内核 NCM 网卡打通 + TUN 墙最终定性（2026-10-06 夜）

> **一页结论**：
> 1. **Root 全权限到手**（二级跳：`/proc/sys/kernel/modprobe` 劫持 → 内核以 CapEff=全开的完全体 root 执行我们的脚本 → 建立 rootd 全权限命令队列守护）。
> 2. **内核 NCM 网卡 `usb1` 打通**（rootd 强制 `cdc_ncm` 绑定 iPhone 的 NCM 对-1）：link ready、carrier=1、100/100mbit、IPv6 LL。
> 3. **媒体最后一道墙最终定性**：app 日志实锤 `wired VPN/NCM transport attach result=failed Cannot create interface` → 原因是 **CS11 内核没有 TUN 驱动**（/proc/misc 无 tun、无 tun.ko）→ app 的 "VPN+TUN 中继" 传输层永远建不起来 → 视频不上屏（"CS11 degraded mode: control channel only" + 30s 重连循环）。
> 4. **出路**：app 传输层改造为 **"直连系统网卡"**（抛弃 VPN/TUN；CarPlay 栈经系统 IPv6 栈直连手机 `fe80::...%usb1`）——移植代码里本来就写着 "NCM 由内核 cdc_ncm 接管"，这是设计本意。

---

## 1. Root 二级跳（全权限达成）

```
一级（见 15 号文档）: adb-ec(setuid) → ppp execvp 劫持 → #!sh -p → euid0/fsuid0（但 CapEff=0，Bnd=0xc0）
二级（本次）:
  echo /data/.android/mp.sh > /proc/sys/kernel/modprobe   （fsuid0=owner root，直接可写！）
  dd if=/dev/zero of=/data/.android/trig bs=1 count=16; chmod 755 trig
  ./trig   → 内核 binfmt 未识别（前4字节不可打印）→ request_module("binfmt-0000")
           → call_usermodehelper 执行 modprobe 路径 = mp.sh
           → 内核线程血统：uid=0, CapEff=0000001fffffffff（全 37 位！全 capability！）
```

- mp.sh（全权限）职责：bind NCM、拉起 usb1、启动 **rootd 命令队列守护**。
- **rootd 用法**（易失，重启后一键重建）：把命令写入 `/data/.android/rootcmd.sh` → 1 秒内被 rootd（全权限）执行 → 输出 `/data/.android/rootout.txt`。
- 附带战果：`mount -o remount,rw /system` 成功、`touch /system/.probe_write` 成功（=可为持久化铺路）。
- `/data/.android/` 工作目录：mp.sh / rootd.sh / trig / rootcmd.sh / rootout.txt / applogs/。

## 2. 内核 NCM 网卡（usb1）打通

- iPhone config 6 = 7 接口：PTP(0) / usbmux(1) / iAP2(2) / **NCM 对-1：comm(3)+data(4)** / **NCM 对-2：comm(5)+data(6)**。
- `cdc_ncm`/`usbnet`/`cdc_ether`/`rndis_host` 模块**早已加载**，但 iPhone 的 NCM 未被绑定：
  - 开机无 `/sbin/modprobe`（autoload 缺失）；
  - app 的 USB 循环（"did not claim interface before use" + bind() failure，每 ~33s）持续干扰。
- **强制绑定**（rootd 全权限）：`echo 2-1.4:6.3 > /sys/bus/usb/drivers/cdc_ncm/bind` → usb1 诞生：
  ```
  cdc_ncm 2-1.4:6.3 usb1: register ... MAC 9e:92:4f:09:bf:8b
  ifconfig usb1 up → operstate=up, carrier=1, IPv6 fe80::9c92:4fff:fe09:bf8b
  dmesg: cdc_ncm: usb1: network connection: connected / 100 mbit/s downlink 100 mbit/s uplink
  ```
- NCM 对-2（6.5）bind 失败（dmesg "bind() failure"），一对待定。
- dhcpcd DISCOVER 无应答 → 手机不发 DHCP → **CarPlay NCM = IPv6 链路本地模型**（与应用代码注释一致）。

## 3. App 全链路实录（diplay.log，已归档 _analysis/applogs/）

握手全通：MFi(LocalOffline) → USB 发现 → usbmux → lockdown carkit → iAP2 CSM → **认证接受（0xaa05）** → 电源/播放/路况订阅 → `0x4300 CarPlayAvailability` → **`0x4301 CarPlayStartSession`** → phone 推送 PowerUpdate/"中国电信"/NowPlaying/CallState。
控制器启动：`starting controller display=1280x594 negotiated=1280x594 scale=100% hevc=false microphone=true`。

**卡点（每轮）**：
```
wired VPN service bound; attaching NCM transport
wired VPN/NCM transport attach result=failed Cannot create interface
CS11 degraded mode: VPN attach failed; control channel only
失败：Cannot create interface; retrying in 2000/4000/8000/16000/30000ms
```
→ 重连循环（每次重连都会重跑 iAP2 全握手并重发 0x4301，但媒体永不建立）。

## 4. TUN 墙最终定性

- `/proc/misc` 无 `tun`；`/proc/devices` 无；无 `tun.ko`；`/dev/tun` 不存在 → **CS11 内核未编译 TUN 驱动**（ECARX 定制内核砍掉）。
- `Cannot create interface` ＝ AOSP `Vpn.java` 里 `IllegalStateException("Cannot create interface")` —— system_server 的 `nativeCreateInterface`（open `/dev/tun` + TUNSETIFF）失败（ENOENT）→ Java 层包装抛出 → 我们移植的 CarPlayVpnService.attach() 捕获 → 降级。
- **BYD DiLink 能跑** = BYD ROM 有 tun；我们这台没有 → 该架构在 CS11 上物理不可行。

## 5. 出路：直连系统网卡（下一步开发）

已具备的条件：root 全权限 + usb1（内核 cdc_ncm，link ready）+ 移植代码本意（"NCM 由内核 cdc_ncm 接管"）。

改造要点（CarPlay-CS11 仓库）：
1. `CarPlayController.attachVpn()` 分支：新增 **SystemNetdevTransport**（不建 VPN）：
   - 保障 usb1 存在/UP/IPv6（调 root 通道或引导脚本）；
   - CarPlay 栈 endpoint 直连手机链路本地地址（`config.linkLocal` + 实际媒体端口），socket 带 scope（`Inet6Address.getByAddress(null, bytes, ifIndex)`）。
2. 移除/旁路 app 对 raw NCM 的 usbfs 认领路径（`NcmUsbBridge` 的 api<21 分支）——它既不认领就传输（dmesg 噪声），又周期性踢掉内核绑定。
3. 媒体数据面：手机 →（NCM/IPv6）→ 内核 usb1 → 系统栈 → app 的 CarPlay 接收端（H.264 硬解由 i.MX VPU 承担）。
4. 备选（不推荐）：构建匹配 3.10.53+ 的 tun.ko——ECARX 内核定制，vermagic/CRC 匹配风险高。

## 6. 风险与边界

- rootd 易失：车机重启后需重跑 `tools/cs11_root_fire.sh` + 触发链（或后续做持久化：/system 已可写，可考虑 usbmuxd wrapper 自启）。
- app 运行中会持续干扰内核 NCM 绑定（其 sloppy raw 路径）；直连改造后一并解决。
- `/proc/sys/kernel/modprobe` 已被改为 mp.sh（重启恢复 /sbin/modprobe）——实验正常行为，注意。
- 手机 side 媒体服务在 NCM 上的监听地址/端口（IPv6 LL + 端口）需在直连改造时实测确认（mDNS/RTSP）。

## 7. 产物清单

- `tools/cs11_root_fire.sh` —— 一级点火（euid0）。
- `_analysis/stage_fake/`：`pppd`(-p 保权载荷)、`root_cmd.sh`、`mp.sh`(modprobe 落地点/全权限入口)、`rootd.sh`(队列守护)、`rootcmd.sh`(任务样例)。
- `_analysis/applogs/applogs/*.log` —— app 完整日志（810 行，含全部握手 + 卡点证据）。
- `_analysis/scr1-3.png` —— 车机屏幕（"正在准备 CarPlay..."状态页）。
- `_analysis/cs11_initrc.txt` —— root 读出的 /init.rc（27526B）。
- 内核事实：`/sbin/ec_usb_filter` = `[{"tbox":[2,1,1]}]`（USB 端口过滤器，JSON）；`/dev/apple_auth_chip` 存在（ECARX 原生 CarPlay 芯片痕迹）。

## 8. 下一步

1. **直连传输改造**（主攻，见 §5）。
2. 实测手机 NCM 侧服务发现（IPv6 LL 邻居/mDNS/媒体端口）。
3. root 持久化（usbmuxd wrapper 或 init 服务注入）。
4. ADB_CONFIG 投放实验（/dev/shm、已知 JSON 模式）。
