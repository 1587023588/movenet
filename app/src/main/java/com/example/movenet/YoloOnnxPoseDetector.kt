package com.example.movenet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * YOLOv8 Pose Detector using ONNX Runtime
 * 使用ONNX Runtime直接运行ONNX模型
 */
class YoloOnnxPoseDetector(private val context: Context) {
    // 内部数据类：用于NMS的检测结果
    private data class Detection(val box: FloatArray, val confidence: Float, val keyPoints: List<KeyPoint>)
    
    private var ortSession: OrtSession? = null
    private val ortEnv = OrtEnvironment.getEnvironment()
    private val inputWidth = 320   // 恢复原始分辨率以保持识别质量
    private val inputHeight = 320
    private val confidenceThreshold = 0.30f  // 恢复阈值
    
    // 复用缓冲区减少内存分配
    private val pixelBuffer = IntArray(inputWidth * inputHeight)
    private val floatBuffer = FloatBuffer.allocate(1 * 3 * inputWidth * inputHeight)

    init {
        try {
            android.util.Log.i("YoloOnnx", "初始化ONNX Runtime...")
            
            // 从assets加载ONNX模型
            val inputStream = context.assets.open("yolov8n-pose.onnx")
            val modelBytes = inputStream.readBytes()
            inputStream.close()
            
            android.util.Log.i("YoloOnnx", "模型文件读取成功: ${modelBytes.size / 1024 / 1024}MB")
            
            // 配置会话选项以优化性能
            val sessionOptions = OrtSession.SessionOptions().apply {
                // 使用2线程（更稳定，避免过多线程切换开销）
                setIntraOpNumThreads(2)
                
                // 使用基础优化（避免过度优化导致的开销）
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            
            ortSession = ortEnv.createSession(modelBytes, sessionOptions)
            android.util.Log.i("YoloOnnx", "✓ ONNX模型加载成功（2线程，基础优化）")
        } catch (e: java.io.FileNotFoundException) {
            android.util.Log.e("YoloOnnx", "❌ 模型文件不存在: yolov8n-pose.onnx", e)
            android.util.Log.e("YoloOnnx", "请确认文件在 app/src/main/assets/ 目录中")
        } catch (e: Exception) {
            android.util.Log.e("YoloOnnx", "❌ ONNX模型加载失败", e)
            android.util.Log.e("YoloOnnx", "错误类型: ${e.javaClass.name}")
            android.util.Log.e("YoloOnnx", "错误信息: ${e.message}")
            e.printStackTrace()
        }
    }

    fun estimatePoses(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        isFrontCamera: Boolean
    ): PoseResult {
        return try {
            val t0 = System.nanoTime()
            if (ortSession == null) {
                android.util.Log.e("YoloOnnx", "❌ Session为null")
                return PoseResult(emptyList(), imageProxy.width, imageProxy.height)
            }
            
            // 转换为Bitmap
            val bitmap = imageProxy.toBitmap(rotationDegrees, isFrontCamera) ?: run {
                android.util.Log.e("YoloOnnx", "❌ Bitmap转换失败")
                return PoseResult(emptyList(), imageProxy.width, imageProxy.height)
            }
            val t1 = System.nanoTime()
            
            // 预处理图像
            val inputTensor = preprocessImage(bitmap)
            val t2 = System.nanoTime()
            
            // 运行推理
            val inputName = ortSession!!.inputNames.iterator().next()
            val results = ortSession!!.run(mapOf(inputName to inputTensor))
            val t3 = System.nanoTime()
            
            // 获取输出
            val outputTensor = results[0].value as Array<*>
            
            // 解析输出
            val persons = parseYoloOutput(outputTensor, bitmap.width, bitmap.height, isFrontCamera)
            val t4 = System.nanoTime()

            android.util.Log.d(
                "Perf",
                "onnx toBitmap=${"%.2f".format((t1 - t0) / 1_000_000.0)}ms preprocess=${"%.2f".format((t2 - t1) / 1_000_000.0)}ms infer=${"%.2f".format((t3 - t2) / 1_000_000.0)}ms parse=${"%.2f".format((t4 - t3) / 1_000_000.0)}ms"
            )
            
            // 清理
            inputTensor.close()
            results.forEach { it.value.close() }
            bitmap.recycle()
            
            PoseResult(persons, imageProxy.width, imageProxy.height)
        } catch (e: Exception) {
            android.util.Log.e("YoloOnnx", "❌ 推理异常", e)
            e.printStackTrace()
            PoseResult(emptyList(), imageProxy.width, imageProxy.height)
        }
    }

    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        // 缩放到模型输入尺寸
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        
        // 复用像素缓冲区
        resizedBitmap.getPixels(pixelBuffer, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        
        // 转换为CHW格式，归一化到[0, 1]
        // 注意：clear()会重置position到0，但capacity不变
        floatBuffer.clear()
        val size = inputWidth * inputHeight
        
        // R通道
        var idx = 0
        while (idx < size) {
            floatBuffer.put(((pixelBuffer[idx] shr 16) and 0xFF) / 255.0f)
            idx++
        }
        // G通道
        idx = 0
        while (idx < size) {
            floatBuffer.put(((pixelBuffer[idx] shr 8) and 0xFF) / 255.0f)
            idx++
        }
        // B通道
        idx = 0
        while (idx < size) {
            floatBuffer.put((pixelBuffer[idx] and 0xFF) / 255.0f)
            idx++
        }
        
        floatBuffer.flip() // flip而不是rewind，确保limit正确设置
        resizedBitmap.recycle()
        
        // 创建ONNX tensor
        val shape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        return OnnxTensor.createTensor(ortEnv, floatBuffer, shape)
    }

    private fun parseYoloOutput(
        output: Array<*>,
        imgWidth: Int,
        imgHeight: Int,
        isFrontCamera: Boolean
    ): List<Person> {
        try {
            // YOLO输出: [1, 56, 2100]，需要转置为 [2100, 56]
            // 56 = 4 (bbox) + 1 (conf) + 51 (17 keypoints * 3)
            val outputBatch = output[0] as Array<*>
            val numChannels = outputBatch.size // 56
            val numDetections = (outputBatch[0] as FloatArray).size // 2100
            
            // 用于NMS的检测框列表
            val detections = mutableListOf<Detection>()
            
            // 计算缩放因子（从模型输入尺寸到实际图像尺寸）
            val scaleX = imgWidth.toFloat() / inputWidth
            val scaleY = imgHeight.toFloat() / inputHeight
            
            // 遍历所有检测 (转置访问)
            for (i in 0 until numDetections) {
                // 提取bbox和置信度（需要从[56, 2100]格式转置读取）
                val cx = (outputBatch[0] as FloatArray)[i]
                val cy = (outputBatch[1] as FloatArray)[i]
                val w = (outputBatch[2] as FloatArray)[i]
                val h = (outputBatch[3] as FloatArray)[i]
                val confidence = (outputBatch[4] as FloatArray)[i]
                
                // 置信度过滤
                if (confidence < confidenceThreshold) continue
                
                // 提取17个关键点
                val keyPoints = mutableListOf<KeyPoint>()
                var validCount = 0
                
                for (j in 0 until 17) {
                    val idx = 5 + j * 3
                    if (idx + 2 < numChannels) {
                        // YOLOv8关键点是在320x320空间中的坐标（模型接收镜像图像，输出镜像空间坐标）
                        // 屏幕显示的也是镜像图像，所以直接使用，无需再镜像
                        val kpXRaw = (outputBatch[idx] as FloatArray)[i]
                        val kpYRaw = (outputBatch[idx + 1] as FloatArray)[i]
                        val kpScore = (outputBatch[idx + 2] as FloatArray)[i]
                        
                        // 缩放到实际图像尺寸
                        val kpX = kpXRaw * scaleX
                        val kpY = kpYRaw * scaleY
                        
                        keyPoints.add(
                            KeyPoint(
                                bodyPart = BodyPart.values()[j],
                                coordinate = Pair(kpX, kpY),
                                score = kpScore
                            )
                        )
                        
                        if (kpScore > 0.15f) validCount++  // 进一步降低阈值
                    }
                }
                
                // 只保留有足够关键点的检测（进一步降低要求）
                if (validCount >= 2 && keyPoints.size == 17) {  // 从3降到2
                    val bbox = floatArrayOf(cx * scaleX, cy * scaleY, w * scaleX, h * scaleY)
                    detections.add(Detection(bbox, confidence, keyPoints))
                    
                    // 临时调试：打印检测到的第一个人
                    if (detections.size == 1) {
                        val nose = keyPoints.find { it.bodyPart == BodyPart.NOSE }
                        android.util.Log.d("YoloOnnx", "检测到人: conf=$confidence, 鼻子=(${nose?.coordinate?.first?.toInt()}, ${nose?.coordinate?.second?.toInt()}), validKP=$validCount")
                    }
                }
            }
            
            // 应用NMS（非极大值抑制）去除重复检测
            val nmsResults = applyNMS(detections, iouThreshold = 0.5f)
            
            // 临时调试
            if (nmsResults.isNotEmpty()) {
                android.util.Log.d("YoloOnnx", "最终检测到${nmsResults.size}人")
            } else {
                android.util.Log.w("YoloOnnx", "未检测到任何人！NMS前有${detections.size}个候选")
            }
            
            return nmsResults.take(1) // 最多返回1个人
            
        } catch (e: Exception) {
            android.util.Log.e("YoloOnnx", "解析输出失败", e)
            return emptyList()
        }
    }

    // NMS (非极大值抑制) - 去除重复检测
    private fun applyNMS(detections: List<Detection>, iouThreshold: Float): List<Person> {
        if (detections.isEmpty()) return emptyList()
        
        // 按置信度降序排序
        val sorted = detections.sortedByDescending { it.confidence }
        val keep = mutableListOf<Detection>()
        val suppress = BooleanArray(sorted.size) { false }
        
        for (i in sorted.indices) {
            if (suppress[i]) continue
            keep.add(sorted[i])
            
            // 抑制与当前框IoU > 阈值的其他框
            for (j in (i + 1) until sorted.size) {
                if (suppress[j]) continue
                val iou = calculateIoU(sorted[i].box, sorted[j].box)
                if (iou > iouThreshold) {
                    suppress[j] = true
                }
            }
        }
        
        return keep.map { Person(it.keyPoints, it.confidence) }
    }
    
    // 计算两个边界框的IoU (交并比)
    private fun calculateIoU(box1: FloatArray, box2: FloatArray): Float {
        // box = [cx, cy, w, h]
        val x1_min = box1[0] - box1[2] / 2
        val y1_min = box1[1] - box1[3] / 2
        val x1_max = box1[0] + box1[2] / 2
        val y1_max = box1[1] + box1[3] / 2
        
        val x2_min = box2[0] - box2[2] / 2
        val y2_min = box2[1] - box2[3] / 2
        val x2_max = box2[0] + box2[2] / 2
        val y2_max = box2[1] + box2[3] / 2
        
        // 交集
        val inter_x_min = maxOf(x1_min, x2_min)
        val inter_y_min = maxOf(y1_min, y2_min)
        val inter_x_max = minOf(x1_max, x2_max)
        val inter_y_max = minOf(y1_max, y2_max)
        
        val inter_area = maxOf(0f, inter_x_max - inter_x_min) * maxOf(0f, inter_y_max - inter_y_min)
        
        // 并集
        val box1_area = box1[2] * box1[3]
        val box2_area = box2[2] * box2[3]
        val union_area = box1_area + box2_area - inter_area
        
        return if (union_area > 0) inter_area / union_area else 0f
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
                        if (vBuffer.hasRemaining()) nv21[uvIndex++] = vBuffer.get()
                        if (uBuffer.hasRemaining()) nv21[uvIndex++] = uBuffer.get()
                    }
                }
            }

            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                width, height, null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 95, out)
            val imageBytes = out.toByteArray()
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // 旋转
            if (rotationDegrees == 0) {
                bitmap
            } else {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                rotated
            }
        } catch (e: Exception) {
            android.util.Log.e("YoloOnnx", "❌ Bitmap转换失败", e)
            null
        }
    }

    fun close() {
        ortSession?.close()
    }
}
