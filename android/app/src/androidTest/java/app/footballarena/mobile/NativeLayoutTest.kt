package app.footballarena.mobile

import android.graphics.Bitmap
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
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeLayoutTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-check").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
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
                controls.forEach { assertTrue("Pane icons must stay compact", it.width <= 48 * density); assertTrue("Every pane control must be on screen", it.getGlobalVisibleRect(android.graphics.Rect())) }
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
