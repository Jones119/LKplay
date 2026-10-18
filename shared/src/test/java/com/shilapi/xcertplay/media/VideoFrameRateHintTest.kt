package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/_digest/79` A3.3（解码器 `operating-rate` / `frame-rate` 提示）的纯逻辑单测。
 *
 * 提示值本身没法在 JVM 上验证是否被 vendor OMX 采纳（那是真机 A/B 的事），
 * 但"属性值 → 下发值"的钳位逻辑必须可测，否则一个笔误会把 0 或 9999 直接写进 format。
 */
class VideoFrameRateHintTest {

    @Test
    fun `未设置或非法值落到默认 30`() {
        assertEquals(30, VideoFrameRateHint.sanitize(null))
        assertEquals(30, VideoFrameRateHint.sanitize(""))
        assertEquals(30, VideoFrameRateHint.sanitize("   "))
        assertEquals(30, VideoFrameRateHint.sanitize("abc"))
        assertEquals(30, VideoFrameRateHint.sanitize("0"))
        assertEquals(30, VideoFrameRateHint.sanitize("-5"))
    }

    @Test
    fun `正常值原样通过`() {
        assertEquals(30, VideoFrameRateHint.sanitize("30"))
        assertEquals(60, VideoFrameRateHint.sanitize("60"))
        assertEquals(24, VideoFrameRateHint.sanitize(" 24 "))
    }

    @Test
    fun `超出合理区间被钳住而不是原样下发`() {
        // 低于 15 或高于 120 都是笔误；下发 9999 只会让解码器行为不可预测。
        assertEquals(15, VideoFrameRateHint.sanitize("1"))
        assertEquals(15, VideoFrameRateHint.sanitize("14"))
        assertEquals(120, VideoFrameRateHint.sanitize("121"))
        assertEquals(120, VideoFrameRateHint.sanitize("9999"))
    }

    @Test
    fun `默认值与区间常量自洽`() {
        // 默认值必须落在合法区间内，否则 current() 会下发一个自己都不接受的数。
        assertEquals(VideoFrameRateHint.DEFAULT_FPS, VideoFrameRateHint.sanitize("30"))
        assertTrue(VideoFrameRateHint.DEFAULT_FPS in VideoFrameRateHint.MIN_FPS..VideoFrameRateHint.MAX_FPS)
    }
}
