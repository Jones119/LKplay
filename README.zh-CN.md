# DiPlay · 领克 CS11（GKUI / Android 4.4.3）适配分支

**只为一台车机服务的 CarPlay 接收端：领克 CS11**（`model=CS11`，ECARX/GKUI 平台，
**Android 4.4.3 / API 19 / Dalvik**，Freescale **i.MX6Q**，系统按 1280×720 逻辑运行、面板
1920×1080）。支持**有线与无线** CarPlay，并通过厂商 DIM 接口把导航/媒体信息投到**液晶仪表**。

本仓库是 [DiPlay](https://github.com/shihabal3amri/DiPlay)（GPL-3.0）的长期分支。上游面向现代比亚迪
车机（Android 9+）；**本分支已移除 BYD/DiLink 适配**，只对 CS11 做承诺。装到别的车机之前请先看
[适用范围](#适用范围)。

> 非 Apple 认证产品。CarPlay 及其图标归 Apple Inc. 所有；本项目与 Apple、领克、吉利、亿咖通
> （ECARX）均无隶属或背书关系。

- 包名：`com.shihab.diplay`（debug 变体为 `com.shihab.diplay.hudtest`）
- 版本：`0.2.15-cs11.1`（`-hud-test`），versionCode 34
- 引擎参数：`minSdk 19`、`targetSdk 37`、仅 `armeabi-v7a`、开启 core library desugaring、multidex

## 当前状态（2026-10-12）

| 模块 | 状态 |
|---|---|
| 无线 CarPlay（车机自开热点 + 蓝牙 iAP2 交接） | **可用**（实车验证） |
| 有线 CarPlay（USB + 内核 `cdc_ncm` 网卡） | **可用**（2026-10-10 首次上屏） |
| 仪表导航卡片（箭头/路名/距离/剩余时间） | **可用**（实车验收） |
| 仪表媒体卡片（曲名/歌手/专辑/进度） | **可用**（实车验收） |
| 仪表专辑封面 | **未闭环**：厂商取图方始终不画我们给的 URI（已加回环 HTTP 档，判决实验待做） |
| 触控延迟 / 视频流畅度 | 已调优：视频接收窗、迟到丢帧老化阈值、降分配、`TCP_NODELAY`、线程优先级 |
| API-19 兼容加固 | 兼容层清扫 + 源码扫描守卫测试 |
| 有线/无线稳定性缺口 | 已定位未修（首帧看门狗、USB 拔出广播、失败可见性、模式收敛） |
| 单元测试 | `:shared` **940 / 0 失败**、`:common` **590 / 0**，0 skipped |

详细的工程笔记（实测数据、被否证的方案、实车日志证据）按项目约定**放在仓库之外**；本 README 只作为
代码层的入口。

## 构建

需要 **JDK 21**、AGP 9.3.0 所用的 Android SDK/NDK，以及**离线** Gradle（wrapper 指向镜像，构建默认
带 `--offline`）。

```powershell
cd CarPlay-CS11
.\gradlew.bat :mobile:assembleStandaloneDebug --console=plain --offline
# 产物：mobile\build\outputs\apk\debug\mobile-debug.apk（约 10.89 MB）
```

- **认证身份已随仓库提供**（`mobile/src/main/auth-assets/offline-mfi/`，见[认证](#认证)），因此
  clone 下来即可构建、即可连上 iPhone，**不需要**再设环境变量。想换一套身份时才用
  `DIPLAY_AUTH_ASSETS_DIR`（此时仓库自带的那套**不会**进 APK）。
- ⚠️ 若 APK 明显偏大，请先 `:mobile:clean` 再打包：增量打包曾留下约 5 MB 死空间，产出过 15.87 MB 的
  假包；干净构建是约 10.89 MB。
- 仓库里还有 `:vpndialogs-stub`：VPN 对话框依赖所需的小桩模块，属于构建图的一部分（不是可选项）。

## 测试

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest --continue --offline
# 期望：:shared 940 / 0 失败、:common 590 / 0 失败、0 skipped
```

**失败集必须保持为空**（新增用例让数字变大是允许的）。任何时候不要同时跑两个 Gradle（会争抢同一份
构建产物）。

## 适用范围

- **唯一支持目标**是上述 CS11。所有取舍都针对它的 Dalvik 运行时、厂商编解码器与内核
  （`cdc_ncm`、MUSB）做过实测；换车机不承诺可用。现代 Android 平板只作为"没有车机时的冒烟测试台"，
  **不是兼容目标**。
- **有线** CarPlay 需要先让车机的 NCM 网卡就绪（`ip -6 addr show usb1`）；在本 ROM 上这一步需要
  一次 root 通道引导，且**每次点火都要重建**。没有它只能用无线。
- 本应用**不改 `/system`**、**不安装被篡改的第三方 APK**；无线路径**不需要 root**。

## 认证

`DiPlayBootstrap` 首次启动时把 APK 里的 `assets/offline-mfi/{identity.pk8,certificate.p7b}` 复制到应用
私有目录，`LocalMfiAuthenticationClient` 再用它们完成附件认证（即"软模拟认证"）。

本仓库提交的这一对，与**上游公开发行 APK** 中自带的那一份**逐字节相同**（`DiPlay-0.2.12.apk`），
因此放进仓库**没有扩大它的暴露面**。但它确实是一张 **Apple 签发的附件身份**，**私钥任何人都能提取**，
公开分发可能与 MFi 条款冲突，极端情况下会被**吊销**（那样本车机的 CarPlay 会直接失效）。

除自用以外，请**替换成你自己的身份**：把新的两个文件放进
`mobile/src/main/auth-assets/offline-mfi/`，或用 `DIPLAY_AUTH_ASSETS_DIR` 指向含
`offline-mfi/{identity.pk8,certificate.p7b}` 的目录。生效身份缺失会**构建失败**；assets 里出现任何
**其它** `.pk8/.p7b/.key/.pem/.p12/.pfx/.jks/.keystore` 同样会构建失败。

## 目录结构

```
shared/   协议与核心：AirPlay（RTSP / H.264 / AAC·LPCM / HID / NTP / 加密）、iAP2、USB·NCM、
          媒体管线、网络与热点、仪表桥、API-19 兼容层
common/   Android 宿主：Activity、设置界面、持久化、通知、权限、圆角遮罩、封面 Provider
mobile/   打包成 APK 的 application 模块 + 随仓库提供的认证身份
vpndialogs-stub/  VPN 对话框依赖所需的小桩模块
docs/     上游文档原样保留（构建、安装、兼容性、版本说明、许可证）
site/ asset/ samples/ automotive/  上游遗留内容，在 CS11 上不用
```

## 文档

- [`docs/BUILD.md`](docs/BUILD.md)：上游指南，**顶部已加本分支说明**（实际可用的命令与工具链）；
  [`docs/INSTALL.md`](docs/INSTALL.md)、[`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) 面向比亚迪
  目标，不是本分支。
- [`docs/UPSTREAM-README.md`](docs/UPSTREAM-README.md)：上游 README 原文。
- [`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md)、`docs/licenses/`：来源与许可证。

## 来源与致谢

GPL-3.0（[LICENSE](LICENSE)）。基于 shihabal3amri 的
[DiPlay](https://github.com/shihabal3amri/DiPlay)，后者又基于
[xcertplay](https://github.com/shilapi/xcertplay)；主页/设置界面与网站改自
[DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0，许可证文本见 `docs/licenses/`）。
随包的 Opus 编解码（`shared/src/main/jniLibs/armeabi-v7a/libdiplay_opus.so`）来自 Opus/libopus
（BSD，见 `docs/Opus-LICENSE.txt`）。

部分机制还与同代 ECARX/GKUI 平台的开源项目 **吉利 H52 / Android 4.3** 分支
[`xikai6282/DiPlay-Geely-Android43`](https://github.com/xikai6282/DiPlay-Geely-Android43) 做过对比：
USB 短写续写与共享截止时间、USBMUX 持久读请求、视频线程优先级取值、iAP2 `0x5703` 的 BSSID 字段，
均参考了该项目的结论。**没有复制其代码**，每项都在本仓库重新实现并配套了自己的测试。

分发修改版时请保留上游与 DiAuto 的许可证与声明。
