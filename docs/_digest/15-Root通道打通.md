# 15 - CS11 Root 通道打通（2026-10-06 真机）

> 结论先行：**已拿到可重复的 root 执行通道（euid=0 / fsuid=0）**。
> 性质 = "按需点火式 root"：每次要 root，一条 adb 命令触发；已用它以 root 读出
> `/init.rc`（27526B，权限 0750 root:root）与 `/sbin/ec_usb_filter`（23B，0750）。
> 尚未做持久化（无 su、无自启守护）——待下一步。

---

## 1. 利用链（真机验证）

```
adb shell (uid 2000)
  → /sbin/adb-ec                 [ECARX 定制 adb，-rwsr-sr-x root root，setuid!]
      → 子进程 euid=0 且 server 模式保留 root（v6 实测 Uid: 2000 0 0 0）
  → adb-ec 连接设备（service.adb.tcp.port=5555 的 adbd，shell 可设）
  → adb-ec ppp shell:            [ppp 命令：fork 后 execvp("pppd") —— PATH 搜索!]
  → PATH=/data/local/tmp/fake 先命中我们的假 pppd
  → 假 pppd 头行 = #!/system/bin/sh -p   [mksh -p 保住 euid0 不降权]
  → 脚本内以 root 执行任意命令
```

关键点：
- `execvp("pppd")` 是二进制里 **唯一 PATH 可劫持** 的 spawn（IDA 定位：execvp @0x16888，调用点 sub_1A424）。
- `launch_server` 用 /proc/self/exe 自路径（无劫持）；`adb-ec root` 被拒（设计如此）。
- **mksh 不带 -p 会主动把 euid0 降回 2000**（安全机制）；shebang `#!/system/bin/sh -p` 保权。
- **busybox 全套自降权**（读 root 文件必 EACCES）→ **特权操作一律用 toolbox 二进制**（dd/chmod/mkdir/ifconfig/ps…）**+ shell 内建重定向**（shell 自身 = (2000,0,0,0)，其 open() 以 fsuid0 生效）。

## 2. 证据（2026-10-06）

| # | 证据 | 出处 |
|---|------|------|
| 1 | 载荷进程 `Uid: 2000 0 0 0`（euid=0,suid=0,fsuid=0） | v6 点火 ps 现场 |
| 2 | root 属主产物：root_out.txt、initrc.txt、ecfilter.txt 均 `root root` | v8/v9 |
| 3 | **root 专属读取**：`dd if=/init.rc`（0750 root:root）27526B 全量读出 | v9（dd_initrc_rc=0） |
| 4 | **root 专属读取**：`dd if=/sbin/ec_usb_filter`（0750,23B）读成 | v9（dd_filter_rc=0） |
| 5 | root 专属操作：`chmod 777 /dev`+`mkdir /dev/shm` 成功；同一 chmod 从普通 shell 跑 = `Operation not permitted` | v8 |
| 6 | 失败对照：同载荷内 busybox cat /init.rc = EACCES（busybox 自降权）；shell 直接写 /data/local/tmp = EACCES（非 root 属主目录 + CapEff=0） | v8/v9 |

## 3. 能力边界（实测）

- ✅ **root 属主文件/目录的读写改**（fsuid0 == owner 判定）：/init.rc、/sbin/*、/dev、/ 下建文件（如可建 /tmp）。
- ✅ 以 root 执行任意 toolbox 命令 + shell 内建。
- ❌ 非 root 属主且需 capability 的操作还没验通：`CapBnd=0xc0`（只有 CAP_SETUID/SETGID 位），`CapEff=0`——待实测（如需 CAP_NET_ADMIN 的 ifconfig up / CAP_SYS_ADMIN 的 remount /system rw；/system/bin 里有一批 root:root 0750 文件，若能 remount 可替换，但 remount 本身要 CAP_SYS_ADMIN）。
- ❌ CapEff=0 来源：adbd 血统的 bounding set 被 ROM 压到 0xc0。EC 服务器是 init 血统（Bnd 可能全量），其 spawn 路径（carbit: /system/bin/ip、/system/bin/setprop 硬编码）暂未可劫持。
- ⚠️ 会话关闭会连杀载荷子进程（sleep 等）——点火命令里要"护送"(sleep) 或让载荷先完成任务。

## 4. 工具（工作区）

- `tools/cs11_root_fire.sh` — 一键点火：推载荷 → connect → ppp 触发 → 收 root_out.txt。
- 载荷三件套（`_analysis/stage_fake/`）：
  - `pppd` = `#!/system/bin/sh -p` + `. /data/local/tmp/root_cmd.sh > /data/.android/root_out.txt`
  - `root_cmd.sh` = 想以 root 跑的命令（编辑这里）
  - 输出通道：`/data/.android/`（777 目录，root 可写；读不到的产物让 root 先 chmod 644）

## 5. 周边发现（顺路捡的）

- `/dev/shm` 不存在且 /dev 为 755 → 本次已 `chmod 777 /dev; mkdir /dev/shm`（内存态，重启恢复）。
  adb-ec 服务器启动循环（每 1s kill+fork，root 权限）会按序尝试读/写 `/tmp/ADB_CONFIG`、`/dev/shm/ADB_CONFIG`、`/mnt/shm/ADB_CONFIG`（fstream 模式 "w"，nlohmann::json）。相关字符串："filter file=%s"、"usb:%d:%d"、"portNum=%d id=%04x:%04x"、"video/tbox/bingo"。**投放实验待做**（/tmp 现在可以用 root 造）。
- `/sbin` 全套：adb-ec、adb-ec-server.sh、adbd、e2fsck、ec_usb_filter、healthd。
- carbit 子系统（adb-ec 内）：USB 事件 → `ip route add default via 127.0.0.1` + `setprop sys.carbit.tun on/off`（硬编码 /system/bin/ip、/system/bin/setprop）——车机原生"CarPlay 桥(dongle)"支持栈的痕迹。
- `service.adb.tcp.port=5555` 全接口监听（0.0.0.0:5555）。
- USB 现状：2-1.1(TBox, 9 接口) 常驻；iPhone(2-1.4) 未在列（当前未连接）——usb1 网卡随 iPhone 出现。

## 6. 下一步

1. 补测试：chmod 644 战利品 + 读 ec_usb_filter 内容；`ifconfig usb1 up` 在 iPhone 在线时验证（CAP_NET_ADMIN 是否被 euid0 传统规则放行）。
2. 持久化：用 root 做 su/守护（候选：改 /system 需 remount；或利用现有 root 循环脚本）。
3. ADB_CONFIG 投放实验（/tmp 或 /dev/shm），观察服务器行为变化（"filter file=" 日志）。
4. 回到主线：媒体通道三墙（ush1 up + 路由），CarPlay 媒体。
