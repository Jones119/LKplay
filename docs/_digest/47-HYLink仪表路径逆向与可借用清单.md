# 47 · HY Link（`com.hyphp.playkeytool`）仪表路径逆向 与 可借用清单

> 产出范围：**只读审计**。未改动 `CarPlay-CS11` 任何生产代码、未提交任何东西。
> 分析主体：`_analysis/hylink_src/UP_unpacked/sources/`（3073 个 java，jadx 输出）
> ＝ **上游脱壳 dex = v26082601**，作车机实装版（`_artifacts/cs11_apps/com.hyphp.playkeytool-1.apk`，v26091401，百度加固）的**高置信代理**（可信度论证见 §8）。
> fallback：`_analysis/hylink_src/UP_bad/sources/`（jadx `--show-bad-code`）。
> 资源对照：`_analysis/hylink_src/A_26091401/resources/`（实装版资源+Manifest，不加固可直读）、`_analysis/hylink_src/B_26082601_res/resources/`。
>
> 路径简写（全文沿用）：`UP/` = `_analysis/hylink_src/UP_unpacked/sources/`；`UPB/` = `_analysis/hylink_src/UP_bad/sources/`；`A_RES/`、`B_RES/` = 上述两个 resources 目录。
>
> 证据强度约定（**沿用 45/46 号**，便于三篇互相引用）：
> - **【确定-源码】**：直接引自 `file:line`，或 Java 语义必然成立
> - **【确定-实测】**：来自 doc37/doc38 的真机日志或 `dumpsys`/`pm` 输出
> - **【推断】**：由间接证据得出，条目内注明"能定它的证据"
> - **【未决】**：现有材料无法判定
>
> 定位：本篇回答 **"HY Link 用哪条路把东西放到仪表上，以及我们能不能抄"**（= **路线裁决**）。
> 媒体/封面的 DIM 接口细节看 **45 号**；导航/转向的 DIM 接口细节看 **46 号**。分工见 §12。

---

## 0. 必须先用一句话记住的三件事

1. **HY Link 同时用两条完全独立的通道**：**B 投屏**（盒子第二路 H.264 → `Presentation` 贴到系统第二块 Display）给"整块画面"；**A 原生数据注入**（ECARX `DimInteraction`/`INaviInteraction`）给"车机自绘的导航卡与媒体信息"。
   **对 CS11 第三方应用（无 ECARX 签名权限）而言，A 路线是可行的那条**（依据见 §1、§5）。
2. **`com.ecarx.permission.CAR_CONTROL` 与 `com.ecarx.permission.ADAPT_API` 在全部 3073 个 java 文件里 0 次出现**——只在 Manifest 里。而且**没有声明这两条权限的 26082601 版本装着一模一样的 DIM 注入代码**。⇒ "这两条权限非必需"是**强推断**，定案证据见 §5.3。
3. **HY Link 的导航箭头主通路是整数型 `updateTurnByTurnArrow(int)`，不是 Bitmap 型**（§2.3）。它的 `updateDriveDirectionImage(byte[])` 这个名字**极易误导**：它最终送到的是 **HUD 体系**（`HUD.updateHudNavi(byte[])`，`UP/com/hyphp/sdk/hud/HUD.java:8941`），**不是**厂商那个空方法 `INaviInteraction.updateTurnByTurnArrow(Bitmap)`。**不要按名字找方法。**

---

## 1. 架构裁决表（本篇核心）

### 1.1 按内容分工

| 投到仪表的内容 | 走的通道 | 机制一句话 | 证据 `file:line` | 机型归属 |
|---|---|---|---|---|
| **音乐元数据**（曲名/歌手/专辑/状态/进度） | **仅 A** | 盒子 JSON 元数据帧 → `ja` 解析 → ECARX 媒体中心 / `DimInteraction.getPhoneCallInteraction()` + `BitmapParcelable` binder | `UP/a/ca.java:934-960`、`UP/a/ja.java:36-64`、`UP/com/hyphp/sdk/media.java:96,104,9759,10093,10815,10917,11174`、`UP/com/hyphp/CarplayApi/BitmapParcelable.java` | ECARX 通用 |
| **专辑封面** | **仅 A** | `byte[]` → `Bitmap` → **Parcelable 走 binder** 送进车机（不是投屏） | `UP/com/hyphp/CarplayApi/BitmapParcelable.java`、`UP/com/hyphp/sdk/api.java:32208`(`updatePhoneCallInfo` 带 Bitmap)、`UP/com/hyphp/sdk/ArtworkContentProvider`（Manifest 组件） | ECARX 通用 |
| **导航指引**（箭头/距离/路名/ETA） | **A + B 同时** | A：仪表**自绘**导航卡（`INaviInteraction.update*`）；B：整屏地图投上去 | A: `UP/a/ja.java:214-261`、`UP/com/hyphp/sdk/navi/navi.java:6359-6394`；B: `UP/a/ca.java:1030-1045` | ECARX 通用（B 需 `clusterMapEnable`） |
| **整屏画面**（地图 / 主画面镜像） | **仅 B** | 盒子第二路 H.264 → `bc` 管线 → GL 双输出 → `Presentation` + `SurfaceView` | `UP/a/bc.java`、`UP/a/vb.java`、`UP/com/hyphp/playkeytool/video/DualSurfaceRenderer.java` | 全机型通用 |
| **通话信息** | **仅 A** | `DimInteraction.getPhoneCallInteraction()` | `UP/com/hyphp/sdk/phone/phone.java:14-18,590,4255` | ECARX 通用 |

**关键否定结论（也是实锤）**：**HY Link 的投屏通道里没有任何音乐/封面消费点。** 全树 grep `clusterMapEnable` 只影响"独立地图"（`UP/a/h40.java:343` 同时要求 `mapMode`），音乐元数据没有任何"第二路流/投屏"入口。⇒ **"把音乐封面投到仪表"这件事在 HY Link 里根本不是投屏做出来的。**

### 1.2 Web 检索关键词的答复（原审计问题的 (a)/(b)）

| 关键词 | 结果 |
|---|---|
| `VirtualDisplay` | **0 命中**（全树） |
| `MediaRouter` | **0 命中** |
| `createDisplayContext` | **0 命中** |
| `DISPLAY_CATEGORY_PRESENTATION` | **0 命中**（只用等价字面量，见 §3.1） |
| `DisplayManager` / `getDisplays` | 命中，见 `UP/a/xx.java:224-239`（**唯一的发现点**） |
| `Presentation` | 命中，投屏走 `UP/a/vb.java:15`；HUD 走 `UP/com/hyphp/sdk/hud/ElementPresentation.java:23`（**两条不同的 Presentation，见 §3.6**） |
| `cluster_*` / `CLUSTER_*` | 命中，见 §4 |
| `mirrorSingleDecode` | 命中，但**CarPlay 链路上被 `v()` 挡死**，见 §3.5 |
| `com.ecarx.*` | 大量命中（EAS SDK + XUI adaptapi 整包打包） |
| `NowPlaying` / `MediaCenter` / `turnByTurn` / `Maneuver` / `ICar` / `INavigationDI` | 命中，见 §2 |

### 1.3 裁决：对 CS11 第三方应用（无 ECARX 签名权限），哪条可行？

**结论：A 路线可行且已在走；B 路线对我们不可行（缺上游），但 B 的外围有两件可借。**

**A 可行**的依据：
1. **我们已经在走 A 路线，而且它在本机是通的。** 我们的 `VendorClusterBridge` 反射 DIM API；`docs/_digest/38` 【确定-实测】记录"曲名/歌手/专辑/播放状态/进度 ✅ 仪表显示正常"。⇒ 我们缺的不是"这条路的门"，而是**导航那一半的调用细节**（45/46 号已定位到根因：反射分发器拒绝装箱原始类型）。
2. **HY Link 用的正是我们探针反射的那个类**：`UP/com/hyphp/sdk/navi/navi.java:24` `import com.ecarx.xui.adaptapi.diminteraction.DimInteraction;`；`navi.java:4328` `DimInteraction.create((Context) obj)`；`UP/com/hyphp/sdk/phone/phone.java:14-18,4898` 同样。我们的 `CarPlay-CS11/common/src/main/java/com/shilapi/xcertplay/VendorDimProbe.kt:20` 反射的就是 `com.ecarx.xui.adaptapi.diminteraction.DimInteraction`（`:20`；`:30` `create(Context)`）——**同一个类、同一个入口**。
3. **零权限硬检查**：见 §5。

**B 不可行**的依据：
- B 需要**盒子在私有协议层提供并下发第二路 H.264 流**：`UP/a/ca.java:1348-1364` 下发的 `CLUSTER_VIDEO_WIDTH/HEIGHT/FPS/SAFE_*/ENABLE` 全是发给 **USB 盒子**的私有指令，`ca.java:581-601` 的 `CLUSTER_READY(514)` 触发 re-arm 拉流。我们的 CarPlay 接收端**没有这个上游**。
- 我们现有的"仪表地图"走的是 **CarPlay 自己的 `altScreenURLs` / stream type 111**（`CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayClusterDisplay.kt:12-24,75-101`），与 HY Link 的第二路流是**两条互不相干的机制**。

**B 里可以借的两件外围**：
- `DimInteraction.setPresentationToDimSwitch(int,String,String,boolean)`——"投屏时向车机登记"（§2.4）。这其实是 **A 路线的一部分**。
- 安全区几何经验：**封顶 300×200 px + 逐项 clamp**（§4.3）。这是全文**最可能立刻见效**的一条。

### 1.4 本节的推断成分（务必与上面的实锤区分）

| # | 结论 | 性质 | 能定它的证据 |
|---|---|---|---|
| I1 | "`CAR_CONTROL`/`ADAPT_API` 非 DIM 注入的必要条件" | **【推断】**（依据：26082601 未声明却含同样代码 + 代码 0 引用） | 实机 `dumpsys package` 三方对比（§9.5） |
| I2 | `setPresentationToDimSwitch` 两个 String = 包名/Activity 类名 | **【推断】**（字符串被 `ue0` 加密，**未解出**，见 §10） | `dumpsys activity services com.hyphp.playkeytool` 的绑定明文；或实机日志 |
| I3 | CS11 preset 不设 `cluster_id` ⇒ "装完向导仪表不会自动开" | **【推断】**（依据 §6.2 preset 表 + `UP/com/hyphp/playkeytool/ui/MainActivity.java:3564` 的 `h40.h()` 门槛） | 读实机 prefs（§9.5） |
| I4 | `ElementPresentation` 不是仪表投屏主路径 | **【推断】**（结构推断，见 §3.6） | 实机日志 tag `ClusterPresentation`/`HUD` |

---

## 2. A 通道注入面（逐调用表）

### 2.1 数据入口：盒子的 JSON 元数据帧
`UP/a/ca.java:934-960`（`0xAA55AA5B` 元数据分支）：
```java
:942 JSONObject o = new JSONObject(json);
:943 int id = o.optInt("ID", -1);
:945 if (z20.J == 3 && "HiCarNavi".equals(o.optString("Name",""))) jaVar.b(apiVar, o);  // HiCar 专属分支
:947 else if (id == 16725)  jaVar.a(apiVar, o);   // 通话
:949 else if (id == 20481)  jaVar.c(apiVar, o);
:951 else if (id == 20993)  jaVar.d(apiVar, o);   // 导航
:953 else if (id == 20994 && o.optInt("Index",-1) >= 0)
:954     jaVar.f406a.put(Index, new ia(o.optInt("ManeuverType",0), o.optString("ManeuverDesc","")));
```
⇒ **`ManeuverType`(int) + `ManeuverDesc`(String) 是 Turn-by-Turn 箭头的来源**。

### 2.2 `UP/a/ja.java:214-261`（方法 `d`，导航分支）逐调用表

| 行 | 调用 | 语义 | 机型归属 |
|---|---|---|---|
| `:221` | `if (z != this.c)` | 起停边沿检测 | 通用 |
| `:224` | `apiVar.startNavi()` | 起导航会话 | ECARX 通用 |
| `:230` | `apiVar.stopNavi()` | 停导航会话 | ECARX 通用 |
| `:246` | `apiVar.updateDistanceToDestination(iOptInt3)` | 到**目的地**距离 | ECARX 通用 |
| `:250` | `apiVar.updateDistanceToNextGuidancePoint(iOptInt4)` | 到**下一转向点**距离 | ECARX 通用 |
| `:254` | `apiVar.updateETA((int) jOptLong)` | ETA | ECARX 通用 |
| `:260` | `apiVar.updateDriveDirection(iaVar.f372a)` | **转向箭头（整数 id）** | ECARX 通用 |
| `:261` | `apiVar.updateNextGuidancePointName(iaVar.b)` | 路名 | ECARX 通用 |

同文件 `:150`（方法 `b`，**HiCar 专属分支**）：
| `:150` | `apiVar.updateDriveDirectionImage(bArr)` | 转向图（**byte[]，非整数**） |
| `:121` | `apiVar.updateDistanceToNextGuidancePoint(iRound)` | 距离（解析 `"1.2公里"` 这类中文串，`:104-118`） |
| `:127` | `apiVar.updateNextGuidancePointName(strTrim)` | 路名（`navData.RoadName`） |

同文件 `:36-64`（方法 `a`，通话 × ID 16725）：`:60` `apiVar.updatePhoneCallInfo(1,null,2,1000L,i,DisplayName,RemoteID)`、`:62` `apiVar.closePhoneCall()`。

### 2.3 ⭐ 映射表：HY Link 包装名 → 厂商真名（**避免后人照名字找错方法**）

**这是本篇最容易踩坑的一节。** HY Link 有三层同名/近名方法：

```
第 1 层  HY Link 公开包装   UP/com/hyphp/sdk/api.java
第 2 层  HY Link 中间层     UP/com/hyphp/sdk/navi/navi.java   ← 与厂商接口同名的自有包装！
第 3 层  厂商接口           com.ecarx.xui.adaptapi.diminteraction.{DimInteraction,INaviInteraction}
```

| 第 1 层（`api.java`） | 第 2 层（`navi.java`） | 第 3 层（厂商，本机 `ecarx.adapterapi.jar`） | 本机有效性 |
|---|---|---|---|
| `:31104 updateDriveDirection(int)` | `navi.java:17086 updateDriveDirection(int, boolean)` → 最终 **`navi.java:6359 m1437`** → **`:6394 ((INaviInteraction) obj).updateTurnByTurnArrow(i)`** | **`updateTurnByTurnArrow(int)`** | ✅ **唯一能用的箭头通路**（46 号 §1.4：`setArrow2(int)`+`setTurnState(1)`+`turnByTurn()`） |
| `:31629 updateDriveDirectionImage(byte[])` | `navi.java:22025 updateDriveDirectionImage(byte[])` → **`:22120 hudY1.updateHudNavi(bArr)`** | **不映射到厂商**！落到 `UP/com/hyphp/sdk/hud/HUD.java:8941 updateHudNavi(byte[])` | ❌ **与厂商 `updateTurnByTurnArrow(Bitmap)` 无关**；厂商那个 Bitmap 重载本来就是**空方法**（46 号 §1.4，1 条 `return-void`） |
| `:29389 startNavi()` | `navi.java:6852` → | `notifyTurnByTurnStarted()` | ❌ 非法状态（`turnState=0`，服务端抛异常；46 号 §4.3 G4） |
| `:29862 stopNavi()` | `navi.java:4369` → | `notifyTurnByTurnStopped()` | ✅ 有效（清卡） |
| `:30599 updateDistanceToDestination(int)` | `navi.java:3889` → | `updateDistanceToDestination(int)` | ✅（单位=米） |
| `:30859 updateDistanceToNextGuidancePoint(int)` | `navi.java:4534` → | `updateDistanceToNextGuidancePoint(int)` | ✅（单位=米） |
| `:31682 updateETA(int)` | `navi.java:5972` → | `updateETA(int)` | ✅（单位=**秒**） |
| `:31948 updateNextGuidancePointName(String)` | `navi.java:3685` → | `updateNextGuidancePointName(String)` | ✅（doc38 实测唯一生效项） |
| `:32208 updatePhoneCallInfo(...)` | `phone.java:590` → | `getPhoneCallInteraction()` 系 | 通话面 |
| `:23441 closePhoneCall()` | `phone.java` | 同上 | 通话面 |

**厂商 `INaviInteraction` 的完整方法集**（46 号 §1.4 已逐条反汇编验证）：
```
notifyTurnByTurnStarted()          // 非法状态
notifyTurnByTurnStopped()          // ✅
updateTurnByTurnArrow(int)         // ✅ 唯一箭头通路
updateTurnByTurnArrow(Bitmap)      // ❌ 空方法
notifyDestinationReached(String,int)
notifyDestinationReached(String,Bitmap)
updateNextGuidancePointName(String)// ✅
updateDistanceToNextGuidancePoint(int)  // ✅
updateDistanceToDestination(int)        // ✅
updateETA(int)                     // ✅（秒）
updateDriveDirection(int)          // ❌ 空方法！
```

**反面警告**：`updateDriveDirection(int)` 在**厂商侧是空方法**，但 HY Link 的第 2 层**也有一个同名方法**（`navi.java:17086`）且它是活的（最终走 `updateTurnByTurnArrow`）。**照名字去调厂商的 `updateDriveDirection` 会得到静默 no-op。** 这正是 46 号 G2/§1.4 说的问题。

**箭头图片路线的真实归属**：`api.updateDriveDirectionImage(byte[])` → `C0104.m774`（`UP/com/hyphp/sdk/ecarx/C0104.java:247-251`）→ `navi.updateDriveDirectionImage(byte[])` → `HUD.updateHudNavi(byte[])`。
⇒ **HY Link 的"图片箭头"是喂给它自研 HUD 体系的，不是喂给仪表 DIM 的。** 我们 38 号文里"HiCar 用 `updateDriveDirectionImage(byte[])` ⇒ 箭头是图片路线"这条推论**是误导**（46 号 §1.4/U6 已同向结论）。

### 2.4 `setPresentationToDimSwitch` —— 投屏时向车机登记（**可直接借**）

**签名与调用点**【确定-源码】：
```java
// 定义（唯一的厂商调用点）
UP/com/hyphp/sdk/api.java:16158   ((DimInteraction) obj).setPresentationToDimSwitch(i, (String) obj2, (String) obj3, z);
UP/com/hyphp/sdk/navi/navi.java:3812  同签名

// navi 侧包装
UP/com/hyphp/sdk/navi/navi.java:3799
    public static void m1407(Object obj, int i, Object obj2, Object obj3, boolean z)
```
**6 个调用点**（全在 `UP/com/hyphp/sdk/navi/navi.java`）：
```
:7558   m1407(C0101.m194(), 1, <str>, <str>, true)
:8491   m1407(C0101.m194(), 1, ue0.j2(), ue0.j2(), true)
:9011   m1407(C0101.m194(), 0, <str>, <str>, true)
:10237  m1407(C0101.m194(), 0, <str>, <str>, false)
:12997  m1407(C0101.m194(), 1, ue0.j2(), ue0.j2(), false)
:13201  m1407(C0101.m194(), 1, <str>, <str>, false)
```
**签名解读**：`(int index, String pkgOrAction, String classOrAction, boolean on)`。
**【推断，非确证】**：两个 String 是"投屏方的包名/Activity 类名"，用于让车机仪表知道"当前是谁在投屏"；`i=0/1` 是通道/方向开关。
**【未决】**：**两个 String 的明文未解出**（被 `ue0` 加密，见 §10）。定案证据：`dumpsys activity services com.hyphp.playkeytool` 的绑定明文，或实机日志。

**为什么它重要**：它是我们目前**唯一一条"投屏时告诉车机"的线索**。我们 38 号文的现象是"主屏正常、仪表上地图/卡片行为异常"；如果车机 DIM 服务需要 App 主动登记才会正确渲染，缺这一步就能解释一部分异常。

### 2.5 A 通道的底座：ECARX DIM API

**用到的 `DimInteraction` 成员**（全树唯一枚举，【确定-源码】）：
```
DimInteraction.create(Context)                            navi.java:4328 ; phone.java:4898
.getNaviInteraction()                                     navi.java:1861,3726
.getPhoneCallInteraction()                                phone.java:590
.registerInteractionCallback(...)                         navi.java:4902
.setPresentationToDimSwitch(int,String,String,boolean)    navi.java:3812 ; api.java:16158
```
**同族类型**：`UP/com/hyphp/sdk/navi/ServiceAreaImpl.java:4,11` `implements INaviInteraction.IServiceArea`；`UP/com/hyphp/sdk/phone/divICallInfo.java:5` `import ...diminteraction.IPhoneCallInteraction`；`DimInteractionImpl`（`phone.java:15,44,6784`、`navi.java:6784`）。

**`INaviInteraction` 的 10 个方法**（含 `updateServiceAreaInfo`）另见 `navi.java:2246,3685,3889,3960,4369,4534,4833,5972,6155,6394,6852`。

### 2.6 音乐侧（A 通道）
- ECARX 媒体中心：`UP/com/hyphp/sdk/media.java:96`（`mFlymeMediaCenterAPI`）、`:104`（`mMediaCenterAPI`），调用点 `:10917 registerMusic`、`:11174 updateMusicPlaybackState`、`:9759 updateMediaList`、`:10093 updateCurrentProgress`、`:10815 updateCurrentSourceType`（**两套并存**：`com.ecarx.eas.sdk.mediacenter.*` 与 `com.ecarx.sdk.mediacenter.*`）。
- 元数据位掩码（**发给盒子的"要哪种元数据"开关**）：`UP/a/h40.java:327-340`，`bit0=metaMedia`、`bit1=metaCall`、`bit2=metaNavi`、`bit3=metaArt`（默认全开），存进 `z9.g`。
- 封面：`BitmapParcelable`（`UP/com/hyphp/CarplayApi/BitmapParcelable.java`，`Parcelable`）+ `ArtworkContentProvider`（Manifest 组件）。
- **【未决】**：媒体中心 `registerMusic` 是否需要额外授权——未证。

---

## 3. B 通道机制（投屏）

### 3.1 发现 Display（**纯公开 API，零特权**）
`UP/a/xx.java:224-239`：
```java
public static List S(Context context) {
    DisplayManager dm = (DisplayManager) context.getSystemService("display");
    if (dm == null) return Collections.EMPTY_LIST;
    ArrayList out = new ArrayList();
    try { a(out, dm.getDisplays("android.hardware.display.category.PRESENTATION")); } catch (Throwable unused) {}  // :231
    try { a(out, dm.getDisplays()); } catch (Throwable unused2) {}                                                 // :235
    return out;
}
```
- 过滤/去重 `a(...)`：`:352-383` —— `displayId != 0`、`display.isValid()`、按 `getDisplayId()` 去重。
- 取指定 id：`:1108-1115` `y(Context, int)`。
- 目标有效性：`UP/com/hyphp/playkeytool/ui/MainActivity.java:366-382` `T(Display)` = `isValid() && getRealSize() 两轴 > 0`。
- **目标由 prefs `cluster_id` 决定**：`MainActivity.java:3561-3582`（`t()`）：
  ```
  :3564  if (!h40.h()) return null;              // h40.h() = cluster_id 已设置且 >0（UP/a/h40.java:146-157）
  :3569  i = Integer.parseInt(prefs.getString("cluster_id","1"));
  :3577  Display d = xx.y(this, i);
  :3578  return T(d) ? d : null;
  ```
- **诊断串（可直接照抄到我们代码）**：
  - `MainActivity.java:3603-3669` `u(Display)` 打印 `id/name/size/refresh/valid/state/flags`
  - `MainActivity.java:4632-4652` 找不到时打印 `available=[所有 displayId]`
  - `UP/a/q6.java:236-275` 开机自检把所有 display 的 `id,name,WxH@Hz,densityDpi,state` 写日志

### 3.2 `Presentation`（`UP/a/vb.java`，全文 161 行）

| 点 | 行 | 说明 |
|---|---|---|
| 声明 | `:15` | `public final class vb extends Presentation` |
| 构造 | `:28-38` | `super(context, display)`；`int[] bounds` → `d,e,f,g` = `x,y,w,h` |
| **bounds 布局** | `:61-72` | `FrameLayout.LayoutParams(w,h)` + **`leftMargin=d, topMargin=e`** + `gravity=8388659`(TOP\|START)。**这就是"安全区/裁切"的实现方式：靠 margin 在副屏上偏移** |
| overlay 变体 | `:130-140` | `SDK>=26 ? TYPE_APPLICATION_OVERLAY(2038) : TYPE_SYSTEM_ALERT(2003)`；**只有 `cluster_overlay=true` 才设**；catch 后回退默认窗口 |
| 内容 | `:148-158` | `FrameLayout`→`FrameLayout`→**`SurfaceView` + `setZOrderOnTop(true)`**(`:154`) |
| 显示 | `:84-111` | 先 `api.PresentationShow()`，再 `show()`；`show()` 抛 `Error`/`RuntimeException` → `d()`(`PresentationHide`) 后**重新抛出** |
| 隐藏 | `:74-82` | `dismiss()`，`finally` 调 `PresentationHide()` |
| 边界更新 | `:113-125` | `f(int[])` 改 `d,e,f,g` + `setLayoutParams` |

### 3.3 第二路流管线（`UP/a/bc.java`，全文 435 行）
```
盒子 USB 帧 0xAA55AA58（第二路）
 → ca.java:1030  if (x())  → ca.java:1038  bc.k(this, display, bounds, {w,h}, cluster_fps, rearm, autoEnter, overlay, "独立地图(第二路流)")
 → ca.java:1043  bc.g(len, data)                      每帧投喂
 → bc.c(renderer, surface)                            建 H264Decoder 挂到持久 GL 输入面
 → DualSurfaceRenderer.start(null, secondSurface, cb)  主=pbuffer(空)，副=副屏 Surface
 → H264Decoder → SurfaceTexture(OES) → GL blit → eglSwapBuffers(secondEgl)
 → vb.c(SurfaceView.holder.surface)
```
- `bc.k()` `:377-400`：解码/显示尺寸取传入 `{w,h}`（`:389-392`）；fps `Math.min(i,240)`，默认 15（`:387`）。
- `bc.b()` `:88-128`：建 `vb` → `vb.e()`；成功日志 `"仪表副屏 Presentation 已显示 display=…"`（`:105-110`）；失败 `"显示 Presentation 失败:…"` / `"创建 Presentation 失败:…"`（`:121,126`）。
- `bc.g()` `:245-330`：**关键帧缓存** —— 无解码器时只在见到 SPS(`nal 7`) 或 IDR(`nal 5`) 后开始缓存（`:304-325`），上限 **90 帧 / 4 MiB**（`:321`）。
- `bc.c()` `:130-219`：解码器启动后**回放缓存**，统计 `NAL=[…]`/`hasIdr`（`:210-211`）；首包无 IDR → `i("首次无IDR(中途插入)")` + 3s 重试（`:213-217`）。

### 3.4 GL 单 OES 纹理双输出（`UP/com/hyphp/playkeytool/video/DualSurfaceRenderer.java`，全文 488 行）
- `start(surface, surface2, …)` `:460-471`；实现在 `:263-302`（`lambda$start$1`）。
- **两个 EGLSurface 共用同一个 EGLContext + 同一个 OES 纹理**：`:267` `mainEgl`、`:269` `secondEgl`。
- **主屏可以是空的**：`main` 无效时 `createPbuffer()`（`:267-268`，实现在 `:128-135`）⇒ **"只投副屏"是设计内的用法**（`bc`/`x00` 都这么用）。
- `drawFrame()` `:147-170`：`updateTexImage()` → `drawTo(mainEgl)`（`:156`，**pbuffer 时跳过**）→ `drawTo(secondEgl)`（`:158`）；副屏首帧成功打 `"副屏GL首次交换画面成功"`（`:162`）。
- **掉帧保护**：`postLatestFrame()` `:366-372` 用 `drawPosted` CAH 保证同一时刻只有一个待绘帧（**最新帧优先，丢中间帧**）。
- 失败回退：`:291-301` catch → `"GL 渲染器启动失败,回退双解码"` → `onStartFailure`。

### 3.5 ⭐ 两套投屏实现 + 判据函数 `v()` / `x()`

| | 单解码 GL 镜像 | 双解码镜像 | 独立地图第二路流 |
|---|---|---|---|
| 代码 | `UP/a/x00.java` + `MainActivity.java:2291-2429`（`h0`） | `MainActivity.java:819-837`（`I`） | `UP/a/ca.java:1030-1045`（`bc` 直喂） |
| 入口判据 | `MainActivity.a1():1932-1934` 要求 `mirrorSingleDecode && v() && t()!=null` | `MainActivity.java:820` `v() && !a1()` | `ca.java:1030` `x()` |
| 画面 | 主屏与副屏**同一份**解码输出 | 两路各自解码 | 盒子第二路专用流 |
| 日志标签 | `"GL 单解码"`（`:2317`） | `"镜像主画面(双解码)"`（`:833`） | `"独立地图(第二路流)"`（`:1038`） |
| 失败回退 | 启动失败→回退双解码（`UP/a/qw.java:27,34`） | — | `"已收到第二路视频但无法创建本地管线"`（`ca.java:1040`） |

**判据函数（实锤，务必给团队看）** —— `UP/com/hyphp/playkeytool/ui/MainActivity.java`：
```java
:3708 public final boolean v() {                    // == 单解码镜像可用
:3710   return W != null && prefs.getBoolean("clusterMapEnable", false)
                        && (!prefs.getBoolean("mapMode", true) || A2 == 3);   // A2==3 = HiCar
:3786 public final boolean x() {                    // == 走盒子第二路流
:3790   return prefs.getBoolean("clusterMapEnable", false)
              && prefs.getBoolean("mapMode", true) && A2 != 3;
```
`A2` 默认 1（`:260`、`:916`、`:1728`、`:3493` 都置 1）；`:2661-2665` 判 `A2==3`(HiCar) / `A2==2`。

⇒ **① CarPlay（`A2=1`）下 `v()` 恒 false** ⇒ `a1()` 恒 false ⇒ **`mirrorSingleDecode` 在 CarPlay 链路上永不生效**（它只对 HiCar 且开了 `clusterMapEnable` 的机型有意义）。
⇒ **② `x() == clusterMapEnable`**（CarPlay 下）⇒ CS11 preset（`clusterMapEnable` 未设 = false）**两条路都不走**，见 §6.2。

**额外实锤**：`ca.java:1046-1053` 明确处理"镜像模式下仍收到盒子第二路帧"：
```java
:1051 q6.e("异常:镜像模式仍收到盒子第二路视频帧,本地忽略该帧" + (A2==3 ? "(HiCar自动镜像)" : ""));
```
⇒ 两种模式在设计上**互斥**。

### 3.6 `ElementPresentation` / `AspectRatioSurfaceView` 的正确定性（**避免后人走错门**）

- **`UP/com/hyphp/sdk/hud/ElementPresentation.java:23` `extends Presentation`** —— 这是 **HYPHP 自研 HUD/副屏「元素投屏」体系的基类**（同族 `ElementManager`/`ElementView`/`ElementWindowDialog`/`HUD`/`DisplaySelectDialog`，数千行，字符串加密 + 控制流平坦化）。
  **判据：它不是仪表投屏主路径。** 仪表投屏主路径是 `UP/a/vb.java`（也是 `extends Presentation`，但贴的是 `SurfaceView` 视频）。`ElementPresentation` 服务于"元素/卡片式 HUD"，由 `HUD.java:1600-1624,2036-2041,8401-8411` 创建，`ElementWindowDialog.java:36222` 也 `new ElementPresentation(...)`。
  **【推断】** 定性为结构推断（未精读其内部）。**建议先不追这个类。**
- **`UP/com/hyphp/playkeytool/ui/AspectRatioSurfaceView.java`（全文仅 564 B，`:9-13`）** —— **纯 `SurfaceView` 子类 + 宽高比测量，没有任何 Display/Presentation 代码。**
  它是**主屏**视频面（`MainActivity` 的 `f1500a`，见 `UPB/a/ch.java:191,193` 的局部变量 `AspectRatioSurfaceView`）。**与仪表副屏无关**（副屏的 `SurfaceView` 是 `new SurfaceView(getContext())`，`UP/a/vb.java:152`）。

### 3.7 盒子状态机与第二路流开停（`UP/a/ca.java`）
```
:567-573   512 = CLUSTER_STARTED  ("盒子:仪表流已启动")
:574-580   513 = CLUSTER_STOPPED
:581-601   514 = CLUSTER_READY → 若 z9.m 为真则 re-arm 拉第二路流(:586-587)；否则重发关闭配置(:591-597)
:1065-1069 收到第二路帧但 cluster 已关 → 通知盒子停止输出 (z9Var.m=false; caVar.F(z9Var))
:1051      镜像模式收到盒子第二路帧 → 本地忽略
```

---

## 4. 与盒子协商的 cluster 配置（精确字段）

### 4.1 配置对象 `UP/a/z9.java`（全文 32 行）
```java
:15 f914a=1280 :16 b=720 :17 c=30     // 主画面 w/h/fps
:18 d=false(night) :19 e=false(rightHand) :20 f=15000
:21 g=15     // 元数据位掩码（见 UP/a/h40.java:327-340）
:22 h="HY_CarPlay" :23 i="HOME" :24 j=0 :25 k=-1(DISABLE_BONJOUR)
:26 l=true(use_remote_audio) :27 m=false(★clusterMapEnable) :28 q=15(cluster_fps)
:29 v=true(cluster_eta) :30 w=0(eta_pos) :31 x=0(dark_mode)
: 6 n=String(cluster 标签,仅日志用)
: 7 o : 8 p                          // ★cluster 视频编码 w/h
: 9 r :10 s :11 t :12 u              // ★cluster 安全区 x,y,w,h
```

### 4.2 字段来源（`UP/a/h40.java:312-358`，方法 `z()`）
```
:344-346  int[] j = j();  z9.o = j[0]; z9.p = j[1];     // cluster 分辨率
:347      z9.q = m(15, "cluster_fps");
:348-353  int[] j2 = j();
          int[] e = sd.e(j2[0], j2[1], v(j2[0], j2[1], prefs.getString("cluster_safe","")));
          z9.r=e[0]; z9.s=e[1]; z9.t=e[2]; z9.u=e[3];   // 安全区 x,y,w,h
:343      z9.m = prefs.getBoolean("clusterMapEnable", false) && prefs.getBoolean("mapMode", true);
:354-356  z9.v = prefs.getBoolean("cluster_eta", true);
          z9.w = w(0, prefs.getString("cluster_eta_position","0"));
          z9.x = w(0, prefs.getString("clusterDarkMode","0"));
```
- 分辨率解析 `h40.java:179-199`（`j()`）：`cluster_res` 为 `"auto"`/空 → 用 `cluster_bounds` 的 w/h；否则解析 `"WxH"`；**最终一律过 `sd.f()`**。
- 安全区解析 `h40.java:134-136`（`f()`）= `v(0, 0, prefs.getString("cluster_bounds",""))`。

### 4.3 ⭐ 下发字段与**安全区钳制**（`UP/a/ca.java:1289-1368`，方法 `s()`）
```java
:1295 int[] f = sd.f(z9.o, z9.p);                     // 分辨率修正为偶数
:1298-1305  若变化 → "cluster 视频编码尺寸修正 WxH → WxH"，回写 z9
:1307 if (!z9.m || o<=0 || p<=0) {                    // ★关闭路径
:1308     CLUSTER_VIEWAREA_ENABLE = 0
:1309     CLUSTER_ENABLE = 0
:1311     日志 "cluster disable(…→ 不向盒子请求第二路流)"
:1317     return; }
:1323 iMax  = max(min(300, W), min(z9.t, W))          // ★安全区宽：≤300 且 ≤W
:1324 iMax2 = max(min(200, H), min(z9.u, H))          // ★安全区高：≤200 且 ≤H
:1325 iArr  = { clamp(z9.r, 0, W-iMax), clamp(z9.s, 0, H-iMax2), iMax, iMax2 }   // ★钳制 x,y
:1328-1347  若被修正 → "拒绝下发非法 cluster 安全区 …（视频 WxH），已修正为 x,y,w,h"
                       + "安全区越界,下发前修正 requested=… normalized=…"
:1348 CLUSTER_VIDEO_WIDTH  = z9.o
:1349 CLUSTER_VIDEO_HEIGHT = z9.p
:1351-1355  q = z9.q>0 ? z9.q : 15 ;  CLUSTER_FPS = q
:1356 CLUSTER_SAFE_WIDTH  = iArr[2]
:1357 CLUSTER_SAFE_HEIGHT = iArr[3]
:1358 CLUSTER_SAFE_X      = iArr[0]
:1359 CLUSTER_SAFE_Y      = iArr[1]
:1360 CLUSTER_SHOW_ETA    = z9.v ? 1 : 0
:1361 CLUSTER_ETA_POS     = z9.w
:1362 CLUSTER_DARK_MODE   = z9.x
:1363 CLUSTER_VIEWAREA_ENABLE = 1
:1364 CLUSTER_ENABLE          = 1
:1365 日志 "cluster enable WxH@fps safe=x,y,w,h nav[SHOW_ETA=…,ETA_POS=…,DARK_MODE=…]"
```

**注意区分两个几何量**：
- `CLUSTER_VIDEO_WIDTH/HEIGHT` 送的是 **`cluster_res` 派生的编码尺寸**；
- `cluster_bounds` 的 w/h 只在 `cluster_res="auto"` 时用于推分辨率；
- **真正决定副屏上窗口落位的是 `cluster_bounds`**（`MainActivity.W.f()` → `vb` 的 `d,e,f,g`）。

**⭐ 最重要的一条（我们的安全区可能过保守）**：`ca.java:1323-1324` 把安全区**封顶在 300×200 px**。对 1920×720 而言 300 px ≈ 15.6 % 宽。
而我们的 `CarPlayClusterDisplay.SAFE_AREA_PERCENT = AirPlayInsets(top=16, bottom=25, left=35, right=36)`（`CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayClusterDisplay.kt:38`）在 1920 宽下 **left+right = 71 % = 1363 px 被让出**，只剩 29 % 给地图。
⇒ **这是"我们的地图很小 / 位置怪"的一个强候选根因。**（对照：我们的 `ClusterTurnCardOverlay.DEFAULT_X_PERCENT=76`，`:15`）

### 4.4 分辨率 / DPI / 对齐细节
- `UP/a/sd.java:84-89` `f(w,h)`：`w>1 ? w & -2 : 2` —— **强制偶数**（H.264 对齐）。
- `UP/a/h40.java:24-38` `e(w,h)`：≤0 兜底 1280×720；强制偶数；**下限 320**。
- `UP/a/sd.java:80-82` `e(w,h,safe)` = `d(w,h, min(300,w), min(200,h), safe)`；`:66-78` `d()` 逐项 clamp；`:62-64` `c(v,lo,hi)` = `max(lo, min(v,hi))`。
- **主画面 DPI 系数**（`UP/a/ca.java:1394-1405`）：`carplay_report_resolution` 发主分辨率，`carplay_report_physical_size` 发 `round(W/d), round(H/d)`，其中
  `d = W>=1920 ? 5.8895707 : (W>=1280 ? 6.213592 : 5.263158)`。
  例（1920×720）：`1920/5.8895707 ≈ 326`、`720/5.8895707 ≈ 122`。
  这几组系数反推的车机 DPI 基准约 **160 dpi 阶梯**（5.263158≈1000/190、6.213592≈1000/161、5.8895707≈1000/169.8）。**【推断】**
- **cluster 没有独立 DPI 上报**：`CLUSTER_*` 只有 W/H/FPS/SAFE/ETA/DARK。
- 固件版本门槛：`UP/a/ca.java:1426-1438`（`O >= 20250620` 才发 `cpaudio=0`；`>= 20250819` 才发 `DISABLE_BONJOUR`）。
- **主配置突发顺序**（`UP/a/ca.java:1371-1459`，`t()`）：
  `get_android_link_type`/`get_sw_ver`/`get_fv`/`check_wifi_module_is_on`/`get_bt_name` → `carplay_report_resolution` → `carplay_report_physical_size` → `v()` → `set_video_fps` → `set_right_hand_driver` → `set_night_mode` → `get_android_type` → `use_remote_audio` → `set_aec_delay(20)` → `set_log(0)` → **`s()`（cluster 全套）** → `start_work` ×2（`:1441-1442`）。

### 4.5 显示/隐藏手势（`cluster_gesture_fingers`）
- 读数：`UP/a/h40.java:138-144` `g()`：旧键 `cluster_three_finger` 迁移（`true→2`）；`clamp(m(0,"cluster_gesture_fingers"), 0, 4)`。
- 实现：**`UPB/a/ch.java:44-331`**（`UP/a/ch.java` 反编译失败，见 §10）：
  | 行 | 内容 |
  |---|---|
  | `:105-108` | 门槛：`W.g() > 0 && bc.g.get()`（管线活着才吃手势） |
  | `:106` | **实际指数量 = `g()+1`**（`cluster_gesture_fingers=2` ⇒ 三指；这就是旧键叫 `cluster_three_finger` 的原因） |
  | `:111` | 触发：`actionMasked==5 && pointerCount >= fingers+1` |
  | `:120-124` | 取所有指 **x 平均** |
  | `:125` | `dx = avg - downX` |
  | `:126-127` | **阈值 `max(60, min((viewWidth<=0?1280:viewWidth)*0.18, 320))`** |
  | `:128-139` | `dx <= -阈值` → **左滑 = 显示**（`bcVar.f156a.post(new zb(bcVar, 2))`），日志 `"N指左滑 → 显示仪表副屏 dx=… 阈值=…"` / `q6.e("N指左滑显示副屏 dx=… threshold=…")` |
  | `:140-151` | `dx >= +阈值` → **右滑 = 隐藏**（`zb(bcVar,1)`），日志 `"N指右滑隐藏副屏"` |
- 动作：`UP/a/zb.java`：`case 1`（隐藏，`:33-52`）→ `vb.b()`（dismiss）+ **`"手动隐藏:Presentation已销毁,GL+解码器继续保活"`**（`:50-51`）；`default`（显示）→ `showPresentation`，已显示则忽略（`:59-60`）。
- 设置页枚举可选副屏并写 prefs：`UP/com/hyphp/playkeytool/ui/SettingsActivity.java:1303`（`putString("cluster_id", displayId)`）、`:1306`（`putString("cluster_bounds","0,0,W,H")`）；选择回调 `UP/a/j90.java:379`（`y0(Display, Preference)`）。
- 副屏区域预览（`clusterPreview`）：`UP/a/n90.java:263` `xx.y(...)`、`:286-290` `api.PresentationShow()`；配 `UP/a/wb.java:69-72` 的 `PresentationHide`。**纯预览，不参与正式投屏。**

---

## 5. 权限结论

### 5.1 逐条表

| 权限 | 代码里出现次数 | 运行时检查 | Manifest（A 版 / B 版） | 判定 |
|---|---|---|---|---|
| `com.ecarx.permission.CAR_CONTROL` | **0** | 无 | A 有 / **B 无** | **manifest 声明而已**；代码从不使用、从不检查 |
| `com.ecarx.permission.ADAPT_API` | **0** | 无 | A 有 / **B 无** | 同上 |
| `android.car.permission.CAR_VENDOR_EXTENSION` | **0** | 无 | A 有 / B 有 | 同上（`IVendorExtensionController` 是 HY Link 自研 AIDL，见 `UP/com/hyphp/CarplayApi/IVendorExtensionController.java:11`，**不是** android.car 那个） |
| `SYSTEM_ALERT_WINDOW` | 间接（`UP/a/vb.java:132` overlay 窗口类型） | **无 `canDrawOverlays` 检查**（唯一相关检查在 `SettingsActivity.java:1417`，属**设置页**提示，与投屏无关） | 两版都有 | API19 为 install-time 自动授予 |
| `MANAGE_USB` | `UPB/com/hyphp/playkeytool/ui/MainActivity.java:1015` 反射 `grantPermission` | **有回退** | 两版都有 | 与本问题无关 |
| 其余（`RECORD_AUDIO`/`WAKE_LOCK`/`FOREGROUND_SERVICE`/`POST_NOTIFICATIONS`/`REQUEST_INSTALL_PACKAGES`/`music.intent.action.source` 等） | — | — | 两版一致 | 与仪表无关 |

**实锤**：grep 全树 `com\.ecarx\.permission|CAR_CONTROL|ADAPT_API|CAR_VENDOR_EXTENSION` → **命中 0 个 `.java` 文件**，这些字符串**只存在于 `AndroidManifest.xml`**。
代码里真正的运行时权限检查只有 3 处、**都不涉及 ECARX**：
- `UP/a/t1.java:61`（`PackageManager.checkPermission`，批量自查）
- `UP/a/uc0.java:84`（自研 prefs 迁移兼容）
- `UP/a/xx.java:477` + `UP/a/z20.java:1288`（通用 `Context.checkPermission` 工具）

### 5.2 软依赖写法模板（`try/catch + null 守卫`）——**可直接照抄**

| 位置 | 模板 |
|---|---|
| `UP/a/vb.java:46-58` | `PresentationHide()`：先判 `xx.m == null` → `"SDK PresentationHide跳过:SDK未初始化"` 后 `return`；否则 `try { apiVar.PresentationHide(); } catch (Throwable th) { 记日志 }` |
| `UP/a/vb.java:86-98` | `PresentationShow()`：完全同样的结构 |
| `UP/a/xx.java:631-642` | `requestPlay()`：null 判 + try/catch |
| `UP/com/hyphp/playkeytool/ui/MainActivity.java:771-792` | `isNeedrequestMic()` 抛异常 → `"isNeedrequestMic 异常,按不需要仲裁处理(直接录音)"` → **降级继续返回 true** |
| `UP/com/hyphp/sdk/api.java:2734-2790` | `onServiceConnected`：`IApi.Stub.asInterface` → `linkToDeath`（`:2759`）→ `initApi()`（`:2767`）→ `isBinderAlive()` 检查（`:2779`）；配 `aidlReconnectEnabled`/`isPolling`/`pollingRunnable`（`:96,97,112,116`）**重连轮询** |

⇒ **判定：HY Link 对 ECARX 特权能力是"有则用、无则退"的软依赖结构；没有任何一处"没有签名权限就崩/就不启动"。**
唯一硬依赖是 `libu.so` 鉴权（`UP/com/u/j/U.java` `loadLibrary("u")`）——那是**盒子激活**用的，与仪表无关。

### 5.3 "两条权限非必需"是**推断**，定案证据
**依据**：① 代码 0 引用（实锤）；② 26082601 未声明这两条权限，却装着一模一样的 DIM 注入代码（实锤，§8.1）。
**为什么仍是推断**：26082601 是"上游对照包"，**不能证明它在 CS11 上真跑通过**。
**能定它的证据**：实机 `dumpsys package com.hyphp.playkeytool | grep -i permission`，看 `com.ecarx.permission.CAR_CONTROL`/`ADAPT_API` 是 `granted=true` 还是 `false`/`unknown`；三方对比（HY Link / 我们 / EasyPlay）见 §9.5。**若为 false 而仪表仍工作 ⇒ 推断成立，A 路线对我们开放。**

---

## 6. 机型归属表

> 用户明确约束：HY Link 是**多机型**应用，"APK 里有这段代码" ≠ "CS11 上走这段代码"。

### 6.1 机型识别与分发点（实锤）
**HY Link 没有按 `Build.MODEL` 做功能分支**；机型差异集中在**一张 preset 表**：

- **`UP/a/j90.java:42-350`** —— `SettingsActivity` 设置向导的 `DialogInterface.OnClickListener`，按 `presetId` 字符串把一整套 prefs 写进 `com.hyphp.playkeytool_preferences`。**这是全 app 唯一的"按机型选配置"工厂。**
- preset 候选表（UI 来源）：`UP/com/hyphp/playkeytool/ui/SettingsActivity.java:1622`（按车机名）、`:1624`（按年款）。
- **`UP/com/hyphp/sdk/EcarxGN.java:77`** `public class CS11CarSignalReceiver extends CarSignalReceiver` —— 类名直带 CS11，**最强 CS11 锚点**（ECARX `ecarx.arccore.carsignalcommon`）。实例化 `:3219`，赋给 `this.carSignalReceiver`（`:3133`）；相关：`ICarSignalManager`/`CarSignalFilter`/`SignalsToFilter`/`ICarSignalReceiver` 导入（`:36-40`）、`registerReceiver`（`:11728`）、`AddSignal`（`:9621`）、`EcarxGN.java:1509-1511,2483,3721`。
- `Build.MODEL` 只用于**日志 + 1 处音频兼容**：`UP/com/hyphp/playkeytool/audio/AudioSessionController.java:1354,1360`（`NAVIGATION_FOCUS_COMPAT_MODEL.equals(Build.MODEL)`）、`UP/a/q6.java:172`、`UP/a/z90.java:128`。**没有机型 switch。**
- 无 `EX11` / `ro.product.*` / `i.MX` / `SystemProperties` 直接机型判断。ECARX 侧另有 `CacheSystemProperties.getVin()`（`UP/com/hyphp/sdk/HyCheck.java:6979`、`UP/com/hyphp/carplaystart/carplaystart/HyCheck.java:6979`），属激活校验，不是功能分发。

### 6.2 ⭐ CS11 preset 的确切内容（`UP/a/j90.java:176-210`）
`case "head_lynk_cs11"` / `"model_02_18_21_cs11"` / `"model_03_18_21_cs11"` / `"model_01_17_20_cs11"`
（中文名"领克CS11车机"、"领克17~20款01（CS11）"等，见 `SettingsActivity.java:1622,1624`）：
```
:181 usbSettleDelayMs=1000   :182 audioDuck=false    :183 audioNaviChan=13
:184 swKey_voice=…           :185 resolution="auto"  :186 fps=50
:188 keepVideoInBackground=true   :189 showNavigationBar=true
（hysettings: :192 switchLynkcoSiri=1 :193 switchMediaShowImage=1
 :194 switchMediaImageGetType=2 :195 rangeMediaImgZip=80 :196 rangeMediaImgZipSize=200
 :197 rangeCS11VoiceSleeps=500 :198 switchDayNight1=1）
```
**对照其他机型**（都显式设了 cluster 相关键）：
| 机型组 | 行 | `clusterMapEnable` | `cluster_id` | `cluster_bounds/res` | `cluster_overlay` |
|---|---|---|---|---|---|
| **CS11** | `:176-210` | **未设(=false)** | **未设** | 未设 | 未设(=false) |
| E02 | `:65-101` | `true`(`:78`) | `1`(`:80`) | — | — |
| 8155(23-24) | `:102-137` | `true`(`:114`) | `2`(`:116`) | `0,0,1920,720` / `1920x720`(`:117-118`) | — |
| 820A | `:138-175` | `true`(`:154`) | `1`(`:156`) | — | — |
| E01 | `:248-277` | `true`(`:258`) | `1`(`:260`) | — | `true`(`:261`) |
| 8155(25) | `:278-311` | `true`(`:289`) | `1`(`:291`) | — | — |

⇒ **结论 ①【推断 I3】**：CS11 preset **不设 `clusterMapEnable`、不设 `cluster_id`** ⇒ 按 `MainActivity.java:3564`（`h40.h()` 要求 `cluster_id` 已设）与 `:3790`/`:3710`（都要求 `clusterMapEnable`），**"装完向导 → 仪表自动能投"在 CS11 上不成立**；CS11 上必须**手工配一次**（设置页选副屏显示 → `SettingsActivity.java:1303,1306` 写 `cluster_id`/`cluster_bounds`）。
**注意**：`cluster_id` 一旦手工设上，CarPlay 下 `x()` 会变 true（因为 `x()` 只要求 `clusterMapEnable`…**不**：`x()` 也要求 `clusterMapEnable`）。⇒ 严格说 CS11 上**两条路都需要 `clusterMapEnable`**，而该键 CS11 preset 不设，用户若想用仪表必须**同时**手工开 `clusterMapEnable`。**这一点请实机核 prefs 定案（§9.5）。**

### 6.3 ⭐ "820A" 是机型标识，不是通用前缀 —— **doc 08 需要加注记**
**证据**：
- `j90.java:138-140` 三个 preset：`"model_05_19_22_820a"`、`"head_lynk_820a"`、`"model_01_21_22_820"`（中文名"领克19-22款05（820A）"、"领克21-22款01（820）"，`SettingsActivity.java:1622,1624`）——**只有这一个机型组写这个 prefs**。
- 该组**独有** `linkedHashMap.put("mic820AecEnhancement", true)`（`j90.java:150`）→ 被 `UP/a/h40.java:235`（`p()`）读取 → 只影响 `AudioSessionController` 的麦克风 AEC 增强。
- CS11 preset（`:176-210`）里**没有** `mic820AecEnhancement`、没有 `highAudioApi`、没有 `audioMediaChan`；`audioNaviChan=13`（其余 ECARX 机型是 12）。**这就是 CS11 与其他 ECARX 机型的分界。**

⇒ **结论 ②**：doc 08 §4.2「音频焦点/车机音量」里 `AudioSessionController.java:1337-1343` 的 `"820A"` 日志前缀 = **高通 820A 平台的厂商 `AudioManager.setParameters` 参数名**，在 CS11（ECARX + i.MX6Q + Android 4.4.3/API19）上**不会走到**。
**➡ doc 08 该处应加一条注记**："该 '820A' 为 820A 机型专用参数（`UP/a/j90.java:138-175` 的 preset 独有 `mic820AecEnhancement`），非通用前缀；在 CS11/i.MX6Q 上此路径不生效，勿作为 CS11 可用手法移植。"

### 6.4 逐条能力 → 机型归属

| 能力 / 代码 | 归属 | 判据 |
|---|---|---|
| `UP/a/bc.java` 第二路流管线（Presentation+SurfaceView+H264+GL） | **全机型通用** | 只用 `android.app.Presentation`/`SurfaceView`/`MediaCodec`/`EGL14`；全文无机型判断、无 `Build.MODEL` |
| `UP/a/vb.java`（`extends Presentation`） | **全机型通用** | 同上 |
| `video/DualSurfaceRenderer.java` GL 双输出 | **全机型通用** | 纯 EGL/GLES20 |
| `ca.s()` 的 `CLUSTER_*` 下发 | **全机型通用**（数据来自每机型 prefs） | `UP/a/ca.java:1348-1364`，值取 `h40.z()` |
| `cluster_*` prefs 族（17 键） | **全机型通用** | A/B 两版 `res/xml/settings.xml` **逐字节相同**（§8.2） |
| `clusterMapEnable`（"独立地图第二路流"） | **部分机型专属**：820A/8155/E01/E02 开；**CS11 preset 不开** | `j90.java:154/114/258/78` vs `:176-210` |
| `mirrorSingleDecode`（GL 单解码镜像） | **全机型通用**，但 **CarPlay 链路上被 `v()` 挡死** | `MainActivity.java:1934` 要求 `v()`；`v():3710` 要求 `clusterMapEnable` |
| `UP/a/ja.java` → `DimInteraction`/`INaviInteraction` 导航注入 | **ECARX 通用（含 CS11）**；其中 `:945` 的 `"HiCarNavi"` 子分支是 **HiCar 专属** | 各 ECARX 机型 preset 均带 `switchLynkcoSiri`/`switchNavi*`；CS11 有专属 `CS11CarSignalReceiver` |
| `EcarxGN.CS11CarSignalReceiver`（车信号） | **CS11 专属** | 类名带 CS11（`EcarxGN.java:77`） |
| `com.ecarx.eas.sdk.mediacenter.*` | **ECARX 通用** | `UP/com/hyphp/sdk/media.java:96,104` 两套 API 并存 |
| `UP/a/ue0.java:265,359,1437,3216`（"吉利24-26款8155"等） | **其他机型专属** | 8155 平台机型名 |
| `UP/a/ue0.java:1280` `"E01"`、`:2928`/`:926` `"E02"` | **其他机型专属** | E01/E02 平台标识 |
| `head_meizu`（`j90.java:312-340`）、`FlymeMusicClient`/`FlymeMediaInfo`/`FlymeMusicPlaybackInfo`/`FlymeMediaListInfo` | **其他机型专属**（魅族车机） | preset 名 + Flyme 类族 |
| `j90.java:138-175` 的 `mic820AecEnhancement`、`model_05_19_22_820a` 等 | **820A 专属** | preset 名直带 820A |
| `NaviProtocolID.INNER_NAVI_NTF_LANE_INFO_CHANGED`、`assets/lane/*.png`(141)、`assets/hicarNavi/*.png`(68) | **HiCar 专属** | 车道图/华为导航箭头资源；`ca.java:945` 的 `"HiCarNavi"` 判据 |
| `UP/a/t9.java` 车机集成面、`service/CoreService`、`service/BootReceiver` | **全机型通用** | 无机型分支 |
| `ui/SWKeyActivity` 方控映射 | **全机型通用**（映射项按 preset 填） | preset 里 `swKey_*` 是数据 |

### 6.5 仪表这条路是不是 CS11 专属？（定性结论）
**不是"CS11 专属分支"，而是"全机型通用的副屏镜像框架 + 每机型几何/开关配置"。** 判据：
1. 管线的**代码**（`UP/a/bc.java`、`UP/a/vb.java`、`video/DualSurfaceRenderer.java`）里**没有任何机型字符串、没有 `Build.MODEL`、没有 presetId 判断**（全文 grep 实锤）。
2. 机型的**唯一**影响是 prefs：`cluster_id`（用哪块 Display，`MainActivity.java:3561-3582`）、`cluster_bounds`/`cluster_res`/`cluster_safe`（几何，`UP/a/h40.java:134-199,349-353`）、`clusterMapEnable`（走哪条路，`MainActivity.java:3790`/`ca.java:1030`）。全部在 `UP/a/j90.java` 里按机型写。
3. ⇒ **CS11 走同一套代码**，只是 preset 给它关了 `clusterMapEnable`、没给它 `cluster_id`。

---

## 7. 生命周期与健壮性

### 7.1 创建 / 销毁
| 事件 | 行为 | `file:line` |
|---|---|---|
| 管线启动 | `bc.k()` 置 `g=true`，`f156a.post(pw(this, autoShow, 2))` | `UP/a/bc.java:377-400` |
| 建 GL 双输出 | `pw` default 分支 `new DualSurfaceRenderer().start(null, bcVar.e, …)`（**main=null → pbuffer**） | `UP/a/pw.java:54-60` |
| 建 Presentation | `bc.b()` → `new vb(ctx, display, bounds, overlay)` + `vb.e()` | `UP/a/bc.java:88-128` |
| 显示/创建失败 | catch → `f=false`、`vbVar2.b()`、日志 `"显示 Presentation 失败:…"` / `"创建 Presentation 失败:…"` | `UP/a/bc.java:111-127` |
| 管线停止 | `bc.l()`：`g.getAndSet(false)` → 清缓存 → 释放 `H264Decoder`/`DualSurfaceRenderer`/`vb` → **`f156a.post(d8(...,2))` 回主线程销毁** | `UP/a/bc.java:402-434` |
| 手动隐藏 | `zb case 1`：只 `vb.b()`（dismiss），**GL+解码器保活，且不发盒子流控制指令**（"再 show 秒显示"） | `UP/a/zb.java:33-52` |
| 停止顺序 | `bc.l()` **先取字段置 null 再 post 线程释放**（避免重入） | `UP/a/bc.java:414-433` |

### 7.2 Activity 暂停 / 后台
- `MainActivity.java:3251-3302` `onPause()`：读 `V()`（= `UP/a/h40.java:1608` `keepVideoInBackground`）；为真 → **`"后台保持视频输出:跳过 onPause 解码器销毁"`**（`:3269`）。
- `MainActivity.java:3356-3362` `onStop()`：`"onStop -> 返回桌面/后台 …"`；`:3479` 调 `this.g0.l()`（停管线）——**在 keepVideo 分支之外才停**。
- `MainActivity.java:3121-3161` `onDestroy()`：`q6.j("Activity onDestroy retainApi19Link=" + z + " finishing=" + isFinishing() + " changingConfig=" + isChangingConfigurations())` —— **API19 专属会话接管**（配合 doc 08 §4.3 的 `UP/a/bw.java:280-315`）。
- **CS11 preset 设 `keepVideoInBackground=true`**（`j90.java:188`）⇒ CS11 上后台保活是**开的**。
- `onResume` 恢复：`"onResume 进入 wasVideoPaused=" + z`（`:3901`）、`"onResume 完成 needsScreenRestore=… streamChanged=… clusterChanged=…"`（`:4275`、`:4596`）——**分辨率/流/仪表三项变化分别判定并重建**。

### 7.3 分辨率变化 → 解码器重建
- 主画面：`onResume`/配置变化里比对并重建（`clusterChanged` 标记，`:4275`/`:4596`）。
- 副屏：`bc.k()` 每次启动都按传入 `{w,h}` 建解码器（`UP/a/bc.java:389-392`）。
- **下发前修正**：`ca.java:1298-1305` 把非偶数尺寸改偶数并回写 `z9`，避免盒子收到非法分辨率。
- 目标 Display 切换：`MainActivity.java:4200`、`:4519` 判 `display2 == null || display2.getDisplayId() != display.getDisplayId()` → 重新定位 + 更新 `vb` 边界（`UP/a/vb.java:113-125`）。

### 7.4 解码器重建 / 关键帧请求 / re-arm
- **re-arm**：`bc.i(String)`（`:332-343`）：`t++`，日志 `"仪表副屏自愈:<reason> → 第 N 次 re-arm(让盒子重发 SPS+IDR)"` + `q6.e("<标签> 自愈第N次 re-arm reason=…")`，再跑注册回调 `q`。
- **上限**：`zb case 0`（`:20-32`）：`t < 6` 才继续，每 **3000 ms** 重试；上限写明确原因：
  `"仪表副屏中途插入始终无关键帧,re-arm 达上限 6 放弃(此盒子 mid-session 不补 SPS+IDR,只能重连手机/重插盒子让它重发)"`（`:27-28`）。
- **解码器停了就地重建（不重建 GL）**：`bc.j(H264Decoder)`（`:345-375`）：取 **`dualSurfaceRenderer.getInputSurface()`（持久 GL 输入面）**，`h264Decoder.stop()`；若管线仍活且输入面 `isValid()` → `f156a.post(d8(this, renderer, inputSurface, 3))` **在同一个 GL 输入面上重建**；否则 `u=false` 交上层重启。日志 `"检测到解码器停止,准备在持久GL输入面上重建"`。
- **中途插入无 IDR**：`bc.c()` 回放缓存后若 `hasIdr==false` → `i("首次无IDR(中途插入)")` + 3 s 后 `zb case 0` 自愈（`:213-217`）。
- **单解码失败回退双解码**：`DualSurfaceRenderer.java:291-301`（`onStartFailure`）→ `UP/a/c8.java:135` `o0("GL 初始化失败回退双解码", false)`；`UP/a/qw.java:27,34` `"镜像 GL 单解码初始化失败,回退双解码"`。
- **缓冲上限**：`bc.g()` 无解码器时最多缓存 **90 帧 / 4 MiB**（`:321`）。
- **掉帧保护**：`DualSurfaceRenderer.postLatestFrame()`（`:366-372`）`drawPosted` CAS 保证同一时刻只有一个待绘帧（**最新帧优先**）——低延迟关键手法。

---

## 8. 版本代理可信度（26082601 代码 ↔ 26091401 实装版）

### 8.1 Manifest 权限差异：**恰好 2 条**
`A_RES/AndroidManifest.xml` vs `B_RES/AndroidManifest.xml` 逐条比对，A 比 B **多且仅多**：
```
com.ecarx.permission.CAR_CONTROL
com.ecarx.permission.ADAPT_API
```
其余 18 条 `uses-permission`（含 `SYSTEM_ALERT_WINDOW`、`MANAGE_USB`、`android.car.permission.CAR_VENDOR_EXTENSION`、`music.intent.action.source` 等）**完全一致**。
⇒ **doc 08 §2 的记载经本次复核成立。**

### 8.2 资源树全量 MD5 比对（A=1554 文件，B=1442 文件）
**仪表/副屏/HUD 相关资源：A 与 B 完全一致。**
- `res/xml/settings.xml`、`settings_navi.xml`：**`cluster_*` 共 16 个键 + `mirrorSingleDecode`，A/B 逐一相同**，即
  `cluster_auto_enter`、`cluster_bounds`、`cluster_eta`、`cluster_eta_position`、`cluster_fps`、`cluster_gesture_fingers`、`cluster_id`、`cluster_overlay`、`cluster_res`、`cluster_safe`、`clusterDarkMode`、`clusterFontSize`、`clusterGeometryEditor`、`clusterMapEnable`、`clusterPreview`、`clusterWelcomeMessage`、`mirrorSingleDecode`。
- `res/values/strings.xml`：`cluster_editor_accessibility`/`_bounds_mode`/`_bounds_value`/`_display_format`/`_display_missing`/`_safe_mode`/`_safe_value`/`_title` **8 条键名 A/B 完全相同**。
- `res/layout/`：**150 个文件，A/B 文件名集合完全相同**。
- `res/xml/`：11 个文件，A/B 文件名集合相同。
- **组件清单**：`<service>`/`<receiver>`/`<provider>`/`<activity>` 集合**除版本号外无新增**；A **未新增**任何 Presentation/HUD 相关组件。

**A/B 的非仪表差异（列出以免误判）**：
| 差异 | 内容 | 与仪表的关系 |
|---|---|---|
| A 新增 `assets/hicarNavi/*.png`（68 张，编号 `1..48`/`A..F`） | 华为导航箭头图 | **HiCar 分支**，非 CS11 仪表投屏 |
| A 新增 `assets/lane/*.png`（141 张） | 车道图 | **HiCar 分支**（对应 `NaviProtocolID.INNER_NAVI_NTF_LANE_INFO_CHANGED`） |
| A 新增 `assets/dexopt/baseline.prof(m)` | 性能剖析 | 无关 |
| B 独有 `assets/web/*`（18 文件：bootstrap/jquery/index.html…） | web 配置页，A 移除 | 无关 |
| `res/layout/activity_swkey.xml` 有差异 | A 新增"媒体键自动改为通话使用"开关 + `@+id/swMediaKeyCallConvert` | **方控页，非仪表** |
| `res/xml/settings.xml` 差异 6 行 | A 新增 `exitDuringBootDelay` 开关 | **非仪表** |
| `settings_audio.xml`/`settings_mic.xml`/`settings_startup.xml` 有差异 | 音频/麦克风/启动页 | **非仪表** |
| 加固产物 | `assets/baiduprotect*.{dex,jar,md}`、`lib/armeabi-v7a/libbaiduprotect.so`、`assets/libbaiduprotect_x86`、`META-INF/KRABS.*`、`MANIFEST.MF`、`version-control-info.textproto` | 预期内 |
| **`res/values/public.xml` 有差异** | **资源 ID 已重映射** | **⚠️ 重要副作用** |

### 8.3 ⚠️ `public.xml` 已重映射 —— 别拿 B 的 `R` 常量对 A
因为 A 加了新设置项、删了 web 资源，**两版资源 ID 数值不同**。这与代码无关（HY Link 的字符串靠 `ue0` 私有表，不靠 `R`），但**提醒：不要拿 B 的 `R` 常量数值去对 A 的资源**。

### 8.4 一句可引用的结论
> **用 26082601 代码代替 26091401 分析仪表路径，残余风险是：**
> **① 两版 `res/values/public.xml` 不同（资源 ID 已重映射），若结论依赖具体资源 ID 数值则不可直接移植；**
> **② A（26091401）新增 68 张 `assets/hicarNavi/*.png` + 141 张 `assets/lane/*.png`，说明 A 在 HiCar 导航方向做了增强，而这段增强的代码在加固壳内、无法比对 ⇒ 本文关于"HiCar 分支"的结论只覆盖 26082601 的形态，不保证覆盖 26091401 的 HiCar 行为；**
> **③ CS11（`head_lynk_cs11` 等 4 个 preset）相关的 prefs 键、`res/xml/settings.xml`、`res/values/strings.xml` 的 `cluster_*` 条目在两版逐字节一致，组件清单一致，那 2 条差异权限在代码中 0 引用 ⇒ HY Link 的仪表（cluster）路径分析结果对 26091401 高置信成立。**

---

## 9. 可借用清单

### 9.1 离线可实现（不需要上车）

| # | 借用项 | HY Link 证据 | 对我们做什么 |
|---|---|---|---|
| L1 ⭐ | **安全区 px 化 + 封顶 300×200 + 逐项 clamp** | `UP/a/ca.java:1323-1325`、`UP/a/sd.java:62-89`、`UP/a/h40.java:24-38` | 把 `CarPlayClusterDisplay.SAFE_AREA_PERCENT`（百分比、left35/right36 = 让出 71 %）改成 **px 为单位、宽度上限 ≤300** 的模型，并沿用 clamp 公式。**最可能立刻改善"地图太小/位置怪"** |
| L2 | **几何工具函数** | `UP/a/sd.java:62-89`（`c()` clamp / `d()`/`e()`/`f()` 三层校验 + 偶数对齐 + 320 下限）、`UP/a/h40.java:24-38`（兜底 1280×720） | 把 `ClusterSafeAreaPreviewFrame.kt`/`ClusterTurnCardOverlay.kt` 的百分比 + `coerceIn` 换成同一套 px 级 clamp + 偶数对齐，补单元测试 |
| L3 | **Display 诊断串** | `MainActivity.java:3603-3669` `u(Display)`；`:4632-4652` 打印 `available=[displayId…]`；`UP/a/q6.java:236-275` | 直接照抄成我们的日志，**决定下一次登车能不能一次定位问题** |
| L4 | **软依赖模板**（`null 判 + try/catch(Throwable) + 明确日志 + 降级继续`） | `UP/a/vb.java:46-58,86-98`、`UP/a/xx.java:631-642`、`MainActivity.java:771-792` | 把 `VendorClusterBridge` 统一成 `VendorDimProbe.kt` 那种风格 |
| L5 | **手势阈值与指数量语义** | `UPB/a/ch.java:106`（**实际指数 = 设置值 + 1**）、`:126-127`（`max(60, min(viewWidth*0.18, 320))`）、`:120-124`（多指 x 平均） | 若做显示/隐藏手势，直接对齐，避免自己拍脑袋 |
| L6 | **`postLatestFrame` 的 CAS 最新帧** | `video/DualSurfaceRenderer.java:366-372` | 任何把视频推到第二块屏的实现都该有（低延迟关键） |
| L7 | **`setZOrderOnTop(true)`** | `UP/a/vb.java:154` | 副屏 `SurfaceView` 必须置顶，否则被 Presentation 背景盖住。低成本高收益 |
| L8 | **关键帧缓存策略** | `UP/a/bc.java:304-325`（只在见到 SPS/IDR 后开始缓存，上限 90 帧/4 MiB）+ `:213-217`（首包无 IDR → re-arm） | 中途插入/切流时的抗黑屏配方 |

### 9.2 需上车才能验证/实现

| # | 事项 | HY Link 证据 | 验证方式 |
|---|---|---|---|
| C1 ⭐ | **`setPresentationToDimSwitch` 的实际效果与参数语义** | `UP/com/hyphp/sdk/navi/navi.java:3812`、`:3799` 签名 `(int,String,String,boolean)`；`api.java:16158`；6 个调用点 `navi.java:7558,8491,9011,10237,12997,13201` | 扩展 `VendorDimProbe`，把 `setPresentationToDimSwitch` 加进方法名清单，实机打印参数名（反射 `Method.getParameters()`）或看 `dumpsys` 日志 |
| C2 | **`INaviInteraction` 方法逐个验证** | `navi.java` 用到的 10 个方法（§2.3） | 对齐我们的探针枚举，逐个实机调用验证哪个生效 |
| C3 | **整数箭头对照表** | `UP/a/ca.java:953-954` 提供 `ManeuverType`；`navi.java:6394` → `updateTurnByTurnArrow(int)` | 逐值试 + 看仪表（46 号 §2/§3 已有 45 个 `ARROW_ICON_NAVI_*` 常量表和 Apple 类型映射表） |
| C4 | **`CAR_CONTROL`/`ADAPT_API` 的实际授予状态** | §5.3 | `dumpsys package` 三方对比（§9.5） |
| C5 | **`cluster_id`/`clusterMapEnable`/`cluster_overlay` 的实机取值** | §6.2 | 读 HY Link 的 prefs XML，或看它自己的诊断日志（`UP/a/q6.java:676-748` 打的就是这些键） |
| C6 | **`SYSTEM_ALERT_WINDOW` 对非系统应用在 API19 上开 Presentation 是否必需** | `UP/a/vb.java:130-140` | 实机试 `cluster_overlay` true/false 两种 |

### 9.3 明确**不要**照抄的部分
1. **`updateDriveDirectionImage(byte[])` 这条"图片箭头"路线**：它落到 HY Link 自研 HUD（`HUD.java:8941`），**不是厂商 DIM**；厂商的 `updateTurnByTurnArrow(Bitmap)` 是空方法（46 号 §1.4）。**照名字找会找错。**
2. **厂商 `updateDriveDirection(int)`**：**空方法**。HY Link 的同名中间层（`navi.java:17086`）是活的，但那是它自己的包装。
3. **`setPresentationToDimSwitch` 的两个 String**：明文未解出（§10），**不要凭空猜内容**。
4. **`ElementPresentation` 自研 HUD 体系**：与仪表投屏无关（§3.6），别浪费时间。
5. **`AspectRatioSurfaceView`**：主屏组件，与副屏无关（§3.6）。
6. **820A 专属项**（`mic820AecEnhancement` 等）：见 §6.3，CS11 走不到。
7. **`libu.so` 鉴权链路**：绑定包名/签名，我们不可用（doc 08 §4.4 已注明）。

### 9.4 / 9.5 点火取证命令清单（**只列命令与看点，未执行**）

**① 系统到底有几块 Display、仪表是哪块**
```powershell
adb shell dumpsys display                      # 每块 display 的 id/name/type/FLAG_PRIVATE/FLAG_PRESENTATION/state/尺寸/density
adb shell dumpsys window displays              # 每块 display 上的 window 栈、DisplayContent、mBaseDisplayInfo
adb shell dumpsys SurfaceFlinger --display-id  # 各 display 的合成层
```
**看点**：CS11 仪表那块 display 的 **id**、**type**（`TYPE_EXTERNAL`/`TYPE_HDMI`/厂商自定义）、**是否带 `FLAG_PRIVATE`**、**`FLAG_PRESENTATION`**、`uniqueId`/`name`。
⭐ **`FLAG_PRIVATE` 直接决定"第三方 app 能不能用 Presentation 开窗"，这是我们问题的核心未知（U1）。**

**② HyLink 运行时绑定了哪些厂商服务**
```powershell
adb shell dumpsys activity services com.hyphp.playkeytool
adb shell dumpsys activity services | Select-String -Pattern "hyphp|ecarx|carplay|dim"
```
**看点**：`ServiceName`/`ServicePkgName`（§2.4/§10 未解出的那两个字符串，**这里会直接现出明文**）、是否有到 `com.ecarx.*` / `com.ecarx.dim_interaction/.server.DiServer` 的绑定、binder 是否 alive。

**③ 它实际持有哪些权限**
```powershell
adb shell dumpsys package com.hyphp.playkeytool | Select-String -Pattern "permission"
adb shell dumpsys package com.hyphp.playkeytool | Select-String -Pattern "com.ecarx|CAR_CONTROL|ADAPT_API|CAR_VENDOR_EXTENSION|SYSTEM_ALERT_WINDOW"
adb shell dumpsys package com.hyphp.playkeytool | Select-String -Pattern "granted=true"
# 对照我们自己
adb shell dumpsys package com.shilapi.xcertplay | Select-String -Pattern "permission|com.ecarx"
# 以及 EasyPlay（包名由并行审计给出）
adb shell dumpsys package <easyplay.pkg> | Select-String -Pattern "permission|com.ecarx"
```
**看点**：`com.ecarx.permission.CAR_CONTROL`/`ADAPT_API` 是 **`granted=true` 还是 `false`（甚至 "unknown permission"）**。**若为 false 而仪表仍工作 ⇒ §5.3 推断成立，A 路线对我们开放。**

**④ 它给仪表推数据时自身日志里的类/方法名**
```powershell
adb logcat -c
adb logcat | Select-String -Pattern "CarplayMeta|CarLink|ClusterVideo|MirrorGlPipeline|ClusterPresentation|ClusterPreview|DiPlay-Dim|DimInteraction|INaviInteraction|setPresentationToDimSwitch"
```
**看点**（都是 HY Link 自己的日志 tag/文案，可在代码里对上）：
| tag | 代码位置 | 看什么 |
|---|---|---|
| `CarplayMeta` | `UP/a/ja.java` 全篇 | 元数据帧解析；`ID=20994`/`20993` 是否真出现、`ManeuverType` 取值范围 |
| `CarLink` | `UP/a/ca.java` | `cluster enable …`、`CLUSTER_READY(514)`、`盒子状态=…` |
| `ClusterVideo` | `UP/a/bc.java` | **`仪表副屏 Presentation 已显示 display=id=…`（直接把仪表 displayId 打出来）** |
| `MirrorGlPipeline` | `MainActivity.java:2347` | GL 镜像 Presentation 显示 |
| `ClusterPresentation` | `UP/a/vb.java` | **`Presentation请求显示 displayId=… bounds=… overlay=…`（`vb.java:85`）⇒ 就知道它在 CS11 上用哪块屏、什么 bounds、overlay 开没开** |
| `ClusterPreview` | `UP/a/n90.java:288`、`UP/a/wb.java:70` | 副屏区域预览 |

**⑤ 同样这几条对 EasyPlay 再抓一份 ⇒ 两者路线差异将从推断变成实测**
```powershell
adb shell pm list packages | Select-String -Pattern "easy|play|carplay"
adb shell dumpsys activity services <easyplay.pkg>
adb shell dumpsys package <easyplay.pkg> | Select-String -Pattern "permission|com.ecarx"
adb logcat | Select-String -Pattern "Dim|Navi|Cluster|Instrument|HUD|ecarx"
```
**判据**：
- 若 EasyPlay **没有**自己的第二路视频、只在 logcat 里看到 `DimInteraction`/`INaviInteraction` 调用 ⇒ **EasyPlay 走 A（原生注入）**，且不需要 ECARX 签名权限 ⇒ **我们照 A 做**。
- 若 EasyPlay 有 `Presentation`/独立 Surface，且 `dumpsys window displays` 能看到它的 window 落在仪表 display ⇒ **EasyPlay 也走 B**，那才需要解决"第三方 app 在仪表 display 上开窗"的问题。

### 9.6 未决项 U1–U8

| # | 未知 | 能定它的证据 |
|---|---|---|
| **U1** | 仪表那块 display 的 `FLAG_PRIVATE` / `FLAG_PRESENTATION` | `dumpsys display`（§9.4①） |
| **U2** | `CAR_CONTROL`/`ADAPT_API` 实机是否 granted，DIM 注入是否真的不依赖它 | `dumpsys package` 三方对比（§9.4③） |
| **U3** | `setPresentationToDimSwitch(int,String,String,boolean)` 两个 String 的语义 | 解出 `navi.java:7558` 等处的 `ue0` 加密串（**未解出**，§10）；或 `dumpsys activity services` 绑定明文；或实机日志 |
| **U4** | HY Link 绑定/暴露的服务 `ServiceName`/`ServicePkgName` 明文 | `dumpsys activity services com.hyphp.playkeytool`（§9.4②） |
| **U5** | CS11 上 HY Link 的真实 prefs（`cluster_id`/`clusterMapEnable`/`cluster_overlay`/`cluster_gesture_fingers`） | 读 `/data/data/com.hyphp.playkeytool/shared_prefs/*.xml`；或它自己的诊断日志（`UP/a/q6.java:676-748` 打的就是这些键） |
| **U6** | `updateTurnByTurnArrow(int)` 的整数→箭头对照表 | 实机逐值试（46 号 §2/§3 已有常量表与 Apple 映射表） |
| **U7** | 我们 fork "封面只显示第一首"的根因 | doc 38 §3 已定位为我们的封面管线；需实机 `cluster artwork uri=…` 日志（与 HY Link 无关） |
| **U8** | `ElementPresentation`（自研 HUD 体系）在 CS11 上到底有没有被启用 | 实机 `ClusterPresentation`/`HUD` tag + prefs；**建议先不追**，按 §3.6 判据放低优先级 |

---

## 10. 方法与未解项（如实交代）

### 10.1 本次实际使用的方法
jadx 输出精读 + 全树 grep + 资源/Manifest 直读 + **两版资源树全量 MD5 比对** + **从 `_upstream/cs11-carplay-dongle/apk_unpacked/dex/classes2.dex` 直接解析 dex 字符串表**（29054 条字符串）以定位 `com.hyphp.CarplayApi.*` AIDL 类族的存在与方向。

### 10.2 新增的 3 个未跟踪脚本
都在 `_analysis/hylink_src/`（**该目录未被任何 git 仓库跟踪**；本次未改动/未提交任何受跟踪文件）：
| 脚本 | 用途 | 结果 |
|---|---|---|
| `_analysis/hylink_src/dexstr.py` | dex 字符串表提取 | **成功**，用于确认 `com.hyphp.CarplayApi.*` 类族存在于 `classes2.dex` |
| `_analysis/hylink_src/dec.py` | 尝试解 `ue0` 的 `char = short[i] ^ key` 常量 XOR | **失败**（见 §10.3） |
| `_analysis/hylink_src/dexcode.py` | dex 指令遍历骨架 | **未完成**，未用于结论 |

### 10.3 `ue0` 字符串加密**未破**（两种形态 + 为什么不可执行）
1. **字面量型**：`UP/a/ue0.java:2861-2883` `s3(String)` —— 15 字符字母表 + 随机 key。**jadx 反编译有明显残缺**（`str.charAt(-2)` 负索引、`while(str.length() > 0)` 死循环）⇒ **不可直接执行**。相关同族：`UP/a/ue0.java:1713 b2`、`:3221 y4`、`:525 G3`、`:880 M2`、`:1939 e7`、`:829 L2`、`:750 K1`、`:2045 g2`、`:247 n4`。
2. **数字表型**：`public static String <m>(short[] sArr, int i2, int i3, int i4) { cArr[i] = (char)(sArr[i2+i] ^ i4); }` —— **算法本身已还原**，但 **jadx 把 `m619()`（`UP/com/hyphp/sdk/api.java:16016`）这类数组构造器反编译成控制流平坦化代码，无法还原数组内容**；我对文件内联的 `short[] f44short`/`f45short`（`api.java:3935`、`:4904`、`:118-131`）做"delta + 常量 key"暴力 **未得可读明文**。
⇒ **后果**：`ServiceName`/`ServicePkgName`（`api.java:3927-3928`、`:7986-7987`）与 `setPresentationToDimSwitch` 的字符串**明文未知**（U3/U4）。

### 10.4 其他未解/降级项
| 项 | 状况 |
|---|---|
| `UP/a/ch.java` | 在 `UP_unpacked` 里**反编译失败**（`throw new UnsupportedOperationException("Method not decompiled")`）⇒ 手势结论取自 **`UPB/sources/a/ch.java`**（bad-code 版） |
| `UP/a/xx.java` | `DisplayManager` 使用点是 `:225`；但 `HUD.java:264/:1410` 等同类调用是 `ue0` 加密调用，**未逐个解开**。不影响"发现机制 = `getDisplays()`"这一结论 |
| `ElementPresentation`（自研 HUD 体系） | 因字符串加密 + 控制流平坦化**未精读**；§3.6 的定性基于**调用点与结构**，属**【推断】** |
| `com.ecarx.eas.sdk.mediacenter.*` 注册是否需要授权 | **未证**；只确认了代码调用点（`UP/com/hyphp/sdk/media.java`） |
| `ue0` 的"元数据标签"字符串 | 未逐个解开；但 `UP/a/ca.java:1348-1364` 的 `CLUSTER_*` 是**明文**（下发指令名以 `q20.b(value, "NAME")` 形式出现），故 §4 的字段名是实锤 |

### 10.5 没有做的事
- 没有运行/安装任何 APK；
- **没有执行任何 `adb` 命令**（车机不在线；按约定只列清单，见 §9.4）；
- 没有修改或提交任何受跟踪文件。

---

## 11. 与 EasyPlay 审计（42 号）的接口

若 EasyPlay 侧能回答下面任一问题，**两边结论可以立刻合并**：
1. EasyPlay 是否调用 `com.ecarx.xui.adaptapi.diminteraction.DimInteraction`（对应 **A 路线**）？若调用，它是否声明了 `com.ecarx.permission.CAR_CONTROL`/`ADAPT_API`？
2. EasyPlay 是否创建 `android.app.Presentation` 或往非 0 号 display 加 window（对应 **B 路线**）？
3. EasyPlay 是否调用 `setPresentationToDimSwitch`（对应"投屏登记"）？
4. EasyPlay 的导航箭头走 **整数** 还是 **图片**（`updateTurnByTurnArrow(int)` vs `updateTurnByTurnArrow(Bitmap)`）？—— 直接决定 46 号 G2 该修哪一支。
5. EasyPlay 的音乐/封面走 binder `Parcelable` 还是走车机媒体中心注册？（45 号 §2.4 记录：CS11 上 EasyPlay 用的是**本地回环 HTTP 图床**，这条与 HY Link 的 `BitmapParcelable` 不同，值得交叉比对。）

**与我们自身状态的对照**（避免重复劳动）：
- `VendorDimProbe.kt:20` 已确认 `com.ecarx.xui.adaptapi.diminteraction.DimInteraction` **在本机可达**（doc 37）；
- doc 38 【确定-实测】确认 DIM 的**曲名/歌手/专辑/状态/进度已能显示**（`VendorClusterBridge.kt` 反射）；
- ⇒ **"我们完全没有 A 路线"不成立，我们只是没打通导航那一半**。45/46 号已把根因定位到 `VendorClusterBridge.kt:242-256` 的反射分发器拒绝装箱原始类型。
- **本篇的增量价值**：证明 HY Link 在这条路上的**调用清单与权限姿态**（零权限硬检查 + 全软依赖 + 整数箭头为主通路），并给出可借的几何/诊断/健壮性手法。

---

## 12. 与另外两篇的分工

| 文档 | 回答什么问题 | 数据源 |
|---|---|---|
| **45 号** `45-CS11仪表DIM接口与媒体差距清单.md` | **媒体/封面**：厂商 `DimInteraction`/`IMediaInteraction`/`IMediaDI`/`Media` bean 的**符号表**，EasyPlay 的两条媒体通路，我们媒体侧的差距 **G1–G9** | 我们的代码 + `_analysis/cs11_vendor/ecarx.adapterapi.jar` + EasyPlay v71 反编译源 |
| **46 号** `46-CS11仪表导航DIM接口与差距清单.md` | **导航/转向**：`INavigationDI`/`Turn` 权威表、`DimInteraction` 门面、45 个 `ARROW_ICON_NAVI_*` 常量、Apple maneuver→厂商箭头 id 映射、服务端单位转换规则、我们导航侧的差距 **G1–G13** | 同上 + `_analysis/cs11_vendor/XCDimInteractionService.apk`（车机自己的仪表服务）+ `dexdump` |
| **本篇 47 号** | **路线裁决**：HY Link **用哪条路**（投屏 B / 原生注入 A）把什么放到仪表上、**对无 ECARX 签名权限的第三方应用哪条可行**、机型归属（CS11 vs 其他）、**可借用清单**与**点火取证清单** | `_analysis/hylink_src/UP_unpacked/sources`（脱壳 dex v26082601）+ A/B 两版资源与 Manifest |

**一句话区分**：**45/46 号回答"厂商接口长什么样、我们的代码差在哪"（我们 ↔ 厂商）；47 号回答"HY Link 走的是哪条路、我们该走哪条"（参考实现 ↔ 我们）。**
⇒ 三篇**不重叠**：45/46 是**接口与差距**，47 是**架构与取舍**。以后若再有人问"HY Link 的仪表怎么做的"，直接指向本篇；若问"某个 DIM 方法名/参数"，指向 45/46。

**已知需回改的旧文档**：
- **doc 08 §4.2** 的 `"820A"` 日志前缀手法需加注记（§6.3）：该串为 820A 机型专用 `AudioManager.setParameters` 参数（`UP/a/j90.java:138-175` preset 独有 `mic820AecEnhancement`），**非通用前缀，CS11/i.MX6Q 走不到**。
- **doc 38 §2** 引 HiCar `ja.java` 得出的"箭头是图片路线（`updateDriveDirectionImage(byte[])`）"结论是**误导**（§2.3；46 号 §6.2 U6 已同向）：HY Link 的 `updateDriveDirectionImage` 落到自研 HUD，厂商侧箭头只有整数通路 `updateTurnByTurnArrow(int)`。

---

## 附注：CS11 实车事实修正（用户确认 2026-10-10）

**CS11 车机没有 HUD（抬头显示），只有方向盘后的液晶仪表。** 因此本文 §2.6 关于 `com.hyphp.sdk.hud.ElementPresentation` 的"结构推断：不是仪表投屏主路径"可以升级为**确定结论：在 CS11 上完全不适用**——不是"优先级低"，而是那条 HUD 体系在本车没有对应硬件。解析 HYLink 时**不必再精读 `sdk/hud/**`**（字符串加密 + 控制流平坦化，成本高、收益为零）。

受影响的其它结论：
- 火取证清单（§9.4）里 `ClusterPresentation`/HUD 相关 tag 仍然值得抓（它们属于**仪表副屏 Presentation**，与 HUD 无关），但**不要**把 `ElementPresentation` 的调用当成 CS11 的行为线索。
- 我们自己的 `com.shilapi.xcertplay.hud` 包名（`ClusterSongState.kt`/`RouteGuidanceState.kt`/`AdbShell.kt`）只是**包名沿用**，里面装的是"送仪表的数据"状态机，与 HUD 硬件无关。
