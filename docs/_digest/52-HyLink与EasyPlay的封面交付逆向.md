# 52 · HyLink 与 EasyPlay 的封面交付逆向（仪表专辑封面为什么不出图）

> 目标：从源码回答"HyLink（`com.hyphp.playkeytool`）究竟怎么把正在播放的媒体与专辑封面送到 ECARX 仪表"，
> 并据此给出我们（`CarPlay-CS11`）**能停止猜测**的实现方案。
>
> 本次调查是只读的：未修改 `CarPlay-CS11`，未提交。
>
> 证据来源：
> - `_analysis/hylink_src/UP_unpacked/sources/`（jadx 全量反编译，含 obfuscation，来自 `.../apk_unpacked/dex/classes2.dex`）
> - `_analysis/hylink_src/PKT_single/media.java`、`PKT_single2/DimPlaybackInfo3.java`、`PKT_acp/ArtworkContentProvider.java`
>   （本轮用 `jadx --single-class` 从**原始 `classes2.dex`** 重新抽取，去掉了 `UP_unpacked` 因输出截断丢掉的构造参数）
> - `_analysis/hylink_src/A_26091401/resources/AndroidManifest.xml`（真实清单，非 binary）
> - `_analysis/cs11_vendor/api/jadx/sources/com/ecarx/xui/adaptapi/diminteraction/IMediaInteraction.java`
> - `_analysis/easyplay/v71/sources/`（EasyPlay v71 反编译）
> - 我们自己的 `CarPlay-CS11/shared/src/main/java/com/shilapi/xcertplay/media/*`
>
> 相关的既有文档：**47 号**（HYLink 仪表路径逆向）中"专辑封面走 `BitmapParcelable` binder"的结论**本轮被否证**，见 §7。

---

## 0. 一句话结论

**HyLink 用的就是我们已经在用的那条 API（`DimInteraction.create().getMediaInteraction().updatePlaybackInfo(IPlaybackInfo)`），
它交给仪表的封面也只是一个 `Uri`，而且那个 `Uri` 只有两种形态：`content://com.hyphp.playkeytool.artwork/<文件名>`
（默认，走一个 `exported=true` 的 `ArtworkContentProvider`）或 `file://`（另一档）。
HyLink 源码里**存在**一条 HTTP 图床通道的常量与死代码，但**从未真正启用**；真正在 CS11 上被启用 HTTP 图床的是 EasyPlay
（`usesSinglePassArtwork()` 字面量就是 `sdk==19 && Freescale && CS11`）。

因此"我们该抄的机制"只有一条：**让封面以一个对手方进程能主动 `GET` 的 URL（`http://127.0.0.1:<port>/...`）交付**，
而不是一个 `content://`/`file://` 字符串。我们代码里 `ClusterArtworkHttpServer` 已经有这一档，且已经是首选档——
所以本轮的产出不是"新写一个图床"，而是**把这一档变成唯一档、并堵掉让它失效的三个实现缺陷**（§6）。**

---

## 1. Q1 · HyLink 用哪条 API 把媒体发到仪表（端到端）

**答：`com.ecarx.xui.adaptapi.diminteraction` 这条，与我们完全相同。不是 `eas.mediacenter` SDK 那条写路径。**

### 1.1 导入与绑定

`_analysis/hylink_src/UP_unpacked/sources/com/hyphp/sdk/media.java`

```java
41: import com.ecarx.xui.adaptapi.diminteraction.DimInteractionImpl;   // L41
43: import com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction;    // L43
...
82:     private DimPlaybackInfo3 iPlaybackInfo;                          // L82
90:     private DimInteractionImpl mDimInteractionImpl;                  // L90
104:    private com.ecarx.sdk.mediacenter.MediaCenterAPI mMediaCenterAPI; // L104（旁路，见 §1.5）
105:    private IMediaInteraction mMediaInteractionImpl;                  // L105
```

构造（两处，按机型分支）：

```java
12487:  this.mDimInteractionImpl = new DimInteractionImpl(context);   // media.java:12487
14597:  this.mDimInteractionImpl = new DimInteractionImpl(context);   // media.java:14597
```

取接口 + 发布（这是**唯一的发布点**）：

```java
// media.java:19402-19424  取 IMediaInteraction
19402: public static IMediaInteraction m1259(Object obj) {
19424:     IMediaInteraction mediaInteraction = ((DimInteractionImpl) obj).getMediaInteraction();

// media.java:22665-22703  真正的发布调用
22665: public static void m1294(Object obj, Object obj2) {
22683:     ((IMediaInteraction) obj).updatePlaybackInfo((IMediaInteraction.IPlaybackInfo) obj2);
```

同一批静态包装里还有（顺序与我们一致：source → progress）：

```java
// media.java:18299
18299:  ((IMediaInteraction) obj).updateCurrentSourceType(i);
// media.java:17331
17331:  ((IMediaInteraction) obj).updateCurrentProgress(j);
```

### 1.2 binder/服务组件

**DIM 这条路径不 bind 我们自己指定的 component**：`DimInteractionImpl` 在
`/system/framework/ecarx.adapterapi.jar`（boot classpath，`docs/_digest/46`）里，服务端是厂商的
`com.ecarx.dim_interaction`（其 provider 日志 `albumCover2 = …` 就是我们的 handover 证据，`docs/_digest/48 §1`）。

`ecarx.xsf.mediacenter` / `MediaCenterService` 是**另一条**（见 §1.5）：

```java
// com/ecarx/eas/sdk/mediacenter/MediaCenterAPIImpl.java:87（同 e.java:77）
87:  intent.setComponent(new ComponentName("ecarx.xsf.mediacenter", "ecarx.xsf.mediacenter.MediaCenterService"));
```

### 1.3 顺序

```
DimInteractionImpl(context)                                   media.java:12487 / 14597
  └─ getMediaInteraction() : IMediaInteraction                media.java:19424
       ├─ updateCurrentSourceType(int)      （SOURCE_TYPE_*）  media.java:18299
       ├─ updatePlaybackInfo(IPlaybackInfo)                   media.java:22683
       │     IPlaybackInfo 的实现类 = DimPlaybackInfo3        DimPlaybackInfo3.java:12
       └─ updateCurrentProgress(long)                         media.java:17331
```

### 1.4 `IPlaybackInfo` 的实现类与构造

```java
// com/hyphp/sdk/DimPlaybackInfo3.java
 12: public class DimPlaybackInfo3 implements IMediaInteraction.IPlaybackInfo {
 36: public DimPlaybackInfo3(String str, String str2, String str3, long j, Uri uri) {   // ← 第 5 个参数是 Uri
```

（`PKT_single2/DimPlaybackInfo3.java:36` 是同一签名，且**带参数类型**；`UP_unpacked` 那一份因输出截断只剩 `(…, null)`。）

构造调用点，全部是同一个形状：

```java
// PKT_single/media.java:31459（对应 UP_unpacked 的 31460）
DimPlaybackInfo3 dimPlaybackInfo5 = new DimPlaybackInfo3(m1296(this), m1268(this), m1234(this), m1262(this), m1245(this));
//                                                                  title        artist       album        duration   Uri
```

`m1245(this)` 的实体是 `getMusicImageUri(...)`：

```java
// PKT_single/media.java:22578-22657
22578: public static Uri m1291(Object obj, Object obj2) {
22625:     musicImageUri = ((media) obj).getMusicImageUri((String) obj2);
```

### 1.5 HyLink 另外还走了一条 `ecarx.xsf.mediacenter` 的旁路（**我们没有**）

HyLink **同时**把"我等下要播什么"登记进车机自己的媒体中心，方法是
`registerMusic(clientId, MusicClient)` + `updateMusicPlaybackState(source, MusicPlaybackInfo)`，
而且在两个 SDK 分支里各写一遍（旧 `com.ecarx.sdk.mediacenter` 与新 `com.ecarx.eas.sdk.mediacenter`）：

```java
// media.java:10917 / 14169
objRegisterMusic = ((MediaCenterAPI) obj).registerMusic((String) obj2, (MusicClient) obj3);
// media.java:11174 / 13641 / 17658 / 18904
zUpdateMusicPlaybackState = ((MediaCenterAPI) obj).updateMusicPlaybackState(obj2, (MusicPlaybackInfo) obj3);
// media.java:10815 / 13717 / 19731 / 21266
((MediaCenterAPI) obj).updateCurrentSourceType(obj2, i);
```

走哪一支由服务端 metadata 决定：

```java
// com/ecarx/eas/sdk/mediacenter/MediaCenterProxy.java:71
71: return getMetaDataInt(this.mCtx, "ecarx.xsf.mediacenter", "EAS_SUPPORT") == 0 ? ClientType.OpenAPI : ClientType.EASFramework;
```

这几处 `com/ecarx/eas/sdk/mediacenter/control/MediaControllerAPIImpl.java:45/90` 与 `control/c.java:43/91`
（用户给的线索）是 **EAS SDK 的读侧封装**：把服务端的 `op` 结构转成 `Media`/`MusicPlaybackInfo` bean
（`MediaControllerAPIImpl.convertMediaList` / `convertMusicPlaybackInfo`），**不是** app 的写路径。
它们证明 bean 里确实有 `artWork`/`artwork` 字段，但不构成交付机制。

---

## 2. Q2 · 封面是怎么交出去的（本任务最关键的一问）

**答：永远是 `android.net.Uri`，永远是 `IPlaybackInfo.getArtwork()` 返回它。没有任何 `Bitmap`/`byte[]`/`ParcelFileDescriptor` 出现在交付路径上。**

交给仪表的那个 `Uri` 的 scheme **只有两个可能**：`content` 或 `file`（§2.2 的分档）。
注意一处极易误读的地方：常量名 `MEDIA_IMAGE_GET_TYPE_HTTP = 1` 的那一档，实际产出的是
`ArtworkContentProvider.createUri(...)`，也就是 **`content://`**，不是 `http://`。
HyLink 里确实存在 HTTP 通道的常量与代码（`MUSIC_IMAGE_HTTP_BASE_URL`、`getMusicImageUrl`、整个 `NanoHTTPD`），
但它们**从不产出交付给仪表的 Uri**（§2.5）。

### 2.1 每一处设置/返回封面的地方

| 位置 | 代码 | 说明 |
|---|---|---|
| `DimPlaybackInfo3.java:17` | `private Uri artWork;` | 唯一存储字段 |
| `DimPlaybackInfo3.java:1687` | `public Uri getArtwork() { return m275(this); }` | `IPlaybackInfo.getArtwork()` 的实现，返回 `artWork` |
| `DimPlaybackInfo3.java:1821` | `public void setArtWork(Uri uri) { … this.artWork = uri; … }` | 唯一写入 |
| `DimPlaybackInfo3.java:1715/1727` | `getNextArtwork()` / `getPreviousArtwork()` 返回 **null 字面量** | **HyLink 也不给下一首/上一首封面** |
| `media.java:22626` | `musicImageUri = ((media) obj).getMusicImageUri(str);` | `m1245` 的来源 |
| `media.java:3643` | `Uri uriCreateUri = ue0.z6() <= 0 ? ArtworkContentProvider.createUri(m1282(this), str) : null;` | `getMusicImageUri` 的 content 分支 |
| `media.java:3655` | `uriFromFile = ue0.Y() > 0 ? Uri.fromFile(file2) : null;` | `getMusicImageUri` 的 file 分支 |
| `media.java:3706` | `uriE6 = ue0.e6(strA3);` | `getMusicImageFileUri` 变体 |
| `media.java:23520-23615` | `public Uri getArtworkContentUri(byte[] bArr) { … ArtworkContentProvider.createUri(m1282(this), strM1250) … }` | 另一条生成路径（同样的 provider） |
| `media.java:33382` | `this.nowArtworkUrl = m1235(this, m1248(this, m1243(this, bArr)));` | **URL 字符串**，只写进 `nowArtworkUrl` |
| `media.java:17996` | `str2 = ((media) obj).nowArtworkUrl;`（`m1243` 的实体） | `nowArtworkUrl` 的**唯一读取处** |
| `media.java:16929` | `C0105.m1564(intent, ue0.G3(…, 1143, …), m1243(this));` | 那个唯一读取处：给**唤起手机播放器的 Intent** 塞 extra |

### 2.2 `getMusicImageUri` 的三档开关（这就是"分档"的全部）

```java
// media.java:67-78  常量
71: public static final int MEDIA_IMAGE_GET_TYPE_CONTENT = 0;
72: public static final int MEDIA_IMAGE_GET_TYPE_FILE = 2;
73: public static final int MEDIA_IMAGE_GET_TYPE_HTTP = 1;
```

```java
// com/hyphp/sdk/C0105.java:480-485
480: public static int m1597() {
481:     if (ue0.h3() <= 0) {
482:         return Function.switchMediaImageGetType;
483:     }
484:     return 0;
485: }
```

```java
// media.java:3714-3740   getMusicImageUri 的分档（分支目标见下）
3715: int iM1597 = C0105.m1597();
3716: if (iM1597 == 1) { … }        // 命中的分支跳到 3643  ArtworkContentProvider.createUri(...)  ⇒ content://
3723: else if (iM1597 == 2) { … }   // 命中的分支跳到 3746  new File(m1292(this), str) + Uri.fromFile ⇒ file://
```

> **本清单最容易误读的一处（请以此为准）**：`MEDIA_IMAGE_GET_TYPE_HTTP = 1` 的**名字**说 HTTP，
> 但它命中的分支里 `ArtworkContentProvider.createUri`（`media.java:3643`）是**唯一**的 Uri 构造器，
> 产物是 `content://`；`MEDIA_IMAGE_GET_TYPE_FILE = 2` 命中的分支里 `new File(m1292(this), str)`（`media.java:3746`）
> 配 `Uri.fromFile(file2)`（`media.java:3655`），产物是 `file://`。
>
> **诚实边界**：这两个分支内部的 `switch (iM1597)` 是被混淆过的 dispatch，
> jadx 只解出了 `case 1747686`（`media.java:3642`，即 `createUri`）一个标签，
> `1754592` / `1751682` 等目标标签在 `getMusicImageUri` 的 switch 里**不存在**（说明它们由外层别的分支抵达）。
> 因此"哪个偏好值精确对应哪一支"**本轮没有完全解出**，需要时再看 smali。
> **但这不影响本任务的结论**：`getMusicImageUri` 全函数体（`media.java:3599-3811`）里只有
> `ArtworkContentProvider.createUri(...)`（`:3643`）与 `Uri.fromFile(...)`（`:3655`）两个 Uri 构造点，
> **没有任何一处构造 `http://` Uri**。所以那条去仪表的 Uri 不可能是 HTTP。
>
> 反过来说，"HyLink 用 HTTP 交付封面"这个说法若要成立，唯一的证据形式是**有代码真的起了 HTTP 服务**——
> 那由 §2.5 的实例化搜索回答，而答案是否定的。

而 `DIM` 交付那条（`m1245` → `getMusicImageUri`）拿到的 `Uri`，**永远**来自这两支之一：
`ArtworkContentProvider.createUri(...)` 或 `Uri.fromFile(...)`。

### 2.3 CS11 上默认是哪一档

```java
// a/j90.java:176-210  机型 "head_lynk_cs11" / "model_*_cs11" 的首次初始化
180: SettingsActivity settingsActivity4 = r5;
191: linkedHashMap.clear();
192: linkedHashMap.put("switchLynkcoSiri", "1");
193: linkedHashMap.put("switchMediaShowImage", "1");
194: linkedHashMap.put("switchMediaImageGetType", "2");     // ← CS11 硬编码为 2
195: linkedHashMap.put("rangeMediaImgZip", "80");
196: linkedHashMap.put("rangeMediaImgZipSize", "200");
197: linkedHashMap.put("rangeCS11VoiceSleeps", "500");
198: linkedHashMap.put("switchDayNight1", "1");
199: str2 = "hysettings";
```
（同一文件对其他机型的默认映射里**没有** `switchMediaImageGetType`，只有 CS11 系列写了这一条。）

⇒ **在 CS11 上偏好值确定为 `2`**（`Function.switchMediaImageGetType == 2`，`C0105.m1597()` 返回它）。

**但"`2` 这一档的产物是什么"本轮没有解出**（§2.2 的诚实边界）：可能落在 `file://` 分支
（`new File(m1292(this), str)` + `Uri.fromFile`，`media.java:3746 / 3655`），也可能落回 `createUri`。
从命名（`MEDIA_IMAGE_GET_TYPE_FILE = 2`）看，最可能是 `file://`；从 `createUri` 的存在与
`ArtworkContentProvider` 在清单里被 `exported=true` 注册看，content 档在别的机型上是主路径。
**这一点对方案不构成阻塞**：两支都不产生 `http://`，而"仪表只画 HTTP"这条假设的处置在 §6 已经给出
（先确证我们的回环 URL 有没有被 `GET`，再决定是否去重放 HyLink 的 content 形状）。

`m1292` 的实体是 `getMusicImageDir()`（`media.java:22487`，入口）——其返回目录的候选链见 §6.4 C2。

### 2.4 `ArtworkContentProvider`：HyLink 交付封面的"正规档"

清单里**明确注册且 `exported=true`**：

```xml
<!-- _analysis/hylink_src/A_26091401/resources/AndroidManifest.xml:147-151 -->
147: <provider
148:     android:name="com.hyphp.sdk.ArtworkContentProvider"
149:     android:exported="true"
150:     android:authorities="com.hyphp.playkeytool.artwork"
151:     android:grantUriPermissions="true"/>
```

URI 形状（`ArtworkContentProvider.createUri`，`PKT_acp/ArtworkContentProvider.java:199-296`）：

```java
252: Uri.Builder builderAppendPath = … C0097.m163(new Uri.Builder(), <"content">)
253:        .authority( ue0.a3(ue0.L5(ue0.L5(new StringBuilder(), ue0.w0(context)), <AUTHORITY_SUFFIX>)) )   // getPackageName() + suffix
254:        .appendPath(str)                                                                                   // 文件名
```
⇒ `content://<getPackageName()>.artwork/<文件名>`，**路径只有一段**（不是我们那种
`covers/<session>/<uuid>.jpg` 四段）。

静态常量（同一文件 `static{}`）：

```java
117: AUTHORITY_SUFFIX = ue0.X6(m249(), 9, ue0.e ^ (-352), 2550);
123: ARTWORK_DIRECTORY_NAME = ue0.J4(m249(), 0, C0101.f52 ^ (-412), 761);
```

`ue0.X6/J4` 的实体是逐字符 XOR（`a/ue0.java:1533` → `C0100.m137(short[],int,int,int)`，
`com/hyphp/carplaystart/carplaystart/C0100.java:97-103`）：

```java
 97: public static String m137(short[] sArr, int i, int i2, int i3) {
 99:     for (int i4 = 0; i4 < i2; i4++) {
100:         cArr[i4] = (char) (sArr[i + i4] ^ i3);
```

把 `f7short`（该文件 `static{}` 第 113 行的 hex）按 `(low8, high8)` 逐字节小端重排后逐字符 XOR，得到：

| 常量 | 解码值 | 依据 |
|---|---|---|
| `AUTHORITY_SUFFIX` | `"." + "artwork"`（终止符前 8 字节） | 字节 18+k XOR `0x20` → `"."`,`a`,`r`,`t`,`w`,`o`,`r`,`k`,0 |
| `ARTWORK_DIRECTORY_NAME` | `"musicimage"`（10 字符，短数组元素少 1 ⇒ 有终止符，长度吻合） | 字节 0..9 XOR `0x20` → `m`,`u`,`s`,`i`,`c`,`i`,`m`,`a`,`g`,`e` |

⇒ provider 从 `getArtworkDirectory(context)` 取目录，`openFile` 以只读 `ParcelFileDescriptor` 交给读取方：

```java
// PKT_acp/ArtworkContentProvider.java
 876: file2 = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
 878:                 <ARTWORK_DIRECTORY_NAME>)                                  // …/Pictures/musicimage
1066: public File requireArtworkFile(Uri uri) throws FileNotFoundException
1085: fileM253 = m253(this, uri);
1887: File fileM251 = m251(this, uri);
1890: return ParcelFileDescriptor.open(fileM251, i);   // i = (-268435646) ^ static-int == MODE_READ_ONLY
1894: if (!ue0.Z1(str, <"r">)) throw new FileNotFoundException(...)   // 只接受 "r"
```
（注：`getArtworkDirectory` 是 `Environment.DIRECTORY_PICTURES` 起步，但 `media.java:3746` 给 `getMusicImageDir()`
留了 `/sdcard/Music/mediacenter/images/` 一类的兜底——具体落点是本清单里**唯一不敢下结论**的一处，见 §6 的确认项 C1。）

### 2.5 HyLink 有没有跑 socket / HTTP 服务？（决定性否定）

全树枚举：

```powershell
# 对 classes2.dex 的原始 ASCII 字符串表（84778 条）搜索
'127.0.0.1'   -> 0 命中
'localhost'   -> 0 命中
'http://127'  -> 0 命中
'http://'     -> 只有 NanoHTTPD 库自身的字面量与 'http://aufnplay.com/auth'
```

代码侧：`com/hyphp/sdk/NanoHTTPD.java` 是完整的旧版 NanoHTTPD（自带 `main(String[])`），
**唯一被 `new` 的地方是它自己的 `main`**，而 `main` 只被一处调用：

```java
// media.java:1982-1993
1982: case 1750783:
1983:     String[] strArr = {…, ue0.J(fileM1292)};
1984:     if (ue0.D2() >= 0) {
1985:         NanoHTTPD.main(strArr);
```

即：没有第二处实例化，且这一处在一个混淆分支里。DEX 里也**没有**任何监听端口 / 绑定地址的字面量。

⇒ **结论（源码级确定）：HyLink 在车上不开回环 HTTP 图床。它交给仪表的是 `content://…`（或 `file://…`）字符串。**
`MUSIC_IMAGE_HTTP_BASE_URL`（`media.java:75, 1783`）与 `getMusicImageUrl`（`media.java:3813-3850`）是真代码，
但产物只落进 `nowArtworkUrl`，而 `nowArtworkUrl` 的唯一读取处是给"唤起手机播放器"的 Intent 塞 extra（`media.java:16929`）。
**对仪表封面没有任何贡献。**

---

## 3. Q3 · HyLink 做了哪些我们没做的事（逐字段对照）

### 3.1 `IPlaybackInfo` 字段表

`IPlaybackInfo` 的完整签名见 `_analysis/cs11_vendor/api/jadx/sources/com/ecarx/xui/adaptapi/diminteraction/IMediaInteraction.java:63-122`（18 个 getter）。

| `IPlaybackInfo` 成员 | HyLink（`DimPlaybackInfo3`） | 我们（`VendorClusterBridge.kt` 代理） | 差异 |
|---|---|---|---|
| `getTitle` | `m286` → `title` | 传出 | 无 |
| `getArtist` | `m287` → `artist` | 传出 | 无 |
| `getAlbum` | `m273` → `album` | 传出 | 无 |
| `getDuration` | `m282` → `duration` | 传出 | 无 |
| **`getArtwork`** | **`m275` → `artWork`，来自 `getMusicImageUri` = `content://<pkg>.artwork/<name>`（CS11 为 `file://<getMusicImageDir()>/<name>`）** | **`ClusterArtworkStore.publish()`：首选 `http://127.0.0.1:<port>/covers/<session>/<uuid>.jpg`，回退 `file:///sdcard/DiPlay/…`，再回退 `content://<applicationId>.artwork/covers/<session>/<uuid>.jpg`** | **机制不同**：我们**已经**有 HTTP 档，HyLink 没有；但我们的 content 档形状与它不同（见 3.2） |
| `getNextArtwork` / `getPreviousArtwork` | **显式 `return null`**（`DimPlaybackInfo3.java:1715-1717 / 1727`） | 未实现（代理默认 null） | 无 |
| `getPlaybackStatus` | `m274`？→ `playbackState` | 传出（`PLAYBACK_STATUS_PLAYING/PAUSED`） | 无 |
| `getSourceType` | `sourceType` 字段 | 固定 `SOURCE_TYPE_ONLINE = 6` | 无（`VendorClusterBridge.kt:61,501`） |
| `getLoopMode` / `getRadioMode` / `getRadioFrequency` / `getRadioStationName` | 有字段，音乐场景基本为空 | 未实现 | 与封面无关 |
| `getMediaPath` | `mediaPath` Uri | 未实现 | 见 §6 的候选 D1 |
| `getLyric` / `getLyricContent` / `getCurrentLyricSentence` | 有 | 未实现 | 与封面无关 |
| `getPlayingItemPositionInQueue` | `positionInQueue` | 未实现 | 与封面无关 |
| `getUUID()`（非接口方法） | **`return null`**（`DimPlaybackInfo3.java:1751-1753`） | — | 无 |

补充：HyLink 也**从不**调用 `updatePlaylist(int, List<IMedia>)` 或 `setMediaInteractionCallback(...)` 来送封面；
`IMediaInteractionCallback.onMediaSelected(IMedia)` / `onMediaHighlighted(IMedia)` 在 `media.java` 里没有对应的发布实现。
**所以"注册媒体列表 / 回调"不是封面出图的前置条件。**

### 3.2 封面这条的实质差异（三条）

1. **URI 形状**：HyLink `content://<pkg>.artwork/<文件名>` 只一段路径；我们
   `content://<pkg>.artwork/covers/<session>/<uuid>.jpg` 三段。
2. **树的位置**：HyLink 落在 `Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES)/musicimage`
   起步的目录链（`ArtworkContentProvider.java:876-878`，外加 `getMusicImageDir()` 的候选）；
   我们落在 `<cacheDir>/cluster-artwork/<session>/`（provider 档）或 `/sdcard/DiPlay/cluster-artwork/<session>/`（file/HTTP 档）。
3. **旁路登记**：HyLink 还调 `registerMusic` + `updateMusicPlaybackState` 把自己登记进
   `ecarx.xsf.mediacenter`（§1.5）。**我们完全不碰媒体中心。**

### 3.3 已否证的假设

- ❌ `docs/_digest/47 §4/§9` 的"专辑封面走 `BitmapParcelable` binder"：`BitmapParcelable`
  （`com/hyphp/CarplayApi/BitmapParcelable.java:9-52`）**只在 `updatePhoneCallInfo(int, BitmapParcelable, …)`
  里使用**（`com/hyphp/CarplayApi/IApi.java:221, 1003, 1180, 1452`），是**来电头像**，与专辑封面无关。
  专辑封面自始至终是 `Uri`。
- ❌ "HyLink 有 HTTP 图床所以我们也该有"：HyLink 的 HTTP 图床是死代码（§2.5）；
  **有** HTTP 图床的是 EasyPlay，且它是 CS11 专属（§4）。
- ❌ `com/ecarx/eas/sdk/mediacenter/control/MediaControllerAPIImpl.java` / `control/c.java` 是写路径：
  它们是**读侧** bean 转换器（`convertMediaList` / `convertMusicPlaybackInfo`）。

---

## 4. Q4 · HyLink 在这台车上到底出不出封面？

**答：源码里无法证明它出图，也无法证明它不出图；但源码能证明它不是靠"特殊 API"出图的。**

能说的（源码级）：
- 交付通道与我们**完全同一条**（§1），字段集合**完全一样**（§3.1）；
- 它的封面**只能是** `content://…` 或 `file://…`（§2.2 / §2.5）；
- CS11 的机型默认把 `switchMediaImageGetType` 设为 `2`（`a/j90.java:194`），走 `file://` 分支；
- 它**没有**任何"仅 ECARX 平台才启用"的封面特性开关——只有上面那个通用偏好。
- 它**没有** `Build.MODEL`/`Build.MANUFACTURER` 的封面分支。（对照：EasyPlay **有**，见下。）

不能说的：HyLink 在这台车上的实际出图行为。源码里没有日志、没有开关状态、没有 ECARX 专用分支能回答它。

⇒ **因此不能把"HyLink 的 content:// 档是不是有效"当成设计依据。**
唯一被**实测**证明过能出图的形态，是车机自带媒体应用给出的 `http://y.gtimg.cn/...`（`docs/_digest/45 §2.4`、
`docs/_digest/28 §9.11-9.17`），以及 EasyPlay 针对 CS11 才打开的 `http://127.0.0.1:<port>/...`。

---

## 5. Q5 · 参照实现（EasyPlay v71）的 HTTP 档：核实 / 否证 docs 45 §2.4

**结论：docs 45 §2.4 的记述成立，并且我把它精确到了行。** 补充一点 45 号没写清的：HTTP 档是**机型硬门禁**。

### 5.1 门禁字面量

```java
// _analysis/easyplay/v71/sources/com/shilapi/xcertplay/legacy/LegacyEcarxMediaCenter.java:2143-2147
2143: public final boolean usesSinglePassArtwork(int sdk, String manufacturer, String model, boolean modernSdk) {
2146:     return sdk == 19 && StringsKt.equals(manufacturer, "Freescale", true)
2147:            && StringsKt.equals(model, "CS11", true) && !modernSdk;
```

启用（同一文件）：

```java
// LegacyEcarxMediaCenter.java:1354-1358
1354: String MANUFACTURER = Build.MANUFACTURER;
1356: String MODEL = Build.MODEL;
1358: this.artworkTransport = new ArtworkUriTransport(context2,
1358:        companion.usesSinglePassArtwork(i, MANUFACTURER, MODEL, this.modernCallbackType != null), onDiagnostic, null, 8, null);
```

`ArtworkUriTransport.uriFor`（`LegacyEcarxMediaCenter.java:1223-1257`）：

```java
1228: if (this.enabled) {
1230:     File fileCoverFile$legacy = LegacyArtworkProvider.INSTANCE.coverFile$legacy(this.context, source);
1232:     LegacyArtworkHttpServer legacyArtworkHttpServer = this.server;
1233:     if (legacyArtworkHttpServer == null) { legacyArtworkHttpServer = this.createServer.invoke(); … }
1237:     uriUriFor = legacyArtworkHttpServer.uriFor(fileCoverFile$legacy);
1251: } else {
1252:     uriUriFor = source;          // ← 非 CS11：原样返回 content:// Uri，不开服务
```

### 5.2 端口 / 路径 / 谁取图

```java
// LegacyArtworkHttpServer.java
156: serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);   // 回环 + 临时端口 + backlog 4
189: cover = new Cover(canonicalFile, "/covers/" + this.session + "/" + UUID.randomUUID() + ".jpg");
198: uri = Uri.parse("http://127.0.0.1:" + serverSocket.getLocalPort() + cover.getPath());
```
谁取图：**车机侧**（DIM 服务/仪表），EasyPlay 只负责应答一次（"single pass"）：
`respond()`（:340-383）读一行请求、匹配 `cover.getPath()`、`readCover(file)` 后 `200` + `Content-Type: image/jpeg`
（`reply()` :477-487）；`HEAD` 也接受，其他方法 `405`。

**并且 EasyPlay 同时保留了 `content://` 档**：

```java
// LegacyArtworkLoader.java:299 / 359
299: uri = new Uri.Builder().scheme("content").authority(this.context.getPackageName() + ".artwork")
299:        .appendPath("covers").appendPath(this.session).appendPath(file.getName()).build();
301: for (String reader : OEM_READERS) { this.context.grantUriPermission(reader, uri, 1); … }
// :57
 57: OEM_READERS = { "ecarx.xsf.mediacenter", "com.ecarx.dim_interaction" }
```
（`LegacyArtworkLoader` 的 `content://` 形状与我们的**完全一致**：`covers/<session>/<uuid>.jpg`。）

⇒ docs 45 §2.4 修正版：
- HTTP 档存在、CS11 专属、`127.0.0.1` + 临时端口、路径 `/covers/<session>/<uuid>.jpg` —— **成立**；
- 但 45 号把顺序写成"先 provider+grant，再 HTTP"，源码里 `ArtworkUriTransport` 在 `enabled` 时是
  **只走 HTTP**（`if (this.enabled) {…} else { uriUriFor = source; }`，:1228-1253），**不是**"先 content 失败再 HTTP"。
  两条档是在**不同代码路径**上（`LegacyArtworkLoader` 负责 content 档，`ArtworkUriTransport` 负责 HTTP 档），
  45 号把它们串成了"先后顺序"，这一点应更正为"**并列的两条交付通道**"。

---

## 6. Q6 · 我们的实现方案

### 6.0 先厘清现状：**我们的 HTTP 档已经写了，而且已经是首选档**

```kotlin
// shared/.../media/ClusterArtworkHttpServer.kt
 29: private const val CLUSTER_ARTWORK_HTTP_LOOPBACK = "127.0.0.1"
 75: private fun loopbackSocket(): ServerSocket = ServerSocket().apply {
 77:     bind(InetSocketAddress(InetAddress.getByName(CLUSTER_ARTWORK_HTTP_LOOPBACK), 0), CLUSTER_ARTWORK_HTTP_BACKLOG)
 316: fun uriFor(disk: File): String =
 317:     "$CLUSTER_ARTWORK_HTTP_BASE:$port/$CLUSTER_ARTWORK_HTTP_ROOT/" +
 318:         "${disk.parentFile?.name.orEmpty()}/${disk.name}"
```

```kotlin
// shared/.../media/ClusterArtworkTransport.kt:617-627
617: internal fun clusterArtworkTier(
618:     httpStarted: Boolean, fileUsable: Boolean, providerRegistered: Boolean, granted: Int,
622: ): ClusterArtworkTier? = when {
623:     httpStarted -> ClusterArtworkTier.HTTP
624:     fileUsable -> ClusterArtworkTier.FILE
625:     providerRegistered && granted > 0 -> ClusterArtworkTier.CONTENT
626:     else -> null
```
```kotlin
// shared/.../media/ClusterArtworkTransport.kt:1119-1132（publish）
1119: val serving = serverOrNull(directory)
1120: if (serving != null) {
1125:     val url = serving.uriFor(target)
1129:     Log.i(TAG, "cluster artwork tier=http port=${serving.port} bytes=${target.length()} uri=$url …")
1132:     return Uri.parse(url)
```

`common/src/main/AndroidManifest.xml:41-45` 也已经把 `ClusterArtworkProvider` 注册为
`exported=true` + `grantUriPermissions=true`，authority `${applicationId}.artwork` ——
**与 HyLink/EasyPlay 的 provider 档同构**。

⇒ 所以本轮不需要"新造一个机制"。要做的是**把唯一被证明的机制做对**，并堵掉三个会让它失效的实现缺陷。

### 6.1 单一机制（该抄的那一条）

> **交给仪表的 `IPlaybackInfo.getArtwork()` 必须是一个对手方进程能主动 `GET` 的 `http://127.0.0.1:<port>/…` URL，
> 且该 URL 从交付那一刻起直到这一轮会话结束都必须可解析。**

依据：
1. EasyPlay 在 **CS11 上只走这一条**（`LegacyEcarxMediaCenter.java:1228-1253` + `:2143-2147`）；
2. 车机自带媒体应用唯一被观察到能让仪表出图的形态是 `http://y.gtimg.cn/...`（`docs/_digest/45 §2.4`）；
3. 我们的 `content://`（带 grant）与 `file://`（含 `/sdcard/DiPlay/…`，adb 可读）**已交付多次且被厂商服务全部记录、仪表一张未画**
   （`ClusterArtworkTransport.kt:25-29` 的实测表）；
4. HyLink 的实车效果无源码证据（§4），其 HTTP 代码是死代码（§2.5）——**两边都不能反证这一条**，
   所以依据是 1+2+3 三条**实测/机型门禁**，不是"HyLink 也这么做"。

### 6.2 三个会让我们现有 HTTP 档失效的实现缺陷（按怀疑度排序）

**D1（最可疑）· 服务随宿主 Activity 一起被关掉，而仪表的取图是懒的。**

```kotlin
// VendorClusterBridge.kt:333-345（KDoc 自述）
334:  * Tears the bridge down with the host activity: the covers go and the loopback port is released.
338:  * handover is also a URL on a port this process owns, and `onDestroy` is the moment nothing will
339:  * fetch it any more. [ClusterArtworkStore.close] revokes and deletes the retained covers *and*
340:  * closes the listening socket …
344: fun detach() {
```
`ClusterArtworkStore.close()` → `server.shutdown()`（`ClusterArtworkTransport.kt:1191-1194`）会
`serverSocket.close()` 并清空 session 表。CarPlay 会话的生命周期**长于**这个 Activity
（`keepVideoInBackground`/前台服务是既有能力）。若仪表是在**它自己的节奏**里取图（而不是我们 push 的同一下），
那么它拿到的 URL 在 `onDestroy` 后就是 `connection refused` —— 仪表画不出图，而我们的日志里 `tier=http` 一切正常。
**这类失败在 logcat 里完全看不出来。**

**D2 · 同一轮内文件被"移动 + 删除"两次，存在窗口让读到 404。**
`publish()` 先编码到 `<cacheDir>`，再 `deliverToBase` 把同一份字节搬到共享树、并 `runCatching { encoded.delete() }`
（:1124、:1136）。HTTP 档按请求从磁盘读（`ClusterArtworkHttpServer.writeCover` :409-419），
所以"URL 已交给仪表"与"文件在磁盘上"之间必须没有空洞。retention 只保留 4 个（:230），
长会话里旧封面会被 `evict` 删掉，而仪表手上可能还握着那个旧 URL。

**D3 · 端口绑定失败被"静默降级"成 `file://`，而 `file://` 已被证伪。**
`ClusterArtworkHttpServer.start()` 绑定失败时 `Log.w(TAG, "cluster artwork http server unavailable")` 并返回 null
（:231-234），`publish()` 于是交付 `file://`（:1134-1138）。在一台 `file://` 永远不出图的车机上，
"绑定失败"应当是一个**显式错误**，而不是悄悄换成一个已知无效的档。

### 6.3 实施方案（按顺序的精确改动清单）

> 全部改动都在 `CarPlay-CS11`，本任务**未执行**。

1. **`shared/.../media/ClusterArtworkTransport.kt` — `ClusterArtworkTier` 收敛为 HTTP 优先、`file://` 仅作诊断余量。**
   在 `clusterArtworkTier(...)`（:617-627）保持 `httpStarted -> HTTP` 的优先级不变，
   但把 `publish()`（:1111-1160）里的 `file://` 分支加上一条显式告警日志
   （例如 `Log.e(TAG, "cluster artwork http tier unavailable; delivering file:// which this car has never drawn uri=$delivered")`），
   让"降级到已证伪档"在 logcat 里一次可见。**这是唯一一处必须改的判定逻辑。**

2. **`shared/.../media/ClusterArtworkHttpServer.kt` — 让生存期不再依赖宿主 Activity。**
   把 `shutdown()` 的触发从"`VendorClusterBridge.detach()`（宿主 onDestroy）"改成
   "CarPlay 会话结束 / 进程退出"，并让 `detach()` 只 `clear()` 不 `shutdown()`。
   当前调用链：`VendorClusterBridge.kt:344 detach()` → `ClusterArtworkStore.close()`（:1191）→ `server.shutdown()`。
   落地方式二选一（都只需改 `VendorClusterBridge.kt` 的 `detach()` 与 `ClusterArtworkTransport.kt:1191`）：
   - (a) `detach()` 改调 `artworkStore?.clear()`，把 `close()` 留给会话真正结束的路径；或
   - (b) 保留 `close()` 但把 `ClusterArtworkHttpServer` 提升为进程级单例，`shutdown()` 只在
     `ActivityManager` 判定为最后一刻时调用。
   **推荐 (a)**：改动最小，且 `clear()` 已经负责撤销 grant 与删除文件。

3. **`shared/.../media/ClusterArtworkTransport.kt` — 交付期间不得删除正在被 URL 指向的文件。**
   在 `publish()` 里，把 `runCatching { encoded.delete() }`（:1124、:1136）改为
   "只删 `cacheDirectory` 里那份、**绝不删** `target` 指向的共享树文件"，
   并把 `CLUSTER_ARTWORK_MAX_RETAINED`（:230，当前 4）提高，
   或把 retention 的淘汰改成"只删 `cacheDir` 副本、共享树副本留到会话结束"。
   验收判据：从交付 `uri=http://127.0.0.1:<port>/covers/<session>/<uuid>.jpg` 那一刻起，
   直到会话结束，同一 URL 用 `adb shell curl`（或 `toybox wget`）都能拿到 `200` + 完整 JPEG 字节。

4. **`shared/.../media/VendorClusterBridge.kt:502` — 保持 `getArtwork` 返回 HTTP `Uri` 不变**（已经是 `artworkUri`）。
   顺带核对：`pushMedia` 里 `getNextArtwork`/`getPreviousArtwork` 继续返回 null（与 HyLink 一致，`:1716/:1727`），
   不要为了"多给两张"引入未验证行为。

5. **`shared/.../media/ClusterArtworkHttpServer.kt:354`（`handle`）— 加一行请求日志，否则"仪表没来取"永远无法与"我们没记录"区分。**
   ```kotlin
   Log.i(TAG, "cluster artwork http request path=${parts[1]}")
   ```
   （`parts` 已经解析出来了，见 `:370-375`。）这一行是 §6.4 C1 的全部仪表侧证据来源，
   也是本条里**唯一**必须新增的可观测性。建议长期保留：它体积极小，且是"取图方到底来没来"的唯一实车信号。

6. **（可选，只在 6.2 的 D1/D3 都修完仍不出图时才做）重放 HyLink 的 `content://` 形状作为第二条通道。**
   把 `ClusterArtworkProvider` 的 path 映射（`ClusterArtworkTransport.kt:676-696 clusterArtworkCoverFile`）
   从 3 段 `covers/<session>/<uuid>.jpg` 扩成同时接受 **1 段** `<name>`（HyLink 形状），
   并在 `publish()` 的 provider 分支用 1 段形状交付（`clusterArtworkUriText` 加一个短形状变体，:359-360）。
   理由：这是 HyLink 唯一在用、且我们**尚未试过**的形状；若 HTTP 档被平台拒（例如仪表侧不允许连回环端口），这是最省的一条对照。
   **不做**"登记进 `ecarx.xsf.mediacenter`"（`registerMusic`+`updateMusicPlaybackState`）：
   EasyPlay 在 CS11 上开着 HTTP 档也没走那条（`LegacyEcarxMediaCenter` 的 media-center 分支是另一支），
   它引入一条全新 binder 依赖，收益不可估，**不在最小改动内**。

### 6.4 只有实车能确认的事（**压到两条**）

- **C1 · 仪表的取图方到底会不会 `GET` 我们的回环 URL。**
  一次点火窗口即可。
  **(i) 先证明服务本身能服务**（与仪表无关，只是把 D2 排掉）：交付后**不要退出 App**，
  ```
  adb shell "curl -sS -o /dev/null -w '%{http_code} %{size_download}\n' 'http://127.0.0.1:<port>/covers/<session>/<uuid>.jpg'"
  ```
  `200` + 完整字节 ⇒ 服务与文件都在位，D2 不是根因；`404` ⇒ D2（文件被移走/删掉）；
  `connection refused` ⇒ 端口没绑上或已被 `shutdown`（D3 / D1）。
  **(ii) 再证明仪表来取过**。我们的服务端目前**不打请求日志**，所以必须在
  `ClusterArtworkHttpServer.handle()`（`:354`）临时补一行 `Log.i(TAG, "cluster artwork http request path=${parts[1]}")`；
  否则"仪表没来取"与"仪表取了但我们没记录"无法区分。判据：
  - 有 `request` 行、仪表仍不出图 ⇒ 取图方读到了我们返回的字节却不画 ⇒ 问题在**编码格式或 `Content-Type`**（下一步试 PNG / `Content-Length` / `HEAD`）；
  - 无 `request` 行 ⇒ 取图方**根本没连过回环端口** ⇒ HTTP 形状在这台车上不成立，转 §6.3 第 6 条（重放 HyLink 的 `content://` 单段形状）或检查厂商 `setAlbumCover2` 侧。
  **(iii) D1 的专项判据**：让 App 走一次"进后台 → 宿主 Activity `onDestroy`"的流程，再重放 (i)。
  若 `shutdown` 后再 `curl` 得 `connection refused`，而会话仍在继续，则 D1 成立。
  **这一条同时替掉了原来分散的三次验证。**

- **C2 · `getMusicImageDir()` 在 CS11 上到底解析成哪个目录。**
  源码里这条路有多个候选（`media.java:3320-3460` 的 `getMusicImageDir()`、
  `ArtworkContentProvider.java:876-878` 的 `Pictures/<ARTWORK_DIRECTORY_NAME>`、
  `media.java:3746` 的 `new File(m1292(this), str)`），而字符串表在 `media.java` 里
  尚未被完全解出（`f91short` 是 4348 个 short 的混合串表，本轮的 delta/XOR 只对前几条有效）。
  只有在决定实施 6.3 第 6 条（重放 HyLink 的 `content://` 形状）时才需要它：
  ```
  adb shell ls -l /sdcard/Music/mediacenter/images/ 2>/dev/null | head
  adb shell ls -l /sdcard/Pictures/musicimage/ 2>/dev/null | head
  adb shell run-as com.hyphp.playkeytool ls -l files/ 2>/dev/null | head
  ```
  **若不实施第 6 条，这一条可以不做。**

---

## 7. 本轮对既有文档的更正

| 文档 | 原记述 | 本轮更正 | 依据 |
|---|---|---|---|
| `47-HYLink仪表路径逆向与可借用清单.md §4/§9` | 专辑封面走 `BitmapParcelable` + `MediaControllerAPI` 读路径 | **否证**：`BitmapParcelable` 只用于 `updatePhoneCallInfo`（来电头像）；封面始终是 `Uri`，经 `IMediaInteraction.updatePlaybackInfo` | `BitmapParcelable.java:9-52`、`IApi.java:221/1003/1180/1452`、`DimPlaybackInfo3.java:36,1687,1821`、`media.java:22683` |
| `45-CS11仪表DIM接口与媒体差距清单.md §2.4` | "先 provider+grant，再本地回环 HTTP"（顺序关系） | **修正为并列两条**：`LegacyArtworkLoader`（content 档）与 `ArtworkUriTransport`（HTTP 档）是不同路径；`enabled=true` 时 `uriFor` **只**走 HTTP | `LegacyEcarxMediaCenter.java:1228-1253` |
| `45` 号 §2.4 隐含"HyLink 也有 HTTP 档" | — | HyLink **有常量与死代码**（`MUSIC_IMAGE_HTTP_BASE_URL`、`getMusicImageUrl`、`NanoHTTPD`），但**从未启用**：DEX 无 `127.0.0.1`/`localhost`，`NanoHTTPD` 只在自身 `main` 里 `new`，而 `main` 只被一个混淆分支调用 | `media.java:75,1783,3813-3850,1985`；`NanoHTTPD.java:5287,5920,6131`；classes2.dex 字符串表 |

---

## 8. 证据索引（file:line）

### HyLink（`_analysis/hylink_src/…`）
- `UP_unpacked/sources/com/hyphp/sdk/media.java`
  - `:41,43` DIM 导入 · `:82,90,105` 字段 · `:12487,14597` `new DimInteractionImpl(context)`
  - `:19402-19424` `getMediaInteraction()` · `:22665-22683` `updatePlaybackInfo(IPlaybackInfo)`
  - `:18299` `updateCurrentSourceType(i)` · `:17331` `updateCurrentProgress(j)`
  - `:71-75` `MEDIA_IMAGE_GET_TYPE_*` 与 `MUSIC_IMAGE_HTTP_BASE_URL` 声明 · `:1783,:1802` 静态初始化
  - `:67-78` 常量 · `:3599-3811` `getMusicImageUri`（`:3643` content 分支、`:3655` file 分支、`:3715-3740` 分档）
  - `:3813-3850` `getMusicImageUrl`（**死代码**） · `:17996` `nowArtworkUrl` 唯一读取 · `:16929` 该读取处的用途
  - `:22487-22566` `getMusicImageDir` 入口 · `:3320-3460` `getMusicImageDir` 实体
  - `:31296-34560` `updateSongArtwork`（`:31460,32844,34084,35128,39588` 构造 `DimPlaybackInfo3`；`:32843,32947,33178,33278,33382,34521` 写 `nowArtwork`/`nowArtworkUrl`）
  - `:22626` `getMusicImageUri` 调用点 · `:23520-23615` `getArtworkContentUri`
  - `:9759,10093,10815,10917,11174,12782,12979,13717,14169,17658,17744,18846-18904,19731,21266,21511,22042,23332` 媒体中心旁路
  - `:1982-1993` `NanoHTTPD.main(strArr)`（唯一调用点）
- `UP_unpacked/sources/com/hyphp/sdk/DimPlaybackInfo3.java`
  - `:12` 实现 `IMediaInteraction.IPlaybackInfo` · `:17` `private Uri artWork;`
  - `:1687` `getArtwork()` · `:1715/:1727` `getNextArtwork/getPreviousArtwork` 返回 null · `:1751` `getUUID()` 返回 null
  - `:1821-1832` `setArtWork(Uri)`
- `PKT_single2/DimPlaybackInfo3.java:36` — `DimPlaybackInfo3(String, String, String, long, Uri)`
- `PKT_single/media.java:31459, 22578-22657, 3599-3850` — 同上（带完整参数与调用点）
- `UP_unpacked/sources/com/hyphp/sdk/C0105.java:480-485` — `m1597()` ↔ `Function.switchMediaImageGetType`
- `UP_unpacked/sources/com/hyphp/sdk/Function.java:77` — `switchMediaImageGetType` 声明
- `UP_unpacked/sources/a/j90.java:176-210` — CS11 机型默认 `switchMediaImageGetType=2`
- `UP_unpacked/sources/com/hyphp/sdk/ArtworkContentProvider.java`
  - `:29-30` 常量声明 · `:102-132` `static{}` · `:117` `AUTHORITY_SUFFIX` · `:123` `ARTWORK_DIRECTORY_NAME`
  - `:199-296` `createUri`（`:252` `Uri.Builder` + authority + `appendPath(str)`）
  - `:435` `findArtworkFile` · `:615` `findArtworkFile(Uri)` · `:757` `getArtworkDirectory`（`:876-878` Pictures 起步）
  - `:1066` `requireArtworkFile` · `:1746-1756` `delete` 抛异常 · `:1762-1855` `getType`（`:1787` 走 `requireArtworkFile`）
  - `:1858` `insert` 抛异常 · `:1868-1905` `openFile`（`:1887` `m251`，`:1890` `ParcelFileDescriptor.open(file, MODE_READ_ONLY)`，`:1894` 只接受 `"r"`）
  - `:1915-2234` `query` 返回 `MatrixCursor`
- `PKT_acp/ArtworkContentProvider.java` — 同一类，无截断版（`:117,123,199-296,876-878,1885-1895`）
- `UP_unpacked/sources/com/hyphp/carplaystart/carplaystart/C0100.java:97-103` — `m137` 逐字符 XOR
- `UP_unpacked/sources/a/ue0.java:713,1533,1571,1713` — `J4/X6/Y4/b2`（均转 `C0100.m137`）
- `UP_unpacked/sources/com/hyphp/CarplayApi/BitmapParcelable.java:9-52` — **来电头像**用
- `UP_unpacked/sources/com/hyphp/CarplayApi/IApi.java:221,1003,1180,1452` — `updatePhoneCallInfo(…, BitmapParcelable, …)`
- `UP_unpacked/sources/com/ecarx/eas/sdk/mediacenter/control/MediaControllerAPIImpl.java:30-117` — 读侧 bean 转换（`:45` `setArtWork`，`:90` `setArtwork`）
- `UP_unpacked/sources/com/ecarx/eas/sdk/mediacenter/control/c.java:34-125` — 同上（`:43,91`）
- `UP_unpacked/sources/com/ecarx/eas/sdk/mediacenter/control/bean/Media.java:9,46,166` — `private Uri artWork` / `getArtWork` / `setArtWork`
- `UP_unpacked/sources/com/ecarx/eas/sdk/mediacenter/control/bean/MusicPlaybackInfo.java:13,55,175` — `private Uri artwork` / `getArtwork` / `setArtwork`
- `UP_unpacked/sources/com/ecarx/eas/sdk/mediacenter/MediaCenterProxy.java:70-72` — `EAS_SUPPORT` 选型
- `UP_unpacked/sources/com/ecarx/eas/sdk/mediacenter/MediaCenterAPIImpl.java:87,123` 与 `e.java:77,115` — `ComponentName("ecarx.xsf.mediacenter", "ecarx.xsf.mediacenter.MediaCenterService")`
- `_analysis/hylink_src/A_26091401/resources/AndroidManifest.xml:58-71,147-151` — `FileProvider` 与 `ArtworkContentProvider`（`:149 exported=true`，`:150 authorities`，`:151 grantUriPermissions=true`）

### EasyPlay v71
- `legacy/LegacyEcarxMediaCenter.java:1223-1257` `ArtworkUriTransport.uriFor`（`:1228` `enabled`，`:1230` `coverFile$legacy`，`:1237` `server.uriFor`，`:1252` `uriUriFor = source`）
- `legacy/LegacyEcarxMediaCenter.java:1354-1365` 构造（`:1358` 传入 `usesSinglePassArtwork(...)`）
- `legacy/LegacyEcarxMediaCenter.java:2143-2147` `usesSinglePassArtwork` = `sdk==19 && Freescale && CS11 && !modernSdk`
- `legacy/LegacyArtworkHttpServer.java:45-61` 上限常量 · `:142-202` `uriFor`（`:156` bind 回环+端口0+backlog4，`:189` `/covers/<session>/<uuid>.jpg`，`:198` `http://127.0.0.1:<port><path>`）
- `legacy/LegacyArtworkHttpServer.java:340-383` `respond` · `:477-487` `reply`（`Content-Type: image/jpeg`）
- `legacy/LegacyArtworkLoader.java:57` `OEM_READERS` · `:239-241` 字节范围 · `:272` 256 长边 · `:289` JPEG q88 · `:299-321` `content://<pkg>.artwork/covers/<session>/<uuid>.jpg` + `grantUriPermission(reader, uri, 1)`
- `media/CarPlayArtwork.java`、`media/CarPlayArtworkTransferEvent.java` — 封面来源（iAP2 传输）

### 厂商接口
- `_analysis/cs11_vendor/api/jadx/sources/com/ecarx/xui/adaptapi/diminteraction/IMediaInteraction.java:63-122` — `IPlaybackInfo` 全部 18 个 getter · `:27-51` `IMedia` · `:128-138` `IMediaInteraction` 的全部方法

### 我们
- `shared/.../media/VendorClusterBridge.kt:26-34,59,61,203-204,276-302,312-318,344,481,501-502,646-658`
- `shared/.../media/ClusterArtworkTransport.kt:13-101`（KDoc 实测表）`:104-115,125,153,230,359-360,410-420,573-582,598-627,676-696,1000-1200`
- `shared/.../media/ClusterArtworkHttpServer.kt:26-32,75-78,93-158,222-255,267-284,294-305,316-318,354-397,409-429,455-500`
- `common/src/main/AndroidManifest.xml:41-45` — `ClusterArtworkProvider`（`exported=true` + `grantUriPermissions=true`）
- `common/src/main/java/com/shilapi/xcertplay/ClusterArtworkProvider.kt:37-59` — `openFile` 只接受 `"r"`

### 既有文档
- `docs/_digest/28-CS11上机验证清单（点火窗口）.md:433,436,446`（回环图床假设与验收）
- `docs/_digest/42-EasyPlay-v71-逆向与可移植候选.md:82,793`
- `docs/_digest/45-CS11仪表DIM接口与媒体差距清单.md:21,284-299,428,495,539,553-570`
- `docs/_digest/46-CS11仪表导航DIM接口与差距清单.md`（`ecarx.adapterapi.jar` 在 boot classpath）
- `docs/_digest/47-HYLink仪表路径逆向与可借用清单.md:37-43,211,678,728,774`
- `docs/_digest/48-仪表投屏两次会话对照（10-08实测 vs 10-09审计）.md:1`（`albumCover2 = …` 日志）
