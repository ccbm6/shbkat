package com.shield.detector

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LanScanner(context: Context) {
    private val app = context.applicationContext
    private val ports = intArrayOf(80, 81, 443, 554, 8000, 8080, 8554, 8899, 9000, 37777, 34567, 1935)
    private val httpPorts = listOf(80, 81, 8000, 8080, 8899, 9000)
    private val rtspPorts = listOf(554, 8554)

    private val ssdpMsg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()

    private fun wsMsg() = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><e:Envelope " +
        "xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" " +
        "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
        "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" " +
        "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"><e:Header>" +
        "<w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>" +
        "<w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
        "<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
        "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>").toByteArray()

    @Suppress("DEPRECATION")
    fun findWifi(): LanInfo? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val ip = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress ?: continue
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            return LanInfo(ip, gw, n)
        }
        return null
    }

    suspend fun scan(onProgress: (Float) -> Unit): LanResult = withContext(Dispatchers.IO) {
        val me = findWifi() ?: return@withContext LanResult(
            null, emptyList(), 0, "أنت غير متصل بشبكة واي فاي. اتصل بالشبكة التي تريد فحصها أولاً."
        )
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("shield").apply { setReferenceCounted(false); acquire() }
        try {
            val base = me.ip.substringBeforeLast('.')
            val targets = (1..254).map { "$base.$it" }.filter { it != me.ip }
            val open = ConcurrentHashMap<String, MutableSet<Int>>()
            val alive = ConcurrentHashMap.newKeySet<String>()
            val sem = Semaphore(200)
            val total = targets.size * ports.size
            val done = AtomicInteger(0)

            val ssdpJob = async { udp(me, ssdpMsg, "239.255.255.250", 1900, 3500) }
            val wsJob = async { udp(me, wsMsg(), "239.255.255.250", 3702, 3500) }

            coroutineScope {
                targets.flatMap { ip ->
                    ports.map { p ->
                        async {
                            sem.withPermit {
                                try {
                                    Socket().use { s -> me.network.bindSocket(s); s.connect(InetSocketAddress(ip, p), 350) }
                                    open.getOrPut(ip) { ConcurrentHashMap.newKeySet() }.add(p)
                                    alive.add(ip)
                                } catch (e: ConnectException) {
                                    if (e.message?.contains("refused", true) == true) alive.add(ip)
                                } catch (_: Exception) {
                                }
                                val d = done.incrementAndGet()
                                if (d % 40 == 0) onProgress(d.toFloat() / total * 0.8f)
                            }
                        }
                    }
                }.awaitAll()
            }
            val ssdp = ssdpJob.await()
            val ws = wsJob.await()
            onProgress(0.85f)

            val candidates = (open.keys + ssdp.keys + ws.keys).filter { it != me.ip }.toSet()
            val hosts = candidates.map { ip ->
                async {
                    val op = open[ip]?.toList().orEmpty()
                    val http = op.filter { it in httpPorts }.take(2).mapNotNull { httpProbe(me, ip, it) }
                        .joinToString(" ").ifBlank { null }
                    val rtsp = op.filter { it in rtspPorts }.any { rtspProbe(me, ip, it) }
                    LanHost(ip, op, http, rtsp, ip in ws.keys, ssdp[ip]?.take(400))
                }
            }.awaitAll()
            onProgress(1f)
            LanResult(me, hosts, alive.size, null)
        } finally {
            runCatching { lock.release() }
        }
    }

    private fun udp(me: LanInfo, payload: ByteArray, group: String, port: Int, listenMs: Long): Map<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            DatagramSocket().use { s ->
                me.network.bindSocket(s)
                s.soTimeout = 500
                s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(group), port))
                val end = System.currentTimeMillis() + listenMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val ip = p.address.hostAddress ?: continue
                        out[ip] = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun httpProbe(me: LanInfo, ip: String, port: Int): String? = try {
        Socket().use { s ->
            me.network.bindSocket(s)
            s.connect(InetSocketAddress(ip, port), 800)
            s.soTimeout = 1200
            s.getOutputStream().write("GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: Mozilla/5.0\r\n\r\n".toByteArray())
            val buf = ByteArray(3000)
            val n = s.getInputStream().read(buf)
            if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun rtspProbe(me: LanInfo, ip: String, port: Int): Boolean = try {
        Socket().use { s ->
            me.network.bindSocket(s)
            s.connect(InetSocketAddress(ip, port), 800)
            s.soTimeout = 1200
            s.getOutputStream().write("OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
            val buf = ByteArray(200)
            val n = s.getInputStream().read(buf)
            n > 0 && String(buf, 0, n, Charsets.ISO_8859_1).startsWith("RTSP/1.0")
        }
    } catch (_: Exception) {
        false
    }
}
