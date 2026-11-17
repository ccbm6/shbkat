package com.shield.detector

import android.net.wifi.ScanResult
import kotlin.math.log10
import kotlin.math.pow

object RiskEngine {

    private val SSID_CAM = Regex(
        "(?i)(\\bcam|cam\\b|camera|ipcam|^ipc|spy|hidcam|nanny|\\bdvr|\\bnvr|^yi[-_ ]|xiaoyi|hdwifi|wificam|\\bptz|ezviz|^tapo|reolink|v380|icsee|xmeye|^p2p)"
    )
    private val STRONG = listOf(
        "hikvision", "dahua", "ipcam", "ip camera", "webcam", "camera", "dvr", "nvr", "onvif",
        "netwave", "mjpg", "mjpeg", "amcrest", "reolink", "foscam", "tapo", "ezviz", "uniview",
        "xiongmai", "xmeye", "icsee", "v380", "wyze", "wificam", "realm=\"ipc"
    )
    private val WEAK = listOf("goahead", "boa/", "uc-httpd", "hi3510")
    private val BLE_CAM = Regex("(?i)(cam|ipc|spy|hidcam|ezviz|v380|icsee|xmeye|wyze|yi[-_ ]|tapo|reolink)")

    // ---------- Wi-Fi ----------
    fun ssidOf(r: ScanResult): String {
        @Suppress("DEPRECATION") val s = r.SSID ?: ""
        return if (s.isBlank() || s == "<unknown ssid>") "" else s
    }

    fun security(r: ScanResult): String {
        val c = r.capabilities ?: ""
        return when {
            c.contains("WEP") -> "WEP"
            c.contains("SAE") -> "WPA3"
            c.contains("WPA2") || c.contains("RSN") -> "WPA2"
            c.contains("WPA") -> "WPA"
            c.contains("OWE") -> "OWE"
            else -> "مفتوحة"
        }
    }

    fun channel(f: Int): Int = when {
        f in 2412..2484 -> if (f == 2484) 14 else (f - 2407) / 5
        f in 5170..5895 -> (f - 5000) / 5
        f >= 5955 -> (f - 5950) / 5
        else -> 0
    }

    fun band(f: Int) = when { f < 3000 -> "2.4GHz"; f < 5900 -> "5GHz"; else -> "6GHz" }

    fun distance(rssi: Int, f: Int): Double =
        10.0.pow((27.55 - 20 * log10(f.toDouble()) + kotlin.math.abs(rssi)) / 20.0)

    fun wifi(results: List<ScanResult>): List<Finding> {
        val visiblePrefix = results.filter { ssidOf(it).isNotEmpty() }
            .map { it.BSSID.lowercase().take(14) }.toSet()
        val bySsid = results.filter { ssidOf(it).isNotEmpty() }.groupBy { ssidOf(it) }

        return results.map { r ->
            val ssid = ssidOf(r)
            val hidden = ssid.isEmpty()
            val sec = security(r)
            val v = OuiDb.lookup(r.BSSID)
            var score = 0
            val why = mutableListOf<String>()

            if (hidden) {
                score += 35; why += "شبكة مخفية (بدون اسم معلن)"
                if (r.level > -60) { score += 15; why += "إشارتها قوية جداً أي أنها قريبة منك" }
                if (r.BSSID.lowercase().take(14) in visiblePrefix) {
                    score -= 20; why += "تتبع غالباً لراوتر ظاهر قريب (شبكة ضيوف/مخفية عادية)"
                }
            }
            if (v != null && v.camera) { score += 45; why += "المصنّع ${v.name} (شركة كاميرات)" }
            else if (v != null) { score += 10; why += "المصنّع: ${v.name}" }
            if (ssid.isNotEmpty() && SSID_CAM.containsMatchIn(ssid)) { score += 40; why += "اسم الشبكة يشبه أسماء الكاميرات" }
            if (ssid.startsWith("DIRECT-")) { score += 10; why += "Wi-Fi Direct (طابعات/أجهزة مباشرة)" }
            if (sec == "مفتوحة" && (hidden || score >= 30)) { score += 15; why += "بدون تشفير" }
            else if (sec == "مفتوحة") why += "شبكة مفتوحة بدون تشفير"
            if (sec == "WEP") { score += 5; why += "تشفير WEP ضعيف" }
            if (ssid.isNotEmpty()) {
                val group = bySsid[ssid].orEmpty()
                if (sec == "مفتوحة" && group.any { security(it) != "مفتوحة" }) {
                    score += 30; why += "نفس الاسم موجود بتشفير وبدونه: احتمال شبكة مزيفة (Evil Twin)"
                }
            }
            if (OuiDb.isRandomized(r.BSSID)) why += "عنوان MAC عشوائي"

            val d = distance(r.level, r.frequency)
            Finding(
                key = r.BSSID,
                kind = Kind.WIFI,
                title = if (hidden) "شبكة مخفية" else ssid,
                subtitle = "${r.BSSID} • ${band(r.frequency)} • قناة ${channel(r.frequency)} • ${r.level}dBm • ~${"%.0f".format(d)}م • $sec",
                score = score.coerceIn(0, 100),
                reasons = why
            )
        }
    }

    // ---------- LAN ----------
    fun lan(res: LanResult): List<Finding> {
        val gw = res.self?.gateway
        return res.hosts.map { h ->
            var score = 0
            val why = mutableListOf<String>()
            val text = ((h.http ?: "") + " " + (h.ssdp ?: "")).lowercase()

            if (h.rtsp) { score += 55; why += "يستجيب لبروتوكول بث الفيديو RTSP" }
            if (h.onvif) { score += 45; why += "يعلن نفسه كجهاز ONVIF (كاميرا/مسجّل شبكي)" }
            h.ports.forEach {
                when (it) {
                    554, 8554 -> { score += 20; why += "منفذ $it (بث RTSP)" }
                    37777 -> { score += 40; why += "منفذ 37777 (Dahua)" }
                    34567 -> { score += 40; why += "منفذ 34567 (XiongMai/DVR)" }
                    8000 -> { score += 20; why += "منفذ 8000 (Hikvision SDK)" }
                    8899 -> { score += 20; why += "منفذ 8899 (ONVIF شائع)" }
                    1935 -> { score += 10; why += "منفذ 1935 (RTMP)" }
                }
            }
            val strong = STRONG.firstOrNull { text.contains(it) }
            if (strong != null) { score += 35; why += "رد الجهاز يحتوي على «$strong»" }
            else WEAK.firstOrNull { text.contains(it) }?.let { score += 10; why += "خادم ويب مدمج شائع بالكاميرات ($it)" }

            val isGw = h.ip == gw
            if (isGw && !h.rtsp && !h.onvif) { score = minOf(score, 10); why += "هذا هو الراوتر" }

            Finding(
                key = h.ip,
                kind = Kind.LAN,
                title = if (isGw) "الراوتر ${h.ip}" else "جهاز ${h.ip}",
                subtitle = "منافذ مفتوحة: ${if (h.ports.isEmpty()) "لا شيء" else h.ports.sorted().joinToString()}",
                score = score.coerceIn(0, 100),
                reasons = why
            )
        }
    }

    // ---------- BLE ----------
    fun ble(devs: List<BleDev>): List<Finding> = devs.map { d ->
        var score = 0
        val why = mutableListOf<String>()
        var type = "جهاز بلوتوث"

        if (d.name != null && BLE_CAM.containsMatchIn(d.name)) { score += 45; why += "الاسم يشبه الكاميرات" }
        d.mfr[0x004C]?.let {
            if (it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x12) {
                score += 35; type = "متتبّع محتمل (Find My/AirTag)"; why += "بث شبيه بأجهزة التتبع من آبل"
            }
        }
        if (d.uuids.any { it.startsWith("0000feed", true) }) { score += 35; type = "متتبّع Tile"; why += "خدمة Tile" }
        if (d.uuids.any { it.startsWith("0000fd5a", true) }) { score += 35; type = "متتبّع SmartTag"; why += "خدمة Samsung SmartTag" }
        if (d.mfr.containsKey(0x02E5)) { score += 15; why += "شريحة Espressif (شائعة بأجهزة DIY)" }
        if (d.name == null && d.rssi > -45) { score += 15; why += "جهاز بدون اسم وقريب جداً" }
        Finding(
            key = d.address,
            kind = Kind.BLE,
            title = d.name ?: type,
            subtitle = "${d.address} • ${d.rssi}dBm • $type",
            score = score.coerceIn(0, 100),
            reasons = why
        )
    }
}
