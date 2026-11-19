package app.footballarena.mobile

import android.graphics.*
import android.graphics.drawable.Drawable
import java.util.Locale
import kotlin.math.abs

/** The same deterministic initial/color treatment as the desktop site chips. */
class SiteInitialBadge(name: String, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val initial = (name.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "?").uppercase(Locale("tr"))
    private val hue = name.fold(7L) { value, ch -> (value * 31 + ch.code) and 0xffffffffL }.rem(360).toFloat()
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    private fun hsl(h: Float, s: Float, l: Float): Int {
        val sector = (h % 360) / 60; val c = (1 - abs(2 * l - 1)) * s; val x = c * (1 - abs(sector % 2 - 1)); val m = l - c / 2
        val rgb = when (sector.toInt()) { 0 -> floatArrayOf(c,x,0f); 1 -> floatArrayOf(x,c,0f); 2 -> floatArrayOf(0f,c,x); 3 -> floatArrayOf(0f,x,c); 4 -> floatArrayOf(x,0f,c); else -> floatArrayOf(c,0f,x) }
        return Color.rgb(((rgb[0]+m)*255).toInt(), ((rgb[1]+m)*255).toInt(), ((rgb[2]+m)*255).toInt())
    }
    override fun draw(canvas: Canvas) {
        val box = RectF(bounds); val radius = box.width() / 2
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = LinearGradient(box.left, box.top, box.right, box.bottom, hsl(hue,.85f,.66f), hsl(hue+45,.75f,.5f), Shader.TileMode.CLAMP)
        canvas.drawCircle(box.centerX(), box.centerY(), radius, paint)
        paint.shader = null; paint.color = Color.rgb(11,15,20); paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = box.height() * .53f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(initial, box.centerX(), box.centerY() - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = 0x40ffffff
        canvas.drawCircle(box.centerX(), box.centerY(), radius - .5f, paint)
    }
}
