package com.ttv20.rsyncbackup.settings

import android.content.Context
import android.provider.Settings

object DeviceHostnameReader {
    fun read(context: Context): String? =
        runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
