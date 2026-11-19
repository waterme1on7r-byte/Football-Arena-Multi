package app.footballarena.mobile

import java.net.URI

/** Compatibility is keyed by site identity so rotating addresses keep the same browser profile. */
object SiteBrowserPolicy {
    fun usesDesktop(siteId: String?) = siteId == "extratv"
    fun desktopAgent(defaultAgent: String): String {
        val chrome = Regex("Chrome/[0-9.]+").find(defaultAgent)?.value ?: "Chrome/131.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }
    fun desktopUrl(siteId: String?, url: String, configuredUrl: String): String {
        if (!usesDesktop(siteId)) return url
        return try {
            val source = URI(url); val configuredHost = URI(configuredUrl).host ?: return url
            val base = configuredHost.removePrefix("www.").removePrefix("m.")
            if (source.scheme !in listOf("http", "https") || !source.host.equals("m.$base", true)) return url
            buildString {
                append(source.scheme); append("://"); append(configuredHost)
                if (source.port != -1) { append(':'); append(source.port) }
                append(source.rawPath.orEmpty())
                source.rawQuery?.let { append('?'); append(it) }
                source.rawFragment?.let { append('#'); append(it) }
            }
        } catch (_: Exception) { url }
    }
}
