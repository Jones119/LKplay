# 45 · CS11 仪表 DIM 接口符号表 与 媒体（now-playing/封面）差距清单

> 产出范围：**只读审计**。未改动 `CarPlay-CS11` 任何生产代码、未提交任何东西（工作树保持 clean）。
> 数据来源：
> - 我们的实现 `D:\Development\DiAuto-DiPlay\CarPlay-CS11`（分支 `cs11-api19`）
> - 厂商二进制 `_analysis/cs11_vendor/ecarx.adapterapi.jar`（内含 `classes.dex`，156 KB，417 个类）
> - 参考实现 EasyPlay v71 反编译源 `_analysis/easyplay/v71/sources`（Java）与其资源 `_analysis/easyplay/v71/resources/AndroidManifest.xml`
> - 我们自己的真机结论 `docs/_digest/38-仪表投屏实测与下一步.md`
>
> 证据强度约定（全文沿用）：
> - **【确定-源码】**：直接引自 `file:line`，或 Java/DEX 语义必然成立
> - **【确定-实测】**：来自 doc38/doc37 的真机日志或 `pm list packages`/`dumpsys` 输出
> - **【推断】**：由间接证据得出，条目内注明"能定它的证据"
> - **【未决】**：现有材料无法判定

---

## 0. 必须先用一句话记住的两个坑

1. **我们的数值型 DIM setter 全部没有发出去**（自己的反射分发器把 `Integer` 当成 `int` 的不可赋值类型），但日志会打印 `-> null`，看起来像"调用成功"。这精确解释了 doc38 §2「路名 ✅、箭头/距离/ETA ✗」。→ 见 **G1**。
2. **EasyPlay 的仪表媒体通路是"机型硬编码门禁 + 平台能力探测"两层**，不能假定"源码里有 = 本车会跑"。`LegacyLynkMediaRefresh`（DiServer + `IMediaDI` + `Media` bean）由 `usesSinglePassArtwork()` 判定，字面量就是 `sdk==19 && Freescale && CS11`；但它被构造的位置又在"必须先 `loadApi()` 成功"之后。→ 见 **第 8 节**。

---

## 1. 厂商接口符号表（本节最有价值，后续不必再猜方法名）

### 1.1 解析方法（可复现）

`_analysis/cs11_vendor/ecarx.adapterapi.jar` 不是普通 jar，里面只有一个 `classes.dex`：

```
$ unzip -l ecarx.adapterapi.jar
  META-INF/MANIFEST.MF
  classes.dex        (448152 bytes)
```

因此 `javap` 无效。我用**自写的纯 Python DEX 解析器**读取 `string_ids / type_ids / proto_ids / field_ids / method_ids / class_defs`，逐个还原类的方法名与 `proto` 描述符、字段名与类型。脚本全文见 **附录 A**（同时给出"字段静态值"解析的已知坑）。

脚本落盘位置（**注意在 TEMP，可能被清理**）：
- `C:\Users\gaoji\AppData\Local\Temp\dexsurface\dex_surface.py`（类/方法/字段名与类型）
- `C:\Users\gaoji\AppData\Local\Temp\dexsurface\dex_fields.py`（追加静态字段值解析）
- 复现命令：
  ```powershell
  $py='C:\Users\gaoji\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
  & $py <脚本> 'D:\Development\DiAuto-DiPlay\_analysis\cs11_vendor\ecarx.adapterapi.jar' 'diminteraction/(DimInteraction|IMediaInteraction|MediaInteractionImpl|INaviInteraction|NaviInteractionImpl)\b'
  ```
- 以后解析 `INavigationDI`（`turnByTurn(Turn)` 整体对象通路）只需把正则换成 `bean/(INavigationDI|Turn|IAdapterDI);`，**无需任何新工具**。

### 1.2 `DimInteraction` 门面【确定-源码】

`Lcom/ecarx/xui/adaptapi/diminteraction/DimInteraction; extends Lcom/ecarx/xui/adaptapi/AdaptAPI;`

```
DimInteraction create(android.content.Context)          <- 静态工厂
IContactsInteraction getContactsInteraction()
IMediaInteraction     getMediaInteraction()
INaviInteraction      getNaviInteraction()
IPhoneCallInteraction getPhoneCallInteraction()
IVrInteraction        getVrInteraction()
```

`DimInteractionImpl extends DimInteraction` 另有 `bindService(String)` / `init()`（即绑定是异步的）。

### 1.3 `IMediaInteraction` 全部方法【确定-源码】

```
void setMediaInteractionCallback(IMediaInteraction$IMediaInteractionCallback)
void updatePlaybackInfo(IMediaInteraction$IPlaybackInfo)     <- 我们用的就是这条
void updateCurrentProgress(long)
void updateCurrentSourceType(int)
void updateMediaSourceTypeList(int[])                        <- 我们从不调用
void updatePlaylist(int, java.util.List)
```

对应实现类 `MediaInteractionImpl`（构造签名 `(IMediaDI)`）方法集与接口一致。

### 1.4 `IMediaInteraction$IPlaybackInfo` 的 18 个 getter【确定-源码】

我们目前只实现其中 7 个（见 §3），**其余返回默认值**（`VendorClusterBridge.PlaybackInfoHandler` 的 `else -> defaultValue(...)`）。

| # | getter | 返回类型 | 我们是否填 |
|---|---|---|---|
| 1 | `getTitle` | `String` | ✅ |
| 2 | `getArtist` | `String` | ✅ |
| 3 | `getAlbum` | `String` | ✅ |
| 4 | `getArtwork` | `Uri` | ✅（`file://`，见 G5） |
| 5 | `getDuration` | `long` | ✅ |
| 6 | `getPlaybackStatus` | `int` | ✅ |
| 7 | `getSourceType` | `int` | ✅（6） |
| 8 | `getPlayingItemPositionInQueue` | `int` | ✅（0） |
| 9 | `getLoopMode` | `int` | ✅（0） |
| 10 | `getMediaPath` | `Uri` | ✗ null |
| 11 | `getNextArtwork` | `Uri` | ✗ null |
| 12 | `getPreviousArtwork` | `Uri` | ✗ null |
| 13 | `getLyric` | `Uri` | ✗ null |
| 14 | `getLyricContent` | `String` | ✗ null |
| 15 | `getCurrentLyricSentence` | `String` | ✗ null |
| 16 | `getRadioFrequency` | `String` | ✗ null |
| 17 | `getRadioMode` | `int` | ✗ 0 |
| 18 | `getRadioStationName` | `String` | ✗ null |

> 参考实现同样只提供一个封面 Uri（`LegacyEcarxMediaCenter$Snapshot.artworkUri`），所以 10–18 返回默认值是**可接受**的；但 11/12（前后曲封面）值得注意——车机可能用它做"左右滑动换曲"的预览。

### 1.5 `IMediaDI`（bean 层 AIDL）【确定-源码】

包名 `com.ecarx.dim_interaction.bussiness.bean`。

```
String[] getSupportSourceType()
void registerListener(IMediaDiListener)
void updateCurrentProgress(long)
void updateCurrentSourceType(int)
void updateMediaInfo(com.ecarx.dim_interaction.bussiness.bean.Media)
void updateMediaSourceList(int[])
void updatePlayStatus(com.ecarx.dim_interaction.bussiness.bean.Media)
```

### 1.6 `IAdapterDI`【确定-源码】

```
IMediaDI     getIMediaDI()
INavigationDI getINavigationDI()
IPhoneDI     getIPhoneDI()
```

EasyPlay 用它做"服务返回的 binder 到底是哪一层"的分派（见 §2.1）。

### 1.7 `Media` bean 的实例字段与类型【确定-源码】

**这是最有价值的一张表**：字段名带编号后缀（`songTitle10`/`artistName11`/`albumName9`/`albumCover2`/`sourceIcon17`），我们之前只能从 doc38 的厂商日志里反推，现在有精确类型。

| 字段 | 类型 | 含义（推断） | 我们的对应值 |
|---|---|---|---|
| `baseMediaType` | `int` | 音源类型（`MEDIA_TYPE_ONLINE`） | 6 |
| `basePlayState` | `int` | `MEDIA_PLAY_STATE_PLAY`/`_PAUSE` | playing?1:0 |
| `baseCurrentTime` | `long` | 当前进度（ms） | `elapsedMillis` |
| `baseTotalTime` | `long` | 总时长（ms） | `durationMillis` |
| `albumCover2` | `Uri` | 封面 | `file://…/cluster-artwork-*.png` |
| `songTitle10` | `String` | 曲名 | `title` |
| `artistName11` | `String` | 歌手 | `artist` |
| `albumName9` | `String` | 专辑 | `album` |
| `currentSource8` | `String` | 当前音源显示名 | ✗ 未设 |
| `playIcon1` | `int` | 播放图标 | ✗ 未设 |
| `sourceIcon17` | `int` | 音源图标 | 6（走 EasyPlay 通路时） |
| `templateName18` | `String` | 模板名 | ✗ 未设 |

类常量（**名称确定，数值未可靠解出**，见附录 A 的已知坑）：`MEDIA_PLAY_STATE_PAUSE/_PLAY`、`MEDIA_TYPE_AM/AUX/BT/FM/LOCAL/ONLINE/PODCASTS/USB/USB2`。数值层面有 doc38 的厂商日志与 EasyPlay 源码两处独立佐证：`baseMediaType=6`（在线）、`basePlayState=1`（播放）。

### 1.8 诊断串清单（**为什么重要**）【确定-源码】

DEX 字符串表内存在以下厂商日志：

```
getMediaInteraction()  mMediaDI is null: 
getNaviInteraction()   mNavigationDI is null: 
updatePlaybackInfo()   mMediaDI is null: 
updateCurrentSourceType() mMediaDI is null: 
updateCurrentProgress()   mMediaDI is null: 
updateMediaSourceTypeList() mMediaDI is null: 
updatePlaylist()       mMediaDI is null: 
setMediaInteractionCallback() mMediaDI is null: 
```

**关键含义**：`MediaInteractionImpl` 的构造签名是 `(IMediaDI)`，内部 DI 由构造参数注入。因此：

- `getMediaInteraction()` 完全可能返回一个**非 null 但内部 DI 为 null** 的交互对象；此时所有 `update*` 调用**不抛异常、静默 no-op**，只在厂商自己的 logcat 里打一行 `xxx() mMediaDI is null`。
- 对我们的直接后果：`VendorClusterBridge` 一旦把这种"空壳"缓存下来（`VendorClusterBridge.kt:163-179` 的 `media?.let { return it }`），此后**永久失效且我们这边无任何异常/日志**。→ **G4**
- 它同时给了我们一个**免费的验收探针**：上机时只要厂商 logcat 出现 `mMediaDI is null` / `mNavigationDI is null`，就能确认"我们发出的调用没被消费"。→ 第 5 节判据 2。

### 1.9 `INaviInteraction`（附，供以后解析）【确定-源码】

```
void notifyTurnByTurnStarted()
void notifyTurnByTurnStopped()
void updateTurnByTurnArrow(int)            <- 我们回退分支用（被 G1 丢弃）
void updateTurnByTurnArrow(android.graphics.Bitmap)   <- 我们主用（不受 G1 影响）
void notifyDestinationReached(String, int)
void notifyDestinationReached(String, android.graphics.Bitmap)
void updateNextGuidancePointName(String)   <- doc38 实测唯一生效的字段
void updateDistanceToNextGuidancePoint(int)
void updateDistanceToDestination(int)
void updateETA(int)
void updateDriveDirection(int)
```

DEX 字符串表中**没有** `updateTurnByTurnArrow() mNavigationDI is null` 这类串，但有 `updatePlaybackInfo() mMediaDI is null` 等——两组诊断串的命名风格一致，故 navi 侧同样会静默 no-op（**推断**）。

---

## 2. EasyPlay 的两条通路与字段表

### 2.1 通路 A：`LegacyLynkMediaRefresh`（DiServer + `IMediaDI` + `Media` bean）

**这是我们真正的对标对象**（同一条仪表 DIM 媒体通路）。

- 常量 `BEANS = "com.ecarx.dim_interaction.bussiness.bean"` — `LegacyLynkMediaRefresh.java:29`
- **设备门禁** `isSupported` — `:544-548`：
  ```java
  return sdk == 19 && StringsKt.equals(manufacturer, "Freescale", true)
                    && StringsKt.equals(model, "CS11", true);
  ```
  不满足时 `backend == null`（`:78`），整条通路关闭。
- 类解析与 ClassLoader — `:370-379`：先 `Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction", true, app.getClassLoader())`；`ClassNotFoundException` 时回退 `new PathClassLoader("/system/framework/ecarx.adapterapi.jar", app.getClassLoader())`；**随后用该类的 ClassLoader 解析 bean**，保证 `Media`/`IMediaDI` 与接口同源。
- 绑定 — `:386-391`：
  ```java
  new Intent()
    .setComponent(new ComponentName("com.ecarx.dim_interaction",
                                    "com.ecarx.dim_interaction.server.DiServer"))
    .setAction(app.getPackageName() + ".DIM_MEDIA_REFRESH")
    .putExtra("app_type", "com.ecarx.di_server")
  bindService(intent, connection, 1);   // 1 = BIND_AUTO_CREATE
  // 返回 false -> throw new IllegalStateException("Media instrument service unavailable")
  ```
- 接口获取（按 `IBinder.getInterfaceDescriptor()` 分派）— `:426-441`：
  - `"…bean.IMediaDI"` → `IMediaDI$Stub.asInterface(binder)`
  - `"…bean.IAdapterDI"` → `IAdapterDI$Stub.asInterface(binder).getIMediaDI()`
  - 通用形式 `:506-508`：`Class.forName(BEANS+"."+name+"$Stub").getMethod("asInterface", IBinder.class)`
  - 校验 `instanceof IInterface` 且 `asBinder().isBinderAlive()` — `:442-451`
- 关闭 `unbindService` — `:510-531`
- 诊断串 `lynk media refresh stage=<stage> exception=<X>` — `:217`

#### `MediaSender.publish` 逐字段表【确定-源码】`LegacyLynkMediaRefresh.java:241-285`

| 行 | setter | 参数类型 | 值 |
|---|---|---|---|
| `:246` | `Media()` 无参构造 | — | 每次新建 |
| `:250` | `setBaseMediaType` | `int` | `6` |
| `:253` | `setBasePlayState` | `int` | `playing ? 1 : 0` |
| `:259` | `setBaseCurrentTime` | `long` | `positionMs`（非 null 才设） |
| `:266` | `setBaseTotalTime` | `long` | `durationMs`（非 null 才设） |
| `:268` | `setAlbumCover2` | `Uri` | `snapshot.getArtworkUri()`（**总是设，可为 null**） |
| `:273` | `setSongTitle10` | `String` | `title ?: ""` |
| `:278` | `setArtistName11` | `String` | `artist ?: ""` |
| `:280` | `setAlbumName9` | `String` | `album ?: ""` |
| `:283` | `setSourceIcon17` | `int` | `6` |
| `:284` | `IMediaDI.updateMediaInfo(Media)` | — | 一次性提交 |

> 与 §1.7 的 DEX 字段表**逐字对应**，且与 doc38 的厂商日志 `updateMediaInfo ==== baseMediaType = 6 basePlayState = 1 baseTotalTime = 262757 albumCover2 = … songTitle10 = … artistName11 = … albumName9 = …` 对得上。**结论：厂商侧把 `IPlaybackInfo` 映射成同一个 `Media` bean 并打同一条日志**，所以"字段清单"对两条通路都是权威的。

### 2.2 通路 B：`LegacyEcarxMediaCenter`（车机原生媒体中心）

- 绑定常量 `LegacyEcarxServiceBinding.java:23,24,34`：`ACTION="ecarx.xsf.MEDIA_CENTER_SERVICE"`、`DESCRIPTOR="ecarx.xsf.mediacenter.IMediaCenterSvc"`、`COMPONENT=ecarx.xsf.mediacenter/.MediaCenterService`
- 关键类：`IMediaCenterSvc$Stub`（`LegacyEcarxMediaCenter.java:1880`）、`IMusicPlaybackInfo`（`:1371`）、`IMediaCenterClientToken`（`:1370`）、`IMusicClient`（`:1671`）、`IMusicPlaybackInfo`/`IMusicClient` 的现代版 `MusicPlaybackInfo`/`MusicClient`（`:1366-1367`，仅 GKUI）
- 注册回调 map：`info` / `progress` / `next` / `previous` / `play` / `pause` / `focusLost` — `:1628-1665`
- **注意**：`LegacyEcarxMediaCenter` 内部**同时**持有通路 A（字段 `instrumentRefresh`，`:1305`），并在 `:1819-1821` 的 `publish` 里转发 `legacyLynkMediaRefresh.publish(snapshot)`。
- doc37 实测修正：本车真实 action 是 **`ecarx.xsf.MEDIA_CENTER_INTERNAL_API_SERVICE`**，与上面的常量不同（`docs/_digest/37-EasyPlay对标分析.md:30-32`）。

### 2.3 生命周期与"重推/保活"

- `LegacyLynkMediaRefresh` **不是定时器**：`connect()` 只做一次（`:95-113`），之后每次变更 `publish()`（`:130-164`）。名字里的 "refresh" 指的是"刷新仪表上的媒体展示"，不是周期性心跳。
  `publish` 的拒绝条件：`closed || !started || failed || backend==null || playing==null`，以及 `duration < 0` / `position < 0` — `:134-150`。
- **真正的心跳在 `LegacyEcarxMediaCenter`**：
  - `PRESENTATION_REFRESH_TIMEOUT_MS = 10000` — `:65`
  - `PresentationRefresh(target, gate, waitingSince)` — `:471-537`
  - `refreshPresentation(...)`：成功 → 诊断 `presentation_refreshed`；超时 → `presentation_refresh_timeout` — `:1011-1055`
  - 触发条件 — `:924-990`：`supportsPresentationRefresh() && sourceClaimed && lastPublished != null && repaint != null && repaint.getTarget().samePresentation(latest) && playing == true`
  - `update$legacy(..., refreshPresentation)`：`refreshPresentation && !suspended` 就挂新请求，否则若目标不再匹配则清空 — `:779-789`
- `EXPLICIT_CLAIM_INTERVAL_MS = 2000` — `:63`；claim 状态机 `:823-843`（`Claim.PLAYBACK && sourceClaimed && !publishDenied` 直接 return）；`focusLost` 回调重新注册 — `:1662-1665`
- 参数规范化：title/artist/album **截断 512 字符**（`:755-757`）；负 duration/position → null（`:759-776`）
- 会话驱动（`LegacySessionService`）：`mediaCenter.onMediaPlaybackStarted()` / `setPlaybackSuspended(...)` 出现在 `:1795-1799, 2132-2134, 2192-2224, 2836-2867, 2917-2919`；仪表推送入口 `:3166-3222`（`pendingInstrumentRefresh` 去重、`:3190` 调 `update$legacy(...)`、`:3222` `cancelPresentationRefresh$legacy()`）。

### 2.4 封面：EasyPlay 在 CS11 上用的是**本地 HTTP 图床**

这条是本次审计**最反直觉**的发现，且做了自我修正（早期报告中我把它当成"重一点的兜底"，实际它在 CS11 上是**启用**的一等路径）。

- 上限常量 `LegacyArtworkLoader.java:38-42`：`MAX_CACHED_IMAGES=4`、`MAX_EDGE=256`、`MAX_ENCODED_BYTES=1048576`、`MAX_SOURCE_EDGE=8192`、`MAX_SOURCE_PIXELS=16777216`
- 目录 `<cacheDir>/easyplay-covers/<session UUID>/` — `:79-80`（session UUID 每实例一个，`:76-78`）
- 拒绝条件：编码字节不在 `[1, 1048576)`（`:239-241`）；`w/h ≥ 8193` 或 `w*h > 16M`（`:248-252`）
- 缩放 `ratio = min(1f, 256f/max(w,h))`（`:272`）；`compress(JPEG, 88)`（`:289`, `:349`）；文件名 `<UUID>.jpg`（`:286`, `:344`）
- 中间 Uri：`content://<pkg>.artwork/covers/<session>/<uuid>.jpg` — `:299`, `:359`
- **显式授权**：对 `OEM_READERS = {"ecarx.xsf.mediacenter", "com.ecarx.dim_interaction"}`（`:57`）逐个 `grantUriPermission(reader, uri, 1)`（flag 1 = `FLAG_GRANT_READ_URI_PERMISSION`），并打 `Artwork uri grants=N` — `:301-313`, `:360-383`
- Provider 侧：`LegacyArtworkProvider.java:107-131` 校验并映射回 `cacheDir/easyplay-covers/...`；`openFile` 只允许 mode `"r"`，`ParcelFileDescriptor.open(file, 0x10000000)` — `:46-55`；`getType = image/jpeg` — `:58-62`；manifest `<provider … android:authorities="com.shihab.diplay.legacy.artwork" android:grantUriPermissions="true"/>` — `_analysis/easyplay/v71/resources/AndroidManifest.xml:63-67`（无自定义权限、无 `<queries>`）
- 淘汰时 `revokeUriPermission` + 删文件 — `:315-319`, `:427-437`

**CS11 专属的最后一步转换**【确定-源码】：

```java
// LegacyEcarxMediaCenter.java:1924  —— 代理 getArtwork() 的返回值
return this.artworkTransport.uriFor(info.getArtworkUri());
```

```java
// LegacyEcarxMediaCenter.java:1223-1257  ArtworkUriTransport.uriFor(Uri source)
if (this.enabled) {                                    // enabled == usesSinglePassArtwork(...) == CS11
    File f = LegacyArtworkProvider.INSTANCE.coverFile$legacy(this.context, source);
    if (this.server == null) this.server = createServer.invoke();   // new LegacyArtworkHttpServer()
    uriUriFor = this.server.uriFor(f);                  // -> http://127.0.0.1:<port>/<path>
} else {
    uriUriFor = source;                                 // 其它机型：直接透传 content://
}
```

```java
// LegacyArtworkHttpServer.java:152-170, 198
serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);   // 回环 + 临时端口 + backlog 4
uri = Uri.parse("http://127.0.0.1:" + serverSocket.getLocalPort() + cover.getPath());
```

⇒ **CS11 上交给 DIM 服务的封面 Uri 是 `http://127.0.0.1:<临时端口>/…`**，由 EasyPlay 自己的单线程 HTTP 服务按需应答一次（"single pass"）。这解释了为什么这个开关叫 `usesSinglePassArtwork` 且只在 CS11 打开：**车机的取图方对 `content://`/`file://` 只会取一次或干脆不取，必须给一个"可主动 GET 的 URL"**。

> ⚠️ 对我们的含义：G5 的严重性**上调**。我们用的是 `file://`；EasyPlay 在 CS11 上用的是"自有 HTTP 服务 + 回环 URL"，并且**另外还**有 content provider + 显式 grant 作为原料层。也就是说本车的封面通路至少有三级台阶，我们一级都没上。

### 2.5 封面跟对歌：`LegacyVehicleArtwork.associate()` 状态机【确定-源码】

`LegacyVehicleArtwork.java:249-334`，纯逻辑、无任何机型门禁：

- 身份 `Identity(title, artist, album)`，空白归一为 `null` — `:256`
- `retainedCover` **只在身份变化 / 无 title / 暂停时清空** — `:257-265`
- 新歌**没有**新图（`artwork == null`）⇒ 把留存封面**代回当前曲目**再返回 — `:266-277`
- 同一张图 ⇒ 若身份"补全了缺字段"则更新 owner — `:278-291`
- 新图到达但标题/歌手/专辑是分批到达 ⇒ `inheritedArtist`/`inheritedAlbum` + `pendingTitle` — `:292-318`, `:327-332`
- `canRetain` 要求 title 非空且 `playbackStatus != 0` — `:257`

这是"封面只有第一首/换歌回退默认图"的正解。

---

## 3. 我们的端到端链路与实际 DIM 调用面

### 3.1 数据进入 → 变换 → 交给仪表

```
iAP2 0x5001 NowPlayingUpdate
  └─ CarPlayPlaybackStatus.acceptUpdate()          shared/.../media/CarPlayPlaybackStatus.kt:31-52
       ├─ group0: title(1)/duration(4)/album(6)/artist(12)/artworkTransferId(26)   :101-105
       ├─ group1: status(0)/elapsed(1)/sourceApp(7)                                :107-109
       └─ VendorClusterBridge.pushMedia(next)                                      :50
iAP2 session-12 文件传输（封面字节）
  └─ Iap2FileTransferReceiver.accept()             shared/.../transport/Iap2FileTransferReceiver.kt:55-129
       上限 DEFAULT_MAXIMUM_ARTWORK_BYTES = 2 MiB                                  :171
       └─ CarPlayController → artworkListener(id, bytes)   orchestration/CarPlayController.kt:770
            └─ CarPlayMediaKeys.onArtworkChanged()   common/.../CarPlayMediaKeys.kt:131-135
                 └─ NowPlayingArtworkQueue.submit()  common/.../NowPlayingArtworkQueue.kt:43-55
                      └─ decodeArtwork() ≤384px      CarPlayMediaKeys.kt:377-401（:403 MAX_ARTWORK_DIMENSION）
                           └─ onArtworkDecoded()  **仅当 nowPlaying.artworkTransferId == id**  :138-158
                                ├─ VendorClusterBridge.setArtwork(shownArtworkLocked())  :156
                                └─ shownArtworkLocked() = artwork ?: 占位图               :360-361
仪表推送
  └─ VendorClusterBridge.pushMedia()               shared/.../media/VendorClusterBridge.kt:67-77
       ├─ enabled（debug.diplay.cluster，默认 false）                               :65, :31
       ├─ mediaInteraction(ctx) → 缓存 DimInteraction 派生对象                      :163-192
       ├─ playbackInfo() = Proxy.newProxyInstance(IMediaInteraction$IPlaybackInfo)  :195-205
       └─ call("updatePlaybackInfo", proxy) / ("updateCurrentSourceType", 6)
          / ("updateCurrentProgress", elapsed)                                      :74-76, :242-256
  └─ VendorClusterBridge.setArtwork(bitmap)        :86-111
       ├─ getExternalFilesDir(null)/cluster-artwork-<ts>.png                        :92-93
       ├─ compress(PNG, 100) + setReadable(true,false) + 删旧文件                     :94-100
       └─ Uri.fromFile(file)  → getArtwork()                                        :101, :218
导航推送
  └─ CarPlayGlance.publishLocked() → pushGuidance()  shared/.../glance/CarPlayGlance.kt:89
       └─ VendorClusterBridge.pushGuidance()        :117-148
attach 时机（**只在 CarPlayHostActivity 存活期间**）
  └─ CarPlayHostActivity.onStart()  VendorClusterBridge.attach(this) + arrowRenderer   common/.../CarPlayHostActivity.kt:733-738
  └─ CarPlayMediaKeys.attach()（注册 nowPlaying/artwork 监听）只在   CarPlayHostActivity.kt:3657
```

### 3.2 我们实际发出的 DIM 调用面

| 调用 | 位置 | 参数类型 | **是否真的发出** |
|---|---|---|---|
| `updatePlaybackInfo(IPlaybackInfo)` | `:74` | 接口代理 | ✅ |
| `updateCurrentSourceType(int)` | `:75` | `Int` | ❌ **被 G1 丢弃** |
| `updateCurrentProgress(long)` | `:76` | `Long` | ❌ **被 G1 丢弃** |
| `notifyTurnByTurnStarted/Stopped()` | `:128,155` | 无参 | ✅ |
| `updateTurnByTurnArrow(Bitmap)` | `:135` | `Bitmap` | ✅ |
| `updateTurnByTurnArrow(int)` | `:137`（回退） | `Int` | ❌ **被 G1 丢弃** |
| `updateNextGuidancePointName(String)` | `:139` | `String` | ✅ |
| `updateDistanceToNextGuidancePoint(int)` | `:141` | `Int` | ❌ **被 G1 丢弃** |
| `updateDistanceToDestination(int)` | `:143` | `Int` | ❌ **被 G1 丢弃** |
| `updateETA(int)` | `:147` | `Int` | ❌ **被 G1 丢弃** |

**与 doc38 §2 的对应关系**：doc38 记录「路名 ✅ 能显示；箭头/两个距离/ETA ✗ 全部无效，而我们已确认调用发出 ✓」。上表给出解释——`String` 能匹配，`int/long` 匹配不到但日志打 `-> null`（void 方法成功时也是 `null`），所以"看起来发出去了"。

### 3.3 驱动时机

- `pushMedia` 只在 ① iAP2 `NowPlayingUpdate`（`CarPlayPlaybackStatus.kt:50`，播放中约 2 次/秒）② 封面解码完成（`CarPlayMediaKeys.kt:156`）时发生。
- `pushGuidance` 只在 `CarPlayGlance` 快照真的变化时（`CarPlayGlance.kt:86,89`）。
- **没有**任何周期性重推、服务重绑后重推、或前台恢复重推。

### 3.4 测试覆盖（重要事实）

在 `common/src/test/**` 与 `shared/src/test/**` 全量 grep：**没有任何测试引用 `VendorClusterBridge`**（`pushMedia` / `setArtwork` / `pushGuidance` 均无覆盖）。
现存相关测试只覆盖链路的前半段：
- `CarPlayPlaybackStatusTest.kt`（iAP2 状态机）
- `NowPlayingArtworkQueueTest.kt`（解码队列）
- `CarPlayMediaCallbackTest.kt`（`metadataChanged`）
- `DashboardContentDeliveryTest.kt` / `VirtualClusterDisplayTest.kt` / `ClusterSafeArea*Test.kt`（**都是"把内容画到我们自己的仪表画面"**，与"推给车机 DIM"无关）

⇒ **"交给车机"这一步目前零测试**，这也是 G1 能长期潜伏的原因。

---

## 4. 有序 Gap 清单 G1–G9

排序依据：**对"仪表什么都不显示 / 显示错东西"的解释力**。

### G1 · 数值型 setter 全部被自己的反射分发器静默丢弃 —— 最高优先
- **机制**【确定-源码+Java 语义】：`VendorClusterBridge.kt:242-256`
  ```kotlin
  val types = args.map { it?.javaClass }          // Int -> java.lang.Integer
  candidate.parameterTypes.withIndex().all { (i, p) ->
      types[i] == null || p.isAssignableFrom(types[i]) }   // int.class.isAssignableFrom(Integer.class) == false
  ```
  `int.class.isAssignableFrom(Integer.class)` 恒为 `false`（原始类型 Class 只与自身"可赋值"）。因此 §3.2 表中标 ❌ 的 6 个调用**永远不会 invoke**；`?: return@runCatching null` 让它静默返回，`DiagLog.trace` 打出的 `-> null` 与"成功调用 void 方法"无法区分。
- **解释力**：与 doc38 §2 的真机现象逐条吻合。
- **改法**：在查找前把原始类型参数映射成包装类型（`int→Integer, long→Long, boolean→Boolean, float→Float, double→Double`），或在 `parameterTypes` 上做双向兼容判断。
- **可离线单测 ✅**：`call()` 是纯反射逻辑，用含 `int`/`long` 方法的假 target 即可断言"确实被调用"。建议与 `NowPlayingArtworkQueueTest` 同放在 `common/src/test`。

### G2 · 功能默认关 + 重启失效 —— "完全没作用"的最可能解释
- **机制**【确定-源码】：`VendorClusterBridge.kt:31` `SWITCH="debug.diplay.cluster"`；`:65` `enabled = appContext != null && debugSwitch(SWITCH, false)`；`LegacyNativeOpus.kt:137-141` 用 `SystemProperties.get` 读，比较字符串 `== "1"`。
- doc38 §4 自己写明「重启车机失效」且「验证通过后做成设置项」（**TODO 未做**）。
- **改法**：落到 `AirPlayPersistence`（该文件已有 cluster 系列键，`AirPlayPersistence.kt:556-570`），默认开 + 设置项可关；`debug.*` 作为一次性覆盖。
- **可离线单测 ✅**（持久化读写 + 开关优先级；`ClusterSafeAreaPersistenceTest.kt` 已是同类范本）。

### G3 · 封面"只有第一首"：单点 id 相等 vs 身份+留存状态机
- **机制**【确定-源码】：`CarPlayMediaKeys.kt:152` `if (nowPlaying.artworkTransferId == id) { artwork = decoded; … setArtwork(...) }`；`:371-375` `nextArtwork()` 未命中缓存就沿用旧图。一旦解码完成时 `id` 已不是"当前"id，就**只入缓存、永不发布**。
- 对照 `LegacyVehicleArtwork.associate()`（§2.5）。
- **改法**：引入 `identity(title/artist/album) + retainedCover + pendingTitle` 关联，替代 id 相等判断；`nextArtwork()` 在身份未变时复用留存封面、身份已变且无新图时清空（而不是拿旧图充数）。
- **可离线单测 ✅**（纯状态机，最值得先做的一步：它能用单测把 doc38 §3 三种可能中的①②直接分辨出来）。

### G4 · 交互对象被永久缓存，即使其内部 vendor DI 为 null
- **机制**【推断，有强证据】：`VendorClusterBridge.kt:163-179` `media?.let { return it }`；厂商 DEX 证明 `MediaInteractionImpl` 的 DI 由构造参数注入且空壳会静默 no-op（§1.8）。EasyPlay 每次 publish 都重新解析并校验 `asBinder().isBinderAlive()`（`LegacyLynkMediaRefresh.java:419-451, 506-508`）。
- **能定它的证据**：上机时厂商 logcat 是否出现 `mMediaDI is null` / `mNavigationDI is null`（第 5 节判据 2）。
- **改法**：不缓存（每 N 秒或每次状态变化重新 `getMediaInteraction()`），或在 `call()` 全部失败时清空缓存；并在我们侧打印厂商侧是否 null。
- **需上机 ⚠️**（无法离线复现厂商空壳行为）；缓存失效策略本身可单测。

### G5 · 封面交接方式（我们 `file://`；CS11 上 EasyPlay 用回环 HTTP）
- **机制**【确定-源码】：我们 `VendorClusterBridge.kt:92-101`（`getExternalFilesDir` + `Uri.fromFile` + `setReadable(true,false)`），仓库里**没有任何 artwork provider**（`common/src/main/AndroidManifest.xml:25-33` 只有 `DiagnosticReportProvider`）。CS11 上 EasyPlay 是"JPEG q88 ≤256px → content provider + grantUriPermission → **`http://127.0.0.1:<port>`**"（§2.4）。
- doc38 记录第一首可显示 ⇒ `file://` 至少一度可读，所以它**不是"完全不显示"的主因**，但很可能是"换歌后回退默认图 / 偶发读不到"的次要原因（emulated storage 的 FUSE 视图下 mode 位不保证对其他 UID 生效）。
- **改法（分两步，先易后难）**：
  1. 编码与尺寸对齐参考：`Bitmap.compress(JPEG, 88)` + 缩到最长边 256（现在是 PNG 全尺寸，`VendorClusterBridge.kt:94`）。
  2. 交接方式对齐：新增 artwork provider（authority `${applicationId}.artwork`）+ `grantUriPermission("com.ecarx.dim_interaction", uri, FLAG_GRANT_READ_URI_PERMISSION)`；**若仍失败，再上回环 HTTP 服务**（参考已证明这条路在 CS11 上被采用）。
- **可离线单测 ✅**（Uri 构造/校验、编码尺寸、provider 路径映射）；**实际可读性/取图行为需上机 ⚠️**。

### G6 · 把占位图当封面推给仪表
- **机制**【确定-源码】：`CarPlayMediaKeys.kt:360-361` `shownArtworkLocked() = artwork ?: placeholder ?: placeholderArt(...)`，`:156` 直接推它。参考在无图时给的是留存封面或 null（`LegacyVehicleArtwork.java:266-277`，`Snapshot.artworkUri` 可为 null）。
- **后果**：仪表厂家自己的默认图被我们的占位图覆盖；用户看到的是"错的封面"，符合"显示错东西"的描述。
- **改法**：无真封面时推 `null`（`setArtwork(null)`）。
- **可离线单测 ✅**。

### G7 · 没有重推 / 保活 / 断连恢复
- **机制**【确定-缺失】：见 §3.3。参考有 `PresentationRefresh` + 10 s 超时 + `presentation_refreshed`/`presentation_refresh_timeout` 诊断（§2.3）。
- **改法**：`VendorClusterBridge` 增加"播放中每 10–15 s 重推一次；`DimInteraction` 解析失败/服务重绑后重新解析并重推"的轻量循环，带注入时钟以便单测。
- **可离线单测 ✅**（状态机 + 注入时钟，仓库已有同类注入风格）；**效果需上机 ⚠️**。

### G8 · 仪表进度不会走
- **机制**【确定-源码】：`updateCurrentProgress(long)` 受 G1 影响永不生效；且 `elapsedMillis` 只在 iOS 主动上报时更新（`CarPlayPlaybackStatus.kt:44`，iOS 只在 play/pause/seek 上报）。参考有独立 `updateProgress(positionMs)`（`LegacyEcarxMediaCenter.java:392` 接口、`:996` 区调用）并在重推里刷新。
- **改法**：G1 修好后，在重推中带上本机推算的进度（我们已有 `elapsedUpdatedAt` 推算逻辑，`CarPlayMediaKeys.kt:118,316`）。
- **可离线单测 ✅**。

### G9 · 其它缺失（按影响递减）
1. **不调 `updateMediaSourceTypeList(int[])`**（厂商接口存在，§1.3），也**不注册** `IMediaInteractionCallback` / `IMediaDiListener`。后果：无法感知"用户把音源切走了 / 仪表要求刷新 / 我们被顶掉"。EasyPlay 的 claim 状态机正是靠 `focusLost` 重注册（`LegacyEcarxMediaCenter.java:1662-1665`）。**需上机 ⚠️**。
2. **无 `PathClassLoader` 回退**：`VendorClusterBridge.kt:183-186` 只用 `Class.forName`。参考有 `/system/framework/ecarx.adapterapi.jar` 的 `PathClassLoader` 兜底（`LegacyLynkMediaRefresh.java:370-378`）。本车该 jar 在 BOOTCLASSPATH（doc38 §1，【确定-实测】），故风险低。**离线结构性可测 ⚠️**。
3. **无节流**：`NowPlayingUpdate` 约 2 次/秒，每次都做完整反射查找 + 新建代理（`VendorClusterBridge.kt:73,195-205`）。功能无害，性能隐患。**可离线单测 ✅**。
4. **`setArtwork` 未检查 `enabled`**：`VendorClusterBridge.kt:86-87` 只判 `appContext != null`，所以开关关闭时仍然写 PNG 到磁盘并删旧文件（`:91-101`）。**可离线单测 ✅**。

---

## 5. 上机判据清单（逐条含命令与通过/失败判据）

> 应用包名（【确定-源码】`mobile/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml:3`）：`com.shihab.diplay.hudtest`

**① 确认门禁真的开着**
```powershell
adb shell setprop debug.diplay.cluster 1
adb logcat -s DiPlay-Cluster
```
- ✅ 通过：出现 `cluster media interaction=<类名>`
- ❌ 失败：一行都没有 ⇒ `attach` 没跑（`CarPlayHostActivity.kt:733` 未执行），或 `cluster dim api not on the classpath`（`VendorClusterBridge.kt:184`，即 `ecarx.adapterapi.jar` 不在 BOOTCLASSPATH）
- ⚠️ 重启车机后必须重设（G2）

**② 看厂商自己的日志（同时判定 G4）**
```powershell
adb logcat -s MediaDimInteraction
```
- ✅ 通过：`updateMediaInfo ==== baseMediaType = 6  basePlayState = 1  baseTotalTime = …` 且 `songTitle10/artistName11/albumName9` 回显我们的值
- 🔎 **关键**：同一次抓取里 grep `mMediaDI is null` / `mNavigationDI is null` —— **命中即 G4 确证**（交互对象在、内部 DI 为 null、我们被静默 no-op）

**③ 逐字段验证 G1（这是最有决定性的一条）**
修 G1 前后各换一首歌，观察仪表：
- ✅ 通过：仪表上"距下一转向点 / 距目的地 / ETA"从空白变成数字（说明 `int` 参数真的到厂了）
- ❌ 失败但仍能显示路名 ⇒ 回到 G1/G4 继续查（路名是 `String`，不受 G1 影响）

**④ 隔离封面交接（区分 G3 / G5 / G6）**
每次换歌后对照两行日志：
```powershell
adb logcat -s DiPlay-Cluster DiPlay-MediaKeys MediaDimInteraction
```
- 我们侧：`cluster artwork uri=file://…`（`VendorClusterBridge.kt:108`）
- 解码侧：`artwork decoded id=… bytes=… current=…`（`CarPlayMediaKeys.kt:145-148`）
- 厂商侧：`albumCover2=…`
判据：
- **只有解码侧无新 id / `current != id`** ⇒ 问题在我们侧（**G3**）
- **我们写了新 Uri，厂商打的还是旧 Uri 或默认图** ⇒ 问题在读取侧（**G5/G6**）
- **厂商打出 `http://` 之类** ⇒ 说明厂家确实按 URL 取图（佐证 §2.4）

**⑤ 判定 `file://` 是否真能被厂商进程读到（G5 的决定性实验）**
```powershell
adb shell ls -l /storage/emulated/0/Android/data/com.shihab.diplay.hudtest/files/cluster-artwork-*.png
adb shell su -c 'cat /storage/emulated/0/Android/data/com.shihab.diplay.hudtest/files/cluster-artwork-*.png > /dev/null'
```
- ✅ 通过（且换歌后仪表仍回退默认图）⇒ 权限不是主因，回 G3
- ❌ 失败 ⇒ 直接做 G5 step 2（provider + grantUriPermission）

**⑥ 验证"只有第一首"的归属**
连换 3 首**有封面**的歌，逐首看 `artwork decoded` 行：
- 每次都出现新 `id` 且 `current == id` ⇒ 我们侧逻辑过了，问题在厂商/格式（G5 的格式面：PNG 全尺寸 vs JPEG 256px、1 MiB 上限）
- 某一首完全没有 `artwork decoded` 行 ⇒ iOS 没推（doc38 §3 的开放问题）

**⑦ （可选）确认 EasyPlay 本车到底走了哪条通路**（见第 8 节的重要限定）
```powershell
adb shell ls -l /system/framework/ecarx.openapi.impl.jar /system/framework/ecarx.gkui.openapi.impl.jar
adb logcat | Select-String 'lynk media refresh|ecarx artwork unavailable|presentation_refreshed|ECARX system SDK is unavailable'
```
- 若日志出现 `ECARX system SDK is unavailable` 或两个 jar 都不存在 ⇒ EasyPlay 的 `LegacyEcarxMediaCenter`（含通路 A）**在本车根本没构造成功**，那么"EasyPlay 能显示"靠的是**别的**通路（需回头找），而我们的 `DimInteraction` 门面反而是独立可行的（doc38 厂商日志已证明它能送到）。

---

## 6. 未决项（每条注明"能定它的证据"）

| # | 未决问题 | 能定它的证据 | 定性 |
|---|---|---|---|
| U1 | iOS 在换歌时是否真的推了新封面？ | `artwork decoded id=… current=…` 三行 + iAP2 session-12 抓包 | 【未决】（doc38 §3 遗留） |
| U2 | `getMediaInteraction()` 在未绑定时返回 null 还是"内部为 null 的空壳"？ | 上机 logcat 是否出现 `mMediaDI is null`；或 `create()` 后立刻调用一次看厂商日志 | 【推断】（DEX 只能证明诊断串存在 + DI 由构造注入） |
| U3 | 厂商对 `albumCover2` 的真实尺寸/格式/大小限制 | 分别喂 ≤256px JPEG 与大尺寸 PNG，看哪一次被接受 | 【未决】 |
| U4 | 不调 `updateMediaSourceTypeList` / 不注册 listener 是否真的导致不显示 | 上机看"零回调注册"状态下仪表是否仍显示 | 【推断】 |
| U5 | `INaviInteraction.updateTurnByTurnArrow(Bitmap)` 是否被仪表消费（doc38 说发了但没显示） | 上机 logcat 是否出现 `mNavigationDI is null`；及 `updateNextGuidancePointName` 能显示而箭头不能 ⇒ 指向 navi DI 空壳或接口不被消费 | 【推断】 |
| U6 | `INavigationDI.Turn` 整体对象通路（`turnByTurn(Turn)`）是否为箭头/距离/ETA 的正解 | 只有 U5 修好后仍无效才需要走它；参考 `LegacyLynkNavigationBridge.java:1004,1058` | 【未决】 |
| U7 | `/system/framework/ecarx.openapi.impl.jar` 是否存在于本车 | `adb shell ls /system/framework`（doc37 只列到 `ecarx.adapterapi.jar` 与 `ecarx.cmaplatform.api.jar`，**倾向不存在**） | 【推断，强】 |
| U8 | 本车真实媒体中心 action 是否只有 `_INTERNAL_API_SERVICE` | doc37 的 `dumpsys activity services` 输出（已实测；可复测） | 【确定-实测】 |

---

## 7. 明确**不应**照抄的部分

1. **`Build.MODEL.equals("CS11")` 机型门禁**（`LegacyLynkMediaRefresh.java:544-548`、`LegacyEcarxMediaCenter.java:2143-2147`、`LegacyWheelLearningDialog.java:338`）：应按"能力探测"（类能否解析 / 服务是否存在 / jar 是否在 BOOTCLASSPATH）gate。同一台车换个 ROM 串就全废。
2. **媒体中心绑定常量**（`LegacyEcarxServiceBinding.java:23-34` 的 `ecarx.xsf.MEDIA_CENTER_SERVICE`）：doc37 实测本车真实 action 是 `ecarx.xsf.MEDIA_CENTER_INTERNAL_API_SERVICE`，照抄会 bind 失败。
3. **`<自己包名>.DIM_MEDIA_REFRESH` action + `app_type=com.ecarx.di_server` extra + `IMediaDI$Stub.asInterface` 直连**（`LegacyLynkMediaRefresh.java:386, 506-508`）：那是 DiServer 的 AIDL 直连（未公开接口）。我们已在用受支持的 `DimInteraction` 门面，且 doc38 的厂商日志证明它**确实把我们的字段送到了**。除非分项 setter 确认无解，否则不要改走 AIDL 直连。
4. **本地 HTTP 图床（`LegacyArtworkHttpServer` + `ArtworkUriTransport`）**：虽然是 CS11 专属启用，但它是**最后手段**——先做 JPEG/256 与 provider+grant（成本低、可离线测），HTTP 服务只在二者都失败时才上（它要处理端口/生命周期/并发，`LegacyArtworkHttpServer` 有 518 行且只服务单个 active socket）。
5. **title/artist/album 截断 512 字符**（`LegacyEcarxMediaCenter.java:755-757`）：那是 EasyPlay 自己加的防御，不是厂商规范；别当上限。
6. **为了"填满接口"而乱填 `IPlaybackInfo` 的 10–18 号 getter**（`getNextArtwork`/`getPreviousArtwork`/`getLyric*`/`getRadio*`）：参考只提供一个封面 Uri；乱填可能触发厂商侧未测路径。返回默认值即可。
7. **`usesE03Service` / GKUI 分支**：明确不是 CS11（见第 8 节），不要为它们写任何代码。

---

## 8. 多机型归属标签（对每条结论标注）

### 8.1 EasyPlay 的分发点证据（回答"Modern vs Legacy 怎么选"）

| 分发点 | `file:line` | 判定依据 | CS11 落点 |
|---|---|---|---|
| `LegacyMediaControl.Companion.create` | `LegacyMediaControl.java:633` | **`Build.VERSION.SDK_INT >= 21`** | API 19 ⇒ **`PlatformBackend`（Legacy 侧）** |
| `usesSinglePassArtwork(sdk, manufacturer, model, modernSdk)` | `LegacyEcarxMediaCenter.java:2143-2147` | **`sdk==19 && MANUFACTURER≈Freescale && MODEL≈CS11 && !modernSdk`** | **true ⇒ CS11 专属分支打开** |
| `usesE03Service(sdk, device)` | `LegacyEcarxMediaCenter.java:2138-2141` | `sdk >= 29 && DEVICE.startsWith("e03")` | **false（其他机型专属）** |
| `usesGkuiSdk()` | `LegacyEcarxMediaCenter.java:2194-2200` | `SDK_INT >= 21 && /system/framework/ecarx.gkui.openapi.impl.jar 可读` | **false（其他机型专属；GKUI 平台）** |
| `loadApi(context)` | `LegacyEcarxMediaCenter.java:2166-2192` | `Class.forName("com.ecarx.sdk.mediacenter.MediaCenterAPI")` 否则 `DexClassLoader("/system/framework/ecarx.openapi.impl.jar")` | **倾向抛 `IOException("ECARX system SDK is unavailable")`（U7）** |
| `modernCallbackType`（决定 `MusicClient` vs `IMusicClient`） | `LegacyEcarxMediaCenter.java:1340-1350, 1366-1367, 1414-1434` | 由 `usesGkuiSdk()` 决定 | **null ⇒ 走 `IMusicClient`/`IMusicPlaybackInfo` 反射代理（Legacy 侧）** |
| `instrumentRefresh = new LegacyLynkMediaRefresh(...)` | `LegacyEcarxMediaCenter.java:1365` | `usesSinglePassArtwork(...)` | **CS11 专属** |
| `artworkTransport = new ArtworkUriTransport(ctx, usesSinglePassArtwork(...), …)` | `LegacyEcarxMediaCenter.java:1358` | 同上 | **CS11 专属（⇒ `LegacyArtworkHttpServer` 传递性 CS11 专属）** |
| `directBinding = new LegacyEcarxServiceBinding(ctx)` | `LegacyEcarxMediaCenter.java:1373-1385` | `modernCallbackType != null && usesE03Service(...)` | **null ⇒ CS11 上 `LegacyEcarxServiceBinding` 不被实例化** |
| `LegacyLynkNavigationBridge` 构造 | `LegacySessionService.java:2872`；类内 Companion 无门禁 | 无机型/SDK 门禁 | **ECARX 通用（但效果未验证）** |

> **一句话回答"Modern*/Legacy* 对偶是怎么选的"**：**按 API 级别（21）与"现代 SDK jar 是否存在"，不是按机型**（`LegacyMediaControl.java:633`、`LegacyEcarxMediaCenter.java:1340,2194-2200`）。CS11（API 19 / i.MX6Q）稳定落在 **Legacy 侧**；`ModernEcarxObjects` / `LegacyModernEcarxObjects` / `LegacyModernMediaBackend` 这一组只在 GKUI 现代 SDK 可用时才参与（`:1366-1367, 1414-1434`）。

### 8.2 逐条结论的机型归属

| 结论 | 归属标签 | 依据 |
|---|---|---|
| `LegacyLynkMediaRefresh`（DiServer + `IMediaDI` + `Media` bean）字段表与绑定方式 | **CS11 专属**（门禁） | `LegacyEcarxMediaCenter.java:1365,2143-2147`；`LegacyLynkMediaRefresh.java:544-548` |
| `ArtworkUriTransport` / `LegacyArtworkHttpServer`（回环 HTTP 图床） | **CS11 专属** | `LegacyEcarxMediaCenter.java:1358,2143-2147`；`LegacyArtworkHttpServer.java:156,198` |
| 封面原料层：JPEG q88 / ≤256px / 1 MiB / `<cacheDir>/easyplay-covers/<session>/` | **全机型通用**（无门禁） | `LegacyArtworkLoader.java:38-42,239-252,272,286-289` |
| `content://<pkg>.artwork/...` + `grantUriPermission` 给 `com.ecarx.dim_interaction` | **全机型通用**（`OEM_READERS` 是 ECARX 名字） | `LegacyArtworkLoader.java:57,299,301-312` |
| `LegacyVehicleArtwork.associate()` 身份+留存封面状态机 | **全机型通用**（纯逻辑） | `LegacyVehicleArtwork.java:249-334` |
| `Media` bean 字段集 / `IMediaDI` / `IAdapterDI` / `IMediaInteraction` / `IPlaybackInfo` 符号 | **ECARX 通用**（厂商平台 ABI，来自 boot classpath jar） | 第 1 节（DEX） |
| 厂商 `mMediaDI is null` 静默 no-op 机制 | **ECARX 通用** | 第 1.8 节（DEX 字符串） |
| `PresentationRefresh` + 10 s 超时 + claim 状态机（2000 ms） | **ECARX 通用**（`LegacyEcarxMediaCenter` 核心，无机型门禁） | `LegacyEcarxMediaCenter.java:63,65,471-537,779-789,1011-1055` |
| `LegacyEcarxServiceBinding`（`IMediaCenterSvc` 直连 / `ecarx.xsf.MEDIA_CENTER_SERVICE`） | **其他机型专属（E03 / API≥29）** | `LegacyEcarxMediaCenter.java:1373-1385, 2138-2141` |
| GKUI / `MediaCenterAPIImpl` / `ECarXApiClient$Callback` / `MusicClient`+`MusicPlaybackInfo` | **其他机型专属（GKUI 平台，API≥21 + gkui jar）** | `LegacyEcarxMediaCenter.java:1340-1342,1366-1367,2194-2200` |
| `ModernEcarxObjects` / `LegacyModernEcarxObjects` | **其他机型专属（现代 SDK 侧）** | `LegacyEcarxMediaCenter.java:1366-1367,1414-1434` |
| `LegacyLynkNavigationBridge`（DiServer 直连 `INavigationDI`/`Turn`） | **ECARX 通用（无门禁）**，但本车是否走它未验证 | `LegacySessionService.java:2872`；类内无 `isSupported` |
| `LegacyWheelLearningDialog` 的 CS11 音量键特判 | **CS11 专属**（非媒体，佐证同款门禁风格） | `LegacyWheelLearningDialog.java:338` |
| "iOS 是否换歌推新封面" | **全机型通用（协议层）** | iAP2 0x5001 语义，与机型无关 |
| EasyPlay 的 `LegacyEcarxMediaCenter` 是否在**本车**真的构造成功 | **无法判定** ⇒ 见 U7；若 `ecarx.openapi.impl.jar` 不存在则**连 CS11 专属的通路 A 都不会跑** | `LegacyEcarxMediaCenter.java:1336,2166-2192`；doc37 `ls /system/framework` |

### 8.3 ⚠️ 由第 8 节引出的重要限定（会影响后续结论采信）

`LegacyLynkMediaRefresh`（以及 `ArtworkUriTransport`）**只在 `ReflectiveBackend` 的构造函数内被创建**，而该构造函数的前几行就是 `this.apiClass = Companion.loadApi(this.context)`（`LegacyEcarxMediaCenter.java:1336`）。`loadApi` 要求 `com.ecarx.sdk.mediacenter.MediaCenterAPI` 能从 classpath 解析，否则用 `DexClassLoader` 加载：

- `/system/framework/ecarx.gkui.openapi.impl.jar`（GKUI）或
- `/system/framework/ecarx.openapi.impl.jar`（其余），二者都不可读时抛 `IOException("ECARX system SDK is unavailable")`（`:2176`）。

我已用 DEX 字符串扫描确认：**`ecarx.adapterapi.jar` 与 `ecarx.cmaplatform.api.jar` 都不含** `MediaCenterAPI` / `IMusicClient` / `IMusicPlaybackInfo` / `IMediaCenterSvc` 任何符号（两次扫描均 0 命中）。而 doc37 的 `ls /system/framework`【确定-实测】只列到 `ecarx.adapterapi.jar(+odex)` 与 `ecarx.cmaplatform.api.jar(+odex)`。

⇒ **推断（强）**：本车很可能没有 `ecarx.openapi.impl.jar`，于是 `LegacyEcarxMediaCenter`（含通路 A 与 HTTP 图床）在本车**根本没构造成功**，EasyPlay 能显示媒体信息的机制可能另有其路（尚未找到），或者 EasyPlay 在其它入口处做了兜底。

**这对我们的行动意味着两件事**：
1. 我们**不能**用"EasyPlay 能显示 ⇒ 通路 A 就是正解"来推理；第 1–2 节的价值在于**厂商字段/接口清单**（这一层与走哪条路无关，且已被 doc38 的厂商日志独立佐证）。
2. **上机判据 ⑦ 应该尽早做**（一条 `ls` + 一次 logcat grep），它能一次性判定"参考实现到底走没走我们以为的那条路"。若确认没走，则应把注意力放到 doc38 已经实测可用的 `DimInteraction` 门面（我们现有路线），并把 G1/G2 作为主攻方向——这也正是 G1/G2 排在最前的原因。

---

## 附录 A · 自写 DEX 解析脚本（可复现，含已知坑）

> 脚本原样落盘于 `C:\Users\gaoji\AppData\Local\Temp\dexsurface\`（TEMP，易被清理）。为耐久起见全文收录于此；以后解析 `INavigationDI`/`Turn` 只需改正则。

### A.1 主脚本 `dex_surface.py`（类 → 方法名 + proto）

```python
"""Minimal DEX parser: list classes/methods matching a regex, with proto descriptors."""
import re, struct, sys, zipfile

def uleb128(buf, off):
    result, shift = 0, 0
    while True:
        b = buf[off]; off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7

class Dex:
    def __init__(self, data):
        self.d = data
        # header: string/type/proto/field/method/class_defs 的 size+off，从 0x38 起 12 个 u4
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size,   self.type_ids_off,
         self.proto_ids_size,  self.proto_ids_off,
         self.field_ids_size,  self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", data, 56)
        self.strings = [self._string(i) for i in range(self.string_ids_size)]
        self.types   = [self.strings[struct.unpack_from("<I", data, self.type_ids_off + 4*i)[0]]
                        for i in range(self.type_ids_size)]
        self.protos  = [self._proto(i)  for i in range(self.proto_ids_size)]
        self.methods = [self._method(i) for i in range(self.method_ids_size)]
        self.classes = [self._classdef(i) for i in range(self.class_defs_size)]

    def _string(self, i):
        off = struct.unpack_from("<I", self.d, self.string_ids_off + 4*i)[0]
        _, off = uleb128(self.d, off)                 # 跳过 utf16_size
        end = self.d.index(b"\x00", off)
        return self.d[off:end].decode("utf-8", "replace")

    def _type_list(self, off):
        if off == 0: return []
        size = struct.unpack_from("<I", self.d, off)[0]
        return [self.types[struct.unpack_from("<H", self.d, off + 4 + 2*j)[0]] for j in range(size)]

    def _proto(self, i):
        _, ret, params = struct.unpack_from("<III", self.d, self.proto_ids_off + 12*i)
        return self.types[ret], self._type_list(params)

    def _method(self, i):
        cls, proto, name = struct.unpack_from("<HHI", self.d, self.method_ids_off + 8*i)
        return self.types[cls], self.strings[name], self.protos[proto]   # <- name 必须过 strings[]

    def _classdef(self, i):
        off = self.class_defs_off + 32*i
        class_idx, access, super_idx, ifaces, src, anno, class_data, static_vals = \
            struct.unpack_from("<8I", self.d, off)
        return {"name": self.types[class_idx], "super": self.types[super_idx] if super_idx != 0xFFFFFFFF else None,
                "class_data": class_data, "src": self.strings[src] if src != 0xFFFFFFFF else None,
                "static_values": static_vals}

    def class_methods(self, cd):
        off = cd["class_data"]
        if off == 0: return None
        counts = []
        for _ in range(4):                      # <- 这四个是 ULEB128，不是 u4（常见坑）
            v, off = uleb128(self.d, off); counts.append(v)
        static_fields, instance_fields, direct_methods, virtual_methods = counts

        def read_fields(count, off):
            res, idx = [], 0
            for _ in range(count):
                diff, off = uleb128(self.d, off); idx += diff
                _, off = uleb128(self.d, off)                  # access_flags
                res.append(idx)
            return res, off

        def read_methods(count, off):
            res, idx = [], 0
            for _ in range(count):
                diff, off = uleb128(self.d, off); idx += diff
                _, off = uleb128(self.d, off)                  # access_flags
                _, off = uleb128(self.d, off)                  # code_off
                res.append(idx)
            return res, off

        _, off = read_fields(static_fields, off)
        _, off = read_fields(instance_fields, off)
        direct, off = read_methods(direct_methods, off)
        virtual, off = read_methods(virtual_methods, off)
        return direct + virtual

def main(jar, pattern):
    with zipfile.ZipFile(jar) as z:
        data = z.read("classes.dex")
    dex, rx, printed = Dex(data), re.compile(pattern, re.I), 0
    for cd in dex.classes:
        if not rx.search(cd["name"]): continue
        printed += 1
        print(f"\n=== {cd['name']}  extends {cd['super']}  (src={cd['src']})")
        idxs = dex.class_methods(cd)
        if idxs is None: print("   <no class data>")
        else:
            for mi in sorted(set(idxs)):
                _, name, (ret, params) = dex.methods[mi]
                print(f"   {ret} {name}({', '.join(params)})")
    if not printed:
        print(f"(no class matched {pattern})")
    print(f"\n[classes total {dex.class_defs_size}, matched {printed}]")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
```

调用示例（本次用的就是这几条）：

```powershell
$py='C:\Users\gaoji\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$jar='D:\Development\DiAuto-DiPlay\_analysis\cs11_vendor\ecarx.adapterapi.jar'
& $py dex_surface.py $jar 'diminteraction/(DimInteraction|IMediaInteraction|MediaInteractionImpl|INaviInteraction|NaviInteractionImpl)\b'
& $py dex_surface.py $jar 'bean/(Media|IMediaDI|IAdapterDI);'          # 注意：类描述符以 ';' 结尾，别用 '$'
```

### A.2 字段脚本 `dex_fields.py`（字段名 + 类型）

与 A.1 共用 `Dex`，追加 `read_fields` 返回 `(field_idx, access_flags)` 列表并按下标取 `strings[name]` 打印。**字段名与类型可靠**（本报告的 §1.7 即由此得出）。

### A.3 已知坑（务必知道，免得复现时踩）

1. **`class_data_item` 开头的 4 个计数是 ULEB128，不是 `u4`**。我第一次按 `struct.unpack_from("<4I")` 解析，直接越界崩掉。
2. **`method_id_item.name_idx` / `field_id_item.name_idx` 是字符串池下标**，必须过 `strings[]`，否则会打印出一堆整数（我第一次就是这样，把方法名打成了 `5892`）。
3. **类描述符以 `;` 结尾**（`Lcom/.../Media;`），正则用 `$` 会全部匹配失败。
4. **静态字段值（`static_values`，编码数组 `encoded_array`）的解码不可靠**：各 `value_type` 的 `value_arg` 语义（尤其 `INT`/`SHORT` 的字节宽度与符号）容易被写错。我这次解出来的 `MEDIA_TYPE_*` 数值明显是垃圾。
   ⇒ **常量数值请改用"源码 + 真机日志"双证**（本报告 §1.7 就是这么做的：EasyPlay 源码给 6 和 1/0，doc38 的厂商日志给 `baseMediaType = 6`、`basePlayState = 1`）。
5. **`editing`/`output`**：控制台若为 GBK，打印某些 UTF-8 串会 `UnicodeEncodeError`；跑之前设 `$env:PYTHONIOENCODING='utf-8'`。

---

## 附录 B · 本次未做的验证（留给下一步）

- 未做任何代码改动（含测试），未提交。
- 未跑 `:shared` / `:common` 测试（本轮为纯阅读审计，不涉及改动；基线 751/3 与 552/6 仍适用）。
- 未解析 `INavigationDI` / `Turn` bean 的字段与 `turnByTurn()` 语义（脚本已具备能力，见 A.1；这是 U6 的前置）。
- 未确认 `/system/framework/ecarx.openapi.impl.jar` 是否存在（U7，需上机一条 `ls`）。

---

## 9. 已实施 · 封面（G3/G5/G6/G7 + G4）与实际节流（G9）

> 实施位置：`CarPlay-CS11` 分支 `cs11-api19`，基线 `7bb08bce`，**未提交**（工作树有改动）。
> 范围：只做封面这半边。导航卡片路径、反射匹配器、`cluster_bridge_enabled` 持久化设置、`DiPlayActivity` 的 `cluster_content`、音频路径**均未改动**。
> 环境政策：CS11 / API 19 是唯一目标；下面凡是标 ⚠️ 的都是**离线测不到、必须上机**的项。

### 9.1 逐 gap 改动

| Gap | 改了什么 | 位置 |
|---|---|---|
| **G3** | 新增纯状态机 `ClusterArtworkAssociation<Cover>`（身份 `(title, artist, album)` 去空白 + trim；留存封面仅在身份变化 / 无 title / 暂停时清空；无新图时把留存封面重新关联当前曲目；`pendingTitle` / `inheritedArtist` / `inheritedAlbum` 保留；title 非空才留存；placeholder 视为"无图"）。`CarPlayMediaKeys` 把它接到仪表推送上：解码完成即交给状态机（不再单点比对 id），元数据帧按 `artworkCache[announcedId] ?: pendingCover ?: artwork` 提供候选图 | `common/.../ClusterArtworkAssociation.kt:32,80,87,90,132`；`common/.../CarPlayMediaKeys.kt:85,98,111,168,207,223` |
| **G4** | 媒介调用失败（未匹配到重载 *或* invoke 抛异常）时 `call()` 返回 `false`，桥随即 `interactions.invalidateMedia()`（丢掉缓存的 media 对象**及其 DimInteraction 门面**，下次更新重新 `getMediaInteraction()`），并把这次推送记为失败；导航侧的 navi 缓存不动 | `shared/.../VendorClusterBridge.kt:416,600,570` |
| **G5** | 缩放 + JPEG q88（阶梯 88→62→45）+ 长度上限 + `content://<pkg>.artwork/covers/<session>/<uuid>.jpg` + 显式 `grantUriPermission` + provider + `file://` 回退（见 9.2） | `shared/.../ClusterArtworkTransport.kt:57,60,63,79,104,180,195,224,273,364`；provider：`common/.../ClusterArtworkProvider.kt:32,35,40`；清单：`common/src/main/AndroidManifest.xml:41-45` |
| **G6** | 仪表只收到状态机的结论：**没有真封面就推 null**（不再 `artwork ?: placeholder`），头显自己画默认图。holder 实例被显式告知状态机，即使误传也等同"无图" | `common/.../CarPlayMediaKeys.kt:168,223`；`common/.../ClusterArtworkAssociation.kt:87` |
| **G7** | 播放中周期重推：`ClusterPushSchedule`（间隔 10 s，失败后 3 s 重试，暂停不推；`nowMillis` 由外部注入 ⇒ 纯可测）+ 守护线程 tick（2 s 粒度）；另加失败重推（去重记录被清掉，下一帧必发） | `shared/.../VendorClusterBridge.kt:197,454,464,474`；`CarPlayMediaKeys` 侧不变 |
| **G9** | 推送载荷去重：`ClusterMediaUpdate` = 厂商 getter 真正看得见的字段（title/artist/album/duration/elapsed/playing/artworkUri），`ClusterMediaPushGate` 记住上次真正发出去的载荷，未变则整段跳过（不再分配代理、不再反射分发）；`artworkTransferId` / `sourceApp` 变化不算变化 | `shared/.../VendorClusterBridge.kt:521,539,149,166` |

### 9.2 精确的尺寸 / 质量 / 大小 / 授权规则

- **缩放**：`ratio = min(1, 256 / max(w,h))`，目标边长 `max(1, (edge * ratio).toInt())`；**从不放大**。源图超出参考限制（任一边 ∉ `1..8192`、像素 > `16 777 216`）直接拒绝。
- **编码**：`Bitmap.CompressFormat.JPEG`，质量阶梯 **88 → 62 → 45**，取第一个编码后大小落在 **`1 .. 1 048 576` 字节**（含 1 MiB）的结果；三次都超限 ⇒ **这张图不推**（宁可不显示，也不推一个超限文件）。参考实现只有 88 一档且超限即弃，这里多两档是为了不让一张噪点封面把封面功能整首歌废掉。
- **落盘**：`<cacheDir>/cluster-artwork/<session-uuid>/<cover-uuid>.jpg`（session 每进程一个 UUID；进程启动时清理其它会话目录）。
- **Uri 形状**：`content://<packageName>.artwork/covers/<session>/<uuid>.jpg`（与参考 `LegacyArtworkLoader:299` 逐段一致，含 `covers` 段）。
- **授权**：对 `CLUSTER_ARTWORK_READERS = ["com.ecarx.dim_interaction", "ecarx.xsf.mediacenter"]` 逐个 `Context.grantUriPermission(reader, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION (=1))`，成功计数并打日志；provider 未注册或授权数为 0 ⇒ 走回退。
- **provider**：`ClusterArtworkProvider`（`:common`），`android:exported="true"`（让那两个进程能解析 authority）+ `grantUriPermissions="true"`，authority `${applicationId}.artwork`（debug 变体合并结果 = `com.shihab.diplay.hudtest.artwork`，与 `getPackageName()+".artwork"` 一致）；`openFile` 只接受 `mode == "r"`（`ParcelFileDescriptor.MODE_READ_ONLY`），`getType` = `image/jpeg`，`insert/update/delete` 抛 `UnsupportedOperationException`；路径映射只认本 authority、无 query/fragment、恰好 3 段、session 与文件名都是 UUID（`.jpg`），且文件必须真的存在。
- **淘汰**：最多保留 **4** 份（参考的 `MAX_CACHED_IMAGES`），超出的立即 `revokeUriPermission` + 删除文件；推 null（无封面）时把这批全部撤销删除。
- **回退**：provider 不可用 / 授权全失败 ⇒ 把**同一份 JPEG** 拷到 `getExternalFilesDir(null)/cluster-artwork-<ts>.jpg`，`setReadable(true,false)`，删掉旧的同名文件，然后 `Uri.fromFile`；缓存里那份随即删除（它只服务 provider 这一级）。
- **三级台阶为什么存在**：① 厂商读取方是按 JPEG/≤256px 的料做的，PNG 全尺寸从来没被验证过；② `file://` 依赖 emulated storage 的 FUSE 视图与 mode 位，跨 UID 不保证（参考因此显式授权）；③ `file://` 是改动前那条"至少第一首显示过"的路，留作兜底。参考的第三级（回环 HTTP 图床）**故意不做**：便宜的两级在当前车上都还没验证，HTTP 那级要占端口/生命周期/单连接（参考 518 行），只有在①②都失败时才值得上（§7.4）。

### 9.3 新增测试（全部通过）

| 模块 | 类 | 用例数 | 覆盖 |
|---|---|---|---|
| common | `ClusterArtworkAssociationTest` | 12 | 留存复用、换歌清空、新图到达、图先于元数据、身份补全、pendingTitle/inherited 校正、暂停、无 title、trim、placeholder 永不作为封面、reset |
| shared | `ClusterArtworkTransportTest` | 15 | 缩放/边长、1 MiB 上下界、源限制、质量阶梯（含"首个合格即停"与"全不合格则弃"）、Uri 形状、授权列表与 flag、tier 判定、provider 映射（含 9 类非法 Uri 与"文件已删"）、4 份淘汰、陈旧会话清理 |
| shared | `ClusterPushScheduleTest` | 7 | 10 s 间隔、暂停不推、失败 3 s 重试、失败后成功回落长间隔、启动后首 tick、注入间隔 |
| shared | `ClusterMediaPushGateTest` | 6 | 未变载荷只发一次、位置变化算变化、厂商看不见的字段不算变化、封面 Uri 算变化、失败后不记为已发、逐字段敏感 |
| shared | `ClusterInteractionCacheTest` | 3 | 只解析一次、解析失败不缓存、失败后门面与句柄都重解析 |

**设备才能确认的**（离线断言不了，见 9.5）：JPEG 压缩产物的真实字节数与可读性；`grantUriPermission` 是否被车机接受（授权计数是否 > 0）；provider 能否被 `com.ecarx.dim_interaction` 进程打开；vendor 是否真的消费新 `albumCover2`；重推是否让车机重启服务后自行恢复。

### 9.4 回归与反向检查

命令：`.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest --continue --console=plain --offline`（该命令因既有失败必然 BUILD FAILED，看 XML 汇总）。

| 模块 | 改前 | 改后 | 失败集合 |
|---|---|---|---|
| `:shared` | 795 / 3 | **826 / 3** | 仍是 `HotspotJoinNormalizedTransactionTest` 3 条 |
| `:common` | 569 / 6 | **581 / 6** | 仍是 `CarPlayControllerUsbServiceTest` 3 + `UsbPermissionSetupTest` 3 |

反向检查（改坏 → 新测试必须红 → 复原）：
1. 把"无新图时返回留存封面"改成 `return null`（即回到"换歌后没有封面"的旧语义）⇒ `ClusterArtworkAssociationTest` **5 条红**，报错全是 `expected same:<cover-one/two> was not:<null>`。
2. 去掉 placeholder 拦截（`val offered = artwork`，即重新把占位图当封面推）⇒ `thePlaceholderIsNeverPublishedAsACover` 红，报错 `expected null, but was:<placeholder-art>`。
3. 把 `ClusterMediaPushGate.shouldPush` 恒置 `true`（即不做节流）⇒ `anUnchangedPayloadIsDispatchedOnlyOnce`、`fieldsTheVendorCannotSeeAreNotPartOfThePayload` 红。
三处均已复原，最终一次全量跑为 9.4 表中的数字。

### 9.5 上机验证清单（必须逐条做，别假设）

统一抓取（`DiPlay-MediaKeys` 是解码侧，`DiPlay-Cluster` 是交接与重推侧，`MediaDimInteraction` 是厂商侧）：

```powershell
adb logcat -s DiPlay-MediaKeys DiPlay-Cluster MediaDimInteraction
```

1. **门禁开着**：`cluster push setting=… property=… enabled=true`。`enabled=false` 时后面什么都不会有。
2. **每次换歌都要有新的交接行**（G5/G3）：
   - 我们侧：`artwork decoded id=… current=…` → `cluster cover identity=Identity(title=…, artist=…, album=…) offered=true published=true` → `cluster artwork encoded bytes=… edge=…` → `cluster artwork grants=N provider=true uri=content://…artwork/covers/<session>/<uuid>.jpg` → `cluster artwork uri=content://…`
   - ✅ 通过：**每换一首（有封面的）歌**都出现一组新 `cluster artwork uri=content://…`，且 `grants` ≥ 1（期望 2）。
   - ❌ `grants=0` 或 `provider=false` ⇒ 立刻看下一行是 `cluster artwork uri=file://…`（回退生效）；若连回退都没有 ⇒ 编码失败（`cluster artwork encode failed`）。
   - ❌ `published=false` / 只有 `cluster cover … published=false` ⇒ G3 状态机判定为"无封面"：看 identity 是否 title 为空或 `playing=false`。
3. **厂商侧必须回显新 Uri**（G5 的判据）：
   `D/MediaDimInteraction: updateMediaInfo ==== … albumCover2 = content://com.shihab.diplay.hudtest.artwork/covers/<session>/<uuid>.jpg`，且**换歌后这个 uuid 变了**。
   - ❌ 还是旧 uuid 或还是 `file://…png` ⇒ 新封面没送到（看第 2 步是否缺行）。
   - ❌ 车机显示默认图但厂商日志已是新 uuid ⇒ 取图侧问题，进入第 4 步。
4. **授权是否被接受（决定性）**：`adb shell dumpsys activity providers | Select-String 'artwork'` 看 provider 是否注册；必要时用
   `adb shell su -c 'cat /storage/emulated/0/Android/data/com.shihab.diplay.hudtest/files/cluster-artwork-*.jpg > /dev/null'` 验证回退文件可读。
5. **G4 探针**：同一次抓取里 grep `mMediaDI is null`。
   - 命中 ⇒ 厂商给的交互对象是空壳；此时应看到我们紧跟一行 `cluster media push failed; cached vendor interaction dropped for re-resolution`，并在此后出现新的 `cluster media interaction=…`。
6. **G7 重推**：放着不动，播放中应每 ~10 s 出现一行 `cluster repush due title=… playing=true artwork=…`（该行只表示"到点重推"，是否真送到厂商看紧接着有没有 `cluster media push failed`）。
   - 车机端验证：**重启厂商 DIM 服务（或切走仪表页再切回）**，10 s 内媒体卡片应自行恢复，不需要换歌。
7. **G9 节流**：`DiagLog`/trace 里 `cluster updatePlaybackInfo…` 的行数应远少于 iAP2 的 ~2 Hz（只在字段真变或重推时出现）。
8. **暂停/收尾**：暂停时 `cluster cover … published=false` 且 `cluster artwork uri=null`（G6/参考语义：暂停不保留封面）。
9. **不要为平板做兼容**：以上任一失败，都按"待上机项"记录，不做平板专用分支。

### 9.6 仍未做 / 开放

1. **参考的第三级（回环 HTTP 图床）未实现**——只有①②都验证失败才上（U3 仍未决）。
2. **`setArtwork` 在功能关掉时仍会写文件**（`docs/_digest/45 §4 G9.4` 的老问题，本次未动）：开关关闭时封面照样编码落盘，只是不推。留待与"开关关闭即停写"一起改。
3. **未注册 `IMediaInteractionCallback` / `IMediaDiListener`**（G9.1），`updateMediaSourceTypeList` 仍不调用：无法感知"用户把音源切走了 / 仪表要求刷新"。
4. **仪表进度（G8）仍依赖 iOS 主动上报**；重推里带的是最后一次已知 `elapsedMillis`，没有本地推算。
5. **`CarPlayPlaybackStatus.clear()`（会话结束但非 `clearAll`）不会推 `playing=false`**，所以重推循环在那种收尾里仍会按上一次的 `playing=true` 继续推；需要一次上机确认它到底走哪条。
6. **`U1`（iOS 换歌到底推不推新封面）仍未决**：本次改动让"推了但 id 对不上"的情况也能显示；若第 2 步每首歌都完全没有 `artwork decoded` 行，那就是 iOS 没推，不是我们的问题。


---

## 更正（2026-10-10 实车）：`ecarx.openapi.impl.jar` **存在**

本文 §8.3 曾推断"本车很可能没有 `/system/framework/ecarx.openapi.impl.jar`，因此 EasyPlay 的 `LegacyLynkMediaRefresh`（通路 A 的 AIDL 直连）在本车根本没构造成功"。**实车 `ls -l /system/framework/ecarx*.jar` 证明该推断错误**——该 jar **确实存在**（74 KB），同目录还有 `ecarx.adapterapi.jar`、`ecarx.cmaplatform.api.jar`、`ecarx.cmaplatform.service.jar`、`ecarx.jar`、`ecarx.policy.jar`、`ecarx.vr.impl.jar`。

因此：
- **不能**用"那条路在本车不存在"来否定 doc 46 的 G6（直连 `INavigationDI.turnByTurn(Turn)`）；它是**可用的兜底方案**。
- 同时也说明我们**不能**据"EasyPlay 能显示"就推断它走的是哪条路——两条路在本车都可能成立。

---

## 更正与补充（2026-10-10 实车）：封面的**交付顺序反了**

本文 §7.6/G5 与 §9.2 记的是"参照实现的顺序：JPEG/256 → `content://`+grant → 回环 HTTP（第三级）"。**本车实测与它相反**：

- **2026-10-09 实车**：我们按 `content://` + `grantUriPermission` 交付了**三张不同的封面**（日志 `cluster artwork grants=2 provider=true`，厂商侧 `albumCover2` 收到每一个新 Uri），**仪表一张都没显示**；其中一次是**全新进程 + 全新 CarPlay 会话（重配后）+ 全新卡片**，仍然不显示 ⇒ 排除"只在建卡时读一次"，指向"**取图方读不了 `content://`**"。
- **2026-10-08 实车**（当时实现）：用 `getExternalFilesDir(null)` 里的 **`file://` + `setReadable(true, false)`**，**仪表显示了封面**（本文 §1 的厂商日志与用户观察都可证）。

⇒ 现行实现改为：**`file://` 为主**（写到外部 files 目录、读回成功即交付，并删掉 cache 副本），**`content://` 降为兜底**（外部树不可写/读不回时）。编码规则（JPEG 88→62→45、最长边 ≤256px、1..1MiB、源限制、每张新 UUID）与 retention（上限 4）未变。

**上机判据（已同步到 doc 28 §9.1）**：每首有封面的歌应出现 `cluster artwork tier=file chmod=… bytes=… uri=file://…`；**不应**出现 `cluster artwork fallback …`；厂商侧 `albumCover2 = file://…`。若日志已是 `file://` 而仪表仍不显示 ⇒ 连 `file://` 也不认，才需要考虑第三级回环 HTTP（本次故意不做）。

---

## 更正（2026-10-10）：§2.4 的两档不是"先后"，而是**并列**

逆向复核（见 `52-HyLink与EasyPlay的封面交付逆向.md`）证明：参照实现的 **content 档与 HTTP 档是两条并列通道**，不是串行优先关系——`LegacyArtworkLoader.java:299-321` 管 content 档（并对 `OEM_READERS = {ecarx.xsf.mediacenter, com.ecarx.dim_interaction}` 授权），`ArtworkUriTransport` 管 HTTP 档，由 `LegacyEcarxMediaCenter.java:2143-2147` 的机型门禁决定用哪条：

```java
usesSinglePassArtwork(sdk, manufacturer, model, modernSdk)
    = sdk == 19 && Freescale && CS11 && !modernSdk      // 正是本车指纹
```

命中时 `ArtworkUriTransport.uriFor` **只返回 HTTP**（`else { uri = source; }`），服务端 `LegacyArtworkHttpServer:156 bind(127.0.0.1, 0, backlog 4)`、`:189 /covers/<session>/<uuid>.jpg`、`:198 http://127.0.0.1:<port><path>`。

**推论**：在 CS11 这一类机器上，参照实现**主动选择**回环 HTTP 交付封面 ⇒ 这是本机构型上被验证过的机制，我们已实现的 HTTP 首档方向正确（URL 形状与它一致）。
