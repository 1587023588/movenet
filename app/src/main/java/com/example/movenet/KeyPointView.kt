package com.example.movenet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class KeyPointView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    
    private var persons: List<Person> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var isMirrored: Boolean = false
    
    private val paintPoint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintLine = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val paintPlane = Paint().apply {
        color = Color.parseColor("#0D00FFFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintGrid = Paint().apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
        pathEffect = CornerPathEffect(4f)
    }

    private val paintAxis = Paint().apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val bodyJoints = listOf(
        Pair(BodyPart.NOSE, BodyPart.LEFT_EYE),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_EYE),
        Pair(BodyPart.LEFT_EYE, BodyPart.LEFT_EAR),
        Pair(BodyPart.RIGHT_EYE, BodyPart.RIGHT_EAR),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_ELBOW),
        Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_WRIST),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
        Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
        Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
        Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
    )
    
    fun setResults(persons: List<Person>) {
        this.persons = persons
    }
    
    fun setImageSourceInfo(width: Int, height: Int, isMirrored: Boolean = false) {
        this.imageWidth = width
        this.imageHeight = height
        this.isMirrored = isMirrored
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 绘制网格背景与空间坐标轴
        drawGrid(canvas)
        drawAxes(canvas)

        if (persons.isEmpty()) {
            canvas.drawText("未检测到人体", 30f, 60f, paintText)
            return
        }
        
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        
        persons.forEach { person ->
            // 绘制骨架连线
            bodyJoints.forEach { (start, end) ->
                val startPoint = person.keyPoints.find { it.bodyPart == start }
                val endPoint = person.keyPoints.find { it.bodyPart == end }

                if (startPoint != null && endPoint != null &&
                    startPoint.score > 0.2f && endPoint.score > 0.2f) {

                    val startX = toViewX(startPoint.coordinate.first * scaleX)
                    val startY = startPoint.coordinate.second * scaleY
                    val endX = toViewX(endPoint.coordinate.first * scaleX)
                    val endY = endPoint.coordinate.second * scaleY

                    canvas.drawLine(startX, startY, endX, endY, paintLine)
                }
            }

            // 绘制关键点
            person.keyPoints.forEach { keyPoint ->
                if (keyPoint.score > 0.2f) {
                    val x = toViewX(keyPoint.coordinate.first * scaleX)
                    val y = keyPoint.coordinate.second * scaleY
                    
                    val radius = 6f + (keyPoint.score * 10f)
                    val alpha = (keyPoint.score * 255).toInt().coerceIn(80, 255)
                    paintPoint.alpha = alpha
                    canvas.drawCircle(x, y, radius, paintPoint)
                }
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        // 透视网格模拟空间感（简单地面平面）
        val bottomY = height - 40f
        val topY = height * 0.38f
        val leftX = 40f
        val rightX = width - 40f
        val vanishingX = width / 2f
        val vanishingY = topY

        // 填充地面平面浅色
        val planePath = android.graphics.Path().apply {
            moveTo(leftX, bottomY)
            lineTo(rightX, bottomY)
            lineTo(vanishingX, vanishingY)
            close()
        }
        canvas.drawPath(planePath, paintPlane)

        val columns = 8
        val rows = 8
        val stepX = (rightX - leftX) / columns

        // 纵向线条向消失点汇聚
        for (i in 0..columns) {
            val x = leftX + i * stepX
            canvas.drawLine(x, bottomY, vanishingX, vanishingY, paintGrid)
        }

        // 横向线条插值形成梯形
        for (i in 1..rows) {
            val t = i / rows.toFloat()
            val y = bottomY - t * (bottomY - vanishingY)
            val xL = leftX + t * (vanishingX - leftX)
            val xR = rightX - t * (rightX - vanishingX)
            canvas.drawLine(xL, y, xR, y, paintGrid)
        }
    }

    private fun drawAxes(canvas: Canvas) {
        val originX = 80f
        val originY = height - 80f
        val axisLen = 220f

        // X 轴（向右）
        val xEndX = originX + axisLen
        val xEndY = originY
        canvas.drawLine(originX, originY, xEndX, xEndY, paintAxis)
        drawArrowHead(canvas, originX, originY, xEndX, xEndY, paintAxis)
        canvas.drawText("X", xEndX + 12f, xEndY + 12f, paintText)

        // Y 轴（向上）
        val yEndX = originX
        val yEndY = originY - axisLen
        canvas.drawLine(originX, originY, yEndX, yEndY, paintAxis)
        drawArrowHead(canvas, originX, originY, yEndX, yEndY, paintAxis)
        canvas.drawText("Y", yEndX - 18f, yEndY - 10f, paintText)

        // Z 轴（朝屏幕外斜向）
        val zEndX = originX - axisLen * 0.58f
        val zEndY = originY - axisLen * 0.32f
        canvas.drawLine(originX, originY, zEndX, zEndY, paintAxis)
        drawArrowHead(canvas, originX, originY, zEndX, zEndY, paintAxis)
        canvas.drawText("Z", zEndX - 22f, zEndY - 10f, paintText)
    }

    private fun drawArrowHead(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        paint: Paint
    ) {
        val arrowSize = 18f
        val angle = Math.atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val angle1 = angle - Math.toRadians(25.0)
        val angle2 = angle + Math.toRadians(25.0)

        val x1 = endX - (arrowSize * Math.cos(angle1)).toFloat()
        val y1 = endY - (arrowSize * Math.sin(angle1)).toFloat()
        val x2 = endX - (arrowSize * Math.cos(angle2)).toFloat()
        val y2 = endY - (arrowSize * Math.sin(angle2)).toFloat()

        canvas.drawLine(endX, endY, x1, y1, paint)
        canvas.drawLine(endX, endY, x2, y2, paint)
    }

    private fun toViewX(x: Float): Float {
        return if (isMirrored) width - x else x
    }
}
