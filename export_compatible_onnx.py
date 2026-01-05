"""
导出兼容 ONNX Runtime 1.17 的 YOLOv8 Pose 模型
使用 opset=13 确保 IR version 9 兼容性
"""
from ultralytics import YOLO

# 加载模型（使用正确的文件名）
model = YOLO('yolov8n-pose.pt')

# 导出为 ONNX，指定 opset=13（对应 IR version 8，兼容 ONNX Runtime 1.17）
# opset 13 是一个安全的选择，广泛支持
model.export(
    format='onnx',
    imgsz=320,
    opset=13,  # 关键参数：使用较低的 opset 版本
    simplify=True,
    dynamic=False
)

print("✅ 导出完成：yolov8n_pose.onnx (opset=13, IR version 8)")
print("该模型应该兼容 ONNX Runtime Android 1.17.0")
