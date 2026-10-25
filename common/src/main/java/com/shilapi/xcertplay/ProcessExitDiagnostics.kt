package com.shilapi.xcertplay

import android.content.Context

/** Own-app numeric exit metadata only; never read traces, descriptions or process state blobs. */
internal object ProcessExitDiagnostics {
    private const val UNSUPPORTED = "Process exits available=false requiresApi=30"

    /** CS11: ApplicationExitInfo is API 30, so this report is never available on the head unit. */
    fun report(context: Context): String = UNSUPPORTED
}
