package com.shield.detector

/** قاعدة صغيرة قابلة للتوسعة لبادئات MAC (OUI) الخاصة بمصنّعي الكاميرات. */
object OuiDb {
    data class Vendor(val name: String, val camera: Boolean)

    private val map: Map<String, Vendor> = buildMap {
        listOf("4C:BD:8F", "C0:56:E3", "44:19:B6", "BC:AD:28", "28:57:BE", "54:C4:15", "A4:14:37")
            .forEach { put(it, Vendor("Hikvision", true)) }
        listOf("3C:EF:8C", "4C:11:BF", "90:02:A9", "E0:50:8B", "9C:14:63", "38:AF:29", "14:A7:8B")
            .forEach { put(it, Vendor("Dahua", true)) }
        put("EC:71:DB", Vendor("Reolink", true))
        put("9C:8E:CD", Vendor("Amcrest", true))
        listOf("2C:AA:8E", "D0:3F:27").forEach { put(it, Vendor("Wyze", true)) }
        listOf("24:0A:C4", "30:AE:A4", "7C:DF:A1", "84:0D:8E", "A4:CF:12", "24:6F:28", "B4:E6:2D")
            .forEach { put(it, Vendor("Espressif (شريحة IoT شائعة بالكاميرات المصنوعة منزلياً)", false)) }
    }

    fun lookup(mac: String?): Vendor? = mac?.uppercase()?.take(8)?.let { map[it] }

    /** عنوان عشوائي (locally administered) لا يمكن مطابقته مع مصنّع. */
    fun isRandomized(mac: String): Boolean {
        val c = mac.getOrNull(1)?.uppercaseChar() ?: return false
        return c in "26AE"
    }
}
