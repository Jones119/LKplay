/*
 * diplay_aac_dec.c — Freescale AAC 解码器的调用封装（docs/_digest/79 的 A1）
 *
 * 本文件不含 JNI，是**纯 C 包装**，可用 NDK clang 单独编译并跑 _Static_assert，
 * 把与厂商库的 ABI 约定锚死（那几条偏移是从 IDA 反编译里读出来的）。
 *
 * ── 依据（全部为权威来源，非推断）────────────────────────────────────────────
 * 1) 结构体定义：Freescale libfslcodec 4.0.7 的 ghdr/aacd_dec_interface.h
 *    （本机副本：_analysis/fslcodec_docs/aacd_dec_interface.h）
 * 2) 初始化与解码顺序：同一发行版 test/aac_dec/c_src/aac_main.c
 * 3) 输出格式与内存布局：同 aac_main.c:1349-1378 —— 16bit 时 outbuf[i] 直接截断为 short
 *    ⇒ **Q15 定点**；顺序为 sample-major 交错（out[sample][channel]）
 * 4) ADTS 输入形态：aac_aux.c:814-840（App_get_adts_header 填 params）
 *    + aac_main.c:2386-2400（prepare_bitstream_adts 返回 frame_length-7）
 *    ⇒ 送**裸访问单元** + 单独的头部参数，并置 adts_format = TRUE
 *
 * ── 与 IDA 反编译的交叉验证（见 work/cs11-aac-jni/evidence/A1-findings.md）──
 *   反编译里 aacd_decoder_init 读 cfg+20 当工作内存基址、cfg+168 必须为 16/24、
 *   在 cfg+180/184/188（步长 12 × 6）写三组指针 —— 与头文件的
 *   aacd_mem_info.mem_info_sub[0].app_base_ptr / num_pcm_bits / ch_info[CHANS] 逐一吻合。
 *   下面的 _Static_assert 把这些偏移钉死；将来换编译器或改结构体会在编译期报错，
 *   而不是在车机上解出噪声。
 */

#include "diplay_aac_dec.h"

#include <stdlib.h>
#include <string.h>

/*
 * 本文件**只在 32 位 ARM 下编译**。
 *
 * 厂商 AAC 解码器只有 armeabi-v7a 版，而且下面所有结构体布局与 _Static_assert 都建立在
 * `sizeof(void*) == 4` 之上。arm64 上那套布局根本不成立（而且也无法 dlopen 一个 32 位 .so），
 * 因此这里在非 32 位 ARM 上编译成**空翻译单元**，由 diplay_aac_fsl.c 提供明确"不可用"的桩。
 *
 * 这样做的价值：断言不只是文档，它会**阻止**一份在 arm64 上按错误偏移读写厂商结构体的实现被构建出来。
 */
#if !defined(__arm__) || defined(__aarch64__)
/* 非 32 位 ARM：空单元。 */
#else

/* ── 厂商 ABI 结构体（逐字对应 aacd_dec_interface.h；不改字段顺序）───────────── */

#define AACD_MAX_NUM_MEM_REQS (5 + 1 + 1 + 1) /* = 8 */
#define AACD_PATH_LEN 128
#define AACD_LEN_TAG 4
#define AACD_TRUE 1
#define AACD_FALSE 0
#define AAC_FRAME_SIZE 1024
#define CHANS 6 /* FCHANS(3)+SCHANS(0)+BCHANS(2)+LCHANS(1)+XCHANS(0) */

#define AACD_16_BIT_OUTPUT 16
#define AACD_24_BIT_OUTPUT 24

#define AACD_ERROR_NO_ERROR 0

typedef struct {
    int aacd_size;
    int aacd_type;      /* AACD_FAST_MEMORY(0) / AACD_SLOW_MEMORY(1) */
    int aacd_mem_desc;  /* AACD_MEM_SCRATCH(0) / AACD_MEM_PERSISTENT(1) */
    int aacd_priority;
    void *app_base_ptr;
} AACD_Mem_Alloc_Info_Sub;

typedef struct {
    int aacd_num_reqs;
    AACD_Mem_Alloc_Info_Sub mem_info_sub[AACD_MAX_NUM_MEM_REQS];
} AACD_Mem_Alloc_Info;

typedef struct {
    int num_ele;
    int ele_is_cpe[1 << AACD_LEN_TAG];
    int ele_tag[1 << AACD_LEN_TAG];
} AACD_EleList;

typedef struct {
    int present;
    int ele_tag;
    int pseudo_enab;
} AACD_MIXdown;

typedef struct {
    int profile;
    int sampling_rate_idx;
    AACD_EleList front;
    AACD_EleList side;
    AACD_EleList back;
    AACD_EleList lfe;
    AACD_EleList data;
    AACD_EleList coupling;
    AACD_MIXdown mono_mix;
    AACD_MIXdown stereo_mix;
    AACD_MIXdown matrix_mix;
    char comments[1];
    int buffer_fullness;
    int tag;
} AACD_ProgConfig;

typedef struct {
    int num_pce;
    AACD_ProgConfig *pce;
    int ChannelConfig;
    int SamplingFreqIndex;
    int BitstreamType;
    int BitRate;
    int BufferFullness;
    int ProtectionAbsent;
    int CrcCheck;
    int frame_length;
} AACD_Block_Params_Tmp;

typedef struct {
    int num_pce;
    AACD_ProgConfig *pce;
    int ChannelConfig;
    int SamplingFreqIndex;
    int BitstreamType;
    int BitRate;
    int BufferFullness;
    int ProtectionAbsent;
    int CrcCheck;
    int frame_length;
    int scalOutObjectType;
    int scalOutNumChannels;
    int sampleRate;
    int framelengthflag;
    int iMulti_Channel_Support;
    int bsacDecLayer;
} AACD_Block_Params;

typedef struct {
    int sbrd_down_sample;
    int sbrd_stereo_downmix;
} SBRD_Decoder_Config;

typedef struct {
    int *present;
    char *fext;
    int *file_opened;
} AACD_Ch_Info;

typedef struct {
    void *ps_aacd_ptr;
    void *psStreamSbr;
    void *poutbuf;
    void *psStreamSbr_tmp;
    void *psStreamSbr_restore;
} SBR_Temp_Info;

struct AACD_decoder_config_struct {
    AACD_Mem_Alloc_Info aacd_mem_info;
    void *aacd_decode_info_struct_ptr;
    unsigned char num_pcm_bits;
    long *AACD_bno;
    AACD_Block_Params *params;
    AACD_Ch_Info ch_info[CHANS];
    int adts_format;
    int packet_loss;
    unsigned char *aacd_initialized_data_start;
    void *sbrd_info_struct_ptr;
    SBRD_Decoder_Config sbrd_dec_config;
    void *pContext;
    SBR_Temp_Info sbr_temp_struct;
    int aac_plus_enable;
};

struct AACD_decoder_info_struct {
    unsigned int aacd_sampling_frequency;
    unsigned int aacd_num_channels;
    unsigned int aacd_bit_rate;
    unsigned int aacd_len;
    unsigned int aacd_frame_size;
    char output_path[AACD_PATH_LEN];
    unsigned int BitsInBlock;
    char ch_is_present[CHANS];
    unsigned int AACD_bno;
};

typedef struct AACD_decoder_config_struct AACD_Decoder_Config;
typedef struct AACD_decoder_info_struct AACD_Decoder_info;

/* 编译器已假定指针与 int 同为 4 字节（否则上面的偏移断言都无从谈起）。 */
_Static_assert(sizeof(void *) == 4, "本 ABI 假定 32 位指针");

typedef int AACD_OutputFmtType;

/* ── ABI 断言 ────────────────────────────────────────────────────────────────
 * 只钉**能从反编译唯一确定**的偏移。凡是 H 里字段顺序 + 编译器对齐后存在歧义的
 * （例如 sbrd_info_struct_ptr 是落在 0x108 还是 8 字节对齐后的 0x10C），
 * **一律不写断言**——假断言比没有断言更危险，它会让人以为验过了。
 *
 * 三个锚点都与 `aacd_decoder_init` 的反编译逐一吻合
 * （work/cs11-aac-jni/evidence/A1-findings.md E4.2）：
 *   - 反编译读 cfg+20 当工作内存基址 ⇒ aacd_mem_info.mem_info_sub[0].app_base_ptr
 *     （aacd_mem_info 占 4 + 8*20 = 0xA4 字节，所以是它而不是别的字段）
 *   - 反编译要求 cfg+168 为 16 或 24 ⇒ num_pcm_bits
 *   - 反编译在 cfg+180/184/188 写三元指针、步长 12、共 6 组 ⇒ ch_info[6]
 */
_Static_assert(sizeof(AACD_Mem_Alloc_Info_Sub) == 20, "mem_info_sub = 4 DWORD + 指针 = 20 字节");
_Static_assert(sizeof(AACD_Mem_Alloc_Info) == 4 + 20 * 8, "mem_info = num_reqs + 8 项");
_Static_assert(sizeof(AACD_Ch_Info) == 12, "ch_info 三元指针，IDA 观测步长 12");
_Static_assert(sizeof(AACD_Block_Params) == 64, "Block_Params = 16 字段（15 int + 1 指针）");

_Static_assert(offsetof(AACD_Decoder_Config, aacd_mem_info) == 0x00, "cfg+0x00");
_Static_assert(offsetof(AACD_Decoder_Config, num_pcm_bits) == 0xA8, "IDA: cfg+168=0xA8，必须 16 或 24");
_Static_assert(offsetof(AACD_Decoder_Config, ch_info) == 0xB4, "IDA: cfg+180=0xB4，步长 12 ×6");
/* 配置结构必须容得下 IDA 里用到的 cfg+300。 */
_Static_assert(sizeof(AACD_Decoder_Config) >= 0x130, "cfg 必须至少 0x130 字节");

_Static_assert(offsetof(AACD_Decoder_info, aacd_len) == 12, "IDA: dec_info+12 是入参长度");
_Static_assert(offsetof(AACD_Decoder_info, aacd_frame_size) == 16, "IDA: dec_info+16 是出参帧长");
_Static_assert(offsetof(AACD_Decoder_info, output_path) == 20, "IDA: dec_info+20 起 output_path");
_Static_assert(offsetof(AACD_Decoder_info, BitsInBlock) == 148, "IDA: dec_info+148 是位计数");
_Static_assert(offsetof(AACD_Decoder_info, ch_is_present) == 152, "紧跟 BitsInBlock");
_Static_assert(sizeof(AACD_Decoder_info) == 164, "dec_info 共 164 字节");

/* ── 实现 ──────────────────────────────────────────────────────────────────── */

/* 定义（头文件里已 `typedef struct diplay_aac_decoder diplay_aac_decoder;`，
   这里只用带标签的 struct 定义，避免 typedef 重定义）。 */
struct diplay_aac_decoder {
    diplay_aac_query_dec_mem_fn query_dec_mem;
    diplay_aac_decoder_init_fn decoder_init;
    diplay_aac_decode_frame_fn decode_frame;
    diplay_aac_version_info_fn version_info;

    AACD_Decoder_Config *config;
    AACD_Decoder_info info;
    AACD_Block_Params params;
    int *out_buf;         /* Q15 交错，容量 out_capacity 个 int */
    int out_capacity;     /* 单通道样本容量 */    int in_channels;      /* 最近一次解码得到的声道数（诊断用） */
    int in_rate;          /* 最近一次解码得到的采样率 */
    int bno;              /* 厂商要 long*；每个解码器一个计数器 */
};

/* 配置结构的总大小：IDA 里 aacd_decoder_init 用到 cfg+300，所以必须 ≥ 0x130。 */
_Static_assert(sizeof(AACD_Decoder_Config) >= 0x130, "cfg 必须至少 0x130 字节");

/* ── 实现 ──────────────────────────────────────────────────────────────────── */

/* 资源只能用 8 字节对齐的块；用 posix_memalign 保证，且记录以便释放。 */
static void *alloc_chunk(int size) {
    if (size <= 0) return NULL;
    void *p = NULL;
    /* 32 字节对齐：厂商 memaccess=-UL41，对齐不足会直接跑飞。 */
    if (posix_memalign(&p, 32, (size_t) size) != 0) return NULL;
    memset(p, 0, (size_t) size);
    return p;
}

diplay_aac_decoder *diplay_aac_decoder_create(diplay_aac_query_dec_mem_fn query_dec_mem,
                                             diplay_aac_decoder_init_fn decoder_init,
                                             diplay_aac_decode_frame_fn decode_frame,
                                             diplay_aac_version_info_fn version_info,
                                             int max_channels) {
    if (query_dec_mem == NULL || decoder_init == NULL || decode_frame == NULL) {
        return NULL;
    }
    if (max_channels < 1 || max_channels > CHANS) max_channels = 2;

    diplay_aac_decoder *d = (diplay_aac_decoder *) calloc(1, sizeof(*d));
    if (d == NULL) return NULL;
    d->query_dec_mem = query_dec_mem;
    d->decoder_init = decoder_init;
    d->decode_frame = decode_frame;
    d->version_info = version_info;
    /* 厂商解码器按码流自己的声道数写最多 CHANS 路输出；按调用方诉求（通常 2）分配
       时，多声道或畸形 ADTS 帧会在 aacd_decode_frame 内越界写。缓冲区必须按上限分配。 */
    d->out_capacity = CHANS * AAC_FRAME_SIZE;

    /* 1) 先让厂商库填内存需求清单（头文件：aacd_query_dec_mem(dec_config) 就地填 aacd_mem_info） */
    d->config = (AACD_Decoder_Config *) calloc(1, sizeof(AACD_Decoder_Config));
    if (d->config == NULL) {
        free(d);
        return NULL;
    }
    int rc = d->query_dec_mem(d->config);
    if (rc != AACD_ERROR_NO_ERROR) {
        free(d->config);
        free(d);
        return NULL;
    }

    /* 2) 逐个分配并清零（参考实现 aac_main.c:884-913；清零是厂商注释要求的 TLSbo74884） */
    int nr = d->config->aacd_mem_info.aacd_num_reqs;
    if (nr < 0 || nr > AACD_MAX_NUM_MEM_REQS) {
        free(d->config);
        free(d);
        return NULL;
    }
    for (int i = 0; i < nr; i++) {
        AACD_Mem_Alloc_Info_Sub *mem = &d->config->aacd_mem_info.mem_info_sub[i];
        mem->app_base_ptr = alloc_chunk(mem->aacd_size);
        if (mem->app_base_ptr == NULL) {
            diplay_aac_decoder_destroy(d); /* 已分配的会被回收 */
            return NULL;
        }
    }

    /* 3) 输出缓冲：Q15、sample-major 交错，每通道 AAC_FRAME_SIZE 样本 */
    d->out_buf = (int *) alloc_chunk((int) (sizeof(int) * (size_t) d->out_capacity));
    if (d->out_buf == NULL) {
        diplay_aac_decoder_destroy(d);
        return NULL;
    }

    /* 4) 固定字段 */
    d->config->num_pcm_bits = AACD_16_BIT_OUTPUT; /* 只有 16/24 被接受，见反编译 cfg+0xA8 */
    d->config->adts_format = AACD_TRUE;           /* 我们是 ADTS 流（RTP 里去掉头之后的裸 AU + 单独 params） */
    d->config->packet_loss = 0;
    d->config->aac_plus_enable = 0;
    d->config->params = &d->params;
    {
        /* 厂商要 long*；解码器结构体里本来就有 bno 字段。这里曾是进程级 static，
         * 两路 AAC（媒体/导航）并发解码时会互相踩计数器，厂商的状态机因此错乱。 */
        d->config->AACD_bno = (long *) &d->bno;
    }

    /* 5) 初始化 */
    rc = d->decoder_init(d->config);
    if (rc != AACD_ERROR_NO_ERROR) {
        diplay_aac_decoder_destroy(d);
        return NULL;
    }
    return d;
}

void diplay_aac_decoder_destroy(diplay_aac_decoder *d) {
    if (d == NULL) return;
    if (d->config != NULL) {
        int nr = d->config->aacd_mem_info.aacd_num_reqs;
        if (nr < 0 || nr > AACD_MAX_NUM_MEM_REQS) nr = AACD_MAX_NUM_MEM_REQS;
        for (int i = 0; i < nr; i++) {
            free(d->config->aacd_mem_info.mem_info_sub[i].app_base_ptr);
            d->config->aacd_mem_info.mem_info_sub[i].app_base_ptr = NULL;
        }
        free(d->config);
    }
    free(d->out_buf);
    free(d);
}

/**
 * 解一帧。返回 0 成功；非 0 = 厂商返回码原样上报（见头文件 AACD_RET_TYPE）。
 *
 * @param au       裸 AAC 访问单元（不含 ADTS 头）
 * @param au_len   访问单元字节数
 * @param hdr      ADTS 头字段（调用方已从 ADTS 帧头解析）
 * @param pcm_out  Q15 交错输出；成功时写入 (aacd_len * channels) 个 short
 * @param out_cap  pcm_out 的 short 容量
 * @param out_samples 出参：写入的样本数（已乘声道数）
 * @param out_channels 出参：声道数
 * @param out_rate 出参：采样率
 */
int diplay_aac_decode_frame(diplay_aac_decoder *d,
                            const unsigned char *au, int au_len,
                            const diplay_aac_adts_header *hdr,
                            short *pcm_out, int out_cap,
                            int *out_samples, int *out_channels, int *out_rate) {
    if (d == NULL || au == NULL || au_len <= 0 || hdr == NULL || pcm_out == NULL) {
        return -1;
    }

    /* 把 ADTS 头字段填进 Block_Params（逐字对应 aac_aux.c:816-832） */
    memset(&d->params, 0, sizeof(d->params));
    d->params.num_pce = 0;
    d->params.pce = NULL;
    d->params.ChannelConfig = hdr->channel_config;
    d->params.SamplingFreqIndex = hdr->sampling_freq_index;
    /* BitstreamType: ADTS 里 buffer_fullness == 0x7ff 表示 VBR(=1)，否则 CBR(=0) */
    d->params.BitstreamType = (hdr->adts_buffer_fullness == 0x7ff) ? 1 : 0;
    d->params.BitRate = 0; /* 参考实现注明 "Caution !" */
    d->params.BufferFullness = hdr->adts_buffer_fullness << 5; /* 单位由 32bit 字换算成 bit */
    d->params.ProtectionAbsent = hdr->protection_absent;
    d->params.CrcCheck = 0;
    /* 注意：这里传的是**裸 AU 长度**（ADTS 头已被上游剥掉），
       与参考实现 prepare_bitstream_adts 返回 frame_length-7 的口径一致。 */
    d->params.frame_length = au_len;

    memset(&d->info, 0, sizeof(d->info));
    d->info.aacd_len = (unsigned int) au_len;
    d->info.output_path[0] = '\0';
    d->info.BitsInBlock = 0;

    memset(d->out_buf, 0, sizeof(int) * (size_t) d->out_capacity);

    int rc = d->decode_frame(d->config, &d->info, d->out_buf, (signed char *) au, au_len);
    if (rc != AACD_ERROR_NO_ERROR && rc != 31 /* AACD_ERROR_START_BLOCK：输出由错误隐藏产生，可当无错用 */) {
        return rc;
    }

    int ch = (int) d->info.aacd_num_channels;
    if (ch < 1 || ch > CHANS) ch = (hdr->channel_config == 1) ? 1 : 2;
    int frames = (int) d->info.aacd_len;
    if (frames <= 0 || frames > AAC_FRAME_SIZE) return -2;

    int total = frames * ch;
    if (total > out_cap) return -2;

    /* 输出转换：16bit 时 outbuf[i] 直接就是 Q15（参考实现 aac_main.c:1374 的 `*psOutbuf = outbuf[i]`）。
       布局是 sample-major 交错：out[sample][channel]。 */
    for (int i = 0; i < total; i++) {
        pcm_out[i] = (short) d->out_buf[i];
    }

    if (out_samples != NULL) *out_samples = total;
    if (out_channels != NULL) *out_channels = ch;
    if (out_rate != NULL) {
        /* 采样率索引 → Hz（头文件枚举 AACD_samp_rate_info 的公共取值） */
        static const int rates[13] = {96000, 88200, 64000, 48000, 44100, 32000, 24000,
                                      22050, 16000, 12000, 11025, 8000, 7350};
        int idx = hdr->sampling_freq_index;
        *out_rate = (idx >= 0 && idx < 13) ? rates[idx] : 48000;
    }
    d->in_channels = ch;
    d->in_rate = (out_rate != NULL) ? *out_rate : 0;
    return 0;
}

#endif /* 32 位 ARM */
