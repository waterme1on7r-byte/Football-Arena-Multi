package app.footballarena.mobile

import org.json.JSONObject
import java.net.IDN
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/** DNS for tunneled origins. Never fall back to the ISP's potentially redirected answers. */
class SecureDns(private val fetch: (String) -> String = ::fetchDns) {
    private data class Entry(val addresses: List<InetAddress>, val expires: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    @Volatile var lastResult = "No encrypted lookup yet"
        private set

    fun resolve(rawHost: String): List<InetAddress> {
        val host = IDN.toASCII(rawHost.trimEnd('.')).lowercase()
        require(host.isNotBlank() && host.length <= 253) { "Invalid DNS name" }
        cache[host]?.takeIf { it.expires > System.currentTimeMillis() }?.let {
            lastResult = "$host → ${it.addresses.joinToString { a -> a.hostAddress ?: "?" }} (cached DoH)"
            return it.addresses
        }
        var last: Exception? = null
        // The IP-based second endpoint does not need Android DNS to bootstrap the resolver.
        for (provider in listOf("https://dns.google/resolve", "https://1.1.1.1/dns-query")) {
            try {
                for (type in listOf(1, 28)) {
                    val name = URLEncoder.encode(host, "UTF-8")
                    val json = JSONObject(fetch("$provider?name=$name&type=$type&edns_client_subnet=0.0.0.0%2F0"))
                    require(json.optInt("Status", -1) == 0 && !json.optBoolean("TC")) { "DNS status ${json.optInt("Status", -1)}" }
                    val answers = json.optJSONArray("Answer")
                    val addresses = mutableListOf<InetAddress>()
                    var ttl = 300
                    if (answers != null) for (i in 0 until answers.length()) {
                        val answer = answers.optJSONObject(i) ?: continue
                        ttl = minOf(ttl, answer.optInt("TTL", 0).coerceAtLeast(0))
                        if (answer.optInt("type") != type) continue
                        val value = answer.optString("data")
                        val numeric = if (type == 1) value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && value.split('.').all { it.toInt() in 0..255 }
                            else value.contains(':') && value.matches(Regex("[0-9a-fA-F:]+"))
                        if (numeric) addresses.add(InetAddress.getByName(value))
                    }
                    if (addresses.isNotEmpty()) {
                        val result = addresses.distinctBy { it.hostAddress }
                        if (ttl > 0) cache[host] = Entry(result, System.currentTimeMillis() + ttl * 1000L)
                        lastResult = "$host → ${result.joinToString { it.hostAddress ?: "?" }} (${URL(provider).host}, HTTPS)"
                        return result
                    }
                }
                throw UnknownHostException("No A or AAAA answers")
            } catch (e: Exception) { last = e }
        }
        lastResult = "$host: encrypted DNS failed (${last?.message})"
        throw UnknownHostException("Encrypted DNS failed for $host: ${last?.message}")
    }
}

private fun fetchDns(url: String): String {
    val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpsURLConnection
    try {
        connection.connectTimeout = 5000; connection.readTimeout = 5000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/dns-json")
        require(connection.responseCode == 200) { "DoH HTTP ${connection.responseCode}" }
        val bytes = connection.inputStream.use { it.readBytesLimited(65536) }
        return bytes.toString(Charsets.UTF_8)
    } finally { connection.disconnect() }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "DNS reply too large" }; output.write(buffer, 0, n) }
    return output.toByteArray()
}
