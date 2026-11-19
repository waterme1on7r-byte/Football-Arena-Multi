package app.footballarena.mobile

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Session routing keyed by stable site ID. A changed address invalidates learned routing. */
class AutoRouting(private val now: () -> Long = System::currentTimeMillis) {
    private data class SiteRoute(val url: String, @Volatile var bypassUntil: Long = 0)
    private val sites = ConcurrentHashMap<String, SiteRoute>()
    private val owners = ConcurrentHashMap<String, String>()
    private val expiresAfter = 4 * 60 * 60 * 1000L

    @Synchronized fun registerSite(id: String, url: String) {
        if (sites[id]?.url != url) {
            owners.entries.removeAll { it.value == id }
            sites[id] = SiteRoute(url)
        }
        URI(url).host?.let { registerHost(id, it) }
    }
    fun registerHost(id: String, host: String) { if (sites.containsKey(id)) owners[host.lowercase()] = id }
    fun usesBypass(host: String): Boolean = owners[host.lowercase()]?.let { sites[it] }?.let { it.bypassUntil > now() } ?: false
    @Synchronized fun retryWithBypass(host: String): Boolean {
        val route = owners[host.lowercase()]?.let { sites[it] } ?: return false
        if (route.bypassUntil > now()) return false
        route.bypassUntil = now() + expiresAfter
        return true
    }
    fun reset() { sites.values.forEach { it.bypassUntil = 0 } }
}
