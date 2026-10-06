package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** CS11 移植：原 I2C 诊断界面（Compose）已移除，先以最简视图占位。 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "CS11 port - diagnostics placeholder" })
    }
}
