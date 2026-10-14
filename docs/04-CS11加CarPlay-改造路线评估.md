# 04 · 给领克 CS11 加 CarPlay：改造基座评估与路线

评估日期：2026-10-06 · 目标设备：领克03 CS11（NXP i.MX6Q / 2GB RAM / **Android 4.4.3 = API 19**）· 目标：在本机车机上获得 CarPlay（改造向，非盒子方案）

## 0. 一句话结论

- **DiAuto 完全无关**（它是 Android Auto 接收端，不是 CarPlay）。
- **推荐基座：`shilapi/xcertplay`**（唯一"正统上游"，代码结构对向下移植最友好，见 §2）。
- 备选/参考：`ztecjo/nexuscp`（minSdk 24、"外网同网"模式默认内置）；`Roylyl/AndroidPlay`（中文、系统热点方案、同为 Android 9 起）。
- `shihabal3amri/DiPlay` **不建议当基座**（BYD 特化 + 更多现代栈），但它是很好的"补丁来源"（Same-LAN、USB 处理、ExoPlayer 视频等通用改进都在它的提交里）。
- 心理预期：这不是配置级改造，是一次 **API 28 → 19 的下探移植**。代码结构意外友好（§3），但 CS11 的硬件行为（USB、Wi-Fi、老 MediaCodec）是无法远程验证的未知项。

## 1. 候选全景（2026-10-06 实测）

| 项目 | Star | 语言 | 平台/门槛 | 定位 | 适合作基座？ |
| --- | --- | --- | --- | --- | --- |
| **shilapi/xcertplay** | 1,240 | Kotlin | Android 9+ (minSdk 28) | 安卓车机 CarPlay 接收端（MFi 芯片/CH341/本地文件/远程） | ✅ **主推** |
| shihabal3amri/DiPlay | 4,825 | Kotlin | Android 9+ | xcertplay 的 BYD 特化分支（+完整大屏 UI/集群） | 补丁来源 |
| ztecjo/nexuscp | 5 | Kotlin | Android 7+ (minSdk 24) | xcertplay+LIVI 衍生，多品牌，内嵌身份，外部 Wi-Fi 默认，浏览器镜像 | 🔶 备选参考 |
| Roylyl/AndroidPlay | 29 | Kotlin | Android 9+ | 中文项目：系统热点 + 蓝牙引导的无线 CarPlay 接收 | 参考 |
| fython/wheelplay | 3 | Kotlin | - | "局域网 CarPlay 服务端" | 参考 |
| youcci/playport | 246 | Kotlin | - | 浏览器里的 CarPlay（服务端渲染） | ❌ 老 WebView 不行 |
| f-io/LIVI | 851 | TypeScript | Linux/macOS | 车机端 CarPlay+AA（协议研究源头之一） | ❌ 非安卓 |
| niellun/FastCarPlay | 158 | C++ | Linux/Pi | carlinkit 盒子固件替代 | ❌ |
| jwinarske/carlinkit-cxx | 8 | C++ | Linux | 同上（carlinkit 盒子） | ❌ |
| amineross/showcase | 167 | C | 越狱 iOS | iPad 当 CarPlay 接收端 | ❌ |
| harrylepotter/carplay-receiver | 414 | Python | Linux | 树莓派向 | ❌ |

> 冷知识：DiPlay、nexuscp 内嵌的 MFi 身份与我们本地从 DiPlay APK 提取出的 `identity.pk8` + `certificate.p7b` **逐字节相同**（sha256 全同），是全社区流通的同一份 Carlinkit 提取物。见 §4。

## 2. 为什么选 xcertplay 当基座

1. **它是所有 Android 端 CarPlay 接收端的源头**：DiPlay 是它的分支；nexuscp 声明"Built using open sources from xcertplay, LIVI"；AndroidPlay 同属这一族。选上游而不是分支，少了各分支的私有包袱。
2. **模块结构对移植极友好**（实测）：
   - `shared/` 26,256 行 = 协议核心（iAP2、AirPlay、MFi、NCM/USB、MPI 解密、媒体管线）**零 Compose、零 UI**；
   - `common/` 6,104 行里 UI 其实几乎是"传统安卓"：`CarPlayHostActivity.kt` 4,165 行全是 View/SurfaceView/自绘；**Compose 只有 `MainActivity.kt`(122 行)+主题文件(~100 行)**；
   - `mobile/`、`automotive/` 只是薄壳（≈0 行代码）。
   - → 移植工作集中在"平台 API 替换 + 一个 122 行入口重写"，而不是"重写整个 UI"。
3. **原生支持"本地证书文件"模式**（README：Local files：`.p7b` + `.pk8` 用系统文件选择器加载，启动前自动校验配对）→ 直接对接你手里已有的身份文件，不需要先买 MFi 芯片。
4. **GPL-3.0**：改造/分发没有许可冲突（分发 fork 需开源）。
5. 无线子系统（API 26+ 的唯一集中地）与你主攻的 USB 路径完全解耦，可整块裁剪。

## 3. 移植差距清单（API 28 → 19，实测扫描）

| 项目 | 现状 | 处理 |
| --- | --- | --- |
| minSdk | 28 | 改 19（4.4.3=API 19，恰好在 AGP 支持下限附近；AGP 8.x 可用） |
| Compose | 4 个文件（入口 122 行 + 主题） | 重写为 View 入口；可借鉴 DiAuto/open-headunit 的老安卓 UI 做法（它们本来就支持 4.1+） |
| `java.util.Base64` | 4 处（RemoteMfiAuthenticationClient / LockdownPairRecord / LockdownPlistChannel / LockdownTlsEngineFactory） | 换 `android.util.Base64` |
| `java.time` | 1 处（Iap2LocationClient：Instant/ZoneOffset） | 换 SimpleDateFormat 手写 UTC 格式化 |
| LocalOnlyHotspotManager | 41 处 API 26 集中在这一个文件 | **整体裁剪**（CS11 用不了；USB 路径不依赖） |
| Media3 | 2 个文件（CarPlayMediaSessionService、VideoParameters 引用） | 换框架 MediaSession 或裁剪 |
| BouncyCastle | `bcprov-jdk18on:1.79` | 换 `bcprov-jdk15to18` 系列（老 Android 兼容） |
| TLS 1.2 | 4.4.3 默认未启用 TLS1.2 | 加 Conscrypt（DiAuto github flavor 的先例：conscrypt 2.5.x 支持 API 16+）或 `setEnabledProtocols` |
| JNI | `shared/src/main/jni`（Android.mk，i2c/热点用途） | 用本地身份文件时**可直接删**；若保留需注意新版 NDK 最低只到 API 21，得用 ≤r26 的 NDK |
| 无线 | P2P / LocalOnlyHotspot | 放弃；可选"外网同网"模式从 DiPlay #223 / nexuscp 借（车机当 station 接入手机热点，iPhone 同网） |
| 视频 | 自研 MediaCodec 管线（AndroidMediaSink/VideoDecodeFlow，非 ExoPlayer） | 保留；i.MX6Q 有 H.264 硬解，720p 级别可行 |
| 文件选择 | SAF（API 19 恰好引入） | 可用 |
| 依赖版本 | androidx/core 1.19、lifecycle 2.6.1 等 | 逐项下调到支持 minSdk 19 的版本 |

## 4. MFi 身份（CarPlay 的硬前提）

CarPlay 会话必须通过 Apple 的配件认证（MFi）。xcertplay 支持三种接入：

1. **硬件**：板载 `/dev/i2c-N` 或 **CH341 USB 转接板** + MFi 协处理器芯片（原作者有配套硬件项目 `shilapi/ch341-to-mfi-chip`）；
2. **本地文件**：`.pk8`（P-256 私钥）+ `.p7b`（Apple 签发的证书）——**你已有**：`_artifacts/apk_assets/assets/offline-mfi/`
   - `identity.pk8`：67B，sha256 `bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75`
   - `certificate.p7b`：607B，sha256 `634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e`
   - 实测：DiPlay APK、nexuscp 源码内嵌 = 同一份；证书为 Apple 签发（`CN=IPA_24ACBB48646F8077F7463523085B60D2`，issuer `Apple Accessories Certification Authority - 00000002`），自述来源为公开 Carlinkit C2Air 固件提取物。
3. **远程服务**：HTTP `/mfi/certificate|sign|reset`（自建服务端把签名能力放在别处）。

⚠️ 法律提醒：该身份是第三方提取物、非 Apple 为你的项目签发；个人实验可以，公开分发有法律/合规风险（DiPlay 对此有明确自我声明，供参考）。

## 5. CS11 侧必须先实机确认的事（不可远程验证）

1. **USB host 行为**：CS11 的 USB 口能否（a）枚举 iPhone（Apple VID `0x05AC`）、（b）允许 App 认领接口、（c）bulk 传输稳定、（d）供电足够。对照实验：插任意 iOS 设备看系统反应；或先插一个 CarPlay 盒子看是否被系统识别。
2. **Wi-Fi 角色**：CS11 的 Wi-Fi 能否作为 station 加入外部网络（"外网同网"无线模式的前提；USB 模式不需要）。CS11 原厂有联网能力（OTA），大概率可以，但需实测。
3. **安卓 4.4.3 的框架限制**：后台服务限制、`UsbManager` 权限弹窗、SurfaceView 行为等（4.4 时代限制反而比现代少）。
4. 性能：i.MX6Q 解码瓶颈在 1080p60 以上；CS11 屏幕分辨率内（720p 级）用 H.264 硬解大概率可行。

## 6. 路线图（建议顺序）

- **阶段 0（半天，先做）**：`git clone xcertplay` → 把 `shared/build.gradle` 的 minSdk 改成 19、`APP_PLATFORM` 改 android-19（或删 JNI）→ `./gradlew :shared:assembleDebug`。**编译错误清单就是完整的移植差距清单**（这是最有效的摸底实验）。
- **阶段 1**：拿一台 Android 9+ 的平板/旧手机，把原版 xcertplay 跑通（USB + 本地身份文件），完整过一遍 iAP2→MFi→AirPlay→视频→触摸 链路，作为对照系。
- **阶段 2**：做新壳（minSdk 19，View UI，参考 open-headunit/DiAuto 的设置界面），搬入 `shared` 核心，逐项替换 §3 清单；保留 USB 路径，剪掉无线管理器。
- **阶段 3**：CS11 实机 bring-up：ADB 安装 → USB 枚举 → NCM → iAP2 → MFi 认证 → 视频 → 触摸。
- **阶段 4**：音频/麦克风/自动启动/全屏打磨；确认稳定后再考虑（可选）"外网同网"无线从 DiPlay/nexuscp 移植。

## 7. 工作量与风险

- **结构友好 ≠ 简单**：仍是一次真正的下探移植，现实预期"数周集中的工程时间 + 大量实机调优"；不确定性集中在 CS11 的 USB 栈、老 MediaCodec 行为、4.4.3 的加密/网络兼容。
- 最坏情况：CS11 的 USB 口行为被 ROM 限制（只能充电/只认特定设备），此时改造路线整体不成立，只能回到盒子方案。
- 保底与对照：买一个 Carlinkit 类盒子（几十到一两百元）既是"立刻能用"的方案，也是验证 CS11 的 USB 数据/供电/音频链路是否正常的**最小实验**，强烈建议先做。

## 8. 参考（本地路径）

```
_upstream/xcertplay/                 # 基座（shallow clone, HEAD 17c9243）
_upstream/nexuscp/                   # 备选参考（HEAD ea4e9a5；OfflineMfiIdentity.kt 即内嵌身份）
_upstream/AndroidPlay/               # 中文参考项目（HEAD 793e488）
DiPlay/（已分析的 BYD 分支）          # 补丁来源；docs/02-DiPlay-源码分析.md
_artifacts/apk_assets/assets/offline-mfi/   # 已有 MFi 身份（pk8+p7b）
```

关键源码参考点：
- `xcertplay/shared/src/main/java/com/shilapi/xcertplay/`：`transport/NcmUsbBridge`（USB NCM 帧搬运）、`iap2/`、`airplay/`、`mfi/`、`media/`（MediaCodec 管线）、`network/`（无线，可裁剪）
- `xcertplay/common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`（4,165 行，View 体系主界面）
- 上游最低要求（README）："Android 9 (API 28) or newer"；本地文件模式见 README "Local MFI files" 一节

---

## 9. 社区适配生态与"支持安卓 4.4"版本的核实（2026-10-06 追加）

### 9.1 官方现状（当日拉取最新代码核实）

- 官方 DiPlay 最新 `main`（2026-10-06 fetch）：**所有模块 `minSdk = 28`，没有任何 4.4 支持**；官方从未发布 4.4 版本。
- 官方 issue（均 open，无官方承诺）：
  - #28 "Can it be compatible with Android 4.4"（09-29）
  - #31 "Feature Request: Add legacy Android 4.4.2 support"（09-29，详细描述了日产老车机场景）
  - #321 "版本问题"（10-06，中文："目前已知的最低版本4.4而我的小车车机版本4.2无法安装"）

### 9.2 流传的"支持 4.4"版本 = 第三方私改重打包（不建议直接使用）

- B 站相关视频（近几日集中出现）：
  - `BV1MHHj6UE8w`"CarPlay车机版DiPlay更新，支持安卓4.4老车机"（10-04，上传者"手机互联Pro"，3 万播放）
  - `BV1jFHx6REY8`"最新版本！！DiPlay更新，CarPlay车机版支持安卓4.4老车机"（10-05，上传者"diplay教程"）
  - `BV1Xcho6oEeC`"国外大神开发的全免费无需盒子Carplay软件【已汉化】"（升级说明自述"汉化了……签名是用 MT 管理器签的"）
- 判断：非官方源码构建，传播渠道为评论区/网盘，无源码、无校验和、重签名后无法追溯；而 DiPlay 本体自带可提取 MFi 身份、远程 MFi 签名接口、自 ADB shell 与 app_process 通道——对此类包应与用户保持同等级别的警惕，**不要直接安装运行**；如要利用，仅作为"适配手法参考"，通过差分分析提取改动。
- 快速真伪检查办法：任何 DiPlay 系 APK 都可 `apksigner verify --print-certs` 看签名——官方发布签名固定为 CN=DiPlay（SHA-256 `87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363`）；凡签名不同者均为第三方构建。
- 截至今日，**未发现公开的 CS11/4.4 专用源码分支**（598 个 fork 全量关键词扫描，无 cs11/4.4/kitkat 命名项）；流传的 4.4 版大概率没有公开源码。

### 9.3 公开 fork 生态（证明"低版本下探 + 品牌适配"已被社区反复走通，可作参考实现）

| fork | 目标 | 备注 |
| --- | --- | --- |
| `KrunkZhou/DiPlay-Android8.0` | Android 8.0 | 单提交 "Android 8 Support"，低版本适配参考 |
| `cqybalalala/DiPlay-Persona-Android6` | Android 6 | 更低版本下探参考 |
| `hiscatwang/DiPlay` | 欧拉好猫哈曼车机 / Android 8.1 / Intel x86 | 完整适配分支 |
| `xiaoshengwpp/DiPlay-Geely` | 吉利星越 L / 银河 | 社区适配，未实车验证 |
| `soBigRice/LynkCo-DiPlay` | 领克 OS N（8155 平台） | 领克专属，含自带更新站点 |
| `CoderLin99/DiPlay` | 吉利博越 L（银河 OS 2.5） | 音频/方控/副屏适配 |
| `ding2548-ui/DiPlay`（★21） | 零跑（Leapmotor） | 默认"外网同网"无线模式；移除过 system sharedUserId |
| `carlito12345/DiPlay`（★56）、`umarz317/LoopLink`（★36） | 综合性 fork | star 最高的两个 |

### 9.4 CS11 专用的另一个开源工程（盒子路线）

- `cjlhll/cs11-carplay-dongle`：针对 CS11 逆向"吉利 CarPlay 盒子"的工作区——
  - 含盒子 APK 脱壳件（`CarPlay apk 脱壳.zip.7z`、`apk_unpacked/dex`）、盒子固件 dump（`fw/`：uboot/rootfs/zImage…）、`docs/PROTOCOL.md`；
  - `hu/`：从 0 写的车机端 App（Java，`cn.carplay.hu`，H264 解码/PCM/触控/会话）；
  - 开发拓扑为"盒子→PC 代理→adb reverse→老安卓设备"，明确针对"老 USB 口"这类 CS11 限制。
  - 价值：若接受盒子硬件，这是比闭源盒子 App 透明得多的做法（早期阶段）。

### 9.5 针对本目标的"可信获得 CarPlay"路径

1. **自建移植（推荐）**：基于 xcertplay（或 DiPlay）建立自己的"CS11 适配分支"，自编译、自签名；流程见 §3/§6，并参考 §9.3 的公开 fork 学习低版本下探做法。
2. **对流传包做差分"体检"**（研究向）：拿到 4.4 包后不运行，直接与官方 DiPlay/源码做 DEX/资源/清单/原生库差分，提取其适配手法并排查夹带；无论结果如何，最终运行自编译版本。
3. **推动/等待官方**：issue #28/#31 下表态；关注官方仓库与 fork 网络是否出现公开的 4.4 源码。
4. （替代路线）盒子方案（§9.4 / 商业盒子），或换用高版本安卓设备承载 DiPlay。
