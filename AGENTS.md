# AGENTS.md · 本仓库的硬规则

面向所有接手本仓库的助手/会话。违反规则的改动比不写代码更糟。

## 0. 项目定位

DiPlay 的 CS11 分支：把 CarPlay 接收端跑在领克 CS11 车机上。

- 车机：`cs11_19a_h`，**Android 4.4.3 / API 19 / Dalvik / 32 位 ARM**，是**唯一交付目标**。
- 平板（小米 `2307BRPDCC`，Android 15 / API 35）只是**台架**：用来在车不方便上电时复现问题、拿 ASan 行号。
- 分支 `cs11-api19`；环境事实见 `docs/DEV-ENVIRONMENT-CS11.md`；历史结论见 `docs/_digest/`。

## 1. 平板调试的兼容性规则（**硬性**）

> 在使用平板进行调试时，所做的**任何修改、新增**，都必须保证**兼容 API 19**。

具体含义，缺一不可：

1. **不许裸用 API > 19 的符号**。`Build.VERSION.SDK_INT` 常量、类、方法、字段、清单属性，凡是 API 19 没有的，
   必须有版本分支或兼容封装（本仓库已有 `androidx`/`compat`/`Workers` 等封装可复用）。
   典型反例：`finishAndRemoveTask()`（API 21）、`UsbDeviceConnection.setInterface()`（API 21）、
   `Context.RECEIVER_NOT_EXPORTED`（API 33）、`PendingIntent.FLAG_MUTABLE`（API 31）——
   这些在车机上都必须走回退分支。
2. **平板验证通过 ≠ 车机可用**。为台架新增的路径（例如让 API 21+ 也走 userspace lwIP）必须复用
   API 19 已有的实现，而不是另起一套只在平板上成立的逻辑。
3. **提交说明必须写清 API 19 路径的验证状态**：`✅ API19 实机` / `⚠️ 仅平板验证` / `必须上机复验`。
   不允许把“平板能跑”写成“已修复”。
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
