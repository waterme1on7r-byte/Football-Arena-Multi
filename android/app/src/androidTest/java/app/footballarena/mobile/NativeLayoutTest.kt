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

    @Test fun tabletControlsAndContentSizedPopups() {
        instrumentation.targetContext.getSharedPreferences("football-arena", 0).edit().clear()
            .putBoolean("languageChosen", true).putString("language", "tr").putBoolean("restore", false).putString("dpiMode", "off").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                @Suppress("UNCHECKED_CAST")
                val pages = MainActivity::class.java.getDeclaredField("pages").apply { isAccessible = true }.get(activity) as MutableMap<String, WebView>
                for (i in 1..4) pages["test-$i"] = WebView(activity)
                MainActivity::class.java.getDeclaredField("selectedKey").apply { isAccessible = true }.set(activity, "test-1")
                MainActivity::class.java.getDeclaredMethod("renderPages").apply { isAccessible = true }.invoke(activity)
                AppStrings.apply(activity.window.decorView)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val controls = children(activity.window.decorView).filterIsInstance<Button>().filter { it.contentDescription?.toString() in listOf("Toggle main pane", "Ana bölmeyi değiştir") }
                assertEquals(4, controls.size)
                val density = activity.resources.displayMetrics.density
                controls.forEach { button ->
                    assertTrue("Pane icons must stay compact", button.width <= 48 * density)
                    assertTrue("Every pane control must be on screen", button.getGlobalVisibleRect(android.graphics.Rect()))
                    val image = android.graphics.Bitmap.createBitmap(button.width, button.height, android.graphics.Bitmap.Config.ARGB_8888)
                    button.draw(android.graphics.Canvas(image))
                    var ink = 0
                    for (x in button.width / 4 until button.width * 3 / 4) for (y in button.height / 4 until button.height * 3 / 4) {
                        if (image.getPixel(x, y) == android.graphics.Color.rgb(11, 15, 20)) ink++
                    }
                    image.recycle()
                    assertTrue("Main-pane icon must be visible on its gold button", ink > 5)
                }
            }
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
