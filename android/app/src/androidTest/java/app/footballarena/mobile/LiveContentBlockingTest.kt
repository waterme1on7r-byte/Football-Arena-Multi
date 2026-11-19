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
    private fun evaluate(page:WebView, script:String):String {
        var value="null"; val latch=CountDownLatch(1)
        instrumentation.runOnMainSync { page.evaluateJavascript(script) { value=it;latch.countDown() } }
        latch.await(3,TimeUnit.SECONDS);return value
    }
    private fun awaitBoolean(page:WebView,script:String,timeout:Long=30000):Boolean {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<deadline) {
            if(evaluate(page,script)=="true")return true
            Thread.sleep(250)
        }
        return false
    }
    private fun screenshot(name:String,page:WebView,adSelector:String) {
        // Animated live banners can keep the UI busy forever. Capture the current compositor frame.
        val paintToken=java.util.UUID.randomUUID().toString()
        evaluate(page,"requestAnimationFrame(()=>requestAnimationFrame(()=>window.__faPaintReady=${JSONObject.quote(paintToken)}));true")
        awaitBoolean(page,"window.__faPaintReady===${JSONObject.quote(paintToken)}",3000)
        val visual=CountDownLatch(1)
        instrumentation.runOnMainSync {
            page.postVisualStateCallback(1,object:WebView.VisualStateCallback(){override fun onComplete(requestId:Long){page.invalidate();visual.countDown()}})
        }
        assertTrue("WebView did not commit the checked DOM to its visual state",visual.await(5,TimeUnit.SECONDS))
        Thread.sleep(400) // Allow the renderer frame to reach Android's compositor; never wait for UI idle.
        for(command in listOf("mkdir -p /data/local/tmp/football-arena-ui", "screencap -p /data/local/tmp/football-arena-ui/$name.png")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        val raw=evaluate(page,"""JSON.stringify({url:location.href,ready:document.readyState,site:window.__faSiteId,enabled:window.__faBlockAds,stats:window.__faContentStats,
            ads:Array.from(document.querySelectorAll(${JSONObject.quote(adSelector)})).slice(0,30).map(e=>({id:e.id,cls:e.className,height:e.getBoundingClientRect().height,display:getComputedStyle(e).display,marked:!!e.closest('[data-fa-ad-hidden]')})),
            banners:Array.from(document.querySelectorAll('a img')).slice(0,25).map(e=>({alt:e.alt,height:e.getBoundingClientRect().height,marked:!!e.closest('[data-fa-ad-hidden]')})),
            frames:Array.from(document.querySelectorAll('iframe')).slice(0,8).map(e=>({id:e.id,src:e.getAttribute('src'),height:e.getBoundingClientRect().height}))})""")
        val summary=runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(raw)
        // Gradle uninstalls the APK after instrumentation: write through shell-owned tee stdin.
        val pipes=instrumentation.uiAutomation.executeShellCommandRw("tee /data/local/tmp/football-arena-ui/$name.json")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(summary.toByteArray()) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
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
                MainActivity::class.java.declaredMethods.single { it.name=="openSite" }.apply { isAccessible=true }.invoke(activity,site)
                page=field("browser").get(activity) as WebView
            }
            try {
            val selector=JSONObject.quote(playerSelector)
            val reachable=awaitBoolean(page,"!!document.querySelector($selector)")
            if(!reachable)screenshot("live-$id-unavailable",page,adSelector)
            assumeTrue("Live $id was unreachable or challenged; no live-ad result is claimed",reachable)
            evaluate(page,"window.__faLiveDocumentToken='${java.util.UUID.randomUUID()}';window.__faLiveOriginalToken=window.__faLiveDocumentToken;true")
            // Wait for an actual promotion where possible; a site may not return an ad for this location.
            val ads=JSONObject.quote(adSelector)
            awaitBoolean(page,"Array.from(document.querySelectorAll($ads)).some(e=>e.getBoundingClientRect().height>0)",10000)
            screenshot("live-$id-before",page,adSelector)
            assertTrue("Live $id ad selectors matched no elements; cannot claim live filtering verification",evaluate(page,"document.querySelectorAll($ads).length>0")=="true")
            instrumentation.runOnMainSync {
                val activity=page.context as MainActivity
                activity.getSharedPreferences("football-arena",0).edit().putBoolean("adblock",true).putBoolean("autoskip",true).commit()
                MainActivity::class.java.getDeclaredMethod("installContentScript",WebView::class.java).apply { isAccessible=true }.invoke(activity,page)
                MainActivity::class.java.getDeclaredMethod("runAutoSkip",WebView::class.java).apply { isAccessible=true }.invoke(activity,page)
            }
            val cleaned=awaitBoolean(page,"""(()=>{
                const player=document.querySelector($selector);
                return Array.from(document.querySelectorAll($ads)).every(e=>e.getBoundingClientRect().height===0)
                    &&player&&player.getBoundingClientRect().width>0&&player.getBoundingClientRect().height>0
                    &&(${JSONObject.quote(id)}!=='patronspor'||getComputedStyle(document.body).backgroundImage==='none')
                    &&window.__faLiveDocumentToken===window.__faLiveOriginalToken;
            })()""")
            screenshot("live-$id-after",page,adSelector)
            assertTrue("Live $id still has visible banners or lost its player",cleaned)
            if(preRoll) {
                val skipped=awaitBoolean(page,"""(()=>{try{
                    const frame=document.querySelector($selector);const doc=frame.contentDocument;
                    const ad=doc&&doc.querySelector('#preroll-container');const main=doc&&doc.querySelector(${JSONObject.quote(if(id=="patronspor") "#player" else "#player-container")});
                    return !!ad&&!!main&&frame.contentWindow.getComputedStyle(ad).display==='none'
                        &&main.getBoundingClientRect().width>0&&main.getBoundingClientRect().height>0
                        &&Array.from(main.querySelectorAll('[data-poster],.poster-container')).every(e=>frame.contentWindow.getComputedStyle(e).backgroundImage==='none')
                        &&main.querySelectorAll('video[poster]').length===0;
                }catch(e){return false;}})()""",45000)
                screenshot("live-$id-player-after",page,adSelector)
                assertTrue("Live $id did not transition from its custom pre-roll to the main player",skipped)
            }
            } finally {
                // Finish before ActivityScenario.close(), which otherwise waits for animated pages to idle.
                instrumentation.runOnMainSync { (page.context as MainActivity).finish() }
            }
        }
    }
    @Test(timeout=120000) fun netsporFirstPartyBannersAndCustomPreroll() = checkSite("netspor","#videoPlayer",".banner-container.fullwidth",true)
    @Test(timeout=120000) fun mahsunFirstPartyBannersAndCustomPreroll() = checkSite("mahsunsports","#customIframe",".banner-container",true)
    @Test(timeout=120000) fun patronPromotionalBannersPreservePlayer() = checkSite("patronspor","iframe#player","#ustBannerLink,#altBannerLink,#footerBannerLink,.hellobar,.background-link",true)
    @Test(timeout=120000) fun ardaAdZonesPreservePlayer() = checkSite("ardaspor","#playerFrame","[id^='zone_'],a[href*='1xbet.com']")
    @Test(timeout=120000) fun trgoalsBannersPreservePlayer() = checkSite("trgoals","#customIframe",".ads-container,.ads_single_font,.match-flow-ad")
    @Test(timeout=120000) fun zeusBannersPreservePlayer() = checkSite("zeustv","#main-player-container","#ad-hellobar,#ad-top-banner,#ad-player-top,#ad-player-bottom,#ad-background,#page-skin-iframe")
}
