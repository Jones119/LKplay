/*
 * diplay_aac_dec.h — Freescale AAC 解码器包装的公开接口（docs/_digest/79 的 A1）
 *
 * 这些结构体与厂商 ABI 一一对应，定义为放在 .c 内部；本头文件只暴露**不含厂商类型**的接口，
 * 这样 JNI 层不必重复声明它们。
 */
#ifndef DIPLAY_AAC_DEC_H
#define DIPLAY_AAC_DEC_H

/* 一帧最多 1024 样本/声道（AAC_FRAME_SIZE）。 */
#define DIPLAY_AAC_MAX_CHANNELS 6
#define DIPLAY_AAC_MAX_OUT_SAMPLES (DIPLAY_AAC_MAX_CHANNELS * 1024)

/* 由 ADTS 帧头解析出来的字段（上游从 RTP 里拿到裸 AU，这些值从 ADTS 头取）。 */
typedef struct {
    int sampling_freq_index;  /* 0..12 */
    int channel_config;       /* 1..7 */
    int protection_absent;    /* 1 = 无 CRC */
    int adts_buffer_fullness; /* 0x7FF 表示 VBR */
} diplay_aac_adts_header;

typedef struct diplay_aac_decoder diplay_aac_decoder;

/*
 * 厂商结构体只做前置声明：调用方（JNI 层）从来不需要看到它们的布局，
 * 只需要把 dlsym 得到的函数指针传进来。
 */
struct AACD_decoder_config_struct;
struct AACD_decoder_info_struct;

/* 与 aacd_dec_interface.h 的四个原型逐字对应。 */
typedef int (*diplay_aac_query_dec_mem_fn)(struct AACD_decoder_config_struct *);
typedef int (*diplay_aac_decoder_init_fn)(struct AACD_decoder_config_struct *);
typedef int (*diplay_aac_decode_frame_fn)(struct AACD_decoder_config_struct *,
                                          struct AACD_decoder_info_struct *,
                                          int * /* out_buf */,
                                          signed char * /* buf */,
                                          int /* buf_len */);
typedef const signed char *(*diplay_aac_version_info_fn)(void);

/** 用已 dlsym 到的四个入口创建一个解码器；失败返回 NULL。 */
diplay_aac_decoder *diplay_aac_decoder_create(diplay_aac_query_dec_mem_fn query_dec_mem,
                                             diplay_aac_decoder_init_fn decoder_init,
                                             diplay_aac_decode_frame_fn decode_frame,
                                             diplay_aac_version_info_fn version_info,
                                             int max_channels);

/** 释放解码器与它持有的所有厂商内存块。可传 NULL。 */
void diplay_aac_decoder_destroy(diplay_aac_decoder *d);

/**
 * 解一帧。返回 0 成功；非 0 = 厂商返回码原样上报（见 aacd_dec_interface.h 的 AACD_RET_TYPE）。
 *
 * @param au           裸 AAC 访问单元（**不含** ADTS 头）
 * @param au_len       访问单元字节数
 * @param hdr          ADTS 头字段
 * @param pcm_out      Q15 交错输出（sample-major）
 * @param out_cap      pcm_out 的 short 容量
 * @param out_samples  出参：写入的 short 数（已乘声道数）
 * @param out_channels 出参：声道数
 * @param out_rate     出参：采样率 Hz
 */
int diplay_aac_decode_frame(diplay_aac_decoder *d,
                            const unsigned char *au, int au_len,
                            const diplay_aac_adts_header *hdr,
                            short *pcm_out, int out_cap,
                            int *out_samples, int *out_channels, int *out_rate);

#endif /* DIPLAY_AAC_DEC_H */
