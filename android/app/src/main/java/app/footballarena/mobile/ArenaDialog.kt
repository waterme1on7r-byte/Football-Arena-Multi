package app.footballarena.mobile

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared themed dialog/dropdown content, with the Builder interface used by this app. */
class ArenaDialog(context: Context) : Dialog(context) {
    private val buttons = mutableMapOf<Int, Button>()
    fun getButton(which: Int): Button = buttons.getValue(which)
    override fun show() {
        super.show()
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(minOf(dp(520), context.resources.displayMetrics.widthPixels - dp(32)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.decorView?.let { AppStrings.watch(it) }
    }
    class Builder(private val context: Context) {
        private var title = ""
        private var message = ""
        private var view: View? = null
        private var items: Array<String>? = null
        private var choice = -2
        private var choose: ((DialogInterface, Int) -> Unit)? = null
        private var positive: Pair<String, ((DialogInterface, Int) -> Unit)?>? = null
        private var negative: Pair<String, ((DialogInterface, Int) -> Unit)?>? = null
        private var cancelable = true
        fun setTitle(value: String) = apply { title = value }
        fun setMessage(value: String) = apply { message = value }
        fun setView(value: View) = apply { view = value }
        fun setCancelable(value: Boolean) = apply { cancelable = value }
        fun setItems(value: Array<String>, listener: (DialogInterface, Int) -> Unit) = apply { items = value; choose = listener }
        fun setSingleChoiceItems(value: Array<String>, selected: Int, listener: (DialogInterface, Int) -> Unit) = apply { items = value; choice = selected; choose = listener }
        fun setPositiveButton(label: String, listener: ((DialogInterface, Int) -> Unit)?) = apply { positive = label to listener }
        fun setNegativeButton(label: String, listener: ((DialogInterface, Int) -> Unit)?) = apply { negative = label to listener }
        fun create(): ArenaDialog {
            val dialog = ArenaDialog(context)
            fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
            val shell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(16))
                background = GradientDrawable().apply { setColor(Color.rgb(18,25,35)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(44,60,78)) }
            }
            fun label(value: String, size: Float) = TextView(context).apply { text = AppStrings.t(value); textSize = size; setTextColor(Color.rgb(233,237,242)); setPadding(0, dp(5), 0, dp(14)) }
            if (title.isNotEmpty()) shell.addView(label(title, 21f).apply { typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(246,196,81)) })
            val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            if (message.isNotEmpty()) body.addView(label(message, 15f).apply { setTextIsSelectable(true) })
            view?.let { body.addView(it) }
            items?.forEachIndexed { index, item ->
                val b = Button(context).apply {
                    text = AppStrings.t((if (choice == index) "●  " else "") + item); isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    minWidth = 0; minimumWidth = 0; setPadding(dp(16), 0, dp(16), 0)
                    setTextColor(if (choice == index) Color.rgb(245,196,81) else Color.rgb(233,237,242)); textSize = 16f
                    background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Color.rgb(53,47,32)); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Color.rgb(245,196,81)) })
                        addState(intArrayOf(), GradientDrawable().apply { setColor(if (choice == index) Color.rgb(48,41,26) else Color.rgb(23,33,46)); cornerRadius = dp(10).toFloat(); setStroke(dp(1), if (choice == index) Color.rgb(246,196,81) else Color.rgb(44,60,78)) })
                    }
                    setOnClickListener { choose?.invoke(dialog, index); if (choice == -2) dialog.dismiss() }
                }
                body.addView(b, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) })
            }
            shell.addView(object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val limit = minOf(dp(430), context.resources.displayMetrics.heightPixels - dp(200)).coerceAtLeast(dp(100))
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
                }
            }.apply { isFillViewport = false; addView(body) }, LinearLayout.LayoutParams(-1, -2))
            val actions = LinearLayout(context).apply { gravity = Gravity.END; setPadding(0, dp(12), 0, 0) }
            for ((which, option) in listOf(-2 to negative, -1 to positive)) if (option != null) {
                val button = Button(context).apply {
                    text = AppStrings.t(option.first); isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(dp(20), 0, dp(20), 0); typeface = Typeface.DEFAULT_BOLD; setTextColor(if (which == -1) Color.rgb(15,20,28) else Color.rgb(233,237,242))
                    background = GradientDrawable().apply { setColor(if (which == -1) Color.rgb(245,196,81) else Color.rgb(34,48,66)); cornerRadius = dp(8).toFloat() }
                    setOnClickListener { option.second?.invoke(dialog, which); dialog.dismiss() }
                }
                dialog.buttons[which] = button
                actions.addView(button, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(10) })
            }
            shell.addView(actions)
            dialog.setContentView(shell); dialog.setCancelable(cancelable)
            return dialog
        }
        fun show() = create().apply { show() }
    }
}
