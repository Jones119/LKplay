# 46 · CS11 仪表导航（CarPlay 转向指引 → DIM）接口权威表 与 差距清单 G1–G13

> 产出范围：**只读审计**。**本次审计期间**未改动 `CarPlay-CS11` 任何生产代码、未提交任何东西。
> 📌 落盘时的观察（2026-10-10，**非本次审计所为**）：`CarPlay-CS11` 工作区已出现**并行修复**的未提交改动
> （`common/.../media/VendorClusterBridge.kt` +243 行、`CarPlayHostActivity.kt`、`AirPlayPersistence.kt`，
> 新增测试 `media/VendorMethodMatcherTest.kt`、`common/.../ClusterBridgePersistenceTest.kt`、`media/ClusterBridgeEnabledTest.kt`），
> 从文件名看正是 G1/G2 方向。**本篇未审阅、也未参与**这些改动；引用本清单时请以"审计基线 = commit `5863dd7c` 的干净工作树"为准。
> 定位：45 号（`45-CS11仪表DIM接口与媒体差距清单.md`）是**媒体/封面**侧；本篇是**导航/转向指引**侧。
> 两篇的 G1 是**同一个根因**（我们的反射分发器拒绝装箱原始类型），谁先修都行，修完两边一起好。
>
> 数据来源：
> - 我们的实现 `D:\Development\DiAuto-DiPlay\CarPlay-CS11`（分支 `cs11-api19`）
> - 厂商接口 jar `_analysis/cs11_vendor/ecarx.adapterapi.jar`（内含 `classes.dex`，448 152 B）
> - **车机自己的仪表服务** `_analysis/cs11_vendor/XCDimInteractionService.apk`（`classes.dex`，即 `/system/app/XCDimInteractionService.apk`；doc 37 实测 `dumpsys activity services` 指向它）
> - 参考实现 EasyPlay v71 反编译源 `_analysis/easyplay/v71/sources`（Java）
> - 我们的真机结论 `docs/_digest/37-…`、`38-仪表投屏实测与下一步.md`；`39-…` 交接
>
> 证据强度约定（沿用 45 号）：
> - **【确定-源码】**：直接引自 `file:line`，或 Java/DEX 语义必然成立
> - **【确定-反汇编】**：来自本篇新做的 `dexdump`，条目给出工件文件与指令地址（附录 A 可复跑）
> - **【确定-实测】**：来自 doc37/doc38 的真机日志或 `pm list packages` / `dumpsys` 输出
> - **【推断】**：由间接证据得出，条目内注明"能定它的证据"
> - **【未决】**：现有材料无法判定（见 §6.2）

---

## 0. 根因速览（先记住这一条）

**【确定-源码 + 确定-反汇编 + 最小复现】** `shared/src/main/java/com/shilapi/xcertplay/media/VendorClusterBridge.kt:242-256` 的反射分发器用

```kotlin
val types = args.map { it?.javaClass }
... parameter.isAssignableFrom(types[index]) ...   // :245-250
```

做匹配。Kotlin 的 `Int`/`Long` 传进 `vararg args: Any?` 后**装箱**成 `java.lang.Integer`/`java.lang.Long`，而厂商形参是**原始类型** `int`/`long`，于是

```
int.class.isAssignableFrom(Integer.class)  == false
long.class.isAssignableFrom(Long.class)    == false
```

⇒ **厂商所有数字型 setter 一个都没有被调用过**（`:250` 的 `?: return@runCatching null` 静默跳过），而日志仍打印 `-> null`，看起来像"调用成功"（见 G8）。唯一能过去的是 `String` 形参和 0 参方法。

这一条精确解释 doc38 §2 的实测现象：

| 我们调用的方法 | 形参 | 是否真的执行 | 仪表上的结果（实测） |
|---|---|---|---|
| `notifyTurnByTurnStarted()` | 0 参 | **执行了，但服务端抛异常**（G4） | 无变化 |
| `updateTurnByTurnArrow(Bitmap)` | `Bitmap` | **执行了，但厂商实现是空方法**（G2） | 箭头不显示 |
| `updateTurnByTurnArrow(int)` | `int` | **未执行**（G1） | — |
| `updateNextGuidancePointName(String)` | `String` | ✅ 执行 | **路名 ✅**（唯一正确的项） |
| `updateDistanceToNextGuidancePoint(int)` | `int` | **未执行**（G1） | 距离槽位缺 ⇒ 集群渲染自己的占位值 `0 m` |
| `updateDistanceToDestination(int)` | `int` | **未执行**（G1） | 同上 ⇒ 第二个 `0 m` |
| `updateETA(int)` | `int` | **未执行**（G1） | 时间槽位缺 ⇒ 集群渲染 `<1min` |

⇒ "路名对、箭头/两个距离/ETA 全不对，或整个卡什么都不显示"= 上表最后四行的直接后果。
（`road` 为空时 `:139` 直接跳过，此时我们一帧有效提交都没有 ⇒ "仪表什么都不显示"。）

---

## 1. 厂商接口与语义权威表

> 本节结论全部来自**车机自己的二进制**，不是猜的，也不是从 EasyPlay 反推的。以后要再解析 `INavigationDI` 之外的东西，按附录 A 的命令复跑即可。

### 1.1 `INavigationDI`（整体对象通路）【确定-反汇编】

- 接口全名 / descriptor：`com.ecarx.dim_interaction.bussiness.bean.INavigationDI`
- `INavigationDI$Stub.DESCRIPTOR = "com.ecarx.dim_interaction.bussiness.bean.INavigationDI"`
- `INavigationDI$Stub.TRANSACTION_turnByTurn`（事务号常量，`onTransact` 里 `readInt` 后 `CREATOR.createFromParcel`）
- 接口**只有一个方法**：
  ```
  turnByTurn(Lcom/ecarx/dim_interaction/bussiness/bean/Turn;)V
  ```
- 代理侧调用序列（`INavigationDI$Stub$Proxy.turnByTurn`，dex 0x019be0 起）：
  `Parcel.writeInterfaceToken(DESCRIPTOR)` → `writeInt(TRANSACTION)` → `Turn.writeToParcel(parcel, 0)` → `IBinder.transact` → `Parcel.readException()`
- 实现类（服务端）：`com.ecarx.dim_interaction.server.dim_interaction.NavigationDimInteraction extends INavigationDI$Stub`
- 同类还有 `IAdapterDI`（含 `getINavigationDI()/getIMediaDI()/getIPhoneDI()`）、`IMediaDI`、`IPhoneDI`，都在同一个 jar 的 `...bussiness.bean` 包里。

### 1.2 `Turn` 字段全表（类型 / 单位 / 过不过 binder）【确定-反汇编】

`com.ecarx.dim_interaction.bussiness.bean.Turn implements Parcelable`，实例字段（全部 `private`）：

| # | 字段 | DEX 类型 | 语义（服务端怎么用） | 单位/编码 | 过 binder？ |
|---|---|---|---|---|---|
| 1 | `turnState` | `I` (int) | 状态机：**只有 1/2/3 合法**（见 §4.3） | 枚举 | ✅ 第 1 个 |
| 2 | `navigationIcon1` | `S` (short) | 模板槽 1 图标（`commonIcon`） | 图标 id，`-1`=隐藏 | ❌ **不参与序列化** |
| 3 | `arrow2` | `I` (int) | 模板槽 2 图标 = **转向箭头** | `ARROW_ICON_NAVI_*`（§2） | ✅ 第 2 个 |
| 4 | `streetName3` | `String` | 模板槽 3 文本 = 下一转向点路名 | UTF-8 文本 | ✅ 第 3 个 |
| 5 | `distanceIcon4` | `S` (short) | 模板槽 4 图标（距离单位图标） | 图标 id，`-1`=隐藏 | ❌ 不参与序列化 |
| 6 | `distance5` | `String` | 模板槽 5 = **到目的地距离** | **整数米**（十进制字符串，如 `"41900"`） | ✅ 第 4 个 |
| 7 | `timeIcon6` | `S` (short) | 模板槽 6 图标（时间图标） | 图标 id，`-1`=隐藏 | ❌ 不参与序列化 |
| 8 | `timeEstimated7` | `String` | 模板槽 7 = **剩余时间** | **整数秒**（十进制字符串，如 `"600"`） | ✅ 第 5 个 |
| 9 | `nameOfSource8` | `String` | 模板槽 8 = 来源名（EasyPlay 留 null） | 文本 | ✅ 第 6 个 |
| 10 | `distanceToNextGuidance9` | `String` | 模板槽 9 = **到下一转向点距离** | **整数米**（十进制字符串，如 `"744"`） | ✅ 第 7 个 |

**构造与序列化（两条都记下来，避免以后踩坑）**

- 无参构造 `Turn()`：反射路径要用的就是它（EasyPlay `TurnSender:882` 用的也是无参构造）。
- 10 参构造，**参数顺序 = 字段声明顺序**（dex 0x01ae38–0x01ae76 的 `iput` 序列逐步确认）：
  ```
  Turn(int turnState, short navigationIcon1, int arrow2, String streetName3,
       short distanceIcon4, String distance5, short timeIcon6,
       String timeEstimated7, String nameOfSource8, String distanceToNextGuidance9)
  ```
- `writeToParcel`（dex 0x01b0e8–0x01b12e）**只写 7 个值**，顺序固定：
  ```
  writeInt(turnState)
  writeInt(arrow2)
  writeString(streetName3)
  writeString(distance5)
  writeString(timeEstimated7)
  writeString(nameOfSource8)
  writeString(distanceToNextGuidance9)
  ```
  `Turn(Parcel)` 构造函数（dex 0x01ae88–0x01aee2）按同序 `readInt/readString`。**三个 `short` 图标字段完全不参与序列化** ⇒ 即便客户端 `setNavigationIcon1/setDistanceIcon4/setTimeIcon6`，服务端收到的也永远是默认 `0`。
  **结论：不要花力气设这三个图标槽位**（它们也解释了为什么 EasyPlay 一个都不设）。
- 反射 setter 名单（全部存在，可 `getMethod`）：`setTurnState(int)`、`setArrow2(int)`、`setStreetName3(String)`、`setDistanceIcon4(short)`、`setDistance5(String)`、`setTimeIcon6(short)`、`setTimeEstimated7(String)`、`setNameOfSource8(String)`、`setDistanceToNextGuidance9(String)`；对应 getter `get*`。
  ⚠️ 三个图标 setter 形参是 **`short`**（不是 `int`），若真要调用，实参必须是 `java.lang.Short` 且反射匹配要处理原始类型（同 G1 的坑）。

### 1.3 服务端单位转换权威规则【确定-反汇编】

这些规则在**车机的 DiServer 里**，不由我们决定；我们只需要送对单位。

**(a) `TurnBuilder.revertDistance(String)` —— 距离：入参=整数米字符串**（dex 0x00668c 起，工件 `TurnBuilder.txt`）

| 条件 | 结果 |
|---|---|
| `v <= 1000`（米） | `"<int>m"`（例：`744` → `744m`） |
| `v > 1000` 且 `v/1000 > 100` | `"<int>km"` |
| `v > 1000` 且 `v/1000 <= 100` | `"<round(km*10)/10>km"`（例：`41900` → `41.9km`） |
| **解析失败（含 `null`）** | 返回 **`"0m"`**（异常被 catch，返回前面预先拼好的 `"0"+meter`） |

- 资源常量：`0x7f05001f = "m"`、`0x7f050020 = "km"`（`aapt2 dump resources` 实测）。
- 入参按 `Float.valueOf(String)` 解析，所以**必须是纯数字字符串，不能自带单位**（`"744m"` 会解析失败 → 退化成 `"0m"`）。

**(b) `TurnBuilder.secondToTime(String)` —— 时间：入参=整数秒字符串**（dex 0x00692c 起）

| 条件 | 结果 |
|---|---|
| `second < 0` | `"<1min"` |
| `minutes = (second + 30) / 60`，`minutes <= 1` | `"<1min"`（即 **≤ 89 s 都显示 `<1min`**） |
| `minutes < 60` | `"<minutes>min"`（例：`600` → `10min`） |
| `hours = minutes/60 < 24` | `"<hours>h <minutes%60>min"` |
| 否则 | `"<days>d <hours%24>h"` |

- 资源常量：`0x7f050021="m"`、`0x7f050022="min"`、`0x7f050023="h"`、`0x7f050024="d"`。
- 入参 `Integer.valueOf(String)` ⇒ **不能送 epoch**（1 791 467 456 会变成"多天"甚至溢出显示），必须送剩余秒数。
- ⚠️ `minutes` 的 `+30` 意味着它是**向上取整到分钟**；`<= 89 s` 显示 `<1min`，这解释了 doc38 早期观察到的 `<1min`。

**(c) `ItemsBuilder.commonString(byte id, String value)` —— 文本槽位的可见性**（dex 0x0058cc 起）

```
isVisible = !StringUtils.isEmpty(value)      // null / "" 都算 empty
value 为空  → length=0, rawData=null, isVisible=false
value 非空  → length=value.length(), rawData=value.getBytes(), isVisible=true, type=1
```

⇒ **空/null 字段不会崩、也不会被跳过：它会被发过去并标记为"不可见"**。集群对缺失/不可见槽位渲染**它自己的占位默认值**（实测表现为 `0 m`、`<1min`）——这一点是 G5 的直接依据。

**(d) `ItemsBuilder.commonIcon(byte id, int linkedResId)` —— 图标槽位的可见性**（dex 0x005860 起）

```
rawData = 2 字节小端 short（rawData[1]=低字节, rawData[0]=高字节）
isVisible = (linkedResId != -1)              // -1 ⇒ 隐藏；0 ⇒ 可见但图标 id=0
type = 3
```

**(e) 九宫格槽位映射**（`TurnBuilder` 各 setter 里 `commonXxx(slot, ...)` 的字面量，逐个确认）

| 槽位 | 字段 | 类型 |
|---|---|---|
| 1 | `navigationIcon1` | icon |
| 2 | `arrow2` | icon |
| 3 | `streetName3` | text |
| 4 | `distanceIcon4` | icon |
| 5 | `distance5` | text（经 `revertDistance`） |
| 6 | `timeIcon6` | icon |
| 7 | `timeEstimated7` | text（经 `secondToTime`） |
| 8 | `nameOfSource8` | text |
| 9 | `distanceToNextGuidance9` | text（经 `revertDistance`） |

### 1.4 现代包装层 `NaviInteractionImpl` 逐方法有效性表【确定-反汇编】

`com.ecarx.xui.adaptapi.diminteraction.NaviInteractionImpl implements INaviInteraction`（工件 `NaviInteractionImpl.txt`）：

| 方法 | 内部动作 | 有效？ |
|---|---|---|
| `updateTurnByTurnArrow(int)` | `setArrow2(int)` + `setTurnState(1)` + `turnByTurn()` | ✅ **唯一的箭头通路** |
| `updateTurnByTurnArrow(android.graphics.Bitmap)` | `return-void`（**1 条指令的空方法**，dex 0x0269d4） | ❌ **空方法** |
| `notifyTurnByTurnStarted()` | `setTurnState(0)` + `turnByTurn()` | ❌ 服务端抛异常（§4.3） |
| `notifyTurnByTurnStopped()` | `setTurnState(3)` + `turnByTurn()` | ✅ 有效（清卡） |
| `notifyDestinationReached(String, int)` | `setTurnState(2)` + `setDistanceToNextGuidance9(msg)` + `setArrow2(icon)` | ✅ 有效（显示后 2.5 s 自动清） |
| `notifyDestinationReached(String, Bitmap)` | `return-void` | ❌ 空方法 |
| `updateDistanceToDestination(int)` | `setTurnState(1)` + `setDistance5(String.valueOf(v))` | ✅（单位=米） |
| `updateDistanceToNextGuidancePoint(int)` | `setTurnState(1)` + `setDistanceToNextGuidance9(String.valueOf(v))` | ✅（单位=米） |
| `updateETA(int)` | `setTurnState(1)` + `setTimeEstimated7(String.valueOf(v))` | ✅（单位=**秒**） |
| `updateNextGuidancePointName(String)` | `setTurnState(1)` + `setStreetName3(v)` | ✅ |
| `updateDriveDirection(int)` | `return-void` | ❌ **空方法** |

要点：
1. **每一次 setter 都是"改一个字段 + 置 `turnState=1` + 整体重提交"**：`NaviInteractionImpl` 内部持有**一个** `Turn mTurn`（构造时 `new Turn()`），所以连续调用几个 setter 等价于"把累积后的整个 `Turn` 提交一次"。这解释了为什么我们的路名能显示：它之后的整体重提交里带着之前置过的状态。
2. **箭头只有整数 id 通路**。doc37/38 里"HiCar 用 `updateDriveDirectionImage(byte[])` ⇒ 箭头是图片路线"这条线索是**误导**：本车 jar 的 `INaviInteraction` 里**根本没有 `updateDriveDirectionImage` 这个符号**（全 dex 字符串扫描 0 命中），`updateDriveDirection(int)` 还是空方法。见 §6.2 U6。
3. `getNaviInteraction()` 每次调用都 `new NaviInteractionImpl(...)`（不缓存），我们缓存的是"第一次拿到的那个对象"，见 G7。

### 1.5 `DimInteraction` 门面（`DimInteractionImpl`）【确定-反汇编】

工件 `DimInteractionImpl.txt`：

| 环节 | 行为 | 指令/行 |
|---|---|---|
| `DimInteraction.create(Context)` | `new DimInteractionImpl(context)`（构造里 `init()`） | dex 0x025dbc |
| `init()` | `mAppType = "com.ecarx.di_server"`；`bindService(mAppType)` | dex 0x0260b4–0x0260e4 |
| `bindService(String appType)` | `Intent{component=com.ecarx.dim_interaction / com.ecarx.dim_interaction.server.DiServer, extra app_type=<appType>}`，**flag=1（BIND_AUTO_CREATE）**，**不设 action** | dex 0x026024–0x0260b0 |
| `onServiceConnected` | `IAdapterDI$Stub.asInterface(binder)` → `getINavigationDI()/getIPhoneDI()/getIMediaDI()` | dex 0x025df4–0x025f12 |
| `onServiceDisconnected` | 四个 DI 引用全部置 null | dex 0x025f20–0x025f68 |
| `getNaviInteraction()` | `mNavigationDI != null ? new NaviInteractionImpl(mNavigationDI) : null` | dex 0x026168–0x0261d0 |
| 日志标签 | `DimInteractionImpl`（`Utils.LogD`），含 `getNaviInteraction() mNavigationDI is null: <bool>` | — |

⇒ 绑定参数与我们/ EasyPlay 用的完全一致（EasyPlay 多设一个 `<pkg>.DIM_NAVIGATION` action，因显式 ComponentName 而无害）。**`app_type` 是我们（`com.ecarx.di_server`）拿 `IAdapterDI` 的关键**，缺了就 `onBind` 返回 null（§4.1）。

### 1.6 与媒体侧的关系

`IMediaInteraction` / `IPlaybackInfo` / `IMediaDI` / `Media` bean 的完整符号表在 **45 号 §1.3–1.7**，本篇不重复。导航与媒体共用同一个 `DimInteractionImpl` + `DiServer` 绑定与 `app_type=com.ecarx.di_server`，因此 **G1 的反射 bug 同时命中媒体侧**：
`updateCurrentSourceType(int)`（`SOURCE_TYPE_ONLINE=6`）与 `updateCurrentProgress(long)` 也从未执行（见 G10）。媒体能显示曲目/封面是因为它们走 `updatePlaybackInfo(IPlaybackInfo)`（**对象形参**，能匹配）。

---

## 2. `Turn` 常量全表（45 个 `ARROW_ICON_NAVI_*` + `STATE_*`）【确定-反汇编】

全部为 `public static final short`（`STATE_*` 是 `int`），静态值由 `dexdump` 直接读出（工件 `Turn_section.txt`）。**没有 0，没有 13**（即不存在"13 号箭头"），`EXIT_L` 与 `DEST_1` 都是 `19`。

| 名称 | 值 | 名称 | 值 |
|---|---|---|---|
| `ARROW_ICON_NAVI_STRAIGHT` | **1** | `ARROW_ICON_NAVI_TOLL` | 16 |
| `ARROW_ICON_NAVI_TURN_BACK_L` | 2 | `ARROW_ICON_NAVI_DEST` | 17 |
| `ARROW_ICON_NAVI_TURN_BACK_R` | 3 | `ARROW_ICON_NAVI_FERRY` | 18 |
| `ARROW_ICON_NAVI_TURN_R_T` | 4 | `ARROW_ICON_NAVI_EXIT_L` | 19 |
| `ARROW_ICON_NAVI_TURN_L_T` | 5 | `ARROW_ICON_NAVI_DEST_1` | 19 |
| `ARROW_ICON_NAVI_TURN_R` | **6** | `ARROW_ICON_NAVI_DEST_2` | 20 |
| `ARROW_ICON_NAVI_TURN_L` | **7** | `ARROW_ICON_NAVI_DEST_3` | 21 |
| `ARROW_ICON_NAVI_TURN_R_D` | 8 | `ARROW_ICON_NAVI_DEST_4` | 22 |
| `ARROW_ICON_NAVI_TURN_L_D` | 9 | `ARROW_ICON_NAVI_ENTRY_R` | 23 |
| `ARROW_ICON_NAVI_KEEP_R` | 10 | `ARROW_ICON_NAVI_ENTRY_L` | 24 |
| `ARROW_ICON_NAVI_KEEP_L` | 11 | `ARROW_ICON_NAVI_SA` | 25 |
| `ARROW_ICON_NAVI_EXIT_R` | 12 | `ARROW_ICON_NAVI_TUNNEL` | 26 |
| *（13：不存在）* | — | `ARROW_ICON_NAVI_GO_AHEAD_ALONG_RIGHT_SIDE` | 27 |
| `ARROW_ICON_NAVI_EXIT_MID` | 14 | `ARROW_ICON_NAVI_GO_AHEAD_ALONG_LEFT_SIDE` | 28 |
| `ARROW_ICON_NAVI_ROUND` | **15** | `ARROW_ICON_NAVI_THE_ROUND_EXIT` | **29** |
| `ARROW_ICON_NAVI_30` … `ARROW_ICON_NAVI_45` | **30 … 45**（连续 16 个） | | |

状态常量：

| 名称 | 值 | 服务端行为 |
|---|---|---|
| `STATE_ONGOING_GUIDANCE` | 1 | 构建并下发完整卡（**唯一的"正常更新"状态**） |
| `STATE_REACHED` | 2 | 构建并下发完整卡，**2.5 s 后自动清除** |
| `STATE_END` | 3 | 立即 `dropNavigationScreen()` |

> **`-1` 不是常量但是有效值**：`commonIcon` 用 `-1` 表示"该图标槽位不可见"（§1.3(d)）。EasyPlay 在"前往路线起点"（`proceedToRoute`）时就用 `arrow = -1` 把箭头藏掉（§3）。

---

## 3. Apple maneuver 类型 → 厂商箭头 id 完整映射表【确定-源码】

来源：`_analysis/easyplay/v71/sources/com/shilapi/xcertplay/legacy/LegacyLynkNavigationBridge.java:786-829`（`arrowFor$legacy`）。这是**在 CS11 上能工作的参考实现**用的表，直接照抄即可（右侧 id 全部与 §2 的常量对得上）。

| Apple 类型（`RouteGuidanceManeuverType`） | 条件 | 厂商箭头 id | 常量名 |
|---|---|---|---|
| 1, 20, 47, 49 | — | 7 | `TURN_L` |
| 2, 21, 48, 50 | — | 6 | `TURN_R` |
| 3, 5 | — | 1 | `STRAIGHT` |
| 4, 18, 26 | `drivingSide == 0` | 2 | `TURN_BACK_L` |
| 4, 18, 26 | `drivingSide == 1` | 3 | `TURN_BACK_R` |
| 4, 18, 26 | 其它 | **null** | 不提交 |
| 6, 19 | — | 15 | `ROUND` |
| **7** | — | 29 | `THE_ROUND_EXIT` |
| **28 … 46** | — | 29 | `THE_ROUND_EXIT` |
| 10, 12, 24, 25, 27 | — | 17 | `DEST` |
| 13, 52 | — | 11 | `KEEP_L` |
| 14, 53 | — | 10 | `KEEP_R` |
| 15, 16, 17 | — | 18 | `FERRY` |
| 22 | — | 19 | `EXIT_L`（= `DEST_1`） |
| 23 | — | 12 | `EXIT_R` |
| **其它（8, 9, 11, 30+ …）** | — | **null** | **整卡清除**（`prepare` 返回 null ⇒ `clearOwned()`，不是画直行） |
| **`proceedToRoute`**（`routeState==6` 且 Apple 类型 `==11`） | — | **-1**（图标不可见）+ 路名 `"前往路线"` | `:724-726, :767-769` |

配套的文本规则（同样值得照抄）：
- 路名回退链：`afterRoad` → `road` → `currentRoad`（`:758-765`）。
- `cleanRoad(String)`：去掉 ISO 控制字符、按 **UTF-8 ≤ 120 字节**截断、`trim()`（`:831-855`）。
- 可显示状态白名单：`{1, 5, 6}`（`CarPlayNavigationState.java:24`）；不在白名单 ⇒ 不显示。
- 数值门禁（`:735-757`）：`distanceMeters >= 0`、`remainingMeters ∈ [0, 2^31)`、`remainingSeconds ∈ [0, 2147483618)`；**三者任一缺失 ⇒ 整卡不提交**（宁可没卡，也不显示 `0 m`）。

---

## 4. DiServer（`com.ecarx.dim_interaction/.server.DiServer`）侧事实【确定-反汇编】

工件：`dimsvc_dexdump.txt`（本文引用其行号）+ `NavigationDimInteraction.txt`。

### 4.1 `onBind` 的 `app_type` 分派表

`DiServer.onBind(Intent)`（工件行 5401–5473）**只读 `intent.getStringExtra("app_type")`**，然后：

| `app_type` | 返回的 Binder | 接口 |
|---|---|---|
| `com.ecarx.di_server` | `mWholeDimInteraction` | `IAdapterDI`（再 `getINavigationDI()`/`getIMediaDI()`/`getIPhoneDI()`） |
| `com.ecarx.navigation` | `mNavigationDimInteraction` | `INavigationDI` |
| `com.ecarx.phone` | `mPhoneDimInteraction` | `IPhoneDI` |
| `com.ecarx.media_center` | `mMediaDimInteraction` | `IMediaDI` |
| 其它 / 缺失 | **null** | 绑定失败 |

`WHITE_LIST_TOTAL/MEDIA/PHONE/NAVIGATION` 这几个私有静态常量的**值就是上面四个字符串**，没有别的含义。日志：`DiServer` 标签下打印 `appType = <值>   mAppScreenManager = <对象>`。

### 4.2 权限 / Manifest【确定-反汇编 + 确定-实测】

`XCDimInteractionService.apk`（即车上 `/system/app/XCDimInteractionService.apk`）的 `AndroidManifest.xml`（`aapt2 dump xmltree`）：

```
package="com.ecarx.dim_interaction"  android:sharedUserId="android.uid.system"
versionCode=53  versionName="0.0.33"  minSdkVersion=19  targetSdkVersion=19
<application android:persistent="true" …>
  <service android:name=".server.DiServer" android:exported="true"/>   ← 无 android:permission、无 intent-filter
uses-permission: RECEIVE_BOOT_COMPLETED, READ_EXTERNAL_STORAGE
```

⇒ **我们侧不需要任何新权限，也不需要 intent-filter**（用显式 ComponentName 绑定即可）。服务端**不校验调用方**（没有签名/包名白名单逻辑），所以任何 App 都能绑。

### 4.3 `turnState` 合法值只有 1/2/3；`0` 会抛异常【确定-反汇编】

`NavigationDimInteraction.turnByTurn(Turn)`（dex 0x00cd88，工件 `NavigationDimInteraction.txt` 第 256 行起）控制流（`if-nez` = **不为 0 则跳转**）：

```
0x005e  if (turn == null) throw RuntimeException("Turn object can not be null.")
0x0068  items = null
0x0069  state = turn.getTurnState()
0x006d  if (state != 0) goto 0x0079          // state == 0 直接落到下面
0x006f  if (items != null) goto 0x013d       // state==0 时 items 为 null ⇒ 不跳
0x0071  throw RuntimeException("Navigation state is not correct.")   ← turnState==0 走这里
...
0x0079  if (state == 1)  → 构建完整 payload（0x007f–0x00d0）→ goto 0x006f → items != null → 0x013d 下发
0x00d1  if (state == 2)  → 构建 payload + 启动 2.5 s 后 drop 的线程（0x0129–0x0131）
0x0133  if (state == 3)  → dropNavigationScreen(); return
        其它值          → 同样落到 0x0071 抛异常
```

- **`turnState == 0` 及其它非 1/2/3 值 → 服务端抛 `RuntimeException("Navigation state is not correct.")`**，AIDL 把它作为异常回给客户端（`Parcel.readException()` 会 rethrow）。
  ⇒ `NaviInteractionImpl.notifyTurnByTurnStarted()`（它置 0）是**死方法**，我们在 `VendorClusterBridge.kt:126-129` 调它只会被 `runCatching` 吞掉 + 一行 `Log.w`。**参考实现从不发 0。**
- **`state == 2`（REACHED）**：先下发完整卡，再 `Thread.sleep(2500)` 后调 `dropNavigationScreen()`（`NavigationDimInteraction$1.run`）。
- **`state == 3`（END）**：直接 `dropNavigationScreen()`，即 `DimScreenUpdateData(operationType=1, sendType=1, templateId=3, templateStatus=0, emptyList)`。
- **`state == 1`**：`DimScreenUpdateData(1, 1, 3, templateStatus=1, items)`，`items` 由 `TurnBuilder` 构建（槽位见 §1.3(e)）。

### 4.4 ⭐ 上车验收的**首要依据**：厂商那一行 6 值日志【确定-反汇编】

`turnByTurn` 的第一件事就是打一行 `Log.d`（tag = 类简单名 **`NavigationDimInteraction`**，dex 0x00cd98–0x00ce4e）：

```
<arrow2>   <streetName3>   <distance5>   <timeEstimated7>   <nameOfSource8>   <distanceToNextGuidance9>
```

- 分隔符是 3 个空格（`"   "`），顺序固定：`getArrow2() / getStreetName3() / getDistance5() / getTimeEstimated7() / getNameOfSource8() / getDistanceToNextGuidance9()`。
- **这行日志只有 `turnState ∈ {1,2,3}` 才可能出现** ⇒ 它同时是"事务到达服务端"和"服务端接受了状态"的双重证据。
- 采信规则：
  - 一行都没有 ⇒ 我们的调用根本没到（客户端侧问题，见 G1/G8）；
  - 有行但数值全是默认值（`arrow2=0`、字符串为空） ⇒ 事务到了但载荷是空的（就是当前构建的现象）；
  - 有行且数值正确 ⇒ 客户端已修好；此后仪表仍不对，问题就在集群侧（模板/渲染）。

### 4.5 模板层（供理解"为什么缺字段会显示 0 m"）

- 导航卡固定用 `templateId = 3`；`templateStatus = 1`（显示）/`0`（隐藏）；`operationType=1`、`sendType=1`。
- 槽位 1..9 见 §1.3(e)；`DimScreenUpdateData(IIBBLjava/util/List;)` 构造函数里 `items == null` 是被容忍的（`numberOfItems` 保持 0，不 NPE；`if-eqz` 空判在前）。
- 因此"**我们少发一个槽位**"≠"集群不画"：集群会用**自己的**默认值把该槽位填上（`0 m`、`<1min`）。这正是 doc38 实测"两个距离都是 0 m、ETA 是 `<1min`"的最可能来源【推断，见 U1】。

---

## 5. 差距清单 G1–G13

> 排序 = "能解释'仪表什么都不显示/卡不完整'的强度"。
> 标 **离线** = 不需要车就能改+单测；标 **需上车** = 必须真机确认。
> G12/G13 是**非差距**（列出来是为了阻止后人白费时间）。

### G1 · 反射分发器拒绝装箱原始类型 ⇒ 所有数字型 setter 静默丢弃 —— **最高优先** 【确定-源码 + 确定-最小复现】

- 机制：`VendorClusterBridge.kt:242-256`。`args.map { it?.javaClass }` 得到 `Integer`/`Long`，与形参 `int`/`long` 不满足 `isAssignableFrom`；`:250` 的 `?: return@runCatching null` **静默返回**，既不打 warning 也不打 not-found 标记。
- 命中方法：`updateDistanceToNextGuidancePoint(int)`、`updateDistanceToDestination(int)`、`updateETA(int)`、`updateTurnByTurnArrow(int)`、`updateCurrentSourceType(int)`、`updateCurrentProgress(long)`。
- 后果：`Turn` 里只有 `streetName3` 被置过（`updateNextGuidancePointName` 之后的整体重提交把状态带过去）⇒ 路名 ✅，其余槽位缺失 ⇒ 集群渲染占位默认值。`road` 为空时干脆一帧有效提交都没有 ⇒ "仪表什么都不显示"。
- 改法：原始类型与包装类型等价匹配（如 `boxed(p) == t || p.isAssignableFrom(t)`），并对 `args[i] == null` **拒绝**原始形参（否则 `invoke` 会抛 `IllegalArgumentException`）；或直接 `getMethod(name, *explicitTypes)`。
- **离线可单测**：把匹配抽成纯函数（无 Android 依赖），用带 `int/long/short/String/0 参` 方法的假类做 JVM 测试。现成风格：`shared/src/test/java/com/shilapi/xcertplay/glance/CarPlayGlanceTest.kt`（纯 JVM）、`common/src/test/java/com/shilapi/xcertplay/VirtualClusterDisplayTest.kt`（Robolectric）。
  （落盘时工作区已有一个并行实现的 `shared/src/test/java/com/shilapi/xcertplay/media/VendorMethodMatcherTest.kt`，**本篇未审阅**；无论谁实现，判据就是这一条。）

### G2 · "自己画箭头"通向空方法 【确定-反汇编】

- 机制：`VendorClusterBridge.kt:131-138` 优先调 `updateTurnByTurnArrow(Bitmap)`；厂商实现是 `return-void` 空方法（§1.4）。
- 改法：改用 `updateTurnByTurnArrow(int)` + §3 的映射表（Apple 类型 → §2 的 id）；`proceedToRoute` 用 `-1`。`-1` 是"隐藏箭头"，`0` 是"可见但图标 0"。
- **离线可单测**：映射表是纯函数（输入 `maneuverType, drivingSide, proceedToRoute` → `Int?`），用 `CarPlayGlance` 的帧构造器造输入即可。

### G3 · ETA 单位/语义反了（我们送绝对到达时刻，服务端当剩余秒数）【确定-反汇编】

- 机制：`VendorClusterBridge.kt:144-147`（注释还把 `arrivalEpochSeconds` 描述成"仪表字段读作到达时间"）；服务端 `updateETA(int) → setTimeEstimated7 → secondToTime`（§1.3(b)）。
- 改法：只送 `remainingSeconds`；对 `>= 2147483618` 或负值视作 null（照参考 `:750-757`）。`arrivalEpochSeconds` **不要送**。
- **离线可单测**：断言"载荷里的时间是秒而不是 epoch"，含 `< 90 s` ⇒ 服务端 `<1min` 的边界。

### G4 · `notifyTurnByTurnStarted()` 是非法状态（`turnState=0`）【确定-反汇编】

- 机制：`VendorClusterBridge.kt:126-129`；服务端 §4.3，0 → `RuntimeException("Navigation state is not correct.")`。
- 改法：删掉该调用（"卡片出现"由第一次 `turnState=1` 提交完成）。
- **离线可做**；"删掉后卡片照旧出现"**需上车**确认（当前它只产生一行被吞掉的 warning，理论上无副作用，但那是**未经证实**的假设）。

### G5 · 缺"完整性与回退链"⇒ 集群渲染自己的占位默认值 【参考确定 / 默认值机制推断】

- 参考（`LegacyLynkNavigationBridge.java:714-784`）：`routeState ∈ {1,5,6}`、`distanceMeters>=0`、`remainingMeters∈[0,2^31)`、`remainingSeconds∈[0,2147483618)` **全部**满足才构造卡；任一缺失或 `arrowFor` 返回 null ⇒ **不提交并清掉自己写的卡**。路名链 `afterRoad→road→currentRoad`，`proceedToRoute` 用 `-1` + `"前往路线"`。
- 我们：`VendorClusterBridge.kt:139`（只 `road`，无回退链）、`:140`（`> 0` 才发，0 米时保留陈旧值）、`:143`（`toInt()` 无溢出保护）、`:144-147`；`CarPlayGlance.Snapshot.proceedToRoute`（`glance/CarPlayGlance.kt:24,82`）**完全没被使用**。
- 改法：① 路名回退链 + UTF-8 ≤120 字节截断；② `distanceMeters >= 0`；③ `remainingMeters` 溢出保护；④ `proceedToRoute` → `-1` + `"前往路线"`；⑤ 决定策略：照抄"齐全才提交"（卡片会消失而不是显示 `0 m`），并接受"iPhone 短暂空列表"期间的抖动（参考用 3 s 宽限 + 30 s 过期，我们 `hud/RouteGuidanceState.kt:218-219` 已有同样窗口）。
- **离线可单测**：把"快照 → 载荷"做成纯函数，直接断言字段与 null 策略。

### G6 · 是否绕过现代包装层，直接走 `INavigationDI.turnByTurn(Turn)` 【参考确定 / 需上车验证】

- 参考 deliberately 不用 `getNaviInteraction()`，而是自己 `bindService` → 读 `getInterfaceDescriptor()` → `INavigationDI$Stub.asInterface`（或 `IAdapterDI` → `getINavigationDI()`）→ 反射构造 `Turn` → 一次 `turnByTurn`（`LegacyLynkNavigationBridge.java:983-1085`、`TurnSender:880-912`）。
- 它规避的三件事都是我读字节码确认的：包装层的 Bitmap/DriveDirection **空方法**、`notifyTurnByTurnStarted` 的**非法状态**、`getNaviInteraction()` **每次 new 一个新对象**。
- 建议顺序：**先做 G1/G3/G4（最小改动、离线可测）**；包装层在修好后功能上够用（同一个 `Turn` 累加，每次 setter 整体重发）。只有在仪表仍不对时才上 G6。
- 判据：G6 的"直连"版本能自己控制 `turnState` 与字段顺序，并能在 §4.4 日志里逐字段核对；**需上车**。
- 附：反射实现要点（照抄参考即可）
  ```kotlin
  Class.forName("com.ecarx.dim_interaction.bussiness.bean.INavigationDI\$Stub")
      .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
  val turn = Class.forName("com.ecarx.dim_interaction.bussiness.bean.Turn")
      .getConstructor().newInstance()
  // setTurnState(int) / setArrow2(int) / setStreetName3(String)
  // setDistanceToNextGuidance9(String 米) / setDistance5(String 米) / setTimeEstimated7(String 秒)
  navigationType.getMethod("turnByTurn", turnType).invoke(navigation, turn)
  ```

### G7 · 无连接 epoch / 重连重解析 【参考确定 / 需上车】

- 参考：`observeConnection()`/`ownedConnectionEpoch`/`submitted` 去重（`:554-601`）、`connectionEpoch()`（`:1088-1094`）、`clear(expectedEpoch)`（`:1097-1125`，只清"自己写的"）、`close()` 先清后解绑（`:1128-1149`）。
- 我们：`VendorClusterBridge.kt:163-192` 的 `dim/media/navi` 一旦非 null 就**永久缓存**；DiServer 重启后拿到的是死代理，`DeadObjectException` 被 `runCatching` 吞掉，此后**再也不更新且没有任何日志**。
- 改法：给接口加"代"（binder/接口对象身份或 onServiceDisconnected 回调），代变则置空重取；清除时只在"自己是当前拥有者"时发 `turnState=3`。
- **需上车**（要能制造 DiServer 重启；`am force-stop com.ecarx.dim_interaction` 可能可行，但它是 `persistent` + `uid=system`，风险自评）。

### G8 · 诊断不可分辨"方法没找到"和"调用成功返回 void" 【确定-源码】

- `VendorClusterBridge.kt:243-255`：未找到方法时**没有任何日志**；成功与未找到都打 `-> null`；且 `shared/.../diag/DiagLog.kt:36-39` 默认关闭（需 `debug.diplay.trace 1` 或设置里的 debug logs）。
- 这就是 doc38 / 提交 `7d9d863d` 把"日志里有 `updateETA(1791467456)`"当成"调用已发出"的原因——那行以 `-> null` 结尾，其实**没调用**。
- 改法：未找到时显式 `Log.w(TAG, "cluster $method: no matching overload (args=...)")`；trace 里区分 `invoked(void)` / `not-found` / `threw`。
- **离线可单测**。

### G9 · binder 调用在 `CarPlayGlance` 的监视器内、iAP2 控制线程上 【确定-源码】

- `glance/CarPlayGlance.kt:38-42`（`snapshot()`）与 `:45-53`（`onFrame`）都在 `synchronized(this)` 里调 `publishLocked()`，而 `:89` 在其中直接调 `pushGuidance` ⇒ 单次最多 6 次**同步 binder 事务**，厂商服务忙/卡住会阻塞整条 iAP2 帧处理路径。
- 参考：全部经单线程 `ScheduledExecutorService`（daemon 线程名 `diplay-lynk-navigation`，`LegacyLynkNavigationBridge.java:858-862` + `post()` `:674-690`）。
- 改法：把集群推送移出锁，或在专用单线程 executor 上 `post`。
- **离线可改**；建议引入可注入 hook（如 `internal var clusterSubmit: (Snapshot) -> Unit`）以便单测断言"提交发生在锁外"。

### G10 · 媒体侧被同一个 bug 命中（同 G1）【确定-源码】

- `VendorClusterBridge.kt:74-76`：`updatePlaybackInfo(IPlaybackInfo)`（**对象形参，能匹配** ⇒ 曲目/歌手/专辑/封面能用）、`updateCurrentSourceType(SOURCE_TYPE_ONLINE=6)`（**int，不匹配**）、`updateCurrentProgress(long)`（**不匹配**）⇒ 音源类型从未设置、进度条不更新。
- 改 G1 即修。**离线可单测**（同一个纯函数测试覆盖即可）。

### G11 · "地图+自定义转向卡"没有任何绘制实现 【确定-源码】

- `airplay/ClusterTurnCardOverlay.kt:33-51` 的 `card()` **全仓只有测试调用**；`AirPlayPersistence.kt:626-698` 的 X/Y/尺寸/透明度**只写不读**；`CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD` 仅被 `usesCustomTurnCard`（`CarPlayClusterDisplay.kt:26`）与设置界面（`DiPlayActivity.kt:744-760`，且只触发重连）引用。
- 后果：选这个模式 = 只有地图、**不会**有转向卡（"仪表什么都不显示"的另一种可能）。
- 改法：要么实现卡片渲染（`ClusterTurnCardOverlay.card()` + `CarPlayGlance` 数据画在 overlay 上），要么把该选项从设置里去掉/改名以免误导。
- **离线可查**（grep 即可复现），渲染实现**离线可做**。

### G12 · 非差距：权限 / Manifest —— **不要在这里找原因**

`DiServer` `exported=true` 且**无 `android:permission`**、无 intent-filter，服务端**不校验调用方**（§4.2）。我们不需要新增权限、不需要改 Manifest、不需要 service 声明。

### G13 · 非差距：CarPlay 采集侧（iAP2 订阅与解析）—— **不要在这里找原因**

- 订阅：`iap2/message/Iap2Messages.kt:287-291`（`START_ROUTE_GUIDANCE_UPDATES`，`u16(0,42); void(1); void(2)`），端点 `iap2/catalog/Iap2Endpoint.kt:431-436`（0x5200）。
  与 EasyPlay **逐字节相同**：`_analysis/easyplay/v71/sources/com/shilapi/xcertplay/iap2/message/Iap2ControlMessages.java:74-77`。
- 解析：`hud/RouteGuidanceState.kt:53-60, 102-157`（0x5201：0x01 state / 0x03 road / 0x05 arrivalEpoch / 0x06 remainingSeconds / 0x07 remainingMeters / 0x0a distanceMeters / 0x0d maneuver 列表）、`:159-174`（0x5202：0x01 index / 0x03 type / 0x04 afterRoad / 0x08 drivingSide）、`:218-219`（`STALE=30 s`、`EMPTY_LIST_HIDE=3 s`）。
  与 EasyPlay `CarPlayNavigationState.java:200-252, 254-293`、`:24,27,30` 完全对得上。
- 一个**可选**的小差异（不是根因）：我们的 `activeManeuver()`（`:90-97`）没有"可显示状态白名单 `{1,5,6}`"这一层，`routeState` 为 3/4/7 等时仍会当作有效引导。修不修都行，与 G1 无关。

---

## 6. 上机判据 与 未决项

### 6.1 上机判据（第 0/1/2 步）

**第 0 步 · 不改代码，先把观测点架好**

```powershell
adb shell setprop debug.diplay.cluster 1     # 打开仪表推送（VendorClusterBridge.kt:31,65）
adb shell setprop debug.diplay.trace 1       # 打开 DiagLog（否则 call() 的 trace 一行都不打）
adb logcat -c
adb logcat -s DiPlay-Cluster:I DiPlay-Dim:I NavigationDimInteraction:D NavigationDimInteraction:V DiServer:I DimInteractionImpl:D
```

冷启动 DiPlay → 连上 CarPlay → **用 Apple 地图开始一段带转向的导航**。

| 判据 | 看什么 | PASS | FAIL 的含义 |
|---|---|---|---|
| **P1 绑定成功** | `DiPlay-Cluster` 出现 `cluster navi interaction=com.ecarx.xui.adaptapi.diminteraction.NaviInteractionImpl`（`VendorClusterBridge.kt:177`）；`DiServer` 出现 `appType = com.ecarx.di_server` | 我们拿到了包装层对象 | 只有 `dim api class not on the classpath` 或 `navi interaction unavailable` ⇒ 先修绑定，其余免谈 |
| **P2 服务端收到了什么** | `NavigationDimInteraction` 那一行 6 值日志（§4.4） | 出现 `arrow2=§3 表里的 id`、`streetName3=转向后路名`、`distance5/distanceToNextGuidance9=非空整数米`、`timeEstimated7=非负整数秒` | **当前构建必然 FAIL**：只有 `streetName3` 有值、其余空（G1/G2/G3 未修）。一行都没有 ⇒ 事务没到服务端 |
| **P3 我们自己的日志别被误读** | `cluster updateETA(1791467456) -> null` | —— | 这行**不代表调用成功**（G8）。必须是显式"no matching overload"告警才算证据 |

**第 1 步 · 最小修复（全部离线可完成、可单测）**
G1（反射匹配）→ G2（改整数箭头 id + §3 映射）→ G3（ETA 送秒）→ G4（删掉 `notifyTurnByTurnStarted`）→ G5（路名回退链、`>=0`、溢出保护、`proceedToRoute`）。

**第 2 步 · 上车复测**

PASS 需三条同时成立：
1. §4.4 的日志里 `arrow2` 是 §3 表里的值（例：左转 = `7`）、`distance5`/`distanceToNextGuidance9` 是非空整数米、`timeEstimated7` 是非负整数秒、`streetName3` 是转向后路名；
2. 车机仪表出现**箭头 + 路名 + 距离 + 倒计时**；
3. 结束导航后卡片消失（`turnState=3`）。

**数量级自检（把数值和仪表显示对上）**

| 我们送出的值 | 服务端转换（§1.3） | 仪表应显示 |
|---|---|---|
| `distanceToNextGuidancePoint = 744` | `revertDistance("744")` | **`744m`** |
| `updateDistanceToDestination = 41900` | `revertDistance("41900")` | **`41.9km`** |
| `updateETA = 600` | `secondToTime("600")` | **`10min`** |
| `updateETA = 60` | `(60+30)/60 = 1 ≤ 1` | **`<1min`** |
| `updateETA = 1791467456`（错误用法） | 会算成多天 | 乱值/不显示 ⇒ 说明 G3 没修 |
| `arrow2 = 7` | — | 左转箭头 |
| `arrow2 = -1` | `commonIcon(-1)` | 箭头隐藏 |

FAIL 但 `arrow2` 正确、距离/ETA 仍是 `0 m`/`<1min` ⇒ **G5 的推断成立**（缺口槽位由集群渲染占位默认值）。此时按"齐全才提交"处理，或做一次实验性尝试补 `distanceIcon4`/`timeIcon6`（值语义未知；`-1`=隐藏；且注意 §1.2：**这三个 short 根本过不了 binder**，所以大概率无意义）。

FAIL 且 §4.4 一行都没有 ⇒ 事务没到服务端 ⇒ 检查 `app_type` / descriptor / `asInterface` 链，考虑 G6 的直连方案。

### 6.2 未决项 U1–U6

| # | 未决 | 现有材料 | 能定它的证据 |
|---|---|---|---|
| **U1** | `0 m`/`<1min` 到底是**集群自己的占位默认值**，还是"集群保留了上一次的值"？ | 我们代码在 G1 下不可能发出这些值（【推断】成分最大的一处） | 修好 G1 后只推 `arrow2`+`streetName3`（**不推距离**），看仪表是否仍显示 `0 m`；更彻底是抓 `FrameBuilder → 车机` 的帧（需集群固件/HMI 侧日志，本仓没有） |
| **U2** | 集群是否要求 `navigationIcon1`/`distanceIcon4`/`timeIcon6` 才显示距离/时间槽位？ | 参考不设也工作 ⇒ 推断"不需要"；且 §1.2 已证这三个 short **不参与序列化** | 上车做一次"设 vs 不设"的对照（结论很可能是"无差异"） |
| **U3** | `nameOfSource8` 的用途（是否显示"Apple 地图"这类来源名） | 参考留 null 也能工作 ⇒ 不必要 | 在车上设一次字符串看仪表哪一处变化 |
| **U4** | DiServer 重启后我们缓存的代理行为（G7 的具体表现） | `DimInteractionImpl.onServiceDisconnected` 会把 DI 置 null，但**我们缓存的是包装对象** | 制造一次重启（`am force-stop` 或 `kill -9`），看是否 `DeadObjectException`、重启后是否自动恢复 |
| **U5** | **用户看到的是"车机真仪表"还是"中控卡片"？** | 两者是**不同通路**：车机真仪表只能由 DIM 模板驱动（§4）；中控卡片/桌面镜像是 iPhone 自己渲染的 stream 111（`CarPlayHostActivity.kt:832-844`、`airplay/CarPlayClusterDisplay.kt:19-29`、`AirPlaySession.kt:165-248`、`CarPlayController.kt:676-706`），代码注释本身写明本车"exposes no cluster projection display" | 让用户拍一张照片/说明位置。若看的是中控卡片 ⇒ G11 与 stream 111 那一路优先 |
| **U6** | `updateDriveDirectionImage(byte[])`（doc38 引 HiCar `ja.java`）属于哪套接口？ | **本车 `ecarx.adapterapi.jar` 的 `INaviInteraction` 里没有这个符号**（字符串扫描 0 命中），`updateDriveDirection(int)` 还是空方法 ⇒ **该线索是误导，应从"下一步配方"里删除** | 重新定位 HiCar 反射的目标类（可能在 `ecarx.cmaplatform.api.jar` 或另一套 API）；即使找到，也与"整数箭头 id + `turnByTurn` 已经能工作"这一事实不冲突 |

---

## 7. 明确**不应**照抄的部分

1. **`PathClassLoader("/system/framework/ecarx.adapterapi.jar")` 回退**（`LegacyLynkNavigationBridge.java:990-995`）：本车该 jar 就在 BOOTCLASSPATH，`Class.forName` 直接成功（doc37 实测 `ls /system/framework`，doc38 实测绑定成功）。留着只会掩盖"类不在"的诊断。（做其他机型可移植分支时再考虑。）
2. **10 秒后 `failed = true` 永久放弃**（`:533-552`）：我们"每次更新重试"更适合仪表（DiServer 会重启）。
3. **`notifyTurnByTurnStarted()` / `updateTurnByTurnArrow(Bitmap)` / `notifyDestinationReached(String, Bitmap)` / `updateDriveDirection(int)`**：前两个我们已经踩过（非法状态 / 空方法），后两个也是空方法。
4. **`LegacyModernEcarxObjects` 的 `DexClassLoader` + `assets/ecarx-modern/classes.dex`**（`LegacyModernEcarxObjects.java:140-204`）：那是**媒体 SDK 桥**，导航完全不需要（`Turn`/`INavigationDI` 都在 boot classpath）。
5. **`LegacyEcarxServiceBinding` 的媒体中心 action**（`ecarx.xsf.MEDIA_CENTER_SERVICE`）：doc37 实测本车是 `ecarx.xsf.MEDIA_CENTER_INTERNAL_API_SERVICE`；而且该类与导航无关（见 45 号 §8.2 的机型归属）。
6. **不要为了"显示 0 m 也能忍"而放弃完整性门禁**：参考宁可没卡也不显示 `0 m`；实测已证明集群的占位默认值会骗人。
7. **不要设三个 short 图标字段**（§1.2：它们过不了 binder，服务端只会拿到 0）。

---

## 8. 我们的端到端链路（file:line，便于定位）

```
iAP2 控制通道 0x5200 StartRouteGuidanceUpdates
  Iap2Messages.kt:287-291  (u16(0,42); void(1); void(2)  —— 与 EasyPlay 逐字节相同)
  → CarPlayController.kt:1148 (另 1245/1809) onIncoming = ::onRouteFrame
  → CarPlayController.kt:757-766 → CarPlayGlance.onFrame
  → CarPlayGlance.kt:44-55 → hud/RouteGuidanceState.accept
  → RouteGuidanceState.kt:102-157 (0x5201) / :159-174 (0x5202)
  → CarPlayGlance.kt:70-91 publishLocked()  —— ★在 synchronized(this) 内
  → CarPlayGlance.kt:89  VendorClusterBridge.pushGuidance(next)
  → VendorClusterBridge.kt:117-148 (started / arrow / road / 2×distance / ETA)
  → VendorClusterBridge.kt:242-256 call()  ← ★G1 的反射分发器在这里
  → DimInteraction.create → getNaviInteraction() → NaviInteractionImpl → INavigationDI.turnByTurn(Turn)
  → DiServer(pid 1109) → NavigationDimInteraction.turnByTurn → Log.d(6 值) → template 3 → 仪表
```

另一条**不是车机仪表**的路径（中控卡片 / 桌面镜像）：`CarPlayHostActivity.kt:832-844`（`clusterDisplayConfig()`，受 `AirPlayPersistence.loadClusterMapEnabled` 控制，默认 false，`:556-557`）→ `CarPlayClusterDisplay.config(...)` → `AirPlaySession.kt:165-248`（`showUI` + `forceKeyFrame`）→ `CarPlayController.kt:676-706` → `CenterMapOverlay.kt` 浮动卡。

---

## 9. 多机型归属（本车不存在分支问题）

**结论：本篇关于 `Turn` / `INavigationDI` / `NaviInteractionImpl` / `DiServer` 的全部结论都是"本车自带二进制"的结论，不需要做机型判断；`Turn` 与 `INavigationDI` 都在本车 boot classpath 上，不存在机型分支问题。**

依据：
1. 两个二进制都是**从本车拉下来的**：`_analysis/cs11_vendor/ecarx.adapterapi.jar`（= `/system/framework/ecarx.adapterapi.jar`，doc37 `ls /system/framework` 实测存在，含 `+odex`）与 `_analysis/cs11_vendor/XCDimInteractionService.apk`（= `/system/app/XCDimInteractionService.apk`，doc37 `dumpsys activity services` 显示 `com.ecarx.dim_interaction/.server.DiServer` 的 baseDir 就是它）。它们的 manifest 也自证是 API 19 目标（`minSdk=19/targetSdk=19`）。
2. **真机行为已经独立佐证过这条链路**（【确定-实测】doc38）：`DimInteraction.create(ctx).getNaviInteraction()` 拿到了对象、`updateNextGuidancePointName` 的字符串**真的出现在了仪表上** ⇒ 绑定 / `app_type=com.ecarx.di_server` / `IAdapterDI.getINavigationDI()` / `turnByTurn(Turn)` / Parcel 序列化 / 模板 3 渲染，这一整条在本车是**通的**。
3. 与 45 号 §8.3 的限定无关：45 号那条警告针对的是 **EasyPlay 的媒体通路（`LegacyEcarxMediaCenter` / `LegacyLynkMediaRefresh`）**——它依赖 `ecarx.openapi.impl.jar` / `MediaCenterAPI`。**导航侧不经过那条路**：`LegacyLynkNavigationBridge` 只依赖本车这两个二进制（`LegacySessionService.java:2872`，类内无 `isSupported`/无 SDK 门禁，45 号 §8.1 也判定为"ECARX 通用"）。
4. 唯一仍需真机确认的是"EasyPlay 的导航推送在**本车**确实显示过指向"（用户口述"EasyPlay works"），以及 U1/U2/U4。这些都不改变上面 1–3 的结论。

---

## 附录 A · 证据工件索引（`work/cs11_audit/`）与可复现命令

### A.1 工件清单（本次生成，**未进 repo**，可随时删）

| 文件 | 大小 | 内容 |
|---|---|---|
| `work/cs11_audit/adapterapi_dexdump.txt` | 5.4 MB / 81 867 行 | `ecarx.adapterapi.jar!classes.dex` 的 `dexdump -d` 全量反汇编（含静态字段值、方法体、指令地址） |
| `work/cs11_audit/adapterapi_strings.txt` | 141 KB | 上述 dex 的可打印字符串（用于快速判定"某个符号到底存不存在"，例如 `updateDriveDirectionImage` 0 命中） |
| `work/cs11_audit/Turn_section.txt` | 44 KB | 只含 `Turn` 类的段落（静态常量值、实例字段、方法、`writeToParcel`/`Turn(Parcel)`） |
| `work/cs11_audit/NaviInteractionImpl.txt` | 28 KB | 现代包装层逐方法反汇编（空方法一览） |
| `work/cs11_audit/DimInteractionImpl.txt` | 53 KB | `DimInteraction` 门面 / 绑定 / `getNaviInteraction` |
| `work/cs11_audit/dimsvc_dexdump.txt` | 1.7 MB / 24 994 行 | `XCDimInteractionService.apk!classes.dex` 的 `dexdump -d`（DiServer 服务端语义） |
| `work/cs11_audit/NavigationDimInteraction.txt` | 39 KB | `NavigationDimInteraction` + `$1`（`turnByTurn` 状态机与 2.5 s 线程） |
| `work/cs11_audit/ItemsBuilder.txt` | 15 KB | `ItemsBuilder.commonIcon/commonString/commonTime`（`isVisible` 语义） |
| `work/cs11_audit/TurnBuilder.txt` | 42 KB | `TurnBuilder.revertDistance/secondToTime` 与各 setter 的槽位号 |
| `work/cs11_audit/BoxCheck.java` | 1.7 KB | G1 的最小复现（装箱原始类型 ⇒ 反射找不到方法） |
| `work/cs11_audit/adapterapi.zip` / `dimsvc.zip` | — | 上面两个容器改名后的副本（`Expand-Archive` 用） |

> 45 号用的是自写 Python DEX 解析器（其附录 A，脚本落在 TEMP，**可能已被清理**）；本篇用的是 **Android SDK 自带的 `dexdump.exe`**，精度更高（能直接读出 `static final short` 的值与每条指令地址），建议以后优先用本篇的方式。

### A.2 工具路径

```text
dexdump : D:\Dev Environment\android-sdk\build-tools\35.0.0\dexdump.exe
aapt2   : D:\Dev Environment\android-sdk\build-tools\35.0.0\aapt2.exe
java    : D:\Dev Environment\Java\jdk-21.0.12+8\bin\java.exe      (java/javap/jar 同目录)
python  : D:\Dev Environment\Python\3.14.7\python.exe             (本次未用)
```

### A.3 可复现命令（本次实际敲过的）

**(1) 解包（jar/apk 都是 zip，先改名再 Expand-Archive）**

```powershell
$work = "D:\Development\DiAuto-DiPlay\work\cs11_audit"
New-Item -ItemType Directory -Force -Path $work | Out-Null
Copy-Item "D:\Development\DiAuto-DiPlay\_analysis\cs11_vendor\ecarx.adapterapi.jar"      "$work\adapterapi.zip" -Force
Copy-Item "D:\Development\DiAuto-DiPlay\_analysis\cs11_vendor\XCDimInteractionService.apk" "$work\dimsvc.zip"     -Force
Expand-Archive "$work\adapterapi.zip" -DestinationPath "$work\adapterapi" -Force
Expand-Archive "$work\dimsvc.zip"     -DestinationPath "$work\dimsvc"     -Force
# jar 内只有 classes.dex（无 .class）；apk 内 classes.dex + 资源
```

**(2) 反汇编（`javap` 对这种"只含 dex 的 jar"无效，必须用 dexdump）**

```powershell
$dex = "D:\Dev Environment\android-sdk\build-tools\35.0.0\dexdump.exe"
& $dex -d "$work\adapterapi\classes.dex" | Set-Content "$work\adapterapi_dexdump.txt" -Encoding UTF8
& $dex -d "$work\dimsvc\classes.dex"     | Set-Content "$work\dimsvc_dexdump.txt"     -Encoding UTF8
```

**(3) 切出某个类的段落（后面所有"读某类"的操作都靠这个）**

```powershell
$f = "$work\adapterapi_dexdump.txt"
$lines = Get-Content $f
$start = ($lines | Select-String -Pattern "Class descriptor  : 'Lcom/ecarx/dim_interaction/bussiness/bean/Turn;'" |
          Select-Object -First 1).LineNumber
$next  = ($lines | Select-String -Pattern "Class descriptor  :" |
          Where-Object { $_.LineNumber -gt $start } | Select-Object -First 1).LineNumber
$lines[($start-1)..($next-2)] | Set-Content "$work\Turn_section.txt" -Encoding UTF8
```

**(4) 一口气列出某类的静态常量 `name = value`（本次拿到 45 个箭头 id 的方式）**

```powershell
$lines = Get-Content "$work\Turn_section.txt"
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($lines[$i] -match "^\s+name\s+: '(.+)'") { $n = $Matches[1]
    for ($j = $i; $j -lt [Math]::Min($i+5, $lines.Count); $j++) {
      if ($lines[$j] -match "value\s+: (.+)") { "$n = $($Matches[1].Trim())" }
    }
  }
}
```

**(5) 字符串扫描（判定符号"存在/不存在"最快的手段）**

```powershell
$b = [System.IO.File]::ReadAllBytes("$work\adapterapi\classes.dex")
$s = [System.Text.Encoding]::Latin1.GetString($b)
([regex]::Matches($s, '[\x20-\x7e]{4,}') | ForEach-Object { $_.Value }) -join "`n" |
  Set-Content "$work\adapterapi_strings.txt" -Encoding UTF8
Select-String -Path "$work\adapterapi_strings.txt" -Pattern "ARROW_ICON|updateDriveDirectionImage"
```

**(6) Manifest 与资源（回答"要不要权限""单位字符串是什么"）**

```powershell
$aapt2 = "D:\Dev Environment\android-sdk\build-tools\35.0.0\aapt2.exe"
& $aapt2 dump xmltree --file AndroidManifest.xml "D:\Development\DiAuto-DiPlay\_analysis\cs11_vendor\XCDimInteractionService.apk"
& $aapt2 dump resources "D:\Development\DiAuto-DiPlay\_analysis\cs11_vendor\XCDimInteractionService.apk" |
  Select-String -Pattern "0x7f0500(1f|20|21|22|23|24)" -Context 0,2
# 期望：navi_unit_meter="m" / navi_unit_km="km" / navi_unit_minute="m" / navi_unit_minute1="min" / navi_unit_hour="h" / navi_unit_day="d"
```

**(7) G1 最小复现（证明 `int.class.isAssignableFrom(Integer.class) == false` 会让分发器找不到方法）**

```powershell
& "D:\Dev Environment\Java\jdk-21.0.12+8\bin\java.exe" "$work\BoxCheck.java"
# 输出：
#   int.isAssignableFrom(Integer) = false
#   long.isAssignableFrom(Long)   = false
#   updateETA(Integer 5)  -> null
#   updateDistanceToNextGuidancePoint(Integer) -> null
#   updateCurrentProgress(Long) -> null
#   updateNextGuidancePointName(String) -> public void BoxCheck$Vendor.updateNextGuidancePointName(java.lang.String)
#   notifyTurnByTurnStarted() -> public void BoxCheck$Vendor.notifyTurnByTurnStarted()
```

**(8) 上车取证（与 §6.1 同一组命令，单独列出便于复制）**

```powershell
adb shell setprop debug.diplay.cluster 1
adb shell setprop debug.diplay.trace 1
adb logcat -c
adb logcat -s DiPlay-Cluster:I DiPlay-Dim:I NavigationDimInteraction:D NavigationDimInteraction:V DiServer:I DimInteractionImpl:D
```

**(9) 顺便做一次"参考实现到底走没走这条路"的判定（沿用 45 号 §8.3 的建议）**

```powershell
adb shell ls /system/framework | Select-String "ecarx"
adb shell dumpsys activity services | Select-String "dim_interaction"
adb shell ps | Select-String "dim_interaction"
```

### A.4 下一步若还要解析别的接口

把 A.3(3) 的 `Class descriptor` 正则换成目标类即可（`dexdump` 全文已在 `adapterapi_dexdump.txt` 里，**不需要重新拉包**）；要解析 `ecarx.cmaplatform.api.jar` 就用 A.3(1)(2) 同样流程换输入文件。**建议优先用 `dexdump`（能出静态值与指令地址），不要再用自写 DEX 解析器。**

---

## 10. 箭头 id 家族更正：工厂导航的 101–143，而不是 EasyPlay 的 1–45

> 本节写在附录之后，是 **§2 / §3 的更正与补充**（两节本身没错，但那是**另一套家族**）。
> 起因：整数箭头修好之后，仪表上箭头真的出现了，但司机报"工厂导航的箭头是**白色、居中**，我们的是**黄色、偏左**"。
> 本篇据此反查车机上拉下来的工厂导航，结论如下。

### 10.1 结论：同一个 API 对象、同一组调用，唯一差别是 id 家族【确定-源码】

- 工厂导航包 `com.baidu.naviauto`（反编译源 `_analysis/cs11_vendor/naviauto_src/sources/`）。
  它推仪表的是 `com/baidu/a/a.java`（`NaviDashboardManager`，`a.java:3` 的类注释可证）：
  `DimInteraction.create(context)` 拿到 `DimInteraction`（`a.java:91`）、`getNaviInteraction()`（`a.java:110`）、
  箭头走 `k.updateTurnByTurnArrow(arrowId)`（`a.java:199-200, 208-209`）。
- 它调的**就是我们调的那八个方法**（`a.java:114-320`）：
  `updateTurnByTurnArrow` / `updateNextGuidancePointName` / `updateDistanceToNextGuidancePoint` /
  `updateDistanceToDestination` / `updateETA` / `updateDriveDirection` / `notifyTurnByTurnStarted` /
  `notifyTurnByTurnStopped`。
  **没有任何额外的样式 / 颜色 / 位置 / 对齐调用。**
- **唯一差别是 `arrowId` 取哪个家族**：工厂用 **101–143**（`a.java:30-87` 的静态
  `HashMap<String,Integer> h`），我们此前照抄 EasyPlay 的 **1–45**（§2 的 `ARROW_ICON_NAVI_*` + §3 的表）。
  仪表对两套家族**渲染不同**：EasyPlay 家族 = 黄色、偏左；工厂家族 = 白色、居中（车上实测）。

⇒ 老表的出处就是 EasyPlay：`_analysis/easyplay/v71/sources/com/shilapi/xcertplay/legacy/LegacyLynkNavigationBridge.java:786-829`
（`arrowFor$legacy`），§3 是对它的逐行照抄。**§3 的"Apple 语义"部分依然正确**（与 Apple 官方
`RouteGuidanceManeuverType` / `CPManeuverType` 逐个值对得上），错的只是"选哪套 id"。

### 10.2 工厂完整 id 表（`com/baidu/a/a.java:30-87`）【确定-源码】

| 语义（工厂 icon 名） | id |
|---|---|
| `turn_start`（路线起点 / 前往路线） | **101** |
| `turn_left`（含 `turn_left_2branch_left/right`、`turn_left_3branch_middle/right`） | **102** |
| `turn_right`（含 `turn_right_2branch_*`、`turn_right_3branch_*`） | **103** |
| `turn_left_front` / `turn_left_side` / `turn_left_side_ic` / `turn_branch_left` / `turn_lf_2branch_left` / `turn_left_3branch_left` / `turn_rf_2branch_left` | **104** |
| `turn_right_front` / `turn_right_side` / `turn_right_side_ic` / `turn_branch_right` / `turn_lf_2branch_right` / `turn_rf_2branch_right` | **105** |
| `turn_left_back` | **106** |
| `turn_right_back` | **107** |
| `turn_back` / `turn_back_2branch_left_base` / `turn_back_2branch_right_base` / `turn_back_3branch_left_base` / `turn_back_3branch_middle_base` / `turn_back_3branch_right_base` | **108** |
| `turn_front` / `turn_left_side_main` / `turn_right_side_main` / `turn_branch_center` / `turn_branch_left_straight` / `turn_branch_right_straight`（直行） | **109** |
| `turn_via_1..4`（途经点） | **110** |
| `turn_ring`（环岛） | **111** |
| `turn_ring_out` / `turn_ring_front`（出环岛） | **112** |
| `turn_tollgate` | **114** |
| `turn_dest`（到达） | **115** |
| `turn_along`（沿路继续） | **120** |
| `turn_ring_left` / `turn_ring_leftback` / `turn_ring_leftfront` | **141** |
| `turn_ring_right` / `turn_ring_rightback` / `turn_ring_rightfront` | **142** |
| `turn_ring_back`（`= BNOffScreenParams.ORIGINAL_BRIGHTNESS`，`a.java:86`） | **143** |

> 注意 `turn_ring_back` 在反编译源里写成常量名而不是字面量：`a.java:86` 是
> `h.put("turn_ring_back", Integer.valueOf(BNOffScreenParams.ORIGINAL_BRIGHTNESS))`，值即 **143**。

### 10.3 我们实现的 Apple → 工厂 id 表（`VendorClusterBridge.kt:1006-1032`）

| Apple `RouteGuidanceManeuverType` | 语义 | 工厂 id |
|---|---|---|
| 1, 20, 47 | LeftTurn / LeftTurnAtEnd / SharpLeftTurn | 102 |
| 2, 21, 48 | RightTurn / RightTurnAtEnd / SharpRightTurn | 103 |
| 3, 5 | StraightAhead / FollowRoad | 109 |
| 4, 18, 26（`drivingSide=0`） | UTurn / StartRouteWithUTurn / UTurnWhenPossible | 106 |
| 4, 18, 26（`drivingSide=1`） | 同上 | 107 |
| 4, 18, 26（其它 `drivingSide`） | 同上 | 108（无方向 U 形，**不再清卡**） |
| 6 | EnterRoundabout | 111 |
| 7, 28–46 | ExitRoundabout / RoundaboutExit1–19 | 112 |
| 19（`drivingSide=0` / `1` / 其它） | UTurnAtRoundabout | 141 / 142 / 143 |
| 10, 12, 24, 25, 27 | ArriveEndOfNavigation / ArriveAtDestination(+Left/Right) / ArriveEndOfDirections | 115 |
| 13, 22, 49, 52 | KeepLeft / HighwayOffRampLeft / SlightLeftTurn / ChangeHighwayLeft | 104 |
| 14, 23, 50, 53 | KeepRight / HighwayOffRampRight / SlightRightTurn / ChangeHighwayRight | 105 |
| 15, 16, 17 | Enter/Exit/ChangeFerry | 109（该家族**没有渡轮图标**） |
| 其余（8, 9, 11, 51, ≥54 …） | OffRamp / OnRamp / StartRoute / ChangeHighway … | **null ⇒ 清卡**（沿用老规则，不做直行猜测） |
| `proceedToRoute` | 前往路线起点 | **101**（`turn_start`），路名仍为 `前往路线` |

三处判断，写在这里以免以后被"改回去"：

1. **渡轮 → 109**：工厂家族没有渡轮 glyph。选直行而不是清卡，是因为渡轮航段本来就"继续向前"，
   清卡会把**仍然成立**的路名/距离/ETA 一起藏掉；直行箭头唯一可能错的是"往哪边转"，而渡轮不要求转。
2. **110 / 114 / 120 不可达**：`turn_via_*`（110）、`turn_tollgate`（114）、`turn_along`（120）是工厂自己的
   词汇，Apple 的 maneuver 枚举里**没有**对应值（`turn_along` 语义上更贴近 Apple 的 FollowRoad=5，
   但为了与老表逐行一一对应，5 仍给 109）。因此映射永远产生不了这三个 id ——
   要试它们只能用 §10.4 的 bench 覆盖（这也是那三个 id 的正确上车方式）。
3. **19（环岛掉头）→ 141/142/143**：这是本次唯一需要上车确认的取舍。Apple 没有"环岛左/右"这个类型，
   而工厂的 `turn_ring_leftback` / `turn_ring_rightback` / `turn_ring_back` 正好就是"在环岛掉头"
   （左回 / 右回 / 通用），所以按 `drivingSide` 走与 U 形相同的分支；`*_left/*_right` 的左右约定
   （`drivingSide=0` 取左、`1` 取右）与 106/107 保持一致。**若车上方向相反，用覆盖扫 141/142/143 定案。**

### 10.4 bench 覆盖：`debug.diplay.arrow`（`VendorClusterBridge.kt:18, 274-295, 662, 1043`）

- `adb shell setprop debug.diplay.arrow <id>`：**强制** `updateTurnByTurnArrow` 的实参，压过映射，
  **包括映射本来返回 null 的情况**（这正是扫"Apple 产生不了的 id"的前提）。
- 优先级：覆盖 > 映射；未设 / 空 / 全是空格 / 解析不出整数 ⇒ 回落映射（打错字不会把箭头弄没）。
  读法与其他 `debug.diplay.*` 一致：反射 `android.os.SystemProperties.get`（`VendorClusterBridge.kt:669-673`），
  **绝不直接引用隐藏 API**；每帧读一次，所以 `setprop` 对运行中的会话立即生效，重启/熄火后自动失效。
- 允许任意整数，包括 `-1`（`commonIcon(-1)` = 隐藏图标槽位）与越界值 —— 扫描的目的就是试映射不产生的值。
- 日志：`DiPlay-Cluster: cluster arrow id=<id> source=<debug.diplay.arrow|mapping>`，
  **每次变化一行**（不按帧刷；同一 id 同一来源只打一次），便于 `adb logcat` 逐条核对。

### 10.5 测试与复核（`shared/src/test/java/com/shilapi/xcertplay/media/ClusterGuidanceCardTest.kt`）

- 表驱动：`everyMappedManeuverTypeBecomesItsFactoryArrowId`（全部语义组，含渡轮）
- `theUTurnTypesFollowTheDrivingSide`（106/107/108）、`theRoundaboutUTurnFollowsTheDrivingSide`（141/142/143）
- `anUnknownManeuverTypeClearsTheCardInsteadOfDrawingStraightAhead`（8/9/11/51/54/100 → 清卡）
- `theIdsWithNoAppleManeuverAreUnreachableAndOnlyTheOverrideReachesThem`（0–255 全扫，断言 110/114/120
  永不出现、输出集合恰为 14 个 id；再用覆盖分别强制 110/114/120/-1）
- `proceedingToTheRouteStartsTheRouteAndNamesTheRouteStart`（101 + `前往路线`）
- `theBenchOverrideForcesTheArrowIdAndItsAbsenceLeavesTheMappingInCharge`（设 / 未设 / 空 / 不可解析 / 完备性门禁）
- `theArrowIsAnnouncedOncePerChangeAndNotOncePerFrame`（`ClusterArrowChangeGate`）
- 另有 `CarPlayGlanceClusterPushTest` 里右转期望值 `6 → 103` 的同步更新。
- **全部为纯 JVM 测试**（映射、卡片门禁、覆盖解析、变更门禁都是纯函数），**没有 Robolectric 用例**。
- 反向复核：把 `clusterArrowId` 临时恢复成老的 1–45 表（`proceedToRoute → -1`），
  该测试类 17 个用例中 **8 个失败**（即全部语义用例：表驱动、两个 U 形、环岛、101、不可达集合、
  覆盖、两个提交流水用例），恢复后 17/17 通过。
- 全量：`:shared` **830 用例 / 3 失败**、`:common` **581 / 6**，失败集合与改动前完全一致
  （`HotspotJoinNormalizedTransactionTest`；`CarPlayControllerUsbServiceTest` + `UsbPermissionSetupTest`），
  即本次改动没有新增失败。

### 10.6 上车复验（司机看什么）

```powershell
adb shell setprop debug.diplay.cluster 1
adb shell setprop debug.diplay.trace 1
adb logcat -c
adb logcat -s DiPlay-Cluster:I NavigationDimInteraction:D NavigationDimInteraction:V
# 用 Apple 地图开始一段带转弯的导航
```

1. **颜色/位置**：仪表箭头应当是**白色、居中**（与工厂导航一致），不再是黄色偏左。
2. **日志**：`DiPlay-Cluster: cluster arrow id=... source=mapping`，且 `NavigationDimInteraction` 那行 6 值日志里
   `arrow2` 等于 §10.3 表里的值（左转 102、右转 103、直行 109 …）。
3. **覆盖扫描**（不改代码、不重编）：
   ```powershell
   adb shell setprop debug.diplay.arrow 102   # 强制左转；看仪表是否画出"左转"且白/居中
   adb shell setprop debug.diplay.arrow 104   # 左侧/匝道族
   adb shell setprop debug.diplay.arrow 110   # 途经点（映射不可达）
   adb shell setprop debug.diplay.arrow 114   # 收费站（映射不可达）
   adb shell setprop debug.diplay.arrow 120   # 沿路继续（映射不可达）
   adb shell setprop debug.diplay.arrow 141   # 环岛左回
   adb shell setprop debug.diplay.arrow 142   # 环岛右回
   adb shell setprop debug.diplay.arrow 143   # 环岛掉头基型
   adb shell setprop debug.diplay.arrow -1    # 隐藏图标槽位（对照）
   adb shell setprop debug.diplay.arrow ""    # 清覆盖，回到映射（若 shell 把空参丢了，用下面的 "-" 等价写法）
   adb shell setprop debug.diplay.arrow -     # 不可解析 ⇒ 同样回到映射（同一规则的兜底写法）
   ```
   每换一个值，logcat 里应出现一行新的 `cluster arrow id=<id> source=debug.diplay.arrow`；
   同一 id 不会重复刷屏。**重点核对 141 / 142 的左右是否与 §10.3 的判断一致**（见 10.3 第 3 条）。
4. 扫完务必清掉覆盖（`setprop ... ""` 或 `-`，或熄火 —— `setprop` 不持久，下一个点火即失效），
   否则下一个驾驶循环仍被强制成扫描用的 id。

