# 66 - 有线 CarPlay「音乐播一秒就停」修复（A2DP 释放）与两条方法论

日期：2026-10-13　提交：`7d3899dd` → `341e0f5b` → `be97ae05`　相关历史：docs 27 §6sexies / 30 / 39

## 1. 协议语义（这次核准的正确逻辑 ✓）

iOS 在 CarPlay 会话中会通过 **AirPlay 命令通道**发 `disableBluetooth`（带 `deviceID`）：

```
AirPlay command type=disableBluetooth params=deviceID          ← 实测（有线，两次）
```

含义与正确处置（参考实现 `LegacyVendorBluetoothHandoff` 的实证）：

| | **有线** | **无线** |
|---|---|---|
| 时机 | **立即**执行 ✓ | 先起 **45 s** 看门狗（`WIRELESS_HANDOFF_TIMEOUT_MILLIS` ✓）→ 等 AirPlay 媒体流内 **iAP2 type-130 隧道就绪** → 再执行 ✓ |
| 动作 | 断开手机的 **A2DP**（媒体）✓；参考的 `Result(a2dpHandled, hfpHandled)` 显示 **HFP 也一并处理** ✓ | 同上 ✓ |
| 蓝牙射频 | **不关** ✗ | **不关** ✗（无线通话/交接仍依赖它 ✓） |

⇒ 一句话：**"CarPlay 接管音频了，请放掉与这台 iPhone 的经典蓝牙连接（A2DP/HFP），但不要关蓝牙"** ✓；有线立即、无线等隧道 ✓。

## 2. 为什么之前修过又复发（三层断点 ✓）

历史修复（docs 27 §6sexies：车机 `BTMusicPlaybackEngine` 对 A2DP 发 AVRCP 暂停 ✗ ⇒ 用厂商 API 释放 ✓）代码一直在 ✓，但：

1. **控制器只在 WL 分支看这条命令** ✗ ⇒ 有线时只打印不执行 ✓；
2. **`common` 侧会话监听器 `onCommand` 空挂** ✗ ⇒ `uiListener.onCommand(...)` 无人实现 ✓；
3. **看护器拿不到手机蓝牙地址** ✗ ⇒ 实测 `phone media watchdog not started (bad phone address)` ✓。

## 3. 真正的根因：`phone_address` 里存的是 **IP**，不是 MAC ✗✓✓

```
18:41:07.967  bt instance via create(Context)=true
address=[ip]                              ← ★ 脱敏器给出的线索：交给 A2DP API 的值是 IP ★
18:41:07.824  phone media watchdog not started (bad phone address)
```
⇒ 无线流程把**网络地址**写进了 `phone_address` ✓，有线释放路径读同一字段 ✗ ⇒ `getRemoteDevice` 拒绝 ⇒ **每次释放都失败** ✓（`released=false` ✓、蓝牙图标一直亮 ✓）。车机侧同时确认 `A2dpStateMachine: Connected` ✓ ⇒ 正是会被 `BTMusicPlaybackEngine` 暂停的那条链路 ✓。

## 4. 修复（三处 ✓，`be97ae05`）

1. **`phone_bt_address` 专用键** ✓：只由 AirPlay `deviceInfo.deviceId` 写入，且**写入时用参考同款正则** `^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$` 校验 ✓；旧字段仅在**其本身是 MAC** 时被采用 ✓；再兜底到**配对表里的 iPhone** ✓（参考问后端 `connectedAddress/isBonded` 的同一思路 ✓）。
2. **直接释放** ✓：`releasePhoneA2dp(context, onLog)` 由 `disableBluetooth` 处理器直接调用 ✓，**不依赖看护器的启动路径** ✓（看护器仍负责周期复查 ✓）。
3. **日志接线** ✓：看护器与释放的每一步都写**文件诊断日志** ✓（`PhoneMediaAudioWatchdog(context, onLog)` ✓、`releasePhoneA2dp(context, onLog)` ✓，Activity 传 `appendLog` ✓）。

## 5. 实车验证（✓ 通过）

```
18:46:54.831  a2dp release address=… source=stored connected=true hfp=true released=true
18:46:54.838  released the phone's A2DP link=true
偏 好: phone_address / phone_bt_address / phone_name        ← 新键已写入 ✓
用户可见: 车机蓝牙图标由「蓝牙+声波」(A2DP) 变为「蓝牙+小电话」(HFP) ✓，音乐持续播放 ✓
```
⇒ 媒体音频交给 CarPlay 自己的通道 ✓、通话仍走车机免提 ✓ —— **协议要求的正确状态** ✓。

## 6. 两条方法论教训（比修复本身更重要 ✓）

1. **我们应用的 `Log.i` 在这台 ROM 上不落 logcat** ✗✓✓ —— 只有写**文件**的诊断日志可见 ✓。本轮因此得出一串**错误结论**（"看护器从未运行" ✗）⇒ **约定：诊断必须同时写文件落点** ✓。
2. **"日志里没有" ≠ "没执行"** ✗ —— 同样的坑（识别握手、iAP 隧道、NA、看护器）本轮出现了**四次** ✓。凡是"应该发生却没出现"的判定，**先证明观测通道可靠** ✓，再下结论 ✓。

## 7. 遗留

- 图表（仪表封面）那条线：交付链已打通到厂商**解码**（`DimImageUtils` ✓、`CHANGE COVER:` ✓、`FrameSenderBackground` ✓），但实车**仪表仍未显示** ✗ —— 关键变量是 **DIM 服务的模板协商窗口**（`sendDefaultTemplates` 只推 5 次 ✗，陈旧服务会让封面永远画不出 ✓，重启车机后 `addScreen Template id :2` 由 0 变 45 ✓）。下一步：会话建立时主动触发一次重新协商/重登记，或引导重启 ✓。
- `HFP` 是否也需要主动断（参考的 `hfpHandled` ✓）：本次实测 **`hfp=true` 保留** ✓，通话仍可用 ✓；若后续发现 CarPlay 通话与车机免提冲突，再按参考语义一并释放 ✓。
