package app.footballarena.mobile

import android.webkit.*
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ContentFeaturesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun prepare() {
        instrumentation.targetContext.getSharedPreferences("football-arena", 0).edit().clear()
            .putBoolean("languageChosen", true).putBoolean("restore", false).putString("dpiMode", "off").commit()
    }
    @Test fun settingsUsesBoundedModalAndVerticalSidebar() {
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: android.app.Dialog
            scenario.onActivity { a ->
                dialog = MainActivity::class.java.getDeclaredMethod("settingsDialog").apply { isAccessible = true }.invoke(a) as android.app.Dialog
                val sidebar = dialog.window!!.decorView.findViewWithTag<LinearLayout>("settings-sidebar")
                assertEquals(LinearLayout.VERTICAL, sidebar.orientation)
                assertEquals(4, sidebar.childCount)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { a ->
                assertTrue(dialog.window!!.decorView.width < a.resources.displayMetrics.widthPixels)
                assertTrue(dialog.window!!.decorView.height < a.resources.displayMetrics.heightPixels)
            }
            for (cmd in listOf("mkdir -p /data/local/tmp/football-arena-ui", "screencap -p /data/local/tmp/football-arena-ui/tablet-settings.png")) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
            }
            scenario.onActivity { dialog.dismiss() }
        }
    }
    @Test fun temporaryLinkCanBeSavedWithoutRecreatingAnyPage() {
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                field("routingReady").setBoolean(a, false) // No external networking in this fixture.
                val open = MainActivity::class.java.getDeclaredMethod("openUrl", String::class.java).apply { isAccessible = true }
                open.invoke(a, "https://one.example/match")
                @Suppress("UNCHECKED_CAST") val pages = field("pages").get(a) as Map<String, WebView>
                val first = pages.values.last()
                open.invoke(a, "https://two.example/player")
                val second = pages.values.last()
                assertNotSame(first, second); assertTrue(pages.containsValue(first))
                val key = field("selectedKey").get(a) as String
                val bar = field("listBar").get(a) as LinearLayout
                assertTrue((bar.getChildAt(bar.childCount - 1) as LinearLayout).getChildAt(1).contentDescription.toString().isNotBlank())
                val save = MainActivity::class.java.getDeclaredMethod("saveTemporarySite", String::class.java, String::class.java).apply { isAccessible = true }
                    .invoke(a, key, "https://two.example/player") as ArenaDialog
                save.getButton(-1).performClick()
                assertSame(second, pages[key]); assertTrue(pages.containsValue(first))
                val persisted = a.getSharedPreferences("football-arena", 0).getString("sites", "")!!
                val saved = org.json.JSONArray(persisted)
                val site = (0 until saved.length()).map { saved.getJSONObject(it) }.single { it.getString("id") == key }
                assertEquals("https://two.example/player", site.getString("url"))
            }
        }
    }
    @Test fun autoSkipRunsInsideCrossOriginPlayerFrameAndLeavesMatchControlsAlone() {
        prepare()
        val skipped = CountDownLatch(1)
        lateinit var page: WebView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                page = MainActivity::class.java.getDeclaredMethod("createPage").apply { isAccessible = true }.invoke(a) as WebView
                page.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse {
                        if (r.url.path == "/skipped") skipped.countDown()
                        val html = if (r.url.host == "player.example") """
                            <html><body><button onclick="location.href='/wrong'">Close</button>
                            <button onclick="location.href='/skipped'">Skip ad</button></body></html>
                        """ else "<html><body><iframe src='https://player.example/frame'></iframe></body></html>"
                        return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                    }
                }
                page.loadUrl("https://site.example/match")
            }
            assertTrue("Cross-origin player skip button should be clicked", skipped.await(15, TimeUnit.SECONDS))
            scenario.onActivity { page.destroy() }
        }
    }
}
