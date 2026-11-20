package app.footballarena.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*

/** Four-digit entry with no editable field or IME, shared by startup and settings. */
internal class PinDialog(
    context:Context,
    private val mode:Mode,
    private val verify:(String)->Boolean,
    private val confirmed:(String?)->Unit
):Dialog(context) {
    enum class Mode { UNLOCK, SET, CHANGE, REMOVE }
    private val fields=when(mode){
        Mode.SET->listOf("New password:","Re-enter new password:")
        Mode.CHANGE->listOf("Current password:","New password:","Re-enter new password:")
        else->listOf(if(mode==Mode.REMOVE) "Current password:" else "Enter your four-digit password")
    }
    private var step=0
    private var digits=""
    private var first=""
    private var attempts=0
    private var blockedUntil=0L
    private var advancing=false
    private val digitViews=mutableListOf<TextView>()
    private val keys=mutableListOf<Button>()
    private val label=TextView(context)
    private val status=TextView(context)
    private val progress=LinearLayout(context)
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private fun dp(n:Int)=ArenaStyle.dp(context,n)
    private fun text(value:String,size:Float,color:Int=ArenaStyle.text)=TextView(context).apply {
        this.text=AppStrings.t(value);textSize=size;setTextColor(color)
    }
    init {
        setCancelable(mode!=Mode.UNLOCK)
        setCanceledOnTouchOutside(false)
        val compact=context.resources.displayMetrics.heightPixels/context.resources.displayMetrics.density<520 && context.resources.configuration.screenWidthDp>=500
        val shell=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; background=ArenaStyle.surface(context);clipToOutline=true;isFocusableInTouchMode=true;tag="pin-dialog" }
        val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(22),dp(if(compact)8 else 14),dp(16),dp(if(compact)8 else 14));setBackgroundColor(Color.rgb(25,36,49))}
        val brand=text("FOOTBALL ARENA",23f,ArenaStyle.gold).apply {typeface=Typeface.DEFAULT_BOLD;tag="user-content"}
        header.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
        if(mode!=Mode.UNLOCK)header.addView(Button(context).apply {
            text="×";contentDescription=AppStrings.t("Close");textSize=24f;setTextColor(ArenaStyle.muted);background=ArenaStyle.button(context)
            minWidth=0;minimumWidth=0;setPadding(0,0,0,0);setOnClickListener { dismiss() }
        },LinearLayout.LayoutParams(dp(40),dp(40)))
        shell.addView(header)
        shell.addView(View(context).apply {setBackgroundColor(ArenaStyle.line)},LinearLayout.LayoutParams(-1,dp(1)))
        val body=LinearLayout(context).apply {orientation=if(compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;setPadding(dp(28),dp(if(compact)8 else 20),dp(28),dp(if(compact)8 else 16))}
        val intro=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(0,0,if(compact) dp(22) else 0,0)}
        intro.addView(text(when(mode){Mode.UNLOCK->"Welcome back";Mode.SET->"Create an app lock";Mode.CHANGE->"Change your password";Mode.REMOVE->"Remove app lock"},20f).apply {typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
        intro.addView(text(if(mode==Mode.UNLOCK) "Unlock Football Arena to continue." else if(mode==Mode.REMOVE) "Verify your current password to remove the lock." else "Use a four-digit password for Football Arena.",13f,ArenaStyle.muted).apply {gravity=Gravity.CENTER;setPadding(0,dp(8),0,dp(12))},LinearLayout.LayoutParams(-1,-2))
        progress.orientation=LinearLayout.HORIZONTAL
        if(fields.size>1) fields.forEachIndexed { index,_ -> progress.addView(View(context).apply {tag="pin-step-$index"},LinearLayout.LayoutParams(0,dp(3),1f).apply {marginEnd=dp(5)}) }
        intro.addView(progress,LinearLayout.LayoutParams(-1,-2))
        label.apply {textSize=14f;typeface=Typeface.DEFAULT_BOLD;setTextColor(ArenaStyle.text);gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(6));tag="pin-label"}
        intro.addView(label,LinearLayout.LayoutParams(-1,-2))
        body.addView(intro,if(compact) LinearLayout.LayoutParams(0,-2,1f).apply {gravity=Gravity.CENTER_VERTICAL} else LinearLayout.LayoutParams(-1,-2))
        val entry=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL}
        val slots=LinearLayout(context).apply {gravity=Gravity.CENTER}
        repeat(4){index->
            val slot=text("",24f,ArenaStyle.gold).apply {gravity=Gravity.CENTER;tag="pin-digit-$index";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
            digitViews.add(slot);slots.addView(slot,LinearLayout.LayoutParams(dp(42),dp(46)).apply {setMargins(dp(5),dp(6),dp(5),dp(4))})
        }
        slots.tag="pin-digits";slots.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;slots.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        if(compact)intro.addView(slots)else entry.addView(slots)
        status.apply {textSize=12f;setTextColor(ArenaStyle.muted);gravity=Gravity.CENTER;setPadding(0,dp(5),0,dp(9));tag="pin-status";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        if(compact)intro.addView(status,LinearLayout.LayoutParams(-1,-2))else entry.addView(status,LinearLayout.LayoutParams(-1,-2))
        val keypad=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;tag="pin-keypad"}
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("","0","delete")).forEach { row ->
            val line=LinearLayout(context)
            row.forEach { key ->
                if(key.isBlank())line.addView(View(context),LinearLayout.LayoutParams(0,dp(50),1f)) else {
                    val b=Button(context).apply {
                        text=if(key=="delete") AppStrings.t("Delete digit") else key;tag="pin-key-$key";textSize=if(key=="delete") 14f else 22f
                        isAllCaps=false;minHeight=0;minimumHeight=0;minWidth=0;minimumWidth=0;setPadding(0,0,0,0)
                        typeface=Typeface.DEFAULT;setTextColor(ArenaStyle.text);background=ArenaStyle.button(context,radius=10)
                        contentDescription=if(key=="delete") AppStrings.t("Delete last digit") else key
                        setOnClickListener { enter(key) }
                    };keys.add(b);line.addView(b,LinearLayout.LayoutParams(0,dp(if(compact)44 else 50),1f).apply {setMargins(dp(if(compact)2 else 4),dp(if(compact)2 else 4),dp(if(compact)2 else 4),dp(if(compact)2 else 4))})
                }
            };keypad.addView(line)
        }
        val available=minOf(dp(if(compact)680 else 420),context.resources.displayMetrics.widthPixels-dp(28))-dp(56)
        entry.addView(keypad,LinearLayout.LayoutParams(minOf(dp(if(compact)256 else 280),if(compact)available/2 else available),-2))
        body.addView(entry,if(compact) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2))
        shell.addView(ScrollView(context).apply {isFillViewport=false;addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(22),dp(if(compact)8 else 12),dp(16),dp(if(compact)8 else 12));setBackgroundColor(Color.rgb(16,24,33))}
        footer.addView(text("h o w l . d e v",11f,ArenaStyle.muted).apply {tag="user-content"},LinearLayout.LayoutParams(0,-2,1f))
        if(mode!=Mode.UNLOCK)footer.addView(Button(context).apply {
            text=AppStrings.t("Cancel");isAllCaps=false;setTextColor(ArenaStyle.muted);background=ArenaStyle.button(context);setPadding(dp(14),0,dp(14),0);setOnClickListener {dismiss()}
        },LinearLayout.LayoutParams(-2,dp(40)))
        shell.addView(footer);setContentView(shell)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val height=if(compact) minOf(dp(330),context.resources.displayMetrics.heightPixels-dp(24)) else minOf(dp(625),context.resources.displayMetrics.heightPixels-dp(32))
        window?.setLayout(minOf(dp(if(compact)680 else 420),context.resources.displayMetrics.widthPixels-dp(28)),height)
        render()
    }
    override fun show(){
        super.show()
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        val compact=context.resources.displayMetrics.heightPixels/context.resources.displayMetrics.density<520 && context.resources.configuration.screenWidthDp>=500
        window?.setLayout(minOf(dp(if(compact)680 else 420),context.resources.displayMetrics.widthPixels-dp(28)),minOf(dp(if(compact)330 else 625),context.resources.displayMetrics.heightPixels-dp(32)))
        val token=(context as? Activity)?.window?.decorView?.windowToken
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(token,0)
        if(context.packageManager.hasSystemFeature("android.software.leanback"))keys.first().requestFocus()
    }
    private fun render(message:String?=null){
        label.text=AppStrings.t(fields[step])
        digitViews.forEachIndexed {i,v->v.text=if(i<digits.length) "•" else "";v.background=android.graphics.drawable.GradientDrawable().apply {setColor(Color.rgb(12,20,29));cornerRadius=dp(10).toFloat();setStroke(dp(1),if(i==digits.length) ArenaStyle.gold else Color.rgb(58,74,92))}}
        (digitViews.first().parent as View).contentDescription=AppStrings.t("{0} of 4 digits entered").replace("{0}",digits.length.toString())
        for(i in 0 until progress.childCount) progress.getChildAt(i).setBackgroundColor(if(i<=step) ArenaStyle.gold else ArenaStyle.line)
        status.text=message?:AppStrings.t("Continues automatically after four digits.")
        status.setTextColor(if(message==null) ArenaStyle.muted else ArenaStyle.red)
        keys.forEach {it.isEnabled=!advancing && SystemClock.elapsedRealtime()>=blockedUntil}
    }
    private fun enter(key:String){
        if(advancing||SystemClock.elapsedRealtime()<blockedUntil)return
        if(key=="delete"){digits=digits.dropLast(1);render();return}
        if(!key.matches(Regex("[0-9]"))||digits.length>=4)return
        digits+=key;render()
        if(digits.length==4){advancing=true;render();handler.postDelayed({if(isShowing)advance()},160)}
    }
    private fun advance(){
        advancing=false
        val checkingCurrent=mode==Mode.UNLOCK||mode==Mode.REMOVE||(mode==Mode.CHANGE&&step==0)
        if(checkingCurrent){
            if(!verify(digits)){
                digits="";attempts++
                if(attempts%5==0){blockedUntil=SystemClock.elapsedRealtime()+30000;render(AppStrings.t("Too many attempts. Try again in 30 seconds."));handler.postDelayed({if(isShowing)render()},30000)}else render(AppStrings.t("Incorrect password. Try again."))
                return
            }
            if(mode==Mode.CHANGE){step++;digits="";render();return}
            finish(null);return
        }
        val newStep=if(mode==Mode.CHANGE)1 else 0
        if(step==newStep){first=digits;digits="";step++;render();return}
        if(digits!=first){digits="";render(AppStrings.t("Passwords do not match. Try again."));return}
        finish(digits)
    }
    private fun finish(code:String?){digits="";first="";confirmed(code);dismiss()}
    override fun dismiss(){handler.removeCallbacksAndMessages(null);digits="";first="";super.dismiss()}
    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean {
        val number=when(keyCode){in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9->(keyCode-KeyEvent.KEYCODE_0).toString();in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9->(keyCode-KeyEvent.KEYCODE_NUMPAD_0).toString();KeyEvent.KEYCODE_DEL->"delete";else->null}
        if(number!=null){enter(number);return true};return super.onKeyUp(keyCode,event)
    }
}
