package com.shield.detector

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class BleScanner(context: Context) {
    private val app = context.applicationContext

    /** يرجع null إذا كان البلوتوث مغلقاً أو غير متوفر. */
    @SuppressLint("MissingPermission")
    suspend fun scan(ms: Long): List<BleDev>? {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) return null
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = ConcurrentHashMap<String, BleDev>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val rec = r.scanRecord
                val m = mutableMapOf<Int, ByteArray>()
                rec?.manufacturerSpecificData?.let { for (i in 0 until it.size()) m[it.keyAt(i)] = it.valueAt(i) }
                found[r.device.address] = BleDev(
                    r.device.address, rec?.deviceName, r.rssi, m,
                    rec?.serviceUuids?.map { it.toString() } ?: emptyList()
                )
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, cb)
            delay(ms)
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        return found.values.toList()
    }
}
