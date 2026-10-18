package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/_digest/79` A6（会话期间 CPU 调频）的纯逻辑单测。
 *
 * 为什么这些判断值得测：**本 ROM 没有 `/proc/config.gz`**（doc 78 §5），
 * governor 与频率清单只能在车机上探测。所以"给定清单怎么选"必须与"怎么读 sysfs"分开，
 * 否则这段逻辑一行都测不了，只能上车试。
 */
class CpuFrequencyPlanTest {

    // ---------------------------------------------------------------- governor 选择

    @Test
    fun `有 performance 就用它`() {
        val chosen = CpuFrequencyPlan.sessionGovernor(
            listOf("ondemand", "conservative", "performance", "interactive"),
        )
        assertEquals("performance", chosen)
    }

    @Test
    fun `没有 performance 时优先 interactive 而不是 ondemand`() {
        // i.MX6 的原厂 governor 是 interactive；ondemand 抬频慢。
        val chosen = CpuFrequencyPlan.sessionGovernor(listOf("ondemand", "interactive", "userspace"))
        assertEquals("interactive", chosen)
    }

    @Test
    fun `只有 ondemand 时不猜 governor`() {
        // 明确退化为 null：宁可只抬 min_freq，也不去猜一个没验证过的 governor。
        assertNull(CpuFrequencyPlan.sessionGovernor(listOf("ondemand", "powersave")))
    }

    @Test
    fun `空清单或读不到时不猜`() {
        assertNull(CpuFrequencyPlan.sessionGovernor(emptyList()))
    }

    @Test
    fun `governor 名大小写与空白不影响判定`() {
        assertEquals("performance", CpuFrequencyPlan.sessionGovernor(listOf(" Performance ")))
    }

    // ---------------------------------------------------------------- min_freq 选择

    @Test
    fun `用了 performance 就不再动 min_freq`() {
        // performance 已经锁最高频，再写 min_freq 只会在退出时多一次写入。
        assertNull(
            CpuFrequencyPlan.sessionMinFrequencyKHz(
                availableKHz = listOf(396_000, 792_000, 996_000),
                sessionGovernor = "performance",
            ),
        )
    }

    @Test
    fun `没有 performance 时把 min_freq 顶到最高频`() {
        assertEquals(
            996_000,
            CpuFrequencyPlan.sessionMinFrequencyKHz(
                availableKHz = listOf(396_000, 996_000, 792_000),
                sessionGovernor = null,
            ),
        )
    }

    @Test
    fun `读不到频率清单时不动 min_freq`() {
        assertNull(CpuFrequencyPlan.sessionMinFrequencyKHz(emptyList(), null))
    }

    @Test
    fun `会话外恢复探测到的原值而不是硬编码省电值`() {
        // 原厂可能把 min 设成非最低值，我们无权替它决定。
        assertEquals(396_000, CpuFrequencyPlan.idleMinFrequencyKHz(396_000))
        assertEquals(792_000, CpuFrequencyPlan.idleMinFrequencyKHz(792_000))
        // 探测失败（原值未知）⇒ 不写，保持现状。
        assertNull(CpuFrequencyPlan.idleMinFrequencyKHz(null))
    }

    // ---------------------------------------------------------------- sysfs 解析

    @Test
    fun `解析 available_frequencies`() {
        // 这个节点在 i.MX6 上就是空格分隔的 kHz 列表。
        assertEquals(
            listOf(396_000, 792_000, 996_000),
            CpuFrequencyPlan.parseFrequencies("396000 792000 996000\n"),
        )
        assertEquals(emptyList<Int>(), CpuFrequencyPlan.parseFrequencies(null))
        assertEquals(emptyList<Int>(), CpuFrequencyPlan.parseFrequencies(""))
        // 脏数据不能变成 0 或异常
        assertEquals(listOf(996_000), CpuFrequencyPlan.parseFrequencies("abc 0 -5 996000"))
    }

    @Test
    fun `解析 available_governors`() {
        assertEquals(
            listOf("interactive", "ondemand", "performance"),
            CpuFrequencyPlan.parseGovernors("interactive ondemand performance\n"),
        )
        assertEquals(emptyList<String>(), CpuFrequencyPlan.parseGovernors(null))
    }

    // ---------------------------------------------------------------- 功能开关

    @Test
    fun `默认开启，只有明确的 off 类取值才关闭`() {
        assertTrue(GovernorController.sanitize(null))
        assertTrue(GovernorController.sanitize(""))
        assertTrue(GovernorController.sanitize("on"))
        assertTrue(GovernorController.sanitize("1"))
        assertFalse(GovernorController.sanitize("off"))
        assertFalse(GovernorController.sanitize(" OFF "))
        assertFalse(GovernorController.sanitize("0"))
        assertFalse(GovernorController.sanitize("false"))
        assertFalse(GovernorController.sanitize("disable"))
    }

    // ---------------------------------------------------------------- 控制器行为（注入 IO）

    private class FakeSysfs(
        val files: MutableMap<String, String>,
        val writable: Set<String> = files.keys.toSet(),
    ) {
        val writes = mutableListOf<Pair<String, String>>()
        fun read(path: String): String? = files[path]
        fun write(path: String, value: String): Boolean {
            if (path !in writable) return false
            writes += path to value
            files[path] = value
            return true
        }
    }

    private fun fakeDevice(governors: String = "interactive ondemand performance") =
        FakeSysfs(
            mutableMapOf(
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor" to "interactive",
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq" to "396000",
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors" to governors,
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies" to "396000 792000 996000",
            ),
        )

    @Test
    fun `进入会话写入 governor 并记录原值，退出时恢复`() {
        val fake = fakeDevice()
        val controller = GovernorController(
            roots = listOf("/sys/devices/system/cpu/cpu0/cpufreq"),
            read = fake::read,
            write = fake::write,
            report = {},
            log = {},
        )

        assertTrue(controller.enterSession())
        assertEquals(
            "performance",
            fake.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"],
        )
        // performance 下不该写 min_freq（sessionMinFrequencyKHz 返回 null）
        assertFalse(fake.writes.any { it.first.endsWith("scaling_min_freq") })

        assertTrue(controller.exitSession())
        assertEquals(
            "interactive",
            fake.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"],
        )
        assertEquals("396000", fake.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq"])
    }

    @Test
    fun `没有 performance 时抬 min_freq 且不动 governor`() {
        val fake = fakeDevice(governors = "ondemand conservative")
        val controller = GovernorController(
            roots = listOf("/sys/devices/system/cpu/cpu0/cpufreq"),
            read = fake::read,
            write = fake::write,
            report = {},
            log = {},
        )

        assertTrue(controller.enterSession())
        assertEquals(
            "interactive",
            fake.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"],
        )
        assertEquals("996000", fake.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq"])

        controller.exitSession()
        assertEquals("396000", fake.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq"])
    }

    @Test
    fun `节点不存在时干净失败且不抛异常`() {
        val fake = FakeSysfs(mutableMapOf())
        val controller = GovernorController(
            roots = listOf("/sys/devices/system/cpu/cpu0/cpufreq"),
            read = fake::read,
            write = fake::write,
            report = {},
            log = {},
        )
        assertFalse(controller.enterSession())
        assertFalse(controller.exitSession())
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun `只读节点时报告失败但不抛`() {
        // 固件里这些节点是 0664 system 组；若某个 ROM 把它设成 0444，我们只是写不进去，
        // 绝不能因此让会话建立失败。
        val readOnly = FakeSysfs(fakeDevice().files, writable = emptySet())
        val controller = GovernorController(
            roots = listOf("/sys/devices/system/cpu/cpu0/cpufreq"),
            read = readOnly::read,
            write = readOnly::write,
            report = {},
            log = {},
        )
        // 写不进去 ⇒ enterSession 返回 false（没有一次成功写入），但绝不抛
        assertFalse(controller.enterSession())
        assertTrue(readOnly.writes.isEmpty())
    }

    @Test
    fun `重复 enter 是幂等的，不会把已改的值当成原值`() {
        val fake = fakeDevice()
        val controller = GovernorController(
            roots = listOf("/sys/devices/system/cpu/cpu0/cpufreq"),
            read = fake::read,
            write = fake::write,
            report = {},
            log = {},
        )
        controller.enterSession()
        controller.enterSession()
        controller.exitSession()
        // 关键：原值必须是 interactive（第一次 enter 之前读到的），不是 performance
        assertEquals(
            "interactive",
            fake.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"],
        )
    }

    @Test
    fun `没进过会话就 exit 是空操作`() {
        // CarPlayController.close() 无条件调 exitSession()（会话可能还活着就被拆掉），
        // 这条契约保证那次调用不会往 sysfs 写任何东西，也不会因为"没记录过原值"而写错值。
        val fake = fakeDevice()
        val controller = GovernorController(
            roots = listOf("/sys/devices/system/cpu/cpu0/cpufreq"),
            read = fake::read,
            write = fake::write,
            report = {},
            log = {},
        )
        assertFalse(controller.exitSession())
        assertTrue("exit 之前没有任何写入", fake.writes.isEmpty())
    }

    @Test
    fun `exit 后可以再次 enter`() {
        // 同一进程里会话可能反复建立/结束（重连、切前后台）。第二次 enter 必须重新记录原值。
        val fake = fakeDevice()
        val controller = GovernorController(
            roots = listOf("/sys/devices/system/cpu/cpu0/cpufreq"),
            read = fake::read,
            write = fake::write,
            report = {},
            log = {},
        )
        assertTrue(controller.enterSession())
        assertTrue(controller.exitSession())
        assertTrue(controller.enterSession())
        assertEquals(
            "performance",
            fake.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"],
        )
        assertTrue(controller.exitSession())
        assertEquals(
            "interactive",
            fake.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"],
        )
    }
}
