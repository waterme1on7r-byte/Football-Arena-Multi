package app.footballarena.mobile

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Consistent 24-unit stroke icons for pane and DPI controls. */
class ControlIcon(private val kind: String, private val tint: Int, private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(vararg points: Float) { val path = Path(); path.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) path.lineTo(points[i], points[i+1]); canvas.drawPath(path, paint) }
        when(kind) {
            "back" -> line(14f,5f,7f,12f,14f,19f)
            "forward" -> line(10f,5f,17f,12f,10f,19f)
            "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            "refresh" -> { canvas.drawArc(4f,4f,20f,20f,35f,290f,false,paint); line(20f,4f,20f,10f,14f,10f) }
            "main" -> { canvas.drawRoundRect(3f,4f,21f,20f,2f,2f,paint); line(15f,4f,15f,20f) }
            "sound" -> { line(11f,5f,6f,9f,3f,9f,3f,15f,6f,15f,11f,19f,11f,5f); canvas.drawArc(9f,5f,21f,19f,-60f,120f,false,paint) }
            "power" -> { canvas.drawArc(4f,4f,20f,20f,-45f,270f,false,paint); line(12f,2f,12f,12f) }
        }
        canvas.restore()
    }
}
