package app.footballarena.mobile

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLayoutTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // The Gradle test runner uninstalls the app afterward; shell-owned output survives cleanup.
        for (command in listOf("mkdir -p /data/local/tmp/football-arena-ui", "screencap -p /data/local/tmp/football-arena-ui/$name.png")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }
    @Test fun failedSelectedPageCannotInheritGreenDpiFromAnotherPage() {
        instrumentation.targetContext.getSharedPreferences("football-arena", 0).edit().clear()
            .putBoolean("languageChosen", true).putBoolean("restore", false).putString("dpiMode", "off").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                val page = field("browser").get(activity) as WebView
                field("dpiProxy").set(activity, DpiProxy(32))
                field("dpiState").set(activity, "Always bypass")
                @Suppress("UNCHECKED_CAST") val loaded = field("bypassPages").get(activity) as MutableSet<WebView>
                loaded.add(page)
                val paint = MainActivity::class.java.getDeclaredMethod("paintDpiStatus").apply { isAccessible = true }
                paint.invoke(activity)
                val button = field("dpiButton").get(activity) as Button
                fun dotColor(): Int = (button.text as android.text.Spanned).getSpans(0, 1, android.text.style.ForegroundColorSpan::class.java).single().foregroundColor
                assertEquals(android.graphics.Color.rgb(67, 222, 143), dotColor())
                MainActivity::class.java.getDeclaredMethod("pageFailed", WebView::class.java, String::class.java, String::class.java).apply { isAccessible = true }
                    .invoke(activity, page, "https://test.invalid/", "Connection reset")
                assertEquals(android.graphics.Color.rgb(246, 196, 81), dotColor())
                val other = WebView(activity); loaded.add(other); paint.invoke(activity)
                assertEquals("Another page must not turn the failed page green", android.graphics.Color.rgb(246, 196, 81), dotColor())
                loaded.remove(other); other.destroy()
            }
        }
    }

    @Test fun tabletControlsAndContentSizedPopups() {
        instrumentation.targetContext.getSharedPreferences("football-arena", 0).edit().clear()
            .putBoolean("languageChosen", true).putString("language", "tr").putBoolean("restore", false).putString("dpiMode", "off").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                @Suppress("UNCHECKED_CAST")
                val pages = MainActivity::class.java.getDeclaredField("pages").apply { isAccessible = true }.get(activity) as MutableMap<String, WebView>
                for (i in 1..4) pages["test-$i"] = WebView(activity).apply { setBackgroundColor(android.graphics.Color.rgb(11, 15, 20)) }
                MainActivity::class.java.getDeclaredField("selectedKey").apply { isAccessible = true }.set(activity, "test-1")
                MainActivity::class.java.getDeclaredMethod("renderPages").apply { isAccessible = true }.invoke(activity)
                AppStrings.apply(activity.window.decorView)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(400)
            val image = instrumentation.uiAutomation.takeScreenshot()
            scenario.onActivity { activity ->
                val controls = children(activity.window.decorView).filterIsInstance<Button>().filter { it.contentDescription?.toString() in listOf("Toggle main pane", "Ana bölmeyi değiştir") }
                assertEquals(4, controls.size)
                val density = activity.resources.displayMetrics.density
                controls.forEach { button ->
                    assertTrue("Pane icons must stay compact", button.width <= 48 * density)
                    assertTrue("Every pane control must be on screen", button.getGlobalVisibleRect(android.graphics.Rect()))
                    val position = IntArray(2); button.getLocationOnScreen(position)
                    var ink = 0
                    for (x in button.width / 4 until button.width * 3 / 4) for (y in button.height / 4 until button.height * 3 / 4) {
                        if (image.getPixel(position[0] + x, position[1] + y) == android.graphics.Color.rgb(11, 15, 20)) ink++
                    }
                    assertTrue("Main-pane icon must be visible on its gold button", ink > 5)
                }
            }
            image.recycle()
            screenshot("tablet-four-panes")
            var dialog: ArenaDialog? = null
            scenario.onActivity { activity -> dialog = ArenaDialog.Builder(activity).setTitle("Choose language").setItems(arrayOf("Türkçe", "English")) { _, _ -> }.show() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue("Two choices must not produce a full-height dialog", dialog!!.window!!.decorView.height < activity.resources.displayMetrics.heightPixels * .65)
                dialog!!.dismiss()
                MainActivity::class.java.getDeclaredMethod("languageDialog", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
            }
            screenshot("tablet-language")
        }
    }
}
