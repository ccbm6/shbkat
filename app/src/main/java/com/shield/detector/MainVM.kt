package com.shield.detector

import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val prefs = ctx.getSharedPreferences("shield", Context.MODE_PRIVATE)

    private val raw = MutableStateFlow<Map<Kind, List<Finding>>>(emptyMap())
    val trusted = MutableStateFlow(prefs.getStringSet("trusted", emptySet())!!.toSet())
    val busy = MutableStateFlow<Set<Kind>>(emptySet())
    val progress = MutableStateFlow(0f)
    val notes = MutableStateFlow<Map<Kind, String>>(emptyMap())

    val findings: StateFlow<Map<Kind, List<Finding>>> = combine(raw, trusted) { r, t ->
        r.mapValues { (_, list) ->
            list.map { if (it.key in t) it.copy(trusted = true) else it }
                .sortedByDescending { if (it.trusted) -1 else it.score }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun note(k: Kind, s: String) = notes.update { it + (k to s) }

    fun toggleTrust(key: String) {
        val n = if (key in trusted.value) trusted.value - key else trusted.value + key
        trusted.value = n
        prefs.edit().putStringSet("trusted", n).apply()
    }

    fun scanAll() = Kind.values().forEach { scan(it) }

    fun scan(kind: Kind) {
        if (kind in busy.value) return
        viewModelScope.launch {
            busy.update { it + kind }
            note(kind, "")
            try {
                val list: List<Finding>? = when (kind) {
                    Kind.WIFI -> wifiRun()
                    Kind.LAN -> lanRun()
                    Kind.BLE -> bleRun()
                }
                if (list != null) publish(kind, list)
            } catch (e: SecurityException) {
                note(kind, "الصلاحيات ناقصة. امنح التطبيق الصلاحيات من إعدادات الهاتف.")
            } catch (e: Exception) {
                note(kind, "تعذّر الفحص: ${e.message}")
            } finally {
                busy.update { it - kind }
            }
        }
    }

    private fun publish(kind: Kind, list: List<Finding>) {
        val last = prefs.getStringSet("last_$kind", null)
        val marked = list.map { it.copy(isNew = last != null && it.key !in last) }
        prefs.edit().putStringSet("last_$kind", list.map { it.key }.toSet()).apply()
        raw.update { it + (kind to marked) }
    }

    private fun locationOn(): Boolean =
        (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled

    private suspend fun wifiRun(): List<Finding>? {
        if (!locationOn()) { note(Kind.WIFI, "شغّل الموقع (GPS) من إعدادات الهاتف، فأندرويد يشترطه لفحص الواي فاي."); return null }
        val res = WifiScanner(ctx).scan()
        if (res.isEmpty()) note(Kind.WIFI, "لا نتائج. تأكد من الصلاحيات، وتذكّر أن أندرويد يحدّ الفحص بـ4 مرات كل دقيقتين.")
        return RiskEngine.wifi(res)
    }

    private suspend fun lanRun(): List<Finding>? {
        progress.value = 0f
        val res = LanScanner(ctx).scan { progress.value = it }
        if (res.error != null) { note(Kind.LAN, res.error); return null }
        note(Kind.LAN, "عنوانك: ${res.self?.ip} • الراوتر: ${res.self?.gateway ?: "؟"} • أجهزة نشطة: ${res.alive}")
        return RiskEngine.lan(res)
    }

    private suspend fun bleRun(): List<Finding>? {
        val res = BleScanner(ctx).scan(8000)
        if (res == null) { note(Kind.BLE, "البلوتوث مغلق. شغّله ثم أعد الفحص."); return null }
        return RiskEngine.ble(res)
    }

    fun report(): String {
        val sb = StringBuilder("تقرير كاشف الدرع\n\n")
        findings.value.forEach { (k, list) ->
            val risky = list.filter { it.risk != Risk.LOW }
            sb.append("== ${k.label}: ${list.size} نتيجة، ${risky.size} مشبوهة ==\n")
            risky.forEach { f ->
                sb.append("• [${f.risk.label} ${f.score}] ${f.title}\n  ${f.subtitle}\n")
                f.reasons.forEach { sb.append("   - $it\n") }
            }
            sb.append('\n')
        }
        sb.append("ملاحظة: التطبيق أداة مساعدة ولا يضمن اكتشاف كل الأجهزة.")
        return sb.toString()
    }
}
