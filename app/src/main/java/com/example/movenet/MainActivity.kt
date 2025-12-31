package com.example.movenet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.movenet.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var poseDetector: PoseDetector
    private lateinit var actionDetector: ActionDetector
    private val actionSmoother = ActionSmoother(windowSize = 4, minAgree = 3, holdMs = 400L)
    private var camera: Camera? = null
    
    // TTS相关
    private var textToSpeech: TextToSpeech? = null
    private var lastSpokenAction: StandardAction? = null
    private var lastSpeechTime: Long = 0
    private val speechInterval = 2000L // 2秒间隔，快速响应
    
    // 动作稳定性检查
    private var actionHistory = mutableListOf<StandardAction>()
    private val historySize = 5 // 保留最近5帧的动作判定

    // 可视化稳定：降低重绘频率 + 关键点平滑 + 短暂丢帧保持（减少“闪动/抖动”观感）
    private val uiMaxFps = 60
    private val uiFrameIntervalMs = 1000L / uiMaxFps
    private var lastUiUpdateAtMs: Long = 0L

    private val visualHoldMs = 320L
    private var lastNonEmptyVisualAtMs: Long = 0L
    private var lastNonEmptyPersons: List<Person> = emptyList()
    private var lastNonEmptyActionResults: List<ActionResult> = emptyList()

    private val poseSmoother = PoseSmoother(
        alpha = 0.30f,
        minScore = 0.2f
    )

    companion object {
        private const val TAG = "MoveNetApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化姿态检测器
        poseDetector = PoseDetector(this)

        // 初始化动作检测器
        actionDetector = ActionDetector()
        
        // 初始化TTS
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "中文语言不支持")
                    Toast.makeText(this, "语音播报不可用", Toast.LENGTH_SHORT).show()
                } else {
                    // 设置语速（1.5倍速，范围0.5-2.0）
                    textToSpeech?.setSpeechRate(1.5f)
                    // 设置音调（稍微提高）
                    textToSpeech?.setPitch(1.1f)
                    Log.d(TAG, "TTS初始化成功")
                }
            } else {
                Log.e(TAG, "TTS初始化失败")
            }
        }

        // 初始化相机执行器
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 检查相机权限
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 预览
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            // 图像分析
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            // 选择前置摄像头
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // 解绑所有用例
                cameraProvider.unbindAll()

                // 绑定用例到相机
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            // 检测姿态
            val poseResult = poseDetector.estimatePoses(
                imageProxy,
                imageProxy.imageInfo.rotationDegrees,
                /*isFrontCamera=*/true
            )
            
            // 检测每个人的动作
            val rawActionResults = poseResult.persons.map { person ->
                actionDetector.detectAction(person)
            }

            // 进一步平滑动作结果，减少闪跳
            val stableActionResults = actionSmoother.update(rawActionResults)
            
            // 朗读第一个人的动作（使用平滑后的结果）
            if (stableActionResults.isNotEmpty()) {
                speakActionResult(stableActionResults[0])
            }
            
            // 更新可视化
            runOnUiThread {
                val nowMs = SystemClock.uptimeMillis()

                // 1) 限制UI重绘频率（默认30fps），减少高频跳变带来的“闪动”
                if (nowMs - lastUiUpdateAtMs < uiFrameIntervalMs) {
                    return@runOnUiThread
                }
                lastUiUpdateAtMs = nowMs

                // 2) 短暂丢帧保持：模型偶发返回空结果时，继续显示上一次结果一小段时间
                val rawPersons = poseResult.persons

                val visualPersons = if (rawPersons.isNotEmpty()) {
                    lastNonEmptyPersons = rawPersons
                    lastNonEmptyActionResults = stableActionResults
                    lastNonEmptyVisualAtMs = nowMs
                    rawPersons
                } else if (nowMs - lastNonEmptyVisualAtMs <= visualHoldMs) {
                    lastNonEmptyPersons
                } else {
                    poseSmoother.reset()
                    emptyList()
                }

                val visualActionResults = if (rawPersons.isNotEmpty()) {
                    stableActionResults
                } else if (nowMs - lastNonEmptyVisualAtMs <= visualHoldMs) {
                    lastNonEmptyActionResults
                } else {
                    emptyList()
                }

                // 3) 关键点指数平滑（EMA）：显著降低手/肘等点位抖动
                val smoothedPersons = poseSmoother.smooth(visualPersons)

                // 更新上半部分的骨架覆盖层
                binding.overlay.apply {
                    setResults(smoothedPersons)
                    setActionResults(visualActionResults)
                    setImageSourceInfo(
                        poseResult.srcWidth,
                        poseResult.srcHeight,
                        imageProxy.imageInfo.rotationDegrees,
                        /*isMirrored=*/true
                    )
                    postInvalidateOnAnimation()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理图像失败", e)
        } finally {
            // 必须关闭imageProxy以释放缓冲区
            imageProxy.close()
        }
    }

    private class PoseSmoother(
        private val alpha: Float,
        private val minScore: Float
    ) {
        private val prev: MutableMap<Int, MutableMap<BodyPart, Pair<Float, Float>>> = mutableMapOf()

        fun reset() {
            prev.clear()
        }

        fun smooth(persons: List<Person>): List<Person> {
            if (persons.isEmpty()) return emptyList()

            val result = persons.mapIndexed { personIndex, person ->
                val prevForPerson = prev.getOrPut(personIndex) { mutableMapOf() }

                val smoothedKeyPoints = person.keyPoints.map { kp ->
                    val previous = prevForPerson[kp.bodyPart]

                    val smoothed = if (kp.score < minScore && previous != null) {
                        previous
                    } else if (previous == null) {
                        kp.coordinate
                    } else {
                        val newX = previous.first + alpha * (kp.coordinate.first - previous.first)
                        val newY = previous.second + alpha * (kp.coordinate.second - previous.second)
                        Pair(newX, newY)
                    }

                    prevForPerson[kp.bodyPart] = smoothed
                    kp.copy(coordinate = smoothed)
                }

                person.copy(keyPoints = smoothedKeyPoints)
            }

            // 若人数变少，清理多余缓存（本项目通常是单人模型，但做一下防御）
            val maxIndex = result.lastIndex
            val toRemove = prev.keys.filter { it > maxIndex }
            toRemove.forEach { prev.remove(it) }

            return result
        }
    }

    // 平滑动作识别结果，减少标签来回跳变
    private class ActionSmoother(
        private val windowSize: Int,
        private val minAgree: Int,
        private val holdMs: Long
    ) {
        private data class State(
            val history: ArrayDeque<StandardAction> = ArrayDeque(),
            var lastStable: ActionResult? = null,
            var lastStableAt: Long = 0L
        )

        private val states: MutableMap<Int, State> = mutableMapOf()

        fun update(results: List<ActionResult>): List<ActionResult> {
            val now = SystemClock.uptimeMillis()
            val output = mutableListOf<ActionResult>()

            results.forEachIndexed { index, result ->
                val state = states.getOrPut(index) { State() }
                val history = state.history

                history.addLast(result.action)
                if (history.size > windowSize) history.removeFirst()

                val top = history.groupingBy { it }.eachCount().maxByOrNull { it.value }
                val stableAction = top?.key
                val stableCount = top?.value ?: 0
                val isStable = stableAction != null && stableAction != StandardAction.UNKNOWN && stableCount >= minAgree

                val finalResult = when {
                    isStable -> {
                        val stabilized = result.copy(
                            action = stableAction,
                            confidence = max(result.confidence, 0.8f)
                        )
                        state.lastStable = stabilized
                        state.lastStableAt = now
                        stabilized
                    }
                    state.lastStable != null && now - state.lastStableAt <= holdMs -> state.lastStable!!
                    else -> result
                }

                output.add(finalResult)
            }

            val maxIndex = results.lastIndex
            val stale = states.keys.filter { it > maxIndex }
            stale.forEach { states.remove(it) }

            return output
        }
    }
    
    private fun speakActionResult(result: ActionResult) {
        // 添加到动作历史
        actionHistory.add(result.action)
        if (actionHistory.size > historySize) {
            actionHistory.removeAt(0)
        }
        
        // 只有当动作稳定（最近几帧都是同一个动作）时才朗读
        if (actionHistory.size < historySize) {
            return // 还没有足够的历史数据
        }
        
        // 检查动作是否稳定（最近5帧至少有4帧是同一个动作）
        val actionCounts = actionHistory.groupingBy { it }.eachCount()
        val stableAction = actionCounts.maxByOrNull { it.value }?.key
        val stableCount = actionCounts.maxByOrNull { it.value }?.value ?: 0
        
        if (stableCount < 4 || stableAction == StandardAction.UNKNOWN) {
            return // 动作不稳定或未知
        }
        
        val currentTime = System.currentTimeMillis()
        
        // 避免频繁朗读且动作改变时才朗读
        if (currentTime - lastSpeechTime < speechInterval && stableAction == lastSpokenAction) {
            return
        }
        
        if (textToSpeech?.isSpeaking == true) {
            return
        }
        
        // 构建朗读文本（使用稳定的动作）
        val actionName = when (stableAction) {
            StandardAction.STANDING -> "站立"
            StandardAction.SQUATTING -> "深蹲"
            StandardAction.ARMS_EXTENDED -> "水平举臂"
            StandardAction.JUMPING_JACK -> "开合跳"
            StandardAction.HORSE_STANCE -> "扎马步"
            StandardAction.UNKNOWN -> "未知动作"
            else -> return
        }
        
        val speechText = buildString {
            append("当前动作")
            append(actionName)
            
            if (result.corrections.isNotEmpty()) {
                append("，")
                append(result.corrections.joinToString("，"))
            }
        }
        
        // 朗读
        textToSpeech?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, null)
        
        lastSpokenAction = stableAction
        lastSpeechTime = currentTime
        
        Log.d(TAG, "TTS朗读: $speechText")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能使用此应用", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseDetector.close()
        
        // 关闭TTS
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
