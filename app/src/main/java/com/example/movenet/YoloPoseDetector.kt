package com.example.movenet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.TransformToGrayscaleOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8 Pose Detector
 * 支持单人和多人姿态检测
 * 输入: 320x320 RGB图像
 * 输出: [1, 56, 2100] (实际为[1, 1, 56, 2100]或类似)
 * 
 * YOLO Pose输出格式:
 * 前5位: [cx, cy, w, h, objectness]
 * 接下来51位: 17个关键点 × 3 (x, y, score)
 */
class YoloPoseDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val inputWidth = 320
    private val inputHeight = 320
    private val confidenceThreshold = 0.25f
    private val iouThreshold = 0.45f

    init {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            
            val modelFile = FileUtil.loadMappedFile(context, "yolov8n_pose.tflite")  // 改为.tflite
            interpreter = Interpreter(modelFile, options)
            
            // 打印模型详细信息
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            
            android.util.Log.d("YoloPoseDetector", "========== YOLO Pose模型加载成功 ==========")
            android.util.Log.d("YoloPoseDetector", "输入形状: ${inputShape?.joinToString(",")}")
            android.util.Log.d("YoloPoseDetector", "输出形状: ${outputShape?.joinToString(",")}")
            android.util.Log.d("YoloPoseDetector", "模型文件大小: ${modelFile.capacity() / 1024 / 1024}MB")
            android.util.Log.d("YoloPoseDetector", "========================================")
        } catch (e: Exception) {
            android.util.Log.e("YoloPoseDetector", "❌ YOLO模型加载失败", e)
            e.printStackTrace()
        }
    }

    fun estimatePoses(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        isFrontCamera: Boolean
    ): PoseResult {
        return try {
            if (interpreter == null) {
                android.util.Log.e("YoloPoseDetector", "❌ 模型为null")
                return PoseResult(emptyList(), imageProxy.width, imageProxy.height)
            }

            android.util.Log.d("YoloPoseDetector", "开始处理图像: ${imageProxy.width}x${imageProxy.height}")
            
            // 将ImageProxy转换为Bitmap
            val bitmap = imageProxy.toBitmap(rotationDegrees, isFrontCamera) ?: run {
                android.util.Log.e("YoloPoseDetector", "Bitmap转换失败")
                return PoseResult(emptyList(), imageProxy.width, imageProxy.height)
            }
            
            android.util.Log.d("YoloPoseDetector", "Bitmap转换完成: ${bitmap.width}x${bitmap.height}")
            
            // 预处理图像
            val inputTensor = preprocessImage(bitmap)
            android.util.Log.d("YoloPoseDetector", "图像预处理完成")
            
            // YOLO输出: [1, 56, 2100]
            // 56 = 5 (bbox) + 51 (17 keypoints * 3)
            // 2100 = height * width of feature map (70 * 30)
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            android.util.Log.d("YoloPoseDetector", "输出tensor形状: ${outputShape?.joinToString(",")}")
            
            val outputTensor = TensorBuffer.createFixedSize(outputShape!!, DataType.FLOAT32)
            
            android.util.Log.d("YoloPoseDetector", "开始运行模型推理...")
            interpreter?.run(inputTensor.buffer, outputTensor.buffer.rewind())
            android.util.Log.d("YoloPoseDetector", "✓ 模型推理完成")
            
            // 解析输出
            val output = outputTensor.floatArray
            val persons = parseYoloOutput(output, bitmap.width, bitmap.height, outputShape)
            
            bitmap.recycle()
            
            PoseResult(persons, imageProxy.width, imageProxy.height)
        } catch (e: Exception) {
            android.util.Log.e("YoloPoseDetector", "❌ 推理异常", e)
            e.printStackTrace()
            PoseResult(emptyList(), imageProxy.width, imageProxy.height)
        }
    }

    private fun preprocessImage(bitmap: Bitmap): TensorBuffer {
        // 调整到模型输入尺寸
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        
        // 使用UINT8输入(0-255)
        val inputBuffer = TensorBuffer.createFixedSize(
            intArrayOf(1, inputHeight, inputWidth, 3),
            DataType.UINT8
        )
        
        // 加载像素数据
        val pixels = IntArray(inputWidth * inputHeight)
        resizedBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        
        val byteBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3)
        for (pixel in pixels) {
            byteBuffer.put((pixel shr 16 and 0xFF).toByte()) // R
            byteBuffer.put((pixel shr 8 and 0xFF).toByte())  // G
            byteBuffer.put((pixel and 0xFF).toByte())        // B
        }
        
        inputBuffer.loadBuffer(byteBuffer)
        resizedBitmap.recycle()
        
        return inputBuffer
    }

    private fun parseYoloOutput(
        output: FloatArray,
        imgWidth: Int,
        imgHeight: Int,
        outputShape: IntArray
    ): List<Person> {
        android.util.Log.d("YoloPoseDetector", "解析YOLO输出...")
        android.util.Log.d("YoloPoseDetector", "输出数组长度: ${output.size}, Shape: ${outputShape.joinToString(",")}")
        
        val persons = mutableListOf<Person>()
        
        // YOLO输出格式: [1, 56, num_detections]
        // 56 = 5 (x,y,w,h,conf) + 51 (17 keypoints * 3)
        val numClasses = outputShape[1]
        val numDetections = if (outputShape.size > 2) outputShape[2] else 1
        
        android.util.Log.d("YoloPoseDetector", "Classes: $numClasses, Detections: $numDetections")
        
        if (numClasses < 56) {
            android.util.Log.e("YoloPoseDetector", "输出格式异常: 应为56个值,实际$numClasses")
            return emptyList()
        }
        
        // 处理每个检测框
        val boxesWithScores = mutableListOf<Pair<Int, Float>>() // (index, score)
        
        for (i in 0 until numDetections) {
            val offset = i * numClasses
            if (offset + 4 >= output.size) break
            
            val centerX = output[offset]
            val centerY = output[offset + 1]
            val width = output[offset + 2]
            val height = output[offset + 3]
            val confidence = output[offset + 4]
            
            // 置信度过滤
            if (confidence > confidenceThreshold) {
                boxesWithScores.add(Pair(i, confidence))
            }
        }
        
        android.util.Log.d("YoloPoseDetector", "检测到${boxesWithScores.size}个对象")
        
        // 处理每个有效检测
        for ((detectionIdx, _) in boxesWithScores) {
            val offset = detectionIdx * numClasses
            
            val centerX = output[offset]
            val centerY = output[offset + 1]
            val width = output[offset + 2]
            val height = output[offset + 3]
            val confidence = output[offset + 4]
            
            // 转换为像素坐标
            val boxLeft = ((centerX - width / 2) * imgWidth).toInt()
            val boxTop = ((centerY - height / 2) * imgHeight).toInt()
            val boxRight = ((centerX + width / 2) * imgWidth).toInt()
            val boxBottom = ((centerY + height / 2) * imgHeight).toInt()
            
            // 提取17个关键点
            val keyPoints = mutableListOf<KeyPoint>()
            var validKeyPoints = 0
            var sumScore = 0f
            
            for (j in 0 until 17) {
                val keypointOffset = offset + 5 + j * 3
                if (keypointOffset + 2 < output.size) {
                    val kpX = output[keypointOffset] * imgWidth
                    val kpY = output[keypointOffset + 1] * imgHeight
                    val kpScore = output[keypointOffset + 2]
                    
                    keyPoints.add(
                        KeyPoint(
                            bodyPart = BodyPart.values()[j],
                            coordinate = Pair(kpX, kpY),
                            score = kpScore
                        )
                    )
                    
                    if (kpScore > 0.05f) {
                        validKeyPoints++
                        sumScore += kpScore
                    }
                }
            }
            
            // 计算平均得分
            val avgScore = if (validKeyPoints > 0) sumScore / validKeyPoints else 0f
            
            android.util.Log.d(
                "YoloPoseDetector",
                "Person $detectionIdx: conf=$confidence, validKP=$validKeyPoints, avgScore=$avgScore"
            )
            
            // 只保留有足够关键点的检测
            if (validKeyPoints >= 5) {
                persons.add(Person(keyPoints, avgScore))
            }
        }
        
        android.util.Log.d("YoloPoseDetector", "最终检测到${persons.size}个人")
        return persons
    }

    private fun ImageProxy.toBitmap(rotationDegrees: Int, isFrontCamera: Boolean): Bitmap? {
        return try {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            yBuffer.rewind()
            
            val pixelStride = planes[2].pixelStride
            
            if (pixelStride == 2) {
                vBuffer.get(nv21, ySize, vSize)
                vBuffer.rewind()
                uBuffer.get(nv21, ySize + vSize, uSize)
                uBuffer.rewind()
            } else {
                val uvWidth = width / 2
                val uvHeight = height / 2
                var uvIndex = ySize
                for (row in 0 until uvHeight) {
                    for (col in 0 until uvWidth) {
                        if (vBuffer.hasRemaining()) {
                            nv21[uvIndex++] = vBuffer.get()
                        }
                        if (uBuffer.hasRemaining()) {
                            nv21[uvIndex++] = uBuffer.get()
                        }
                    }
                }
            }

            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                width,
                height,
                null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 95, out)
            val imageBytes = out.toByteArray()
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // 旋转
            val rotated = if (rotationDegrees == 0) {
                bitmap
            } else {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                val rotatedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0, 0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true
                )
                bitmap.recycle()
                rotatedBitmap
            }

            rotated
        } catch (e: Exception) {
            android.util.Log.e("YoloPoseDetector", "❌ Bitmap转换失败", e)
            null
        }
    }

    fun close() {
        interpreter?.close()
    }
}
