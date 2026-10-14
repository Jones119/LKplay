# 43 · 无线 type-102 卡顿根因：AEAD tag 被算进明文长度（2026-10-09）

> 一句话根因：`AudioStream.runData` 解密时用**sealed 长度**（密文 + 16 B Poly1305 tag）当作明文长度去分配
> 输出缓冲，`chachaOpenInto` 只写入真正的明文，于是**每个 access unit 尾部留下 16 个未触碰的 0**；
> 解码器把这 16 个 0 当成"第二个残缺帧"，每个 AU 产出 **2048 帧**并报 `0x4004 substituting silence`。
> 写 2048 帧 = **慢动作**；裁一半 = **杂音**。与解码器无关，与 103 路径无关。
>
> 纪律：本文只记录已发生的实测与已合入的修复；涉及设备的实验均已在平板 bench（Xiaomi Pad / Android 15）
> 上完成，`_traces_stutter.txt` 为唯一日志来源，两份 `.xcap` 为唯一字节来源。

## 1. 症状与归因起点

用户听得见的两种形态（同一台平板、同一台 iPhone、无线 CarPlay）：

| 代码状态 | 听感 | 客观读数 |
|---|---|---|
| 原始（未修） | **慢动作**、断 | `pcm accounting: framesPerBuffer=2048`、`writtenFrames≈48,000 帧/s`、`queue` 顶到 192 上限、`skippedMs≈2415/5000 ms` |
| 改成"只写前 `declaredFrames` 帧" | **语速对了但有杂音** | `skippedMs=0`、`queue≈38`，但 `headHex=0000…` 而 `tailHex` 是正常波形 |

两条读数看似矛盾（"写 2048 帧"却只消费 48,000 帧/s），**根因就在这个矛盾里**：多出来的那一半不是音频，是
**缓冲区尾部没被写过的 0**。前半是真实音频（`headHex` 开头的 0 只是解码器 priming 的几十个样本，
`headRms` 因此仍然很高），后半是 0 —— 所以"裁剪固定一半"会**丢掉另一半真实音频**，听到的就是错位+杂音。

## 2. 证据链

### 2.1 三版 A/B（用户实测，父 agent 组织；`bytes=` 一行来自父 agent 的 A/B 记录）

| 版本 | 提交 | 102 的 `audio decoder output bytes=` | 结论 |
|---|---|---|---|
| 10-07 基线 | `86dac829` | **4096**（1024 帧） | 健康 |
| 10-08 | `090f01e` | 4096 | 健康 |
| HEAD（引入 `chachaOpenInto` 后） | `4f8c4b7d` | **8192**（2048 帧） | 病态 |

三版之间**解码器选择与配置零改动**（`090f01e → HEAD` 只改了解密路径；`86dac829 → 090f01e` 只加了回退枚举），
所以差异只能来自"喂进解码器的字节"。这正是本 bug 的落点。

### 2.2 两批抓包的等价性（`_analysis/audio-capture/*.xcap`，512 包各一份）

| 抓包 | RTP 载荷长度 min / median / max | RTP 时间戳跨度 | 参考解码（ffmpeg，套我们用过的同一个 ADTS 头） |
|---|---|---|---|
| `audio-…788798-type-102.xcap`（全静音 priming） | **22 / 22 / 22 B**（512 包全同） | 523,264 样本 = **10.901 s @48k** | 1024 帧/包 |
| `audio-…809123-type-102.xcap`（真实音乐） | **22 / 662 / 711 B** | 523,264 样本 = **10.901 s @48k** | 1024 帧/包 |

- **RTP 时间戳步长恒为 1024**（512 包，511 个间隔全为 1024）：发送侧按 1024 样本/AU、48 kHz 计时。
- 抓包 monotonic 跨度 **11.125 s**，解出的音频 **10.923 s**，时间戳跨度 **10.901 s** ⇒ **发送侧 1× 实时**，
  **不是 2× 超发**（这一条否掉了"iOS 预送"假设）。
- 参考解码器对**我们实际喂进去的那几帧**单独解码：每种都恰好 **4096 B = 1024 帧**（102 与 103 都一样）
  ⇒ **喂进解码器的"内容"是对的**，问题在尾部那 16 B。

### 2.3 同进程 103(4096) / 102(8192) 对照

同一份 trace、同一个进程、同一个 `c2.android.aac.decoder` 组件：

```
18:55（type 103，缓冲通道）  audio decoder output codec=AAC_LC buffers=… bytes=4096
18:56（type 102，实时通道）  audio decoder output codec=AAC_LC buffers=1   bytes=8192
```

两条路径的 `MediaFormat` **逐键相同**（实测 CCodec dump 两边都是
`is-adts=1 / sample-rate=48000 / channel-count=2 / csd0=1190 / profile=2`，输出 `mime=audio/raw, sample-rate=48000`），
所以差异不可能是配置。**103 之所以一直正常，是因为它走 `BufferedAudioStream`，不经过 `AudioStream.runData`
那段解密**（`BufferedAudioStream.Frame` 直接持有解密后的 rtp），这正是"同组件两条路径表现不同"的解释。

### 2.4 尾部 0 与两次听感的对应

`AudioStream.runData` 的分配（修复前）：

```kotlin
val sealedEnd = wire.size - NONCE_LEN
val plaintextLength = sealedEnd - RTP_HEADER_LEN   // ← 这是「密文 + 16 B tag」的长度
val rtp = ByteArray(RTP_HEADER_LEN + plaintextLength)  // ← 每包多分配 16 字节
val payloadBytes = AirPlayCrypto.chachaOpenInto(...)   // ← 只写入真正明文，返回 = plaintextLength - 16
                                                       //   ⇒ 尾部 16 B 保持 ByteArray 的默认 0
```

- FDK 在 AU 尾部读到 16 个 0 ⇒ 尝试解"第二个残缺帧" ⇒ `aacDecoder_DecodeFrame decoderErr = 0x4004`
  + `AAC decoder returned error 0x4004, substituting silence`（实测错误率恒定 ≈ 每 AU 2 条）。
- 用平台计数器交叉验证：`[mId] audio-debug-dec Stats … Qin:118 DQin:118/118 DQout:118/354`（每 5 s 118 个输入 = 23.6/s），
  而 `audio stats: writtenFrames≈241,664/5 s = 48,000 帧/s` ⇒ **每输入 2048 帧**，恰好实时（23.6 × 2048 ≈ 48,000）。
  写 2048 帧给 48 kHz 轨道 ⇒ 波形被当两倍时长播放 ⇒ **慢动作**。
- **积压的独立旁证**：同一段病态日志里 `totalWrittenFrames` 以每 5 s **+240,640** 的斜率增长
  （230,400 → 471,040 → 710,656），而 `playbackAdvanceFrames` 每 5 s 稳定 **+241,920**（= 48 kHz 实时）。
  写入侧与播放侧**同时**以 48,000 帧/s 前进 ⇒ 多写的那些帧并没有被播放掉，只能堆在轨道里——
  与"每 AU 2048 帧"一致，也与 `queue` 长期顶在 192 上限、`skippedMs≈2415` 一致。

### 2.5 为什么"只比开头 8 字节"的核对会漏掉它（方法论教训）

当时用来"证明喂进去的字节没问题"的核对是：

```
抓包  payloadHead=21000340681c
日志  fedHead=21000340681c        ⇒ 判定"一致，排除喂字节问题"
```

**这个核对在结构上不可能发现本 bug**：`0x21 0x00 0x03 0x40 0x68 0x1c` 是包头，而多出来的 16 B 在**尾部**。
更糟的是，尾部是 **0**，而 0 是"看起来最正常"的字节——在十六进制 dump 里全 0 甚至不会引起注意。

实际留下的痕迹（当时若比**全长与尾部**就能看到）：

| 版本 | 日志里那一行 | 说明 |
|---|---|---|
| 病态（`chachaOpenInto`，ADTS 形态） | `audio AAC access unit bytes=22` + `audio AAC feed type=102 n=1 rtpBytes=34 accessUnitBytes=22 adtsBytes=29 adtsHead=fff14c8003bffc21000340681c0000…` | 22 B 的载荷被套上 7 B ADTS ⇒ 29 B；`adtsHead` 头 8 字节 `fff14c8003bffc` 之后的载荷是 `21000340681c` **接 16 个 0** |
| 健康（旧 `chachaOpen`，A/B 版） | `accessUnitBytes=22 fedBytes=22 raw=true fedHead=21000340681c…` | 长度自洽，尾部就是 `1c` |

两次核对都只比了 `…21000340681c` 这一段（相同），而差异在**长度**（29 vs 22）与**尾部**（`0000…` vs 结尾 `1c`）。
换句话说：**能发现它的两个量（长度、尾部）当时都没被看**。

教训（写进纪律）：
1. **长度不变式比"头几个字节相等"强得多**：任何"缓冲区分配 → 外部填充 → 返回长度"的组合，
   都要断言 `返回长度 == 分配长度`，并且**检查尾部**。
2. **跨版本/跨路径对照时比"全长"**：同进程 103=4096 vs 102=8192 这条现成对照，第一次就该追到底，
   而不是用"103 走另一条路径"一句带过。
3. **0 是危险值**：默认初始化的 `ByteArray` 就是 0，所以"尾部 0"既可能是正常静音，也可能是**没被写过的内存**。
   区分它们只能靠**长度**，不能靠数值。

## 3. 修复

`shared/src/main/java/com/shilapi/xcertplay/airplay/AudioStream.kt`：

- `plaintextLength = sealedLength - TAG_LEN`（sealed 长度减 16 B Poly1305 tag），
  并把这段线格式算术抽成可测的 `AudioStreamWire.sealedPlaintextLength/sealedPayloadLength`；
- 分配长度与实际解密结果对齐后**加校验**：`payloadBytes != plaintextLength` 即按解密失败丢包并计入
  `authenticationFailures`（宁可不播，也不把未写过的字节送进解码器）；
- 数据报短到装不下 tag（`plaintextLength < 0`）同样按失败处理，不再走后续路径。

## 4. 回归测试

`shared/src/test/java/com/shilapi/xcertplay/airplay/AudioStreamWireTest.kt`（3 例，纯 JVM，无需设备）：

1. `plaintextLengthExcludesTheAeadTagAndTheNonce` —— 线格式算术：652 B 明文 ⇒ 668 B sealed ⇒ 704 B wire；
   35 B wire 已装不下 tag（返回负值）。
2. `decryptingIntoASealedLengthBufferLeavesNoTrailingZeroes` —— 用真实 ChaCha20-Poly1305 构造 sealed 输入，
   按修好后的长度分配，断言返回长度 == 明文长度、内容逐字节相等、**尾部不是 0**。
3. `theOldSealedLengthBufferWouldHaveKeptSixteenZeroBytes` —— 用**修复前的长度**分配，
   断言尾部确实留下 **16 个未被写过的 0**；这一条把 bug 本身钉死，防止有人"简化"回去。

`:shared` 710 例 / 3 失败（失败集仍是既存的 `HotspotJoinNormalizedTransactionTest`；
710 = 原 707 + 本次 3），`:common` 544 / 6（不变）。

## 5. 没有走通的两条路（记录以免重犯）

排查过程中试过两个"治症"方案，都已回退，结论留在这里：

1. **固定截断**（只写前 `declaredFrames` 帧）：时基修好了（`skippedMs` 2415→0、`queue` 192→38），
   但丢的是**真实音频的后半**，听感变成"语速对但杂音"。已由 `headHex/tailHex` 证伪并回退。
2. **裸 AU + 显式 CSD**（`is-adts=0` + `csd-0`，不合成 ADTS 头）：把 `0x4004` 从"全程恒定"降到
   "只有开头约 10 s"，但 `framesPerBuffer` 仍是 2048 —— 因为它治的是"解码器怎么读"，而真正多出来的
   16 B 是**解密层**留下的，不在它作用范围。也已回退。

**判断依据**：`audio overproduce` 探针给出的形状（`headZeros=512`、`tailZeros=512`、`q4Rms=0`、
`stereoIdentical=true`）说明"多出来的部分是填充/重复而非独立内容"；而 `2.2` 的参考解码说明
"喂进去的内容本身是对的"。两条合起来才指向"缓冲尾部没被写"，而不是"发送超发"或"解码器上采样"。

## 6. 排查中确认的两条**否定**结论（同样有价值）

- **不是 SBR/96 kHz 上采样**：`audio decoder output format type=102 decoded=48000Hz/2ch`，
  解码器自报 48 kHz / 2 声道，没有 96 kHz。
- **不是 mono→stereo 复制**：`stereoIdentical=true` 只出现在**病态**缓冲上（因为尾部是 0、
  前半是解码器上混后的双声道），健康路径（103）为 `false`；且它不能解释 16 B 的固定多出量。

## 7. 待办 / 未决

- 车机（CS11，API 19，`OMX.google.aac.decoder`）上的同一路径**尚未实机复测**。该 ROM 的解码器是同一
  FDK 系，理论上"尾部 16 个 0"会以同样方式被误读；上车后应直接看
  `audio decoder output … bytes=` 是否回到 4096 与 `0x4004` 是否归零（本文的修复不依赖解码器差异）。
- 我方 `inputQueuedTotal`/`Receive: … packets` 存在**逐字重复、同毫秒的双发**。**根因已定位并修复**（见 §9），
  且**不是计数双发而是写入双发**；本文所有结论都已改用平台计数器（`Qin/DQin/DQout`、`playbackHeadPosition`）
  与写入侧 `writtenFrames` 交叉验证，不受其影响。

## 8. 证据文件索引

| 内容 | 路径 |
|---|---|
| 完整 logcat（含全部探针行） | `D:\Development\DiAuto-DiPlay\_traces_stutter.txt` |
| type-102 抓包 ×2（512 包各一份） | `_analysis/audio-capture/audio-1791543778798-type-102.xcap`、`audio-1791543809123-type-102.xcap` |
| 抓包解析（线格式 + AU 头 + 尺寸分布） | `_analysis/parse_xcap.py`、`_analysis/raw_heads.py`、`_analysis/channel_probe.py` |
| 参考解码（每包帧数、时长、样本率） | `_analysis/full_decode.py`、`_analysis/exact_frames.py`、`_analysis/layout_probe.py` |
| 形状判定（前导/尾部静音、四分之一 RMS） | `_analysis/pcm_shape.py`、`_analysis/window_check.py` |
| 单包喂入字节的解码对照 | `_analysis/cmp_paths.py`、`_analysis/single.py` |

## 9. 后记：日志"双发"的根因与修复（同日）

§7 那条待办已解决，**不是计数双发，而是写入双发**：

```
AirPlaySession.debugLog(message)          Log.i(TAG, message)        ← 第 1 次写 logcat
      └─ listener.onDebugLog(message)
            └─ CarPlayController.onDebugLog() → debugLog(message)
                      Log.i(TAG, message)                            ← 第 2 次写 logcat（同 TAG 同毫秒）
                      uiListener?.onDebugLog(message)                ← UI/诊断缓冲，这里才是该做的
```

**修正**：`CarPlayController.onDebugLog` 改为只做 UI/诊断转发（`uiListener?.onDebugLog`），不再调 `debugLog()`
重复写 logcat。受影响的就是所有经 `session.logDebug` 出去的消息：`Receive: audio/video …`、
`Buffered audio: queued …`、各里程碑行——它们以前**每行都是两条**。

**顺带纠正本会话的一个错误推断**：我曾据"我方 `inputQueuedTotal` 每 5 s +236 而平台 `Qin:118`"
判断"我方计数器双发"——**那是错的**。健康会话里我方 +236 与平台 `Qin:237` **完全一致**（都是 47.4 AU/s）；
坏会话里平台只有 118，是因为**每包被解成 2 倍 PCM、队列只能喂进一半**，与计数无关。
⇒ 教训：**怀疑计数之前先用平台计数器对齐一次**；"两条一模一样的日志行"更可能是**两次写入**，而不是两个数据源。
