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
class AutoSkipIntegrationTest {
    private fun prepare(autoSkip: Boolean = true) {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("football-arena", 0).edit().clear()
            .putBoolean("languageChosen", true).putBoolean("restore", false).putString("dpiMode", "off").putBoolean("autoskip", autoSkip).commit()
    }
    private fun awaitJs(scenario: ActivityScenario<MainActivity>, view: WebView, expression: String) {
        repeat(100) {
            val latch = CountDownLatch(1); var result = ""
            scenario.onActivity { view.evaluateJavascript(expression) { result = it; latch.countDown() } }
            assertTrue(latch.await(3, TimeUnit.SECONDS)); if (result == "true") return
            Thread.sleep(100)
        }
        var snapshot = ""; val latch = CountDownLatch(1)
        scenario.onActivity { view.evaluateJavascript("JSON.stringify({upper:window.upper,icon:window.icon,jw:window.jw,countdown:window.countdown,custom:window.custom,adPosition:window.adPosition,width:innerWidth,hidden:document.hidden})") { snapshot = it; latch.countDown() } }
        latch.await(3,TimeUnit.SECONDS)
        fail("Fixture did not reach: $expression; snapshot=$snapshot")
    }
    private fun create(a: MainActivity, html: (WebResourceRequest) -> String): WebView {
        val page = MainActivity::class.java.getDeclaredMethod("createPage").apply { isAccessible = true }.invoke(a) as WebView
        page.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest) = WebResourceResponse("text/html", "UTF-8", html(request).byteInputStream())
        }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val pages = field("pages").get(a) as MutableMap<String,WebView>
        pages["fixture"] = page
        field("selectedKey").set(a,"fixture"); field("browser").set(a,page)
        MainActivity::class.java.getDeclaredMethod("renderPages").apply { isAccessible = true }.invoke(a)
        return page
    }
    @Test fun observedNetsporBannersAndHiddenPrerollControlAreHandledWithoutBlankingThePlayer() {
        prepare()
        val root = """<html><body>
            <div class="banner-container"><a href="/"><img id="logo" alt="Netspor" width="100" height="30"></a></div>
            <div class="banner-container fullwidth"><a href="https://promo.invalid/"><img alt="Banner" width="300" height="90"></a></div>
            <iframe id="videoPlayer" src="https://player.example/event.html" width="500" height="300"></iframe>
            <script>window.token=Math.random();window.original=window.token;addEventListener('message',e=>{if(e.data==='main-player-started')window.started=true;});</script>
            </body></html>"""
        val player = """<html><head><style>#skip-ad-btn{display:none}#preroll-container{width:300px;height:180px}</style></head><body>
            <div id="preroll-container"><video id="preroll-video"></video><button id="skip-ad-btn" onclick="skipAd(event)">Reklamı Geç ▶</button></div>
            <div id="player-container" style="display:none"><video id="match"></video></div>
            <div id="splash-banner"><a href="https://promo.invalid/"><img width="300" height="60"></a></div>
            <script>function skipAd(e){e.preventDefault();e.stopPropagation();document.getElementById('preroll-container').style.display='none';document.getElementById('player-container').style.display='block';parent.postMessage('main-player-started','*');}</script>
            </body></html>"""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var page: WebView
            scenario.onActivity { a ->
                page=create(a) { if(it.url.host=="player.example") player else root }
                val owners=MainActivity::class.java.getDeclaredField("viewSites").apply { isAccessible=true }.get(a)
                @Suppress("UNCHECKED_CAST") (owners as MutableMap<WebView,String>)[page]="netspor"
                MainActivity::class.java.getDeclaredField("routingReady").apply { isAccessible=true }.setBoolean(a,true)
                MainActivity::class.java.getDeclaredMethod("loadPage",WebView::class.java,String::class.java).apply { isAccessible=true }.invoke(a,page,"https://rotated.example/")
            }
            awaitJs(scenario,page,"window.started===true&&getComputedStyle(document.querySelector('.banner-container.fullwidth')).display==='none'&&getComputedStyle(logo).display!=='none'&&videoPlayer.getBoundingClientRect().width>0&&window.token===window.original")
            scenario.onActivity { a ->
                a.getSharedPreferences("football-arena",0).edit().putBoolean("adblock",false).putBoolean("autoskip",false).commit()
                MainActivity::class.java.getDeclaredMethod("runAutoSkip",WebView::class.java).apply { isAccessible=true }.invoke(a,page)
            }
            awaitJs(scenario,page,"getComputedStyle(document.querySelector('.banner-container.fullwidth')).display!=='none'&&window.started===true&&window.token===window.original")
        }
    }
    @Test fun observedArdaZonesAreHiddenWhilePlayerAndScoreboardRemain() {
        prepare()
        val root="""<html><body>
            <div id="zone_1717130822"><a href="https://z.cdn.traffmovie.com/go"><img width="300" height="300"></a></div>
            <div id="zone_info">Scoreboard</div>
            <div id="zone_123"><iframe id="playerFrame" src="https://player.example/channel" width="500" height="300"></iframe></div>
            </body></html>"""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var page: WebView
            scenario.onActivity { a -> page=create(a) { if(it.url.host=="player.example") "<html><body>Match player</body></html>" else root }; page.loadUrl("https://ardaspor99.example/") }
            awaitJs(scenario,page,"getComputedStyle(document.getElementById('zone_1717130822')).display==='none'&&getComputedStyle(zone_info).display!=='none'&&getComputedStyle(document.getElementById('zone_123')).display!=='none'&&playerFrame.getBoundingClientRect().width>0")
        }
    }
    @Test fun sponsoredPageSkinAndBannerPrefixesAreRemovedAndRestoredWithoutReload() {
        prepare()
        val root="""<html><head><style>body{background-image:url('https://media.example/pageskin.jpg')}</style></head><body>
            <div id="logo">Site logo</div><div class="background-link"><a href="https://promo.invalid/"></a></div>
            <div class="banner-container"><a href="https://promo.invalid/"><img alt="Üst Banner" width="300" height="90"></a></div>
            <iframe id="player" src="https://player.example/channel" width="500" height="300"></iframe>
            <script>window.token=Math.random();window.original=window.token;</script></body></html>"""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var page:WebView
            scenario.onActivity { a -> page=create(a) { if(it.url.host=="player.example") "<html><body>Player</body></html>" else root };page.loadUrl("https://patronizle99.example/") }
            awaitJs(scenario,page,"getComputedStyle(document.body).backgroundImage==='none'&&getComputedStyle(document.querySelector('.banner-container')).display==='none'&&logo.getBoundingClientRect().height>0&&player.getBoundingClientRect().height>0")
            scenario.onActivity { a ->
                a.getSharedPreferences("football-arena",0).edit().putBoolean("adblock",false).commit()
                MainActivity::class.java.getDeclaredMethod("runAutoSkip",WebView::class.java).apply { isAccessible=true }.invoke(a,page)
            }
            awaitJs(scenario,page,"getComputedStyle(document.body).backgroundImage!=='none'&&getComputedStyle(document.querySelector('.banner-container')).display!=='none'&&window.token===window.original")
        }
    }
    @Test fun uppercaseLabelsIconButtonsAndCountdownsWorkWithoutMutingOrSeekingTheMatch() {
        prepare()
        val html = """<html><body>
            <button onclick="window.wrong=true">Close</button>
            <button onclick="window.upper=true">SKIP AD</button>
            <button aria-label="Skip video ad" onclick="window.icon=true">▶</button>
            <button aria-label="Skip ad" onclick="window.svg=true"><svg width="24" height="24"><path d="M2 2L22 12L2 22Z"/></svg></button>
            <div class="jw-skip jw-skippable" role="button" onclick="window.jw=true"><span>»</span></div>
            <div id="customSkip"><span>Skip ad</span></div>
            <button id="timer" class="vjs-skip-button" onclick="window.countdown=true">Skip ad in 5</button>
            <video id="match"></video><div class="advertisement"><video id="ad"></video></div>
            <div class="ad-banner" id="banner">Advertising</div>
            <script>
            window.token=Math.random();window.originalToken=window.token;
            customSkip.addEventListener('click',()=>{window.custom=(window.custom||0)+1;customSkip.remove();});
            Object.defineProperty(match,'duration',{get:()=>Infinity});
            Object.defineProperty(match,'currentTime',{get:()=>90,set:()=>window.matchSeek=true});
            Object.defineProperty(ad,'duration',{get:()=>30});
            Object.defineProperty(ad,'currentTime',{get:()=>window.adPosition||0,set:v=>window.adPosition=v});
            ad.muted=false;match.muted=false;
            setTimeout(()=>{window.clickedEarly=!!window.countdown;timer.disabled=false;timer.textContent='Reklamı geç →';},600);
            </script></body></html>"""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var page: WebView
            scenario.onActivity { a -> page = create(a) { html }; page.loadUrl("https://fixture.example/player") }
            awaitJs(scenario, page, "window.upper&&window.icon&&window.svg&&window.jw&&window.custom===1&&window.countdown&&window.adPosition===30")
            awaitJs(scenario, page, "!window.wrong&&!window.clickedEarly&&!window.matchSeek&&!match.muted&&!ad.muted&&window.token===window.originalToken&&getComputedStyle(banner).display==='none'")
            scenario.onActivity { a ->
                a.getSharedPreferences("football-arena",0).edit().putBoolean("adblock", false).commit()
                MainActivity::class.java.getDeclaredMethod("runAutoSkip",WebView::class.java).apply { isAccessible = true }.invoke(a,page)
            }
            awaitJs(scenario, page, "getComputedStyle(banner).display!=='none'&&window.token===window.originalToken")
        }
    }
    @Test fun nestedFramesReceiveLiveSettingsAndNewPlayersHandshakeWithoutPageReloads() {
        prepare(false)
        val root = """<html><body><script>
            window.token=Math.random();window.originalToken=window.token;
            addEventListener('message',e=>{if(e.data==='child-ready')window.childReady=true;if(e.data==='child-skipped')window.childSkipped=true;if(e.data==='nested-skipped')window.nestedSkipped=true;});
            </script><iframe src="https://player.example/child"></iframe></body></html>"""
        val child = """<html><body><script>
            parent.postMessage('child-ready','*');addEventListener('message',e=>{if(e.data==='nested-skipped')parent.postMessage(e.data,'*');});
            </script><button onclick="window.top.postMessage('child-skipped','*')">SKIP AD</button>
            <script>setTimeout(()=>{const f=document.createElement('iframe');f.src='https://nested.example/player';document.body.append(f);},1200);</script>
            </body></html>"""
        val nested = """<html><body><button onclick="parent.postMessage('nested-skipped','*')">REKLAMI GEÇ</button></body></html>"""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var page: WebView
            scenario.onActivity { a -> page = create(a) { r -> when(r.url.host) { "player.example" -> child; "nested.example" -> nested; else -> root } }; page.loadUrl("https://fixture.example/root") }
            awaitJs(scenario,page,"window.childReady===true")
            awaitJs(scenario,page,"!window.nestedSkipped&&window.__faAdOff===true")
            scenario.onActivity { a ->
                a.getSharedPreferences("football-arena",0).edit().putBoolean("autoskip",true).commit()
                MainActivity::class.java.getDeclaredMethod("runAutoSkip",WebView::class.java).apply { isAccessible = true }.invoke(a,page)
            }
            awaitJs(scenario,page,"window.childSkipped===true&&window.token===window.originalToken")
            awaitJs(scenario,page,"window.nestedSkipped===true&&window.token===window.originalToken")
            // A later frame starts with the original disabled bootstrap and requests the current parent settings.
            awaitJs(scenario,page,"window.__faContentStats.skipActions>=2&&window.token===window.originalToken")
        }
    }
}
