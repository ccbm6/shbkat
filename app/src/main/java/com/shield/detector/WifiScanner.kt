package com.shield.detector

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class WifiScanner(context: Context) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    suspend fun scan(): List<ScanResult> {
        withTimeoutOrNull(9000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val rcv = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        runCatching { app.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(true)
                    }
                }
                app.registerReceiver(rcv, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
                cont.invokeOnCancellation { runCatching { app.unregisterReceiver(rcv) } }
                // أندرويد يحدّ عدد الفحوص (4 كل دقيقتين)؛ إن رُفض نستخدم آخر نتائج مخزنة.
                if (!wm.startScan()) {
                    runCatching { app.unregisterReceiver(rcv) }
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
        return wm.scanResults ?: emptyList()
    }
}
