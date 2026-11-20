package app.footballarena.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable

/** Android-only Desktop classic surfaces shared by settings, dialogs, menus and PIN entry. */
internal object ArenaStyle {
    val gold = Color.rgb(246,196,81)
    val text = Color.rgb(232,237,243)
    val muted = Color.rgb(154,170,189)
    val line = Color.rgb(41,55,71)
    val background = Color.rgb(14,22,31)
    val blue = Color.rgb(71,127,180)
    val green = Color.rgb(118,215,171)
    val red = Color.rgb(255,170,164)
    fun dp(context:Context,n:Int)=(n*context.resources.displayMetrics.density).toInt()
    private var headingFont:Typeface?=null
    fun titleFont(context:Context):Typeface = headingFont?:runCatching {
        if(android.os.Build.VERSION.SDK_INT>=26) Typeface.Builder(context.assets,"fonts/big_shoulders_display.ttf").setFontVariationSettings("'wght' 700").build()
        else Typeface.create("sans-serif-condensed",Typeface.BOLD)
    }.getOrElse {Typeface.create("sans-serif-condensed",Typeface.BOLD)}.also {headingFont=it}
    fun surface(context:Context,radius:Int=16)=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(25,37,50),Color.rgb(16,24,33))).apply {
        cornerRadius=dp(context,radius).toFloat();setStroke(dp(context,1),line)
    }
    fun button(context:Context,primary:Boolean=false,danger:Boolean=false,radius:Int=8):android.graphics.drawable.Drawable {
        fun shape(focused:Boolean)=GradientDrawable().apply {
            setColor(if(danger) Color.rgb(53,30,35) else if(primary) gold else if(focused) Color.rgb(36,50,67) else Color.rgb(16,25,35))
            cornerRadius=dp(context,radius).toFloat()
            setStroke(dp(context,1),if(focused||primary) gold else if(danger) Color.rgb(112,67,64) else Color.rgb(49,65,83))
        }
        val states=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),shape(true))
            addState(intArrayOf(),shape(false))
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(38,246,196,81)),states,GradientDrawable().apply {setColor(Color.WHITE);cornerRadius=dp(context,radius).toFloat()})
    }
}
