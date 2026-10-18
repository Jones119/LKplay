package com.shilapi.xcertplay.media

/**
 * 音频解码器的用户可选实现（设置页「声音设置」）。
 *
 * 背景（`docs/_digest/78` §4.1）：这台车机的 `media_codecs.xml` 只注册了 AOSP 的
 * `OMX.google.aac.decoder`（软解）。Freescale 的 NEON 定点 AAC 解码器
 * `lib_aac_dec_v2_arm12_elinux.so` **存在于 `/system/lib` 但从未注册**，MediaCodec 拿不到它
 * —— 只能从 JNI 直接 `dlopen`。
 *
 * ⚠️ **默认必须是 [MEDIACODEC]**：Freescale 那条路的调用约定仍在逆向中
 * （见 `work/cs11-aac-jni/evidence/A1-findings.md`：`ctx` 结构的初始化函数没有导出符号）。
 * 选择它时若桥接层不可用，[FreescaleAacBridge.isAvailable] 会返回 false，渲染器**自动回落**
 * 到 MediaCodec 并在诊断行里写明原因 —— 用户不会因此没声音。
 */
enum class AudioDecoderChoice {
    /** 平台 MediaCodec（本机即 `OMX.google.aac.decoder`）。**默认，且始终可用。** */
    MEDIACODEC,

    /**
     * 直接 `dlopen` 厂商的 Freescale AAC 解码器。
     *
     * 按 `/system/lib` 路径加载而**不打包进 APK**：一是避免再分发厂商专有二进制
     * （本项目 GPL-3.0，`libdiplay_opus.so` 能打包是因为它 BSD），二是 API 19 没有
     * API 21+ 的 linker namespace 限制，普通 `dlopen` 就能打开系统库。
     * 代价：非 CS11 设备上必然失败 ⇒ 必须回落，且要能说清原因。
     */
    FREESCALE;

    companion object {
        val DEFAULT = MEDIACODEC

        /** 纯逻辑：一个持久化/属性字符串到一个选择。JVM 可测。 */
        fun parse(raw: String?): AudioDecoderChoice = when (raw?.trim()?.lowercase()) {
            "freescale", "fsl", "vendor", "omx.freescale" -> FREESCALE
            else -> DEFAULT
        }

        fun toStorage(choice: AudioDecoderChoice): String = choice.name.lowercase()
    }
}
