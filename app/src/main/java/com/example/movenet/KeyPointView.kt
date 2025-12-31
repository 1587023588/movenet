package com.example.movenet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class KeyPointView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    
    private var persons: List<Person> = emptyList()
    private var actionResults: List<ActionResult> = emptyList()
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
        strokeWidth = 9f
        isAntiAlias = true
    }

    private val paintArmLine = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 9f
        isAntiAlias = true
    }

    private val paintHandPoint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
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

    private val paintReferenceLine = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    }
    
    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintCenterText = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
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

    fun setActionResults(actionResults: List<ActionResult>) {
        this.actionResults = actionResults
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
            canvas.drawText("检测中...", width / 2f, 80f, paintCenterText)
            return
        }

        // 绘制当前动作状态
        if (actionResults.isNotEmpty()) {
            val result = actionResults[0]
            val actionName = when (result.action) {
                StandardAction.STANDING -> "站立"
                StandardAction.SQUATTING -> "深蹲"
                StandardAction.JUMPING_JACK -> "开合跳"
                StandardAction.HORSE_STANCE -> "扎马步"
                StandardAction.ARMS_EXTENDED -> "水平举臂"
                else -> "检测中..."
            }
            val text = "$actionName (${(result.confidence * 100).toInt()}%)"
            canvas.drawText(text, width / 2f, 80f, paintCenterText)
        } else {
            canvas.drawText("检测中...", width / 2f, 80f, paintCenterText)
        }
        
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val dx = (width - scaledWidth) / 2f
        val dy = (height - scaledHeight) / 2f
        
        persons.forEachIndexed { index, person ->
            // 绘制骨架连线
            bodyJoints.forEach { (start, end) ->
                val startPoint = person.keyPoints.find { it.bodyPart == start }
                val endPoint = person.keyPoints.find { it.bodyPart == end }

                if (startPoint != null && endPoint != null &&
                    startPoint.score > 0.2f && endPoint.score > 0.2f) {

                    val (startX, startY) = mapPoint(startPoint.coordinate.first, startPoint.coordinate.second, scale, dx, dy)
                    val (endX, endY) = mapPoint(endPoint.coordinate.first, endPoint.coordinate.second, scale, dx, dy)

                    // 判断是否为手臂连接线
                    val isArmLine = (start == BodyPart.LEFT_SHOULDER && end == BodyPart.LEFT_ELBOW) ||
                                    (start == BodyPart.LEFT_ELBOW && end == BodyPart.LEFT_WRIST) ||
                                    (start == BodyPart.RIGHT_SHOULDER && end == BodyPart.RIGHT_ELBOW) ||
                                    (start == BodyPart.RIGHT_ELBOW && end == BodyPart.RIGHT_WRIST)

                    if (isArmLine) {
                        canvas.drawLine(startX, startY, endX, endY, paintArmLine)
                    } else {
                        canvas.drawLine(startX, startY, endX, endY, paintLine)
                    }
                }
            }

            // 绘制关键点
            person.keyPoints.forEach { keyPoint ->
                if (keyPoint.score > 0.2f) {
                    val (x, y) = mapPoint(keyPoint.coordinate.first, keyPoint.coordinate.second, scale, dx, dy)
                    
                    val radius = 8f + (keyPoint.score * 10f)
                    val alpha = (keyPoint.score * 255).toInt().coerceIn(120, 255)
                    
                    if (keyPoint.bodyPart == BodyPart.LEFT_WRIST || keyPoint.bodyPart == BodyPart.RIGHT_WRIST ||
                        keyPoint.bodyPart == BodyPart.LEFT_ELBOW || keyPoint.bodyPart == BodyPart.RIGHT_ELBOW) {
                        paintHandPoint.alpha = alpha
                        canvas.drawCircle(x, y, radius, paintHandPoint)
                    } else {
                        paintPoint.alpha = alpha
                        canvas.drawCircle(x, y, radius, paintPoint)
                    }
                }
            }

            // 绘制水平举臂参考线
            // 当任一手臂抬起超过45度时显示参考线
            val leftShoulder = person.keyPoints.find { it.bodyPart == BodyPart.LEFT_SHOULDER }
            val rightShoulder = person.keyPoints.find { it.bodyPart == BodyPart.RIGHT_SHOULDER }
            val leftElbow = person.keyPoints.find { it.bodyPart == BodyPart.LEFT_ELBOW }
            val rightElbow = person.keyPoints.find { it.bodyPart == BodyPart.RIGHT_ELBOW }

            var isArmRaised = false
            
            // 检查左臂是否抬起 (>45度, 即水平距离 > 垂直距离)
            if (leftShoulder != null && leftElbow != null && 
                leftShoulder.score > 0.2f && leftElbow.score > 0.2f) {
                val dy = abs(leftElbow.coordinate.second - leftShoulder.coordinate.second)
                val dx = abs(leftElbow.coordinate.first - leftShoulder.coordinate.first)
                if (dx > dy) isArmRaised = true
            }
            
            // 检查右臂是否抬起
            if (!isArmRaised && rightShoulder != null && rightElbow != null && 
                rightShoulder.score > 0.2f && rightElbow.score > 0.2f) {
                val dy = abs(rightElbow.coordinate.second - rightShoulder.coordinate.second)
                val dx = abs(rightElbow.coordinate.first - rightShoulder.coordinate.first)
                if (dx > dy) isArmRaised = true
            }

            if (isArmRaised && leftShoulder != null && rightShoulder != null &&
                leftShoulder.score > 0.2f && rightShoulder.score > 0.2f) {
                
                val (leftX, leftY) = mapPoint(leftShoulder.coordinate.first, leftShoulder.coordinate.second, scale, dx, dy)
                val (rightX, rightY) = mapPoint(rightShoulder.coordinate.first, rightShoulder.coordinate.second, scale, dx, dy)
                val y = (leftY + rightY) / 2f
                
                // 从肩膀向外延伸绘制水平线
                if (leftX < rightX) {
                    // 左肩在左侧，右肩在右侧（镜像模式下通常如此）
                    canvas.drawLine(leftX, y, 0f, y, paintReferenceLine)
                    canvas.drawLine(rightX, y, width.toFloat(), y, paintReferenceLine)
                } else {
                    // 左肩在右侧，右肩在左侧
                    canvas.drawLine(leftX, y, width.toFloat(), y, paintReferenceLine)
                    canvas.drawLine(rightX, y, 0f, y, paintReferenceLine)
                }
                
                canvas.drawText("标准水平线", 30f, y - 10f, paintText)
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

    private fun mapPoint(x: Float, y: Float, scale: Float, dx: Float, dy: Float): Pair<Float, Float> {
        // Keep skeleton aligned with PreviewView FILL_CENTER scaling
        val mappedX = x * scale + dx
        val mappedY = y * scale + dy
        val finalX = if (isMirrored) width - mappedX else mappedX
        return Pair(finalX, mappedY)
    }
}
