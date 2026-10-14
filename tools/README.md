# tools/ —— CS11 CarPlay 工具集

## 日常恢复（重启车机后）

```bash
# 方案 A：一键恢复（PC 在场时手动跑一次）
./cs11_recover.sh

# 方案 B（推荐）：常驻守护 —— 车一上线自动完成全部恢复，之后持续自愈
./cs11_watchdog.sh              # 30s 周期；日志 _analysis/watchdog.log
```

## 组成

| 脚本 | 用途 | 依赖 |
|---|---|---|
| `cs11_watchdog.sh` | 常驻守护：设备上线 → 体检（rootd/usb1/app/rps）→ 按需自愈 | adb |
| `cs11_recover.sh` | 一次性全链恢复：点火 → 拉 app → 等 NCM 引导 → 调优重放 → 核验 | adb |
| `cs11_root_fire.sh` | root 链点火（adb-ec→ppp 劫持→modprobe→rootd） | 载荷在 `_analysis/stage_fake/` |
| `cs11_tune.sh` | 链路/CPU 调优重放（IRQ 亲和/RPS=2/ksoftirqd/taskset/内核旋钮） | 经 rootd 队列执行 |
| `cs11_root_recon.sh` | root 可行性侦察（零风险试探） | adb |
| `cs11-adb.bat` | Windows 侧 adb 快捷入口 | — |
| `hyphp_decode.py` | **HY 套件字符串池解码器**：`known` 出已知串 / `at <off> <len> <key>` 任意解 / `scan <key>` 滑窗 | 表=脱壳 dex 的 f38short; 原理见 docs/_digest/21 |
| `cs11_lossprobe.sh` | **丢包×逐核利用率相关性探针**（0.5s 粒度：逐核 busy/sirq + Udp6InErrors/RcvbufErrors + loadavg → CSV） | 裁定"丢包瞬间核是否满了"；配 summarize 分析 |
| `lossprobe_summarize.py` | lossprobe.log 分析器：丢包片逐核明细 + A/B/C 假设判定（已合成数据自测） | `python3 lossprobe_summarize.py lossprobe.log` |

## 说明

- 所有调优均为**内存态**，重启丢失；跑一次 `cs11_recover.sh` 或让 watchdog 常驻即可。
- IRQ 亲和已并入 app 的网卡引导（bootstrap），随 app 自动应用。
- 持久化方向调研见 `docs/_digest/19-重启恢复与持久化调研.md`（usbmuxd wrapper / kernellog 等候选与验证清单）。
