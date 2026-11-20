package app.footballarena.mobile

import android.app.Dialog
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkEditingTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun field(n:String)=MainActivity::class.java.getDeclaredField(n).apply {isAccessible=true}
    private fun prepare(){instrumentation.targetContext.getSharedPreferences("football-arena",0).edit().clear().putBoolean("languageChosen",true).putString("language","tr").putBoolean("restore",false).putString("dpiMode","off").commit()}
    private fun id(site:Any)=site.javaClass.getDeclaredMethod("getId").apply {isAccessible=true}.invoke(site) as String
    @Test fun linkOrderSurvivesRestartAndRefreshedCatalogueOrderWithoutReloadingPanes(){
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use {s->
            lateinit var expected:List<String>
            s.onActivity {a->
                field("routingReady").setBoolean(a,false)
                MainActivity::class.java.getDeclaredMethod("openUrl",String::class.java).apply {isAccessible=true}.invoke(a,"https://playing.example/match")
                @Suppress("UNCHECKED_CAST")val pages=field("pages").get(a) as Map<String,WebView>
                val original=pages.toMap()
                @Suppress("UNCHECKED_CAST")val sites=field("sites").get(a) as MutableList<Any>
                val peers=sites.filter {!(it.javaClass.getDeclaredMethod("getQuick").apply {isAccessible=true}.invoke(it) as Boolean)}
                val first=id(peers[0]);val second=id(peers[1])
                val move=MainActivity::class.java.getDeclaredMethod("moveSite",String::class.java,Int::class.javaPrimitiveType).apply {isAccessible=true}
                val before=sites.map(::id)
                move.invoke(a,first,-1);assertEquals(before,sites.map(::id))
                move.invoke(a,first,1)
                val visible=sites.filter {it in peers}.map(::id)
                assertEquals(listOf(second,first),visible.take(2))
                expected=sites.map(::id)
                original.forEach {(key,page)->assertSame("Reordering must retain the playing WebView",page,pages[key])}
                sites.reverse()
                MainActivity::class.java.getDeclaredMethod("applySavedSiteOrder").apply {isAccessible=true}.invoke(a)
                assertEquals(expected,sites.map(::id))
            }
            s.recreate()
            s.onActivity {a->@Suppress("UNCHECKED_CAST")val sites=field("sites").get(a) as List<Any>;assertEquals(expected,sites.map(::id))}
        }
    }
    @Test fun yellowTabSaveHasTitleNameAndUrlAndDoesNotNavigateTheCurrentPage(){
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use {s->
            lateinit var dialog:ArenaDialog
            lateinit var key:String
            lateinit var page:WebView
            s.onActivity {a->
                field("routingReady").setBoolean(a,false)
                MainActivity::class.java.getDeclaredMethod("openUrl",String::class.java).apply {isAccessible=true}.invoke(a,"https://temporary.example/player")
                key=field("selectedKey").get(a) as String;page=field("browser").get(a) as WebView
                dialog=MainActivity::class.java.getDeclaredMethod("saveTemporarySite",String::class.java,String::class.java).apply {isAccessible=true}.invoke(a,key,"https://temporary.example/player") as ArenaDialog
                val root=dialog.window!!.decorView
                assertNotNull(root.findViewWithTag<View>("dialog-titlebar"))
                assertEquals("Siteyi kaydet",root.findViewWithTag<TextView>("dialog-title").text.toString())
                root.findViewWithTag<EditText>("saved-site-name").setText("My match")
                root.findViewWithTag<EditText>("saved-site-url").setText("not a valid URL")
                dialog.getButton(-1).performClick();assertTrue(dialog.isShowing)
                root.findViewWithTag<EditText>("saved-site-url").setText("https://saved.example/watch")
            }
            instrumentation.waitForIdleSync()
            for(cmd in listOf("mkdir -p /data/local/tmp/football-arena-ui","screencap -p /data/local/tmp/football-arena-ui/tablet-save-yellow-tab.png"))android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use {it.readBytes()}
            s.onActivity {a->
                dialog.getButton(-1).performClick();assertFalse(dialog.isShowing)
                @Suppress("UNCHECKED_CAST")val pages=field("pages").get(a) as Map<String,WebView>
                assertSame(page,pages[key]);assertNull("Saving metadata must not navigate the queued page",page.url)
                val data=JSONArray(a.getSharedPreferences("football-arena",0).getString("sites","[]"))
                val site=(0 until data.length()).map {data.getJSONObject(it)}.single {it.getString("id")==key}
                assertEquals("My match",site.getString("name"));assertEquals("https://saved.example/watch",site.getString("url"))
            }
        }
    }
}
