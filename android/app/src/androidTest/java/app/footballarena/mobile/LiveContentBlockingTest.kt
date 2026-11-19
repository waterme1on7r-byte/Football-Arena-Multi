package app.footballarena.mobile

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Public live-site checks. Unreachable/challenged sites are reported as skipped, never as verified. */
@RunWith(AndroidJUnit4::class)
class LiveContentBlockingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun evaluate(scenario:ActivityScenario<MainActivity>, page:WebView, script:String):String {
        var value="null"; val latch=CountDownLatch(1)
        scenario.onActivity { page.evaluateJavascript(script) { value=it;latch.countDown() } }
        latch.await(3,TimeUnit.SECONDS);return value
    }
    private fun awaitBoolean(scenario:ActivityScenario<MainActivity>,page:WebView,script:String,timeout:Long=30000):Boolean {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<deadline) {
            if(evaluate(scenario,page,script)=="true")return true
            Thread.sleep(250)
        }
        return false
    }
    private fun screenshot(name:String) {
        instrumentation.waitForIdleSync()
        for(command in listOf("mkdir -p /data/local/tmp/football-arena-ui", "screencap -p /data/local/tmp/football-arena-ui/$name.png")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }
    private fun checkSite(id:String,playerSelector:String,adSelector:String,preRoll:Boolean=false) {
        instrumentation.targetContext.getSharedPreferences("football-arena",0).edit().clear()
            .putBoolean("languageChosen",true).putBoolean("restore",false).putString("dpiMode","off")
            .putBoolean("adblock",false).putBoolean("autoskip",false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var page:WebView
            scenario.onActivity { activity ->
                val sites=field("sites").get(activity) as List<*>
                val site=sites.first { candidate -> candidate!!.javaClass.getDeclaredMethod("getId").apply { isAccessible=true }.invoke(candidate)==id }!!
                MainActivity::class.java.getDeclaredMethods.single { it.name=="openSite" }.apply { isAccessible=true }.invoke(activity,site)
                page=field("browser").get(activity) as WebView
            }
            val selector=JSONObject.quote(playerSelector)
            val reachable=awaitBoolean(scenario,page,"!!document.querySelector($selector)")
            if(!reachable)screenshot("live-$id-unavailable")
            assumeTrue("Live $id was unreachable or challenged; no live-ad result is claimed",reachable)
            evaluate(scenario,page,"window.__faLiveDocumentToken='${java.util.UUID.randomUUID()}';window.__faLiveOriginalToken=window.__faLiveDocumentToken;true")
            // Wait for an actual promotion where possible; a site may not return an ad for this location.
            val ads=JSONObject.quote(adSelector)
            awaitBoolean(scenario,page,"Array.from(document.querySelectorAll($ads)).some(e=>e.getBoundingClientRect().height>0)",10000)
            screenshot("live-$id-before")
            scenario.onActivity { activity ->
                activity.getSharedPreferences("football-arena",0).edit().putBoolean("adblock",true).putBoolean("autoskip",true).commit()
                MainActivity::class.java.getDeclaredMethod("installContentScript",WebView::class.java).apply { isAccessible=true }.invoke(activity,page)
                MainActivity::class.java.getDeclaredMethod("runAutoSkip",WebView::class.java).apply { isAccessible=true }.invoke(activity,page)
            }
            val cleaned=awaitBoolean(scenario,page,"""(()=>{
                const player=document.querySelector($selector);
                return Array.from(document.querySelectorAll($ads)).every(e=>e.getBoundingClientRect().height===0)
                    &&player&&player.getBoundingClientRect().width>0&&player.getBoundingClientRect().height>0
                    &&window.__faLiveDocumentToken===window.__faLiveOriginalToken;
            })()""")
            screenshot("live-$id-after")
            assertTrue("Live $id still has visible banners or lost its player",cleaned)
            if(preRoll) {
                val skipped=awaitBoolean(scenario,page,"""(()=>{try{
                    const frame=document.querySelector($selector);const doc=frame.contentDocument;
                    const ad=doc&&doc.querySelector('#preroll-container');const main=doc&&doc.querySelector('#player-container');
                    return !!ad&&!!main&&frame.contentWindow.getComputedStyle(ad).display==='none'
                        &&main.getBoundingClientRect().width>0&&main.getBoundingClientRect().height>0;
                }catch(e){return false;}})()""",45000)
                screenshot("live-$id-player-after")
                assertTrue("Live $id did not transition from its custom pre-roll to the main player",skipped)
            }
        }
    }
    @Test fun netsporFirstPartyBannersAndCustomPreroll() = checkSite("netspor","#videoPlayer",".banner-container.fullwidth",true)
    @Test fun mahsunFirstPartyBannersAndCustomPreroll() = checkSite("mahsunsports","#customIframe",".banner-container",true)
    @Test fun patronPromotionalBannersPreservePlayer() = checkSite("patronspor","iframe#player","#ustBannerLink,#altBannerLink,#footerBannerLink,.hellobar,.background-link")
    @Test fun ardaAdZonesPreservePlayer() = checkSite("ardaspor","#playerFrame","[id^='zone_'],a[href*='1xbet.com']")
}
