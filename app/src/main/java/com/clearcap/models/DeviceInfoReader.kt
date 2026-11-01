package com.clearcap.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

/** Reads the phone's real memory, storage, CPU and Android version. Used only to advise on models. */
object DeviceInfoReader {
    fun read(ctx: Context): DeviceInfo {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val free = runCatching { StatFs(ctx.filesDir.path).availableBytes / 1_000_000L }.getOrDefault(0L)
        return DeviceInfo(
            totalRamMb = (mi.totalMem / 1_048_576L).toInt(),
            availRamMb = (mi.availMem / 1_048_576L).toInt(),
            freeStorageMb = free,
            cores = Runtime.getRuntime().availableProcessors(),
            abis = Build.SUPPORTED_ABIS.toList(),
            sdk = Build.VERSION.SDK_INT,
        )
    }
}
