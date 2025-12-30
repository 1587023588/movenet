package com.example.movenet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import kotlin.math.exp

data class KeyPoint(
    val bodyPart: BodyPart,
    val coordinate: Pair<Float, Float>,
    val score: Float
)

data class Person(
    val keyPoints: List<KeyPoint>,
    val score: Float
)

data class PoseResult(
    val persons: List<Person>,
    val srcWidth: Int,
    val srcHeight: Int
)

enum class BodyPart(val position: Int) {
    NOSE(0),
    LEFT_EYE(1),
    RIGHT_EYE(2),
    LEFT_EAR(3),
    RIGHT_EAR(4),
    LEFT_SHOULDER(5),
    RIGHT_SHOULDER(6),
    LEFT_ELBOW(7),
    RIGHT_ELBOW(8),
    LEFT_WRIST(9),
    RIGHT_WRIST(10),
    LEFT_HIP(11),
    RIGHT_HIP(12),
    LEFT_KNEE(13),
    RIGHT_KNEE(14),
    LEFT_ANKLE(15),
    RIGHT_ANKLE(16)
}

class PoseDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val inputWidth = 256
    private val inputHeight = 256

    init {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val modelFile = FileUtil.loadMappedFile(context, "movenet_singlepose_thunder.tflite")
            interpreter = Interpreter(modelFile, options)
            
            // 打印模型详细信息
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            android.util.Log.d("PoseDetector", "========== 模型加载成功 ==========")
            android.util.Log.d("PoseDetector", "输入形状: ${inputShape?.joinToString(",")}")
            android.util.Log.d("PoseDetector", "输出形状: ${outputShape?.joinToString(",")}")
            android.util.Log.d("PoseDetector", "模型文件大小: ${modelFile.capacity()} bytes")
            android.util.Log.d("PoseDetector", "====================================")
        } catch (e: Exception) {
            android.util.Log.e("PoseDetector", "❌ 模型加载失败", e)
            e.printStackTrace()
        }
    }

    fun estimatePoses(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        isFrontCamera: Boolean
    ): PoseResult {
        return try {
            // 如果模型未加载，返回空列表
            if (interpreter == null) {
                android.util.Log.e("PoseDetector", "❌ 模型为null，无法进行推理")
                return PoseResult(emptyList(), imageProxy.width, imageProxy.height)
            }

            android.util.Log.d("PoseDetector", "开始处理图像: ${imageProxy.width}x${imageProxy.height}")
            
            // 将ImageProxy转换为Bitmap
            val bitmap = imageProxy.toBitmap(rotationDegrees, isFrontCamera) ?: run {
                android.util.Log.e("PoseDetector", "Bitmap转换失败")
                return PoseResult(emptyList(), imageProxy.width, imageProxy.height)
            }
            
            android.util.Log.d("PoseDetector", "Bitmap转换完成: ${bitmap.width}x${bitmap.height}")
            
            // 预处理图像
            val inputTensor = preprocessImage(bitmap)
            android.util.Log.d("PoseDetector", "图像预处理完成，输入tensor shape: ${inputTensor.shape.joinToString(",")}")
            
            // SinglePose输出格式: [1, 1, 17, 3]  (y, x, score)
            val outputTensor = TensorBuffer.createFixedSize(
                intArrayOf(1, 1, 17, 3),
                DataType.FLOAT32
            )
            
            android.util.Log.d("PoseDetector", "开始运行模型推理...")
            interpreter?.run(inputTensor.buffer, outputTensor.buffer.rewind())
            android.util.Log.d("PoseDetector", "✓ 模型推理完成")
            
            // 打印前几个输出值来检查
            val output = outputTensor.floatArray
            android.util.Log.d("PoseDetector", "输出数组前10个值: ${output.take(10).joinToString(", ")}")
            android.util.Log.d("PoseDetector", "输出数组总长度: ${output.size}")
            
            // 解析输出
            val persons = parseSinglePoseOutput(output, bitmap.width, bitmap.height)
            
            bitmap.recycle() // 释放bitmap内存
            
            PoseResult(persons, bitmap.width, bitmap.height)
        } catch (e: Exception) {
            android.util.Log.e("PoseDetector", "❌ estimatePoses异常", e)
            e.printStackTrace()
            PoseResult(emptyList(), imageProxy.width, imageProxy.height)
        }
    }

    private fun preprocessImage(bitmap: Bitmap): TensorBuffer {
        // 调整图像大小到模型输入尺寸
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        
        // 使用UINT8输入（0-255范围）
        val inputBuffer = TensorBuffer.createFixedSize(
            intArrayOf(1, inputHeight, inputWidth, 3),
            DataType.UINT8
        )
        
        // 加载图像数据
        val pixels = IntArray(inputWidth * inputHeight)
        resizedBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        
        val byteBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3)
        for (pixel in pixels) {
            byteBuffer.put((pixel shr 16 and 0xFF).toByte()) // R
            byteBuffer.put((pixel shr 8 and 0xFF).toByte())  // G
            byteBuffer.put((pixel and 0xFF).toByte())        // B
        }
        
        inputBuffer.loadBuffer(byteBuffer)
        
        android.util.Log.d("PoseDetector", "输入数据类型: UINT8, 形状: 1,${inputHeight},${inputWidth},3")
        
        return inputBuffer
    }

    private fun parseSinglePoseOutput(output: FloatArray, width: Int, height: Int): List<Person> {
        // SinglePose输出: [1, 1, 17, 3] => 总长度 51
        if (output.size != 51) {
            android.util.Log.e("PoseDetector", "输出长度异常: ${output.size}, 期望 51")
            return emptyList()
        }

        val keyPoints = mutableListOf<KeyPoint>()

        for (i in 0 until 17) {
            val y = output[i * 3]
            val x = output[i * 3 + 1]
            val score = output[i * 3 + 2]

            keyPoints.add(
                KeyPoint(
                    bodyPart = BodyPart.values()[i],
                    coordinate = Pair(x * width, y * height),
                    score = score
                )
            )
        }

        val validKeyPoints = keyPoints.filter { it.score > 0.05f }
        val avgScore = validKeyPoints.map { it.score }.average()

        android.util.Log.d(
            "PoseDetector",
            "单人检测: avgScore=$avgScore, valid=${validKeyPoints.size}, highConf=${keyPoints.count { it.score > 0.2f }}"
        )

        return if (avgScore > 0.1) listOf(Person(keyPoints, avgScore.toFloat())) else emptyList()
    }

    private fun ImageProxy.toBitmap(rotationDegrees: Int, isFrontCamera: Boolean): Bitmap? {
        return try {
            android.util.Log.d("PoseDetector", "开始YUV转Bitmap, format=${format}")
            
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            android.util.Log.d("PoseDetector", "Buffer sizes: Y=$ySize, U=$uSize, V=$vSize")

            val nv21 = ByteArray(ySize + uSize + vSize)

            // 复制Y平面
            yBuffer.get(nv21, 0, ySize)
            yBuffer.rewind()
            
            // 交错复制U和V平面以形成NV21格式
            val pixelStride = planes[2].pixelStride
            android.util.Log.d("PoseDetector", "UV pixelStride=$pixelStride")
            
            if (pixelStride == 2) {
                // UV交错存储，直接复制
                vBuffer.get(nv21, ySize, vSize)
                vBuffer.rewind()
                uBuffer.get(nv21, ySize + vSize, uSize)
                uBuffer.rewind()
            } else {
                // 需要手动交错
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

            // 按rotationDegrees旋转Bitmap
            val rotated = if (rotationDegrees == 0) {
                bitmap
            } else {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                val rotatedBitmap = android.graphics.Bitmap.createBitmap(
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

            android.util.Log.d(
                "PoseDetector",
                "Bitmap处理: src=${bitmap.width}x${bitmap.height}, result=${rotated.width}x${rotated.height}, rot=$rotationDegrees"
            )
            rotated
        } catch (e: Exception) {
            android.util.Log.e("PoseDetector", "❌ Bitmap转换失败", e)
            e.printStackTrace()
            null
        }
    }

    fun close() {
        interpreter?.close()
    }
}
