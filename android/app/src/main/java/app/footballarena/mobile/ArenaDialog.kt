package app.footballarena.mobile

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*

/** Desktop classic title bar, content, list choices and actions for Android app popups. */
class ArenaDialog(context:Context):Dialog(context) {
    private val buttons=mutableMapOf<Int,Button>()
    fun getButton(which:Int):Button=buttons.getValue(which)
    override fun show(){
        super.show()
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(minOf(ArenaStyle.dp(context,520),context.resources.displayMetrics.widthPixels-ArenaStyle.dp(context,28)),android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window?.decorView?.let {AppStrings.watch(it)}
        if (isTv()) window?.decorView?.post { firstFocusable(it)?.requestFocus() }
    }
    private fun isTv() = context.packageManager.hasSystemFeature("android.software.leanback") ||
        context.packageManager.hasSystemFeature("android.software.leanback_only") ||
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    private fun firstFocusable(view:View):View? {
        if (view is Button && view.isShown && view.isEnabled) return view
        if (view is android.view.ViewGroup) for(i in 0 until view.childCount) firstFocusable(view.getChildAt(i))?.let{return it}
        return null
    }
    class Builder(private val context:Context){
        private var title=""
        private var message=""
        private var view:View?=null
        private var items:Array<String>?=null
        private var choice=-2
        private var choose:((DialogInterface,Int)->Unit)?=null
        private var positive:Pair<String,((DialogInterface,Int)->Unit)?>?=null
        private var negative:Pair<String,((DialogInterface,Int)->Unit)?>?=null
        private var cancelable=true
        fun setTitle(value:String)=apply {title=value}
        fun setMessage(value:String)=apply {message=value}
        fun setView(value:View)=apply {view=value}
        fun setCancelable(value:Boolean)=apply {cancelable=value}
        fun setItems(value:Array<String>,listener:(DialogInterface,Int)->Unit)=apply {items=value;choose=listener}
        fun setSingleChoiceItems(value:Array<String>,selected:Int,listener:(DialogInterface,Int)->Unit)=apply {items=value;choice=selected;choose=listener}
        fun setPositiveButton(label:String,listener:((DialogInterface,Int)->Unit)?)=apply {positive=label to listener}
        fun setNegativeButton(label:String,listener:((DialogInterface,Int)->Unit)?)=apply {negative=label to listener}
        fun create():ArenaDialog {
            val dialog=ArenaDialog(context)
            fun dp(n:Int)=ArenaStyle.dp(context,n)
            val shell=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL;background=ArenaStyle.surface(context);clipToOutline=true
                // A focusable shell swallows the first DPAD event on several TV launchers.
                isFocusable=false;isFocusableInTouchMode=false
            }
            val head=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(22),dp(13),dp(12),dp(13));setBackgroundColor(Color.rgb(25,36,49));tag="dialog-titlebar"}
            head.addView(TextView(context).apply {
                text=AppStrings.t(title.ifBlank {"Football Arena"});textSize=24f;setTextColor(ArenaStyle.gold)
                typeface=if(title.contains("LANGUAGE")) Typeface.DEFAULT_BOLD else ArenaStyle.titleFont(context)
                tag="dialog-title"
            },LinearLayout.LayoutParams(0,-2,1f))
            if(cancelable)head.addView(Button(context).apply {
                text="×";textSize=23f;contentDescription=AppStrings.t("Close");isAllCaps=false
                minWidth=0;minimumWidth=0;isFocusable=true;isFocusableInTouchMode=true;setPadding(0,0,0,0);setTextColor(ArenaStyle.muted);background=ArenaStyle.button(context)
                setOnClickListener {dialog.dismiss()}
            },LinearLayout.LayoutParams(dp(38),dp(38)))
            shell.addView(head)
            fun divider()=View(context).apply {setBackgroundColor(ArenaStyle.line)}
            shell.addView(divider(),LinearLayout.LayoutParams(-1,dp(1)))
            val body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(18),dp(22),dp(18))}
            if(message.isNotEmpty())body.addView(TextView(context).apply {
                text=AppStrings.t(message);textSize=14f;setTextColor(ArenaStyle.muted);setTextIsSelectable(true)
                setPadding(0,0,0,if(view!=null||items!=null) dp(14) else 0)
            })
            view?.let {body.addView(it)}
            items?.forEachIndexed {index,item->
                body.addView(Button(context).apply {
                    text=AppStrings.t((if(choice==index) "●  " else "")+item);isAllCaps=false;gravity=Gravity.START or Gravity.CENTER_VERTICAL
                    minWidth=0;minimumWidth=0;isFocusable=true;isFocusableInTouchMode=true;setPadding(dp(14),0,dp(14),0);textSize=14f
                    setTextColor(if(choice==index) ArenaStyle.gold else ArenaStyle.text)
                    background=ArenaStyle.button(context,danger=item=="Delete"||item=="Remove")
                    setOnClickListener {choose?.invoke(dialog,index);if(choice == -2)dialog.dismiss()}
                },LinearLayout.LayoutParams(-1,dp(46)).apply {bottomMargin=dp(6)})
            }
            shell.addView(object:ScrollView(context){
                override fun onMeasure(w:Int,h:Int){
                    val limit=minOf(dp(430),context.resources.displayMetrics.heightPixels-dp(180)).coerceAtLeast(dp(90))
                    super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST))
                }
            }.apply {isFillViewport=false;addView(body)},LinearLayout.LayoutParams(-1,-2))
            val actions=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(22),dp(12),dp(22),dp(14));setBackgroundColor(Color.rgb(16,24,33))}
            actions.addView(TextView(context).apply {text=if(context.resources.configuration.screenWidthDp<450) "" else "h o w l . d e v";tag="user-content";textSize=10f;setTextColor(ArenaStyle.muted)},LinearLayout.LayoutParams(0,-2,1f))
            for((which,option)in listOf(-2 to negative,-1 to positive))if(option!=null){
                val danger=option.first in listOf("Delete","Remove","Verify and remove")
                val button=Button(context).apply {
                    text=AppStrings.t(option.first);isAllCaps=false;minWidth=0;minimumWidth=0;isFocusable=true;isFocusableInTouchMode=true;setPadding(dp(15),0,dp(15),0);typeface=Typeface.DEFAULT_BOLD;textSize=13f
                    setTextColor(if(danger) ArenaStyle.red else if(which == -1) ArenaStyle.background else ArenaStyle.text)
                    background=ArenaStyle.button(context,primary=which == -1,danger=danger)
                    setOnClickListener {option.second?.invoke(dialog,which);dialog.dismiss()}
                }
                dialog.buttons[which]=button
                actions.addView(button,LinearLayout.LayoutParams(-2,dp(42)).apply {marginStart=dp(8)})
            }
            shell.addView(divider(),LinearLayout.LayoutParams(-1,dp(1)));shell.addView(actions)
            dialog.setContentView(shell);dialog.setCancelable(cancelable)
            return dialog
        }
        fun show()=create().apply {show()}
    }
}
