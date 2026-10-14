
---

## 更正（2026-10-10）：`BitmapParcelable` **不是**专辑封面

本文 §4/§9 曾写"专辑封面走 `BitmapParcelable`"——**否证**。`com/hyphp/CarplayApi/BitmapParcelable.java:9-52` 只被 `IApi.updatePhoneCallInfo(int, BitmapParcelable, …)`（`IApi.java:221/1003/1180/1452`）使用，是**来电头像**。

封面交付路径只有一个：DIM `IMediaInteraction.IPlaybackInfo.getArtwork()`，返回 `android.net.Uri`（HyLink 的 `DimPlaybackInfo3.java:17 artWork` / `:1687 getArtwork()` / `:1821 setArtWork(Uri)`；`getNextArtwork/getPreviousArtwork` 显式 `return null`）。交付路径上**没有** Bitmap/byte[]/ParcelFileDescriptor。
