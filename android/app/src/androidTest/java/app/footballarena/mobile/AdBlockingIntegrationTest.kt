package app.footballarena.mobile

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real loopback HTTP traffic exercises WebView and service-worker interception, not a mock client. */
@RunWith(AndroidJUnit4::class)
class AdBlockingIntegrationTest {
    private fun prepare() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("football-arena", 0).edit().clear()
            .putBoolean("languageChosen", true).putBoolean("restore", false).putString("dpiMode", "off").commit()
    }
    private fun page(a: MainActivity) = MainActivity::class.java.getDeclaredMethod("createPage").apply { isAccessible = true }.invoke(a) as WebView
    private fun awaitJs(scenario: ActivityScenario<MainActivity>, page: WebView, expression: String) {
        repeat(100) {
            var result = ""; val latch = CountDownLatch(1)
            scenario.onActivity { page.evaluateJavascript(expression) { result = it; latch.countDown() } }
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            if (result == "true") return
            Thread.sleep(100)
        }
        fail("JavaScript fixture did not reach: $expression")
    }
    private class Fixture(private val route: (String) -> Pair<String, String>) : AutoCloseable {
        private val server = ServerSocket(0)
        private val pool = Executors.newCachedThreadPool()
        val requests = ConcurrentLinkedQueue<String>()
        val origin = "http://127.0.0.1:${server.localPort}"
        init {
            pool.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    pool.execute {
                        socket.use {
                            try {
                                val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                                val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                                while (!reader.readLine().isNullOrEmpty()) { }
                                requests.add(path)
                                val (mime, body) = route(path)
                                val bytes = body.toByteArray()
                                val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nCache-Control: no-store\r\nService-Worker-Allowed: /\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                it.getOutputStream().write(header.toByteArray(Charsets.US_ASCII)); it.getOutputStream().write(bytes)
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
        }
        override fun close() { server.close(); pool.shutdownNow() }
    }
    @Test fun everyPageAndCrossOriginFrameBlocksAdsAndPreferenceChangesApplyWithoutReload() {
        prepare()
        val fixture = """<html><body><script>
            window.token=Math.random();addEventListener('message',e=>{if(e.data==='frame-ready')window.frameReady=true;});
            Promise.all([fetch('/adserver/pixel').then(r=>r.status),fetch('/player.css').then(r=>r.status)])
            .then(v=>{document.body.dataset.result=v.join(':');if(parent!==window)parent.postMessage('frame-ready','*');});</script></body></html>"""
        Fixture { "text/html" to fixture }.use { frameServer ->
            Fixture { path -> if (path == "/player.css") "text/css" to "body{color:white}" else "text/html" to fixture.replace("<body>", "<body><iframe src='${frameServer.origin}/frame'></iframe>") }.use { server ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var first: WebView; lateinit var second: WebView
                    scenario.onActivity { a -> first = page(a); second = page(a); first.loadUrl(server.origin + "/one"); second.loadUrl(server.origin + "/two") }
                    awaitJs(scenario, first, "document.body.dataset.result==='204:200'")
                    awaitJs(scenario, second, "document.body.dataset.result==='204:200'")
                    // Frame responses reach the native blocker too; player assets remain accessible.
                    awaitJs(scenario, first, "window.frameReady===true")
                    awaitJs(scenario, second, "window.frameReady===true")
                    assertFalse(server.requests.any { it.startsWith("/adserver/") })
                    assertFalse(frameServer.requests.any { it.startsWith("/adserver/") })
                    assertTrue(server.requests.contains("/player.css"))
                    scenario.onActivity { a ->
                        val blocker = MainActivity::class.java.getDeclaredMethod("getContentBlocker").apply { isAccessible = true }.invoke(a) as ContentBlocker
                        assertTrue(AdRules.domainCount > 3500); assertTrue(blocker.blockedRequests.get() >= 3)
                        a.getSharedPreferences("football-arena", 0).edit().putBoolean("adblock", false).commit()
                        first.evaluateJavascript("window.originalToken=window.token;fetch('/adserver/allowed').then(r=>document.body.dataset.off=r.status)", null)
                    }
                    awaitJs(scenario, first, "document.body.dataset.off==='200'&&window.originalToken===window.token")
                    assertTrue(server.requests.contains("/adserver/allowed"))
                    scenario.onActivity { first.destroy(); second.destroy() }
                }
            }
        }
    }
    @Test fun serviceWorkerFetchesUseTheSameAdblockPreference() {
        prepare()
        val worker = """
            self.addEventListener('install',e=>e.waitUntil(self.skipWaiting()));
            self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
            self.addEventListener('fetch',e=>{if(new URL(e.request.url).pathname==='/via-worker')e.respondWith(fetch('/adserver/worker',{cache:'no-store'}));});
        """
        val html = """<html><body><script>
            navigator.serviceWorker.register('/sw.js').then(()=>navigator.serviceWorker.ready).then(async()=>{
            if(!navigator.serviceWorker.controller)await new Promise(r=>navigator.serviceWorker.addEventListener('controllerchange',r,{once:true}));
            document.body.dataset.worker=(await fetch('/via-worker')).status;});
            </script></body></html>"""
        Fixture { path -> if (path == "/sw.js") "application/javascript" to worker else "text/html" to html }.use { server ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: WebView
                scenario.onActivity { a -> view = page(a); view.loadUrl(server.origin + "/index") }
                awaitJs(scenario, view, "document.body.dataset.worker==='204'")
                assertFalse(server.requests.contains("/adserver/worker"))
                scenario.onActivity { a ->
                    val blocker = MainActivity::class.java.getDeclaredMethod("getContentBlocker").apply { isAccessible = true }.invoke(a) as ContentBlocker
                    assertTrue(blocker.blockedWorkerRequests.get() > 0)
                    a.getSharedPreferences("football-arena", 0).edit().putBoolean("adblock", false).commit()
                    view.evaluateJavascript("fetch('/via-worker').then(r=>document.body.dataset.off=r.status)", null)
                }
                awaitJs(scenario, view, "document.body.dataset.off==='200'")
                assertTrue(server.requests.contains("/adserver/worker"))
                scenario.onActivity { view.destroy() }
            }
        }
    }
}
