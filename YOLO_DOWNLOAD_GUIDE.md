# YOLOv8 Pose 转 TFLite - Google Colab 脚本

## 使用步骤：

1. 打开 Google Colab: https://colab.research.google.com/
2. 创建新笔记本
3. 复制下面的代码到单元格并运行
4. 下载生成的 yolov8n-pose_saved_model/yolov8n-pose_float16.tflite
5. 重命名为 yolov8n_pose.tflite
6. 放入 app/src/main/assets/ 文件夹

---

## Colab 代码：

```python
# 单元格 1: 安装依赖
!pip install ultralytics -q

# 单元格 2: 下载并转换模型
from ultralytics import YOLO
from google.colab import files

# 加载模型
print("📥 下载YOLOv8n-pose模型...")
model = YOLO('yolov8n-pose.pt')

# 导出为TFLite
print("⚙️  转换为TFLite格式...")
try:
    # 尝试直接导出
    result = model.export(format='tflite', imgsz=320)
    print(f"✅ 转换成功: {result}")
    
    # 下载文件
    print("📦 准备下载...")
    files.download(result)
    
except Exception as e:
    print(f"❌ 转换失败: {e}")
    print("\n尝试导出为SavedModel...")
    
    # 备用方案：导出SavedModel
    result = model.export(format='saved_model', imgsz=320)
    print(f"✅ SavedModel生成: {result}")
    
    # 手动转换为TFLite
    import tensorflow as tf
    converter = tf.lite.TFLiteConverter.from_saved_model(result)
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS
    ]
    tflite_model = converter.convert()
    
    # 保存并下载
    tflite_file = "yolov8n_pose.tflite"
    with open(tflite_file, 'wb') as f:
        f.write(tflite_model)
    
    print(f"✅ TFLite模型已生成")
    files.download(tflite_file)

print("\n✨ 完成！请将下载的文件重命名为 yolov8n_pose.tflite")
print("   然后放入 app/src/main/assets/ 文件夹")
```

---

## 方案3：直接下载（如果可用）

访问这些链接尝试直接下载：

1. **TFHub**: https://tfhub.dev/
   搜索 "yolov8 pose tflite"

2. **Kaggle Models**: https://www.kaggle.com/models
   搜索 "yolov8 pose"

3. **Ultralytics文档**: https://docs.ultralytics.com/models/yolov8/
   查看导出部分

---

## 快速测试命令（本地）

如果想在本地Python环境测试：

```bash
# 确保有足够的依赖
pip install ultralytics tensorflow

# 运行转换
python -c "from ultralytics import YOLO; m=YOLO('yolov8n-pose.pt'); m.export(format='tflite', imgsz=320)"
```

---

## 文件放置位置

下载后放到：
```
movenet/
  app/
    src/
      main/
        assets/
          yolov8n_pose.tflite  ← 放这里
```

## 预期文件大小

- YOLOv8n-pose TFLite: 约 6-13 MB
- 如果文件太小(<1MB)或太大(>50MB)，可能转换有问题

