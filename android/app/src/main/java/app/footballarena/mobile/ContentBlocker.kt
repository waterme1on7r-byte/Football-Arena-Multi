package app.footballarena.mobile

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.atomic.AtomicInteger

/** The same preference and rules cover pages, frames, popups and service-worker fetches. */
class ContentBlocker(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("football-arena", Context.MODE_PRIVATE)
    val blockedRequests = AtomicInteger()
    val blockedWorkerRequests = AtomicInteger()
    init {
        AdRules.loadBundledHosts(context.assets.open("ad-hosts.txt").bufferedReader().use { it.readText() })
    }
    fun blocksNavigation(url: String): Boolean {
        if (!prefs.getBoolean("adblock", true) || !AdRules.blocks(url)) return false
        blockedRequests.incrementAndGet()
        return true
    }
    fun intercept(url: String, serviceWorker: Boolean = false): WebResourceResponse? {
        if (!blocksNavigation(url)) return null
        if (serviceWorker) blockedWorkerRequests.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", 204, "No Content",
            mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"), "".byteInputStream())
    }
    fun installServiceWorkerBlocking() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest) = intercept(request.url.toString(), true)
            })
        }
    }
}
