# 103 · 视频承载做成设置项：TextureView 默认，SurfaceView 实验（2026-10-23 实车）

> 承接 doc 102：SurfaceView 把 App CPU 从 76% 降到 12%（静态）/ 50%（拖动），但 30fps 下
> `dropped` 从 0 变成 11–35，且失去「画面调节」与圆角遮罩、有历史黑屏风险。**结论：默认不动，
> 做成设置里的实验开关。**

## 1. 用户可见的开关

设置 → **显示与视频** → **使用 SurfaceView（实验）**

- 默认 **关**（TextureView，线上路径）；
- 描述里写明代价：CPU 更低、但可能丢帧，且「画面调节」和圆角遮罩不可用；
- **下次启动 CarPlay 生效**（视频视图在 host 创建时构建一次）；
- 持久化在 `AirPlayPersistence` 的 `surface_view_experimental`。

## 2. 决策优先级（`VideoSurfaceMode`）

```
debug.diplay.video_surface = surface/1/true  → SurfaceView（实验调试用，压过设置）
                             texture/0/false → TextureView
未设置/空                    → 设置开关（默认 false）
```

`VideoSurfaceModeTest` 覆盖 5 组：默认、开关打开、属性压过开关、属性拼写、空属性回落。

## 3. 顺带修掉的回归（重要）

SurfaceView 档此前会把**整屏 720** 当成协商尺寸（`surfaceFrame` 在布局前返回显示尺寸），
于是 iPhone 编码 1280×720、而窗口只有 1280×650 → **画面纵向被压扁**（用户实见）。

修法：
- `currentActivitySize()` 同时认 `videoView` 与 `videoSurfaceView`；
- SurfaceView 的 `surfaceCreated/Changed` **在布局完成后按视图尺寸（1280×650）协商**；
- 去掉 `holder.setFixedSize()`（对协商没有帮助，反而多一层几何约束）。

验证（两档各自重启后）：
`starting controller display=1280x650 negotiated=1280x650`；
默认档无 `SurfaceView` 窗口，开关打开后出现 `Window{... u0 SurfaceView}`。

## 4. 结论（写给下次接手）

- **日常用 TextureView**：30fps 拖动 `dropped=0`，鲁棒性已被现网验证；
- **SurfaceView 只在需要 CPU 余量时再拿出来**（音频/导航/语音抢核），且必须先解决
  30fps 的 age-drop 与表面重建黑屏；
- 两档现在都协商 1280×650（车机底条 70px，实测 105 物理像素 / 1.5 = 70 逻辑像素），
  不要再回到 720 —— 会压扁。

## 5. 验证状态

| 项 | 状态 |
|---|---|
| 编译 | ✅ |
| `:common` 全量单测（含新增 5 例） | ✅ BUILD SUCCESSFUL |
| 默认档 = TextureView（无 SurfaceView 窗口） | ✅ API19 实机 |
| 开关打开 = SurfaceView 窗口 + 1280×650 协商 | ✅ API19 实机（prefs 直接改写验证；UI 开关待你手点确认） |
| 开关 UI 的视觉/点击 | ⚠️ 待人工确认（三指手势进设置页） |
