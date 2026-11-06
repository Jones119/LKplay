package com.shilapi.xcertplay.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

/**
 * Registers the USB permission-result receiver with a permission only this app holds.
 *
 * API 19 has no `RECEIVER_NOT_EXPORTED`, so a receiver registered at run time accepts a broadcast
 * from any app: another app could send the permission action with a `UsbDevice` and forge a
 * granted or denied result. The real result is delivered through this app's own `PendingIntent`,
 * which carries this app's identity and therefore holds the signature permission declared in
 * `shared/src/main/AndroidManifest.xml` (upstream 4c9af446).
 */
internal object UsbPermissionBroadcast {
    fun senderPermission(context: Context): String = "${context.packageName}.USB_PERMISSION_RESULT"

    fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        // API 19: the broadcast permission argument is the only way to restrict senders.
        context.registerReceiver(receiver, filter, senderPermission(context), null)
    }
}
