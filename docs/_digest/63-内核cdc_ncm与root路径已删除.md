# 63 - 内核 cdc_ncm + root 传输路径已删除（含文档勘误）

日期：2026-10-13　提交：`2ab6be92`（删除本体）、`e0c4ec61`（退役属性拼写与陈旧说明）

本文**取代**以下旧描述（它们写于该路径仍在时，现已过期）：

- `58-用户态NCM桥(lwIP)路线评估与迁移清单.md` 的 §3、§9、§11、§12 中一切关于"内核兜底 / `ncm_legacy=kernel` / root 点火"的描述；
- `61-CS11车机不支持的设置与功能.md` §5 表里"内核 `cdc_ncm` 网卡传输（需 root + adb ⇒ 不可交付）"那一行的处置说明。

---

## 1. 删了什么

| 对象 | 内容 |
|---|---|
| `shared/…/network/Cs11NetdevBootstrap.kt` | **整个文件**：写 root 命令队列（`/data/.android/rootcmd.sh`）、把 iPhone 的 NCM 控制接口绑到 `cdc_ncm`、拉起 `usb1`、把链路本地地址固定为 `fe80::2` |
| `CarPlayController.attachSystemNetdev()` | 约 60 行：绑定监听器到内核网卡地址，含 4 次重试与 `EADDRNOTAVAIL`（DAD 未就绪）特判 |
| `CarPlayVpnService.attachSystemNetdev()` | 约 31 行：服务侧的同类挂接（不建 VPN） |
| 控制器里的 `directNetdev` 判定与 `if (!directNetdev)` 外层分支 | 选择内核网卡的那一层 |
| `import …Cs11NetdevBootstrap` | 一行 |

## 2. 为什么删

它**需要 adb 与 root 才能点火**：点火配方是 `/sbin/adb-ec -P 5038 connect 127.0.0.1:5555` + `/sbin/adb-ec … ppp shell:`；实车测量表明：

- 没有 adbd 的 TCP 5555 时，点火直接失败：`Error: Could not open adb service: shell:. Error: device not found`；
- 应用自身**写不了** `service.adb.tcp.port` / `persist.adb.tcp.port`（init 属性权限；`setprop` 返回 0 但值不变）⇒ 无法自己打开那条门；
- 因此**正常使用（关 USB 调试）下它永远不可用** ⇒ 从来不是可交付路径。

## 3. 行为变化（重要）

**不再有内核兜底。** 现在有线的传输选择只有两条：

| 平台 | 传输 |
|---|---|
| API 19（CS11） | **用户态 NCM 桥 + 自带 lwIP 2.2.0**（不需要 root/adb） |
| API 21+ | 原生 USB NCM（VPN） |
| 两者都不可用 | **仅控制通道**（明确降级） |

由此，当 APK 里**没有** `liblwip_bridge.so`（或 ABI 不匹配）时：有线会**明确降级为仅控制通道并写日志说明**，而不再"悄悄"走一条需要 root 的路。这正是既定的产品方向：**缺库要明确报错，而不是静默回退。**

## 4. 一并退役的东西

- `debug.diplay.ncm_legacy` 的 `kernel` / `off` / `root` / `netdev` 四个拼写：**现在解析为 `AUTO`**（提交 `e0c4ec61`）；`NcmLegacyPolicy` 里的示例文档同步更新；`NcmLegacyPolicyTest` 的断言改为"这些拼写落到 AUTO"；
- 仓库外的主机侧 root 点火脚本已删除：`tools/cs11_root_fire.sh`、`tools/cs11_probe_rootless.ps1`；
- `WildcardAddress` 里的 `usb1` 查询**保留**（仅更新说明）：它现在只是兜底——每个会话都会通过 `publishSessionBindAddress` 发布绑定地址，因此在 CS11 上它不会被走到。删除它会牵扯**所有**通道的 bind 逻辑，风险大于收益，故留待**单独一轮 + 实车验证**再处理。

## 5. 状态与遗留

- ✅ 代码删除完成：构建通过；`:shared` **1043 / 0**、`:common` **590 / 0**；
- ✅ 属性拼写退役 + 文档勘误（本文）+ 失效脚本删除；
- ⏳ **用户态 lwIP 桥在车机上的可用性仍未验证**（车机不在线；平板无法替代，因为需要真实 NCM 链路）。这仍是整条链路上**唯一**未闭环的验收项；
- ⏳ 遗留：`NcmLegacyMode.KERNEL` 枚举常量本身与 `allowsKernelFallback()` 仍在（现已无人可用它选中 ⇒ 语义上是死的），下次清理可一并删除并把相关断言改为断言"不存在该模式"。
