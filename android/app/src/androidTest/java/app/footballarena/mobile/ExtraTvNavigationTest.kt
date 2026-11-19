package app.footballarena.mobile

import android.webkit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExtraTvNavigationTest {
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Test fun desktopProfilePreventsMobileRedirectAndKeepsPageLinksNavigable() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("football-arena", 0).edit().clear()
            .putBoolean("languageChosen", true).putBoolean("restore", false).putString("dpiMode", "off").commit()
        val first = CountDownLatch(1); val next = CountDownLatch(1)
        var mobileRequests = 0
        lateinit var page: WebView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                page = MainActivity::class.java.getDeclaredMethod("createPage").apply { isAccessible = true }.invoke(activity) as WebView
                val client = MainActivity::class.java.getDeclaredMethod("makePageClient").apply { isAccessible = true }.invoke(activity) as WebViewClient
                page.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        if (request.url.host?.startsWith("m.") == true) mobileRequests++
                        val content = if (request.url.path == "/__arena_next") "<html><body>Next page</body></html>" else """<html><body><a id="next" href="/__arena_next?value=a%26b">Next</a><script>if(navigator.userAgent.includes('Android'))location.href=location.href.replace('www.','m.');</script></body></html>"""
                        return WebResourceResponse("text/html", "UTF-8", content.byteInputStream())
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = client.shouldOverrideUrlLoading(view, request)
                    override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) = client.onPageStarted(view, url, icon)
                    override fun onPageFinished(view: WebView, url: String) {
                        client.onPageFinished(view, url)
                        if (android.net.Uri.parse(url).path == "/__arena_next") next.countDown() else first.countDown()
                    }
                }
                @Suppress("UNCHECKED_CAST") val pages = field("pages").get(activity) as MutableMap<String, WebView>
                @Suppress("UNCHECKED_CAST") val owners = field("viewSites").get(activity) as MutableMap<WebView, String>
                pages["extratv"] = page; owners[page] = "extratv"
                field("selectedKey").set(activity, "extratv"); field("browser").set(activity, page); field("routingReady").setBoolean(activity, true)
                MainActivity::class.java.getDeclaredMethod("renderPages").apply { isAccessible = true }.invoke(activity)
                MainActivity::class.java.getDeclaredMethod("loadPage", WebView::class.java, String::class.java).apply { isAccessible = true }
                    .invoke(activity, page, "https://m.extrabet2113.com/__arena_start")
            }
            assertTrue(first.await(15, TimeUnit.SECONDS))
            scenario.onActivity {
                assertFalse(page.settings.userAgentString.contains("Android"))
                assertEquals("www.extrabet2113.com", android.net.Uri.parse(page.url).host)
                page.evaluateJavascript("document.getElementById('next').click()", null)
            }
            assertTrue("Page links must navigate normally", next.await(15, TimeUnit.SECONDS))
            scenario.onActivity {
                assertEquals("www.extrabet2113.com", android.net.Uri.parse(page.url).host)
                assertTrue(page.url!!.contains("value=a%26b"))
                assertEquals(0, mobileRequests)
            }
        }
    }
}
