/*
 * diplay_aac_fsl — 加载 CS11 车机上厂商的 Freescale AAC 解码器（docs/_digest/79 的 A1）。
 *
 * 为什么是"运行时 dlopen /system/lib"而不是把 .so 打进 APK：
 *   1) lib_aac_dec_v2_arm12_elinux.so 是厂商**专有**二进制；本项目 GPL-3.0。
 *      随 APK 分发属于再分发（对比 libdiplay_opus.so 能打包，因为它是 BSD-3-Clause）。
 *   2) API 19 没有 API 21+ 的 linker namespace 隔离，普通 dlopen 就能打开系统库。
 *
 * 现状（重要）：本文件**只做加载与符号探测**。真正调用 AACD_* 还缺 `ctx` 结构的初始化语义
 * （见 work/cs11-aac-jni/evidence/A1-findings.md），在那之前 dlopen 成功也只是"具备条件"，
 * 不能拿去解码。所以上层拿到的 available=true 仅表示"库和入口符号都在"。
 *
 * 命名约束（与 LegacyNativeOpus 同样的坑）：JNI 符号绑定到**确切的类名与方法名**，
 * 改名会让 System.loadLibrary 之后的方法解析失败。类名固定为
 *   com.shilapi.xcertplay.media.FreescaleAacNative
 */

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>

#include "diplay_aac_dec.h"

/*
 * ⚠️ 本库**只对 32 位 ARM 有意义**。
 *
 * 厂商的 AAC 解码器只有 armeabi-v7a 版（`lib_aac_dec_v2_arm12_elinux.so`），
 * 而 diplay_aac_dec.c 里的结构体布局与 _Static_assert 全部建立在 **32 位指针**之上
 * （`sizeof(void*) == 4`）。在 arm64 上那套布局根本不成立，去 dlopen 一个 32 位 .so 也不可能成功。
 *
 * 所以 arm64 编译成**明确不可用的桩**：probe 返回 false、status 说明原因。
 * 这样比"留一份会按错误偏移读写厂商结构体"的实现安全得多——后者会在 64 位设备上静默读错内存。
 */
#if !defined(__arm__) || defined(__aarch64__)

static const char *kUnsupported = "unsupported ABI (vendor AAC decoder is 32-bit ARM only)";

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_probeNative(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_statusNative(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, kUnsupported);
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_pathNative(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_versionNative(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_queryDecMemNative(JNIEnv *env, jclass clazz,
                                                                     jintArray out) {
    (void) env; (void) clazz; (void) out;
    return -4; /* -4 = 本 ABI 不支持 */
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_createDecoderNative(JNIEnv *env, jclass clazz,
                                                                       jint maxChannels) {
    (void) env; (void) clazz; (void) maxChannels;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_destroyDecoderNative(JNIEnv *env, jclass clazz,
                                                                        jlong handle) {
    (void) env; (void) clazz; (void) handle;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_decodeFrameNative(JNIEnv *env, jclass clazz,
                                                                     jlong handle,
                                                                     jbyteArray au, jint auOffset,
                                                                     jint auLength,
                                                                     jintArray hdr,
                                                                     jshortArray pcmOut,
                                                                     jintArray result) {
    (void) env; (void) clazz; (void) handle; (void) au; (void) auOffset; (void) auLength;
    (void) hdr; (void) pcmOut; (void) result;
    return -4;
}

#else /* 32 位 ARM：真正的实现 */

#define LOG_TAG "DiPlay-AacFsl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

/* 车机上的实际路径。也接受 vendor 变体，便于将来换 ROM 时不用改代码。 */
static const char *kCandidates[] = {
    "/system/lib/lib_aac_dec_v2_arm12_elinux.so",
    "/system/vendor/lib/lib_aac_dec_v2_arm12_elinux.so",
    "/vendor/lib/lib_aac_dec_v2_arm12_elinux.so",
};

/* 入口符号：A1 逆向确认的 API 层（小写 aacd_），不是内部的 AACD_（大写）。
 * 见 evidence/A1-findings.md E4 的符号分层。 */
static const char *kEntrySymbols[] = {
    "aacd_query_dec_mem",
    "aacd_decoder_init",
    "aacd_decode_frame",
};

static void *g_handle = NULL;
static char g_path[256] = {0};
static char g_status[512] = {0};

/* 真正的加载逻辑；只跑一次，结果缓存。 */
static void ensure_loaded(void) {
    if (g_handle != NULL || g_status[0] != '\0') return;

    const size_t n = sizeof(kCandidates) / sizeof(kCandidates[0]);
    for (size_t i = 0; i < n; i++) {
        void *h = dlopen(kCandidates[i], RTLD_NOW | RTLD_LOCAL);
        if (h == NULL) {
            const char *err = dlerror();
            LOGI("dlopen failed path=%s err=%s", kCandidates[i], err ? err : "(null)");
            continue;
        }

        /* 逐个探测入口符号；缺任何一个都算不可用，并说清缺的是哪个。 */
        for (size_t s = 0; s < sizeof(kEntrySymbols) / sizeof(kEntrySymbols[0]); s++) {
            void *sym = dlsym(h, kEntrySymbols[s]);
            if (sym == NULL) {
                snprintf(g_status, sizeof(g_status),
                         "missing symbol %s in %s", kEntrySymbols[s], kCandidates[i]);
                LOGW("%s", g_status);
                dlclose(h);
                return;
            }
        }

        g_handle = h;
        strncpy(g_path, kCandidates[i], sizeof(g_path) - 1);
        snprintf(g_status, sizeof(g_status), "loaded %s (%zu entry symbols ok)",
                 g_path, sizeof(kEntrySymbols) / sizeof(kEntrySymbols[0]));
        LOGI("%s", g_status);
        return;
    }

    snprintf(g_status, sizeof(g_status),
             "not present: tried %zu /system lib paths", n);
    LOGW("%s", g_status);
}

JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_probeNative(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    ensure_loaded();
    return g_handle != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_statusNative(JNIEnv *env, jclass clazz) {
    (void) clazz;
    ensure_loaded();
    return (*env)->NewStringUTF(env, g_status);
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_pathNative(JNIEnv *env, jclass clazz) {
    (void) clazz;
    ensure_loaded();
    return (*env)->NewStringUTF(env, g_path);
}

/*
 * 查询厂商解码器的内存需求：原型已在 A1 逆向中确定 ——
 *   int aacd_query_dec_mem(AACD_Decoder_Config *dec_config)   返回 0 成功 / 51 参数无效
 * 头文件（aacd_dec_interface.h）确认 dec_config 就地被填 aacd_mem_info（5 个描述符）。
 * 这里只把它当作"库真的能被调用"的最小活跃性证明：不解析内容，只看返回值。
 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_queryDecMemNative(JNIEnv *env, jclass clazz,
                                                                     jintArray out) {
    (void) clazz;
    ensure_loaded();
    if (g_handle == NULL) return -1;

    typedef int (*query_fn)(void *);
    query_fn fn = (query_fn) dlsym(g_handle, "aacd_query_dec_mem");
    if (fn == NULL) return -2;

    /* 100 B 输出结构：5 个描述符 × 5 个 DWORD，超配一点更安全。 */
    unsigned int desc[32];
    memset(desc, 0, sizeof(desc));
    int rc = fn(desc);
    if (rc != 0) return rc;   /* 非 0 就是厂商的返回码，原样上报 */

    /* 回传前 32 个 DWORD 供诊断；调用方可能只关心前几个。 */
    if (out != NULL) {
        jsize cap = (*env)->GetArrayLength(env, out);
        jint *buf = (*env)->GetIntArrayElements(env, out, NULL);
        if (buf != NULL) {
            for (jsize i = 0; i < cap && i < 32; i++) {
                buf[i] = (jint) desc[i];
            }
            (*env)->ReleaseIntArrayElements(env, out, buf, 0);
        }
    }
    return 0;
}

/* ── 解码路径（docs/_digest/79 A1 的主体）──────────────────────────────────── */

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_versionNative(JNIEnv *env, jclass clazz) {
    (void) clazz;
    ensure_loaded();
    if (g_handle == NULL) return (*env)->NewStringUTF(env, "");
    typedef const signed char *(*ver_fn)(void);
    ver_fn fn = (ver_fn) dlsym(g_handle, "aacd_decode_versionInfo");
    if (fn == NULL) return (*env)->NewStringUTF(env, "");
    const char *v = (const char *) fn();
    return (*env)->NewStringUTF(env, v != NULL ? v : "");
}

/*
 * 建解码器。返回句柄（0 = 失败）。
 * 内部会：dlsym 四个入口 → aacd_query_dec_mem → 分配 5 块内存并清零 →
 *         num_pcm_bits=16 → adts_format=TRUE → aacd_decoder_init。
 */
JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_createDecoderNative(JNIEnv *env, jclass clazz,
                                                                       jint maxChannels) {
    (void) env; (void) clazz;
    ensure_loaded();
    if (g_handle == NULL) return 0;

    diplay_aac_query_dec_mem_fn q =
        (diplay_aac_query_dec_mem_fn) dlsym(g_handle, "aacd_query_dec_mem");
    diplay_aac_decoder_init_fn i =
        (diplay_aac_decoder_init_fn) dlsym(g_handle, "aacd_decoder_init");
    diplay_aac_decode_frame_fn f =
        (diplay_aac_decode_frame_fn) dlsym(g_handle, "aacd_decode_frame");
    diplay_aac_version_info_fn v =
        (diplay_aac_version_info_fn) dlsym(g_handle, "aacd_decode_versionInfo");
    if (q == NULL || i == NULL || f == NULL) {
        LOGW("createDecoder: 入口符号缺失 q=%p i=%p f=%p", (void *) q, (void *) i, (void *) f);
        return 0;
    }

    diplay_aac_decoder *d = diplay_aac_decoder_create(q, i, f, v, (int) maxChannels);
    if (d == NULL) {
        LOGW("createDecoder: aacd_decoder_init 失败（内存分配或参数问题）");
        return 0;
    }
    return (jlong) (intptr_t) d;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_destroyDecoderNative(JNIEnv *env, jclass clazz,
                                                                        jlong handle) {
    (void) env; (void) clazz;
    if (handle != 0) {
        diplay_aac_decoder_destroy((diplay_aac_decoder *) (intptr_t) handle);
    }
}

/*
 * 解一帧。
 *
 * `au` + `auOffset`/`auLength`：**裸访问单元**，直接从调用方缓冲里取，
 * 不做任何拷贝（厂商库只读它）。
 * `hdr`：[sampling_freq_index, channel_config, protection_absent, adts_buffer_fullness]。
 * `pcmOut`：Q15 交错输出，容量不足则返回 -3 且不写。
 * `result`：出参 [0]=写入的 short 数 [1]=声道数 [2]=采样率Hz。
 *
 * 返回 0 成功；否则是厂商返回码（负数表示我们自己的错误，见下）。
 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_FreescaleAacNative_decodeFrameNative(JNIEnv *env, jclass clazz,
                                                                     jlong handle,
                                                                     jbyteArray au, jint auOffset,
                                                                     jint auLength,
                                                                     jintArray hdr,
                                                                     jshortArray pcmOut,
                                                                     jintArray result) {
    (void) clazz;
    if (handle == 0 || au == NULL || hdr == NULL || pcmOut == NULL) return -1;

    jsize hdrLen = (*env)->GetArrayLength(env, hdr);
    if (hdrLen < 4) return -1;
    jint hdrBuf[4];
    (*env)->GetIntArrayRegion(env, hdr, 0, 4, hdrBuf);

    jsize auSize = (*env)->GetArrayLength(env, au);
    if (auOffset < 0 || auLength <= 0 || auOffset + auLength > auSize) return -1;

    jsize outCap = (*env)->GetArrayLength(env, pcmOut);
    if (outCap <= 0) return -1;

    /* 只 Pin 输入（厂商库只读），输出用局部缓冲避免长时间钉住 Java 堆。 */
    jbyte *auPtr = (*env)->GetPrimitiveArrayCritical(env, au, NULL);
    if (auPtr == NULL) return -1;

    static short scratch[2 * DIPLAY_AAC_MAX_OUT_SAMPLES];
    int samples = 0, channels = 0, rate = 0;
    diplay_aac_adts_header h;
    h.sampling_freq_index = hdrBuf[0];
    h.channel_config = hdrBuf[1];
    h.protection_absent = hdrBuf[2];
    h.adts_buffer_fullness = hdrBuf[3];

    int rc = diplay_aac_decode_frame((diplay_aac_decoder *) (intptr_t) handle,
                                     (const unsigned char *) auPtr + auOffset, (int) auLength,
                                     &h, scratch, (int) (sizeof(scratch) / sizeof(scratch[0])),
                                     &samples, &channels, &rate);
    (*env)->ReleasePrimitiveArrayCritical(env, au, auPtr, JNI_ABORT);

    if (rc != 0) return rc;
    if (samples > outCap) return -3; /* -3 = 调用方给的输出缓冲太小（正常不该发生） */

    (*env)->SetShortArrayRegion(env, pcmOut, 0, samples, scratch);
    if (result != NULL && (*env)->GetArrayLength(env, result) >= 3) {
        jint r[3] = { (jint) samples, (jint) channels, (jint) rate };
        (*env)->SetIntArrayRegion(env, result, 0, 3, r);
    }
    return 0;
}

#endif /* __arm__ && !__aarch64__ */
