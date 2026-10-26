# AGENTS.md · 本仓库的硬规则

面向所有接手本仓库的助手/会话。违反规则的改动比不写代码更糟。

## 0. 项目定位

DiPlay 的 CS11 分支：把 CarPlay 接收端跑在领克 CS11 车机上。

- 车机：`cs11_19a_h`，**Android 4.4.3 / API 19 / Dalvik / 32 位 ARM**，是**唯一交付目标**。
- 本分支的 `minSdk` 与 `targetSdk` 都是 **19**，**只面向 Android 4.4.3，不向上兼容**；不为高版本的安装或运行预留路径。
- 分支 `cs11-api19`；环境事实见 `docs/DEV-ENVIRONMENT-CS11.md`；历史结论见 `docs/_digest/`。

## 1. API 19 规则（**硬性**）

> 所做的**任何修改、新增**，都只面向 **API 19（Android 4.4.3）**：只允许 API ≤ 19 的符号，**不向上兼容**。

具体含义，缺一不可：

1. **不许使用 API > 19 的符号**。类、方法、字段、清单属性，凡是 API 19 没有的一律不能用；也不要写 `Build.VERSION.SDK_INT` 版本分支。
   需要的能力用 `compat`/`androidx`/`Workers` 里的等价实现，**或直接放弃该功能**；不要为高版本预留回退路径。
   典型反例：`finishAndRemoveTask()`（API 21）、`UsbDeviceConnection.setInterface()`（API 21）、
   `Context.RECEIVER_NOT_EXPORTED`（API 33）、`PendingIntent.FLAG_MUTABLE`（API 31）——
   这些在 API 19 上不存在，只能换实现或不使用。
2. **验证必须在车机上进行**。编译、单测和 lint 只证明代码正确，不证明功能可用；没有其它 Android 版本或平板台架可以作为替代验证环境。
3. **提交说明必须写清 API 19 路径的验证状态**：`✅ API19 实机` / `必须上机复验`。
   不允许把“编译通过”写成“已修复”。
4. 改动 `shared`/`common` 的公开行为时，跑全量单测：
   ```powershell
   .\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest --console=plain --offline --rerun-tasks
   ```
   判定看 `BUILD SUCCESSFUL`，不信控制台摘要。

## 2. 工程纪律（沿用 doc 92）

- **先测量再改**：日志里已有 `STEP` / `CONNECTION_DIAGNOSTIC` / `userspace NCM link` 三条稳定口径。
- **不要用计时器判断“连接失败”**：只用事件（界面关闭、明确的失败、连接关闭）。
- **改完标注验证状态**，用大白话解释“这段代码干什么、为什么这么改、出问题会看到什么现象”。
- 关键结论写进 `docs/_digest/`，环境变化改 `docs/DEV-ENVIRONMENT-CS11.md`。
