# MoveNet Android 应用

这是一个基于MoveNet的Android姿态检测应用框架。

## 功能
- 实时相机预览
- 人体姿态检测（17个关键点）
- 关键点和骨架可视化
- 置信度显示

## 项目结构
- `MainActivity.kt` - 主活动，处理相机和UI
- `PoseDetector.kt` - MoveNet姿态检测核心类
- `OverlayView.kt` - 自定义View用于绘制关键点和骨架
- `activity_main.xml` - 主布局文件

## 下一步需要做的事情

### 1. 下载MoveNet模型
您需要下载TensorFlow Lite的MoveNet模型文件：

**推荐模型：**
- **Lightning版本（快速）**: [下载链接](https://tfhub.dev/google/lite-model/movenet/singlepose/lightning/tflite/int8/4)
- **Thunder版本（精确）**: [下载链接](https://tfhub.dev/google/lite-model/movenet/singlepose/thunder/tflite/int8/4)

下载后将 `.tflite` 文件放入 `app/src/main/assets/` 文件夹，并命名为 `movenet_lightning.tflite` 或 `movenet_thunder.tflite`

### 2. 更新PoseDetector.kt
在 `PoseDetector.kt` 文件中取消注释模型加载代码：
```kotlin
interpreter = Interpreter(FileUtil.loadMappedFile(context, "movenet_lightning.tflite"), options)
```

### 3. 改进图像转换
当前的 `ImageProxy.toBitmap()` 方法是简化版本，需要实现正确的YUV到Bitmap转换。可以考虑使用以下库：
- `androidx.camera:camera-core` 的内置转换方法
- 或自己实现YUV_420_888到RGB的转换

### 4. 构建和运行
```bash
./gradlew assembleDebug
```

## 依赖项
已添加的主要依赖：
- CameraX (相机处理)
- TensorFlow Lite (模型推理)
- TensorFlow Lite Support (图像处理)
- TensorFlow Lite GPU (GPU加速)

## 权限
应用需要相机权限，已在AndroidManifest.xml中配置。

## 注意事项
- 最低SDK版本：24 (Android 7.0)
- 建议在真机上测试以获得最佳性能
- 确保设备有足够的计算能力运行实时姿态检测

## 后续优化建议
1. 添加GPU加速支持
2. 实现多人姿态检测
3. 添加姿态记录和分析功能
4. 优化性能和帧率
5. 添加前置/后置摄像头切换
6. 添加姿态动作识别功能
