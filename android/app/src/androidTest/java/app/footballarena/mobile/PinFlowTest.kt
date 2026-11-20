package app.footballarena.mobile

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun prepare(){instrumentation.targetContext.getSharedPreferences("football-arena",0).edit().clear().putBoolean("languageChosen",true).putString("language","tr").putBoolean("restore",false).putString("dpiMode","off").commit()}
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList()
    private fun pinRoot():View=WindowInspector.getGlobalWindowViews().first {it.findViewWithTag<View>("pin-dialog")!=null}
    private fun enter(s:ActivityScenario<MainActivity>,code:String){
        s.onActivity {code.forEach {pinRoot().findViewWithTag<Button>("pin-key-$it").performClick()}}
        Thread.sleep(260);instrumentation.waitForIdleSync()
    }
    private fun change(s:ActivityScenario<MainActivity>):Dialog {
        lateinit var d:Dialog
        s.onActivity {d=MainActivity::class.java.getDeclaredMethod("changePass").apply {isAccessible=true}.invoke(it) as Dialog}
        return d
    }
    private fun verify(s:ActivityScenario<MainActivity>,code:String):Boolean {
        var result=false
        s.onActivity {result=MainActivity::class.java.getDeclaredMethod("verifyPass",String::class.java).apply {isAccessible=true}.invoke(it,code) as Boolean};return result
    }
    private fun screenshot(name:String){
        instrumentation.waitForIdleSync()
        for(cmd in listOf("mkdir -p /data/local/tmp/football-arena-ui","screencap -p /data/local/tmp/football-arena-ui/$name.png"))android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use {it.readBytes()}
    }
    @Test fun setChangeAndRemoveRequireMatchingCodesAndTheCurrentPassword(){
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use {s->
            val prefs=instrumentation.targetContext.getSharedPreferences("football-arena",0)
            val setup=change(s)
            s.onActivity {assertTrue(all(pinRoot()).none {it is EditText});assertEquals("Yeni şifre:",pinRoot().findViewWithTag<TextView>("pin-label").text.toString())}
            enter(s,"1234")
            s.onActivity {assertEquals("Yeni şifreyi tekrar girin:",pinRoot().findViewWithTag<TextView>("pin-label").text.toString())}
            enter(s,"0000");assertFalse(prefs.contains("passHash"));assertTrue(setup.isShowing)
            enter(s,"1234");assertFalse(setup.isShowing);assertTrue(verify(s,"1234"))
            val oldHash=prefs.getString("passHash","")
            val changing=change(s)
            enter(s,"9999")
            s.onActivity {assertEquals("Mevcut şifre:",pinRoot().findViewWithTag<TextView>("pin-label").text.toString())}
            assertEquals(oldHash,prefs.getString("passHash",""))
            enter(s,"1234");enter(s,"5678");enter(s,"0000")
            assertEquals(oldHash,prefs.getString("passHash",""))
            enter(s,"5678");assertFalse(changing.isShowing);assertTrue(verify(s,"5678"));assertFalse(verify(s,"1234"))
            lateinit var removal:ArenaDialog
            s.onActivity {removal=MainActivity::class.java.getDeclaredMethod("removePass").apply {isAccessible=true}.invoke(it) as ArenaDialog}
            screenshot("tablet-remove-code-confirmation")
            s.onActivity {removal.getButton(-1).performClick()}
            enter(s,"1234");assertTrue(prefs.contains("passHash"))
            enter(s,"5678");assertFalse(prefs.contains("passHash"));assertFalse(prefs.contains("passSalt"))
            s.onActivity {
                val settings=MainActivity::class.java.getDeclaredMethod("settingsDialog").apply {isAccessible=true}.invoke(it) as Dialog
                assertNotNull(settings.window!!.decorView.findViewWithTag<View>("enter-passcode"))
                assertNull(settings.window!!.decorView.findViewWithTag<View>("remove-passcode"));settings.dismiss()
            }
        }
    }
    @Test fun startupUsesTheSameKeypadWithoutAnImeOrEditableField(){
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use {s->
            change(s);enter(s,"2468");enter(s,"2468")
            s.recreate();instrumentation.waitForIdleSync()
            s.onActivity {
                val root=pinRoot()
                assertTrue(all(root).none {it is EditText})
                assertFalse(root.rootWindowInsets?.isVisible(WindowInsets.Type.ime())?:false)
                val params=root.layoutParams as WindowManager.LayoutParams
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM!=0)
                val keys=all(root).filterIsInstance<Button>().filter {it.tag?.toString()?.startsWith("pin-key-")==true}
                assertEquals(11,keys.size)
                keys.forEach {b->val visible=android.graphics.Rect();assertTrue("Every PIN key must fit",b.getGlobalVisibleRect(visible));assertEquals(b.height,visible.height())}
            }
            screenshot("tablet-startup-pin")
            enter(s,"9999")
            s.onActivity {assertNotNull(pinRoot().findViewWithTag<View>("pin-keypad"))}
            enter(s,"2468")
            s.onActivity {assertTrue(WindowInspector.getGlobalWindowViews().none {it.findViewWithTag<View>("pin-dialog")!=null})}
        }
    }
    @Test fun pinCooldownDisablesEveryDigitAfterFiveFailedAttempts(){
        prepare()
        ActivityScenario.launch(MainActivity::class.java).use {s->
            lateinit var d:PinDialog
            var accepted=false
            s.onActivity {d=PinDialog(it,PinDialog.Mode.UNLOCK,{false}){accepted=true}.apply {show()}}
            repeat(5){enter(s,"0000")}
            s.onActivity {
                assertFalse(accepted)
                assertTrue(all(pinRoot()).filterIsInstance<Button>().filter {it.tag?.toString()?.startsWith("pin-key-")==true}.all {!it.isEnabled})
                assertTrue(pinRoot().findViewWithTag<TextView>("pin-status").text.contains("30"));d.dismiss()
            }
        }
    }
}
