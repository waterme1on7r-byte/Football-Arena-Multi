package app.footballarena.mobile

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PaneLifecycleTest {
    private class RecordingWebView(context: Context) : WebView(context) {
        var detached = 0; var paused = 0; var reloads = 0; var navigations = 0
        override fun onDetachedFromWindow() { detached++; super.onDetachedFromWindow() }
        override fun onPause() { paused++; super.onPause() }
        override fun reload() { reloads++; super.reload() }
        override fun loadUrl(url: String) { navigations++; super.loadUrl(url) }
    }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun call(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun script(scenario: ActivityScenario<MainActivity>, page: WebView, source: String): String {
        val ready = CountDownLatch(1); var value = ""
        scenario.onActivity { page.evaluateJavascript(source) { value = it; ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS)); return value
    }
    @Test fun openingClosingAndFocusingPanesPreservesExistingDocument() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("football-arena", 0).edit().clear()
            .putBoolean("languageChosen", true).putBoolean("restore", false).putString("dpiMode", "off").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var match: RecordingWebView
            val loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                match = RecordingWebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String) { loaded.countDown() } }
                }
                @Suppress("UNCHECKED_CAST") val pages = field("pages").get(activity) as MutableMap<String, WebView>
                pages["match"] = match; field("selectedKey").set(activity, "match"); field("browser").set(activity, match)
                call(activity, "renderPages")
                match.loadDataWithBaseURL("https://fixture.invalid/", "<html><body><script>window.fixtureToken=String(Math.random()); window.playerPosition=17;</script>Match fixture</body></html>", "text/html", "UTF-8", null)
            }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            val token = script(scenario, match, "window.fixtureToken")
            assertNotEquals("null", token)
            scenario.onActivity { activity ->
                @Suppress("UNCHECKED_CAST") val pages = field("pages").get(activity) as MutableMap<String, WebView>
                val parent = match.parent
                for (i in 1..3) { pages["extra-$i"] = RecordingWebView(activity); call(activity, "renderPages"); assertSame(parent, match.parent) }
                field("selectedKey").set(activity, "extra-1"); call(activity, "closePane")
                MainActivity::class.java.getDeclaredMethod("setLayout", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 1)
                field("selectedKey").set(activity, "extra-3"); field("browser").set(activity, pages.getValue("extra-3")); call(activity, "renderPages")
                val siteClass = Class.forName("app.footballarena.mobile.Site")
                val site = siteClass.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    .newInstance("match", "Match", "https://fixture.invalid/", false, false)
                MainActivity::class.java.getDeclaredMethod("openSite", siteClass).apply { isAccessible = true }.invoke(activity, site)
                assertSame(match, pages["match"]); assertEquals(3, pages.size)
                assertSame("The WebView parent must survive pane operations", parent, match.parent)
                assertEquals(0, match.detached); assertEquals(0, match.paused); assertEquals(0, match.reloads); assertEquals(0, match.navigations)
            }
            assertEquals("The document must survive unchanged", token, script(scenario, match, "window.fixtureToken"))
            assertEquals("17", script(scenario, match, "window.playerPosition"))
        }
    }
}
