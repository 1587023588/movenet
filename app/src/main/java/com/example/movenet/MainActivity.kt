package com.example.movenet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var poseDetector: PoseDetector
    private lateinit var actionDetector: ActionDetector
    private var camera: Camera? = null
    
    // TTS相关
    private var textToSpeech: TextToSpeech? = null
    private var lastSpokenAction: StandardAction? = null
    private var lastSpeechTime: Long = 0
    private val speechInterval = 2000L // 2秒间隔，快速响应
    
    // 动作稳定性检查
    private var actionHistory = mutableListOf<StandardAction>()
    private val historySize = 5 // 保留最近5帧的动作判定

    companion object {
        private const val TAG = "MoveNetApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false) // 隐藏默认标题

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
            val actionResults = poseResult.persons.map { person ->
                actionDetector.detectAction(person)
            }
            
            // 朗读第一个人的动作（如果检测到）
            if (actionResults.isNotEmpty()) {
                speakActionResult(actionResults[0])
            }
            
            // 更新可视化
            runOnUiThread {
                // 更新上半部分的骨架覆盖层
                binding.overlay.apply {
                    setResults(poseResult.persons)
                    setActionResults(actionResults)
                    setImageSourceInfo(
                        poseResult.srcWidth,
                        poseResult.srcHeight,
                        imageProxy.imageInfo.rotationDegrees,
                        /*isMirrored=*/true
                    )
                    invalidate()
                }
                
                // 更新下半部分的关键点视图
                binding.keyPointView.apply {
                    setResults(poseResult.persons)
                    setActionResults(actionResults)
                    setImageSourceInfo(
                        poseResult.srcWidth,
                        poseResult.srcHeight,
                        /*isMirrored=*/true
                    )
                    invalidate()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理图像失败", e)
        } finally {
            // 必须关闭imageProxy以释放缓冲区
            imageProxy.close()
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
