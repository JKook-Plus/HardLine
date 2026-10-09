package dev.hardline.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/** Asks the home router (UPnP Internet Gateway Device) to forward ports to this phone. */
class Upnp {
    private var controlUrl: String? = null
    private var serviceType: String? = null
    private val mapped = ArrayList<Int>()
    @Volatile var externalAddress: String? = null
        private set

    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun discover(): Boolean {
        if (controlUrl != null) return true
        val request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n".toByteArray()
        val location = runCatching {
            DatagramSocket().use { s ->
                s.soTimeout = 3000
                s.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
                val buf = ByteArray(2048)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                String(buf, 0, p.length).lines().firstOrNull { it.startsWith("location:", true) }?.substringAfter(':')?.trim()
            }
        }.getOrNull() ?: return false
        val xml = runCatching { URL(location).readText() }.getOrNull() ?: return false
        for (type in listOf("urn:schemas-upnp-org:service:WANIPConnection:1", "urn:schemas-upnp-org:service:WANIPConnection:2", "urn:schemas-upnp-org:service:WANPPPConnection:1")) {
            val at = xml.indexOf(type)
            if (at < 0) continue
            val control = Regex("<controlURL>(.*?)</controlURL>").find(xml, at)?.groupValues?.get(1) ?: continue
            controlUrl = URL(URL(location), control).toString()
            serviceType = type
            return true
        }
        return false
    }

    private fun soap(action: String, arguments: String): String? = runCatching {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$serviceType\">$arguments</u:$action></s:Body></s:Envelope>"
        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
    }.getOrNull()

    /** Maps [ports] (TCP); returns true when the router accepted every mapping. */
    fun map(ports: List<Int>, description: String): Boolean {
        if (!discover()) return false
        val local = localAddress() ?: return false
        var ok = true
        for (port in ports) {
            val args = "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol><NewInternalPort>$port</NewInternalPort>" +
                "<NewInternalClient>$local</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>$description</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration>"
            if (soap("AddPortMapping", args) != null) mapped += port else ok = false
        }
        externalAddress = soap("GetExternalIPAddress", "")?.let { Regex("<NewExternalIPAddress>(.*?)</NewExternalIPAddress>").find(it)?.groupValues?.get(1) }
        Log.i("Upnp", "mapped $mapped, external $externalAddress")
        return ok
    }

    fun unmapAll() {
        if (controlUrl == null) return
        for (port in mapped) soap("DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>")
        mapped.clear()
        externalAddress = null
    }
}
