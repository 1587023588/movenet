package com.example.movenet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class OverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    
    private var persons: List<Person> = emptyList()
    private var actionResults: List<ActionResult> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var rotation: Int = 0
    private var isMirrored: Boolean = false
    
    private val paintCircle = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 8f
        isAntiAlias = true
    }
    
    private val paintLine = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    
    private val paintStandardLine = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        alpha = 150
    }
    
    private val paintStandardCircle = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        strokeWidth = 6f
        isAntiAlias = true
        alpha = 150
    }
    
    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val paintSmallText = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val paintCorrectionText = Paint().apply {
        color = Color.YELLOW
        textSize = 20f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // 身体骨架连接定义
    private val bodyJoints = listOf(
        // 头部
        Pair(BodyPart.NOSE, BodyPart.LEFT_EYE),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_EYE),
        Pair(BodyPart.LEFT_EYE, BodyPart.LEFT_EAR),
        Pair(BodyPart.RIGHT_EYE, BodyPart.RIGHT_EAR),
        
        // 躯干
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
        
        // 左臂
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_ELBOW),
        Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_WRIST),
        
        // 右臂
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
        
        // 左腿
        Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
        Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
        
        // 右腿
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
        Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
    )
    
    fun setResults(persons: List<Person>) {
        this.persons = persons
    }
    
    fun setActionResults(actionResults: List<ActionResult>) {
        this.actionResults = actionResults
    }
    
    fun setImageSourceInfo(width: Int, height: Int, rotation: Int, isMirrored: Boolean) {
        this.imageWidth = width
        this.imageHeight = height
        this.rotation = rotation
        this.isMirrored = isMirrored
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (persons.isEmpty()) {
            // 显示提示信息
            canvas.drawText("检测中...", 30f, 60f, paintText)
            return
        }
        
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val dx = (width - scaledWidth) / 2f
        val dy = (height - scaledHeight) / 2f
        
        var personCount = 0
        persons.forEach { person ->
            personCount++
            
            // 获取对应的动作结果
            val actionResult = if (personCount - 1 < actionResults.size) {
                actionResults[personCount - 1]
            } else {
                null
            }
            
            // 绘制标准动作骨架（参考线）
            // if (actionResult != null) {
            //     val standardPose = ActionDetector().getStandardPose(
            //         actionResult.action,
            //         imageWidth.toFloat(),
            //         imageHeight.toFloat()
            //     )
            //     
            //     drawPoseSkeleton(canvas, standardPose, scaleX, scaleY, paintStandardLine, paintStandardCircle)
            // }
            
            // 绘制检测到的骨架连接线
            bodyJoints.forEach { (start, end) ->
                val startPoint = person.keyPoints.find { it.bodyPart == start }
                val endPoint = person.keyPoints.find { it.bodyPart == end }
                
                if (startPoint != null && endPoint != null &&
                    startPoint.score > 0.2f && endPoint.score > 0.2f) {
                    
                    val (startX, startY) = mapPoint(startPoint.coordinate.first, startPoint.coordinate.second, scale, dx, dy)
                    val (endX, endY) = mapPoint(endPoint.coordinate.first, endPoint.coordinate.second, scale, dx, dy)
                    
                    canvas.drawLine(startX, startY, endX, endY, paintLine)
                }
            }
            
            // 绘制检测到的关键点
            person.keyPoints.forEach { keyPoint ->
                if (keyPoint.score > 0.2f) {
                    val (x, y) = mapPoint(keyPoint.coordinate.first, keyPoint.coordinate.second, scale, dx, dy)
                    
                    // 根据置信度调整颜色
                    val alpha = (keyPoint.score * 255).toInt().coerceIn(50, 255)
                    paintCircle.alpha = alpha
                    
                    canvas.drawCircle(x, y, 15f, paintCircle)
                }
            }
            
            // 显示动作信息
            if (actionResult != null) {
                val yOffset = 60f
                
                // 显示检测到的动作
                val actionName = when (actionResult.action) {
                    StandardAction.STANDING -> "站立"
                    StandardAction.SQUATTING -> "深蹲"
                    StandardAction.ARMS_EXTENDED -> "水平举臂"
                    else -> "未知动作"
                }
                
                canvas.drawText(
                    "动作: $actionName (${String.format("%.0f%%", actionResult.confidence * 100)})",
                    30f,
                    yOffset,
                    paintText
                )
                
                // 显示纠正建议
                var correctionY = yOffset + 40f
                if (actionResult.corrections.isNotEmpty()) {
                    canvas.drawText("纠正建议:", 30f, correctionY, paintSmallText)
                    correctionY += 35f
                    
                    actionResult.corrections.forEachIndexed { index, correction ->
                        if (index < 3) { // 最多显示3条建议
                            canvas.drawText(
                                "• $correction",
                                50f,
                                correctionY,
                                paintCorrectionText
                            )
                            correctionY += 30f
                        }
                    }
                }
            } else {
                // 显示整体置信度
                val yOffset = 60f
                canvas.drawText(
                    "人物$personCount 置信度: ${String.format("%.2f", person.score)}",
                    30f,
                    yOffset,
                    paintText
                )
            }
        }
    }
    
    private fun drawPoseSkeleton(
        canvas: Canvas,
        keyPoints: List<KeyPoint>,
        scale: Float,
        dx: Float,
        dy: Float,
        linePaint: Paint,
        circlePaint: Paint
    ) {
        // 绘制参考骨架连接线
        bodyJoints.forEach { (start, end) ->
            val startPoint = keyPoints.find { it.bodyPart == start }
            val endPoint = keyPoints.find { it.bodyPart == end }
            
            if (startPoint != null && endPoint != null) {
                val (startX, startY) = mapPoint(startPoint.coordinate.first, startPoint.coordinate.second, scale, dx, dy)
                val (endX, endY) = mapPoint(endPoint.coordinate.first, endPoint.coordinate.second, scale, dx, dy)
                
                canvas.drawLine(startX, startY, endX, endY, linePaint)
            }
        }
        
        // 绘制参考关键点
        keyPoints.forEach { keyPoint ->
            val (x, y) = mapPoint(keyPoint.coordinate.first, keyPoint.coordinate.second, scale, dx, dy)
            canvas.drawCircle(x, y, 10f, circlePaint)
        }
    }

    private fun mapPoint(x: Float, y: Float, scale: Float, dx: Float, dy: Float): Pair<Float, Float> {
        // Match PreviewView default FILL_CENTER scale so overlay joints stick to the video
        val mappedX = x * scale + dx
        val mappedY = y * scale + dy
        val finalX = if (isMirrored) width - mappedX else mappedX
        return Pair(finalX, mappedY)
    }
}
