package com.shilapi.xcertplay.media

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * CPU 调频档位。CarPlay 会话期间要"给得出算力"，会话之外没有理由占着高频。
 */
enum class CpuPerformanceMode {
    /** 会话中：优先 `performance`；没有该 governor 就把 `min_freq` 顶到最高频。 */
    SESSION,

    /** 会话外：恢复开机时的原值。 */
    IDLE,
}

/**
 * [CpuPerformanceMode] 在**给定 governor 清单与频率清单**下应当写入的值。
 *
 * 纯逻辑，不碰文件系统，因此可在 JVM 上单测（本项目 `docs/_digest/34` 要求的做法）。
 * 分成这个对象的原因：**这台车机上 cpufreq 的可用取值只能在真机上探测**（没有
 * `/proc/config.gz`，见 doc 78 §5），所以"怎么选"必须与"怎么读"解耦，否则一行都测不了。
 */
internal object CpuFrequencyPlan {
    const val PERFORMANCE = "performance"
    const val INTERACTIVE = "interactive"
    const val CONSERVATIVE = "conservative"
    const val ONDEMAND = "ondemand"
    const val USERSPACE = "userspace"

    /**
     * 会话中的 governor 选择，**按"确定性"排序**：`performance` 一定锁最高频；
     * `interactive` 在 i.MX6 上通常比 `ondemand` 更跟手（原厂 MPU governor 就是它）；
     * 其余一律不动——**不认识就退回，不猜**。
     *
     * 返回 null = 不改 governor（退化为只抬 `min_freq`）。
     */
    fun sessionGovernor(available: List<String>): String? {
        val set = available.map { it.trim().lowercase() }.toSet()
        // 已经不打算省电了，就别再让 governor 插手（这是最强的一档）
        if (PERFORMANCE in set) return PERFORMANCE
        // 会话期间 interactive 的抬频速度比 ondemand 快（原厂默认档）
        if (INTERACTIVE in set) return INTERACTIVE
        // 全都不是：不猜 governor，交给 min_freq
        return null
    }

    /**
     * 会话中的 `min_freq`（kHz）。只有拿不到 `performance` 时才需要抬——
     * 用 `performance` 时 min_freq 无意义，抬它反而会在退出时多一次写入。
     */
    fun sessionMinFrequencyKHz(availableKHz: List<Int>, sessionGovernor: String?): Int? {
        if (sessionGovernor == PERFORMANCE) return null
        return availableKHz.maxOrNull()
    }

    /**
     * 会话外的 `min_freq`：**恢复成探测到的原值**，而不是硬编码一个"省电值"——
     * 原厂可能已经把 min 设成某个非最低值，我们无权替它决定。
     */
    fun idleMinFrequencyKHz(originalKHz: Int?): Int? = originalKHz

    /** `/sys/.../scaling_available_frequencies` 是空格分隔的 kHz 列表。 */
    fun parseFrequencies(raw: String?): List<Int> =
        raw?.split(' ', '\n', '\t')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
            ?.filter { it > 0 }
            ?: emptyList()

    /** `/sys/.../scaling_available_governors` 是空格分隔的 governor 列表。 */
    fun parseGovernors(raw: String?): List<String> =
        raw?.split(' ', '\n', '\t')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
}

/**
 * 会话期间的 CPU 调频控制（`docs/_digest/79` 的 A6）。
 *
 * **为什么这件事不需要 root**：`init.freescale.rc` 的 `on boot` 里有一段专门的内核授权
 * （doc 78 §3.1，固件实证）：
 *
 * ```
 * chown root system /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
 * chmod 0664        /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
 * chown root system /sys/devices/system/cpu/cpu{0,1,2,3}/online
 * ```
 *
 * app 属 `system` 组，所以 `0664` 就是可写。**原厂把门开着**——这也是本项目里唯一
 * 能持久化、能交付的车机侧性能手段（其余内核旋钮都是内存态，见 doc 79 §0）。
 *
 * **热路径纪律**：本类只在**会话开始/结束**各写一次，不在任何每帧/每包路径上，因此
 * 同步文件写是可接受的（对比 doc 41 §5 对每帧分配的严格口径）。
 *
 * 开关：`adb shell setprop debug.diplay.cpu_governor off`（默认开）。
 */
class GovernorController(
    private val roots: List<String> = DEFAULT_ROOTS,
    /** 注入以便 JVM 单测；返回 null 表示读不到（文件不存在/无权限）。 */
    private val read: (String) -> String? = ::readFileOrNull,
    /** 注入以便 JVM 单测；返回是否写入成功。 */
    private val write: (String, String) -> Boolean = ::writeFile,
    private val report: (String) -> Unit = {},
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private data class Original(val governor: String?, val minKHz: String?)

    private val originals = HashMap<String, Original>()
    private var applied = false

    /** 探测 + 施加会话档。幂等：重复调用不会重复记录"原值"。 */
    @Synchronized
    fun enterSession(): Boolean {
        if (applied) return true
        val targets = sysfsNodes() ?: return false
        var wrote = 0
        var skipped = 0
        for (node in targets) {
            if (originals[node.root] == null) {
                originals[node.root] = Original(read(node.governor), read(node.minFrequency))
            }
            val original = originals.getValue(node.root)
            val availableGovernors = CpuFrequencyPlan.parseGovernors(read(node.availableGovernors))
            val availableFrequencies =
                CpuFrequencyPlan.parseFrequencies(read(node.availableFrequencies))

            val governor = CpuFrequencyPlan.sessionGovernor(availableGovernors)
            if (governor != null && governor != original.governor) {
                if (write(node.governor, governor)) wrote++ else skipped++
            }
            val minKHz = CpuFrequencyPlan.sessionMinFrequencyKHz(availableFrequencies, governor)
            if (minKHz != null && minKHz.toString() != original.minKHz) {
                if (write(node.minFrequency, minKHz.toString())) wrote++ else skipped++
            }
        }
        applied = true
        val line = "CPU: session governor applied nodes=${targets.size} wrote=$wrote skipped=$skipped"
        log(line)
        runCatching { report(line) }
        return wrote > 0
    }

    /** 恢复探测到的原值。幂等。 */
    @Synchronized
    fun exitSession(): Boolean {
        if (!applied) return false
        applied = false
        val targets = sysfsNodes() ?: return false
        var restored = 0
        for (node in targets) {
            val original = originals[node.root] ?: continue
            original.governor?.let { if (write(node.governor, it)) restored++ }
            original.minKHz?.let { if (write(node.minFrequency, it)) restored++ }
        }
        val line = "CPU: session governor restored nodes=${targets.size} restored=$restored"
        log(line)
        runCatching { report(line) }
        return restored > 0
    }

    /** 当前生效的 governor 清单，供诊断行使用；读不到就是空。 */
    fun currentGovernors(): List<String> =
        sysfsNodes()?.mapNotNull { read(it.governor)?.trim() }?.distinct() ?: emptyList()

    private data class Nodes(
        val root: String,
        val governor: String,
        val minFrequency: String,
        val availableGovernors: String,
        val availableFrequencies: String,
    )

    /** 可写的 cpu 节点；每颗核都写（cpufreq 是按核独立的）。 */
    private fun sysfsNodes(): List<Nodes>? {
        val nodes = roots.mapNotNull { root ->
            val governor = "$root/$SCALING_GOVERNOR"
            if (read(governor) == null) return@mapNotNull null
            Nodes(
                root = root,
                governor = governor,
                minFrequency = "$root/$SCALING_MIN_FREQ",
                availableGovernors = "$root/$AVAILABLE_GOVERNORS",
                availableFrequencies = "$root/$AVAILABLE_FREQUENCIES",
            )
        }
        return nodes.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val PROPERTY = "debug.diplay.cpu_governor"

        private const val SCALING_GOVERNOR = "scaling_governor"
        private const val SCALING_MIN_FREQ = "scaling_min_freq"
        private const val AVAILABLE_GOVERNORS = "scaling_available_governors"
        private const val AVAILABLE_FREQUENCIES = "scaling_available_frequencies"

        /** 四颗核各一个 policy 目录；缺哪个就跳过哪个。 */
        val DEFAULT_ROOTS: List<String> =
            (0..3).map { "/sys/devices/system/cpu/cpu$it/cpufreq" }

        /**
         * `debug.diplay.cpu_governor`：`off` 关闭本功能（默认开）。
         * 与项目其余调试属性同形，便于一个点火窗口内 A/B（ADR-19）。
         */
        fun sanitize(raw: String?): Boolean =
            raw?.trim()?.lowercase() !in setOf("off", "0", "false", "disable", "disabled")

        fun enabled(): Boolean = sanitize(systemProperty())

        /** `debug.diplay.cpu_governor`；4.4 没有公开 API，走反射（同 VideoThreadPriority）。 */
        private fun systemProperty(): String? = runCatching {
            val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            get.invoke(null, PROPERTY) as? String
        }.getOrNull()

        private fun readFileOrNull(path: String): String? = runCatching {
            val file = File(path)
            if (!file.canRead()) null else file.readText().trim().ifEmpty { null }
        }.getOrNull()

        private fun writeFile(path: String, value: String): Boolean = runCatching {
            // API 19 的 FileOutputStream 没有 truncate 语义问题，但 sysfs 要求"写后即读回"，
            // 用 RandomAccessFile 可以显式 rw 打开并在失败时立刻暴露（对比 File.writeText 的静默）。
            RandomAccessFile(path, "rw").use { raf ->
                raf.write(value.toByteArray(Charsets.US_ASCII))
            }
            true
        }.getOrElse {
            Log.w(TAG, "CPU governor write failed path=$path value=$value: ${it.javaClass.simpleName}")
            false
        }
    }
}
