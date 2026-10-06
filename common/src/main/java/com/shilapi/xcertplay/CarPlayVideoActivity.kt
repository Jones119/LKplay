package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import com.shilapi.xcertplay.airplay.VideoInCar

/**
 * CS11 移植：iOS27 video-in-car 播放器暂为占位实现（原实现依赖 Media3/ExoPlayer，minSdk 19 不可用）。
 * 对外接口与 CarPlayVideo 协调器保持一致，功能后续按需重建。
 */
class CarPlayVideoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    fun state(): VideoInCar.PlayerState? = null
    fun skip(millis: Int) {}
    fun applyRate() {}
    fun applySeek() {}
    fun load() {}
}
