package com.shield.detector

import android.net.Network

enum class Kind(val label: String) { WIFI("واي فاي"), LAN("الشبكة"), BLE("بلوتوث") }
enum class Risk(val label: String) { LOW("منخفض"), MEDIUM("متوسط"), HIGH("مرتفع") }

data class Finding(
    val key: String,
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val score: Int,
    val reasons: List<String>,
    val trusted: Boolean = false,
    val isNew: Boolean = false
) {
    val risk: Risk
        get() = when {
            trusted -> Risk.LOW
            score >= 60 -> Risk.HIGH
            score >= 30 -> Risk.MEDIUM
            else -> Risk.LOW
        }
}

data class LanInfo(val ip: String, val gateway: String?, val network: Network)
data class LanHost(
    val ip: String,
    val ports: List<Int>,
    val http: String?,
    val rtsp: Boolean,
    val onvif: Boolean,
    val ssdp: String?
)
data class LanResult(val self: LanInfo?, val hosts: List<LanHost>, val alive: Int, val error: String?)
data class BleDev(
    val address: String,
    val name: String?,
    val rssi: Int,
    val mfr: Map<Int, ByteArray>,
    val uuids: List<String>
)
