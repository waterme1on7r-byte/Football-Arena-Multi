package app.footballarena.mobile

import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeaderLayoutTest {
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Test fun initialBadgeRetainsItsColorAcrossRedraws() {
        val badge = SiteInitialBadge("ARDASPOR", 60).apply { setBounds(0,0,60,60) }
        val image = android.graphics.Bitmap.createBitmap(60,60,android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        image.eraseColor(android.graphics.Color.rgb(11,15,20)); badge.draw(canvas)
        val first = image.getPixel(12,30)
        image.eraseColor(android.graphics.Color.rgb(11,15,20)); badge.draw(canvas)
        val second = image.getPixel(12,30)
        assertEquals("The border alpha must not fade the next badge draw", first, second)
        assertTrue(maxOf(android.graphics.Color.red(second), android.graphics.Color.green(second), android.graphics.Color.blue(second)) > 150)
        image.recycle()
    }
    @Test fun desktopStyleHeaderHasSeparateSiteMenusAndAccurateQuickCount() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("football-arena", 0).edit().clear()
            .putBoolean("languageChosen", true).putBoolean("restore", false).putString("dpiMode", "off").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Football Arena", (field("title").get(activity) as TextView).text.toString())
                val bar = field("listBar").get(activity) as LinearLayout
                assertTrue(bar.childCount > 0)
                for (i in 0 until bar.childCount) {
                    val chip = bar.getChildAt(i) as LinearLayout
                    assertEquals(2, chip.childCount)
                    val name = chip.getChildAt(0) as Button
                    val options = chip.getChildAt(1) as Button
                    assertTrue(name.compoundDrawables[0] is SiteInitialBadge)
                    assertTrue(name.isClickable); assertTrue(options.isClickable); assertEquals("⋮", options.text.toString())
                }
                val sites = field("sites").get(activity) as List<*>
                val count = sites.count { site -> site!!.javaClass.getDeclaredMethod("getQuick").apply { isAccessible = true }.invoke(site) as Boolean }
                assertEquals(count.toString(), (field("quickCount").get(activity) as TextView).text.toString())
                assertTrue((field("quickButton").get(activity) as LinearLayout).isClickable)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                if (activity.resources.configuration.screenWidthDp >= 1200) {
                    val bar = field("listBar").get(activity) as LinearLayout
                    val last = bar.getChildAt(bar.childCount - 1); val bounds = android.graphics.Rect()
                    assertTrue("All default sites should fit on the tablet header", last.getGlobalVisibleRect(bounds))
                    assertEquals("Last site must not be clipped", last.width, bounds.width())
                }
            }
        }
    }
}
