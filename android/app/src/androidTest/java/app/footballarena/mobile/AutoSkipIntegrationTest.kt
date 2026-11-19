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
        fail("Fixture did not reach: $expression")
    }
    private fun create(a: MainActivity, html: (WebResourceRequest) -> String): WebView {
        val page = MainActivity::class.java.getDeclaredMethod("createPage").apply { isAccessible = true }.invoke(a) as WebView
        page.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest) = WebResourceResponse("text/html", "UTF-8", html(request).byteInputStream())
        }
        return page
    }
    @Test fun uppercaseLabelsIconButtonsAndCountdownsWorkWithoutMutingOrSeekingTheMatch() {
        prepare()
        val html = """<html><body>
            <button onclick="window.wrong=true">Close</button>
            <button onclick="window.upper=true">SKIP AD</button>
            <button aria-label="Skip video ad" onclick="window.icon=true">▶</button>
            <div class="jw-skip jw-skippable" role="button" onclick="window.jw=true"><span>»</span></div>
            <button id="timer" class="vjs-skip-button" disabled onclick="window.countdown=true">Skip ad in 5</button>
            <video id="match"></video><div class="advertisement"><video id="ad"></video></div>
            <div class="ad-banner" id="banner">Advertising</div>
            <script>
            window.token=Math.random();window.originalToken=window.token;
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
            awaitJs(scenario, page, "window.upper&&window.icon&&window.jw&&window.countdown&&window.adPosition===30")
            awaitJs(scenario, page, "!window.wrong&&!window.clickedEarly&&!window.matchSeek&&!match.muted&&!ad.muted&&window.token===window.originalToken&&getComputedStyle(banner).display==='none'")
            scenario.onActivity { a ->
                a.getSharedPreferences("football-arena",0).edit().putBoolean("adblock", false).commit()
                MainActivity::class.java.getDeclaredMethod("runAutoSkip",WebView::class.java).apply { isAccessible = true }.invoke(a,page)
            }
            awaitJs(scenario, page, "getComputedStyle(banner).display!=='none'&&window.token===window.originalToken")
            scenario.onActivity { page.destroy() }
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
            scenario.onActivity { page.destroy() }
        }
    }
}
