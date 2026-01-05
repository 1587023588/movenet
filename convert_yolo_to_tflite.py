#!/usr/bin/env python3
"""
通过ONNX中间格式将YOLOv8 Pose转换为TFLite
"""
import os
import sys
import subprocess
from pathlib import Path

def convert_via_onnx():
    print("=" * 70)
    print("YOLOv8 Pose -> ONNX -> TFLite 转换 (通过中间格式)")
    print("=" * 70)
    
    try:
        from ultralytics import YOLO
        
        print("\n1️⃣  加载PyTorch模型...")
        model = YOLO('yolov8n-pose.pt')
        print("✓ 模型加载成功")
        
        print("\n2️⃣  导出为ONNX格式...")
        onnx_path = model.export(format='onnx', imgsz=320)
        print(f"✓ ONNX导出成功: {onnx_path}")
        
        # 尝试使用onnx-tf转换
        print("\n3️⃣  尝试安装onnx-tf并转换...")
        
        # 先尝试导入
        try:
            import onnx_tf
        except ImportError:
            print("   安装onnx-tf...")
            result = subprocess.run(
                [sys.executable, "-m", "pip", "install", "-q", "onnx-tf"],
                capture_output=True,
                text=True
            )
            if result.returncode != 0:
                print(f"   ⚠️ onnx-tf安装失败: {result.stderr}")
                raise ImportError("onnx-tf")
        
        import onnx
        from onnx_tf.backend import prepare
        
        # 加载ONNX模型
        onnx_model = onnx.load(onnx_path)
        
        # 准备TensorFlow表示
        print("   正在转换...")
        tf_rep = prepare(onnx_model)
        
        # 导出为SavedModel
        saved_model_path = "yolov8n_pose_saved_model"
        tf_rep.export_graph(saved_model_path)
        print(f"✓ SavedModel已导出: {saved_model_path}")
        
        # 转换为TFLite
        print("\n4️⃣  转换为TFLite...")
        import tensorflow as tf
        
        converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_path)
        converter.optimizations = []  # 不优化，保持原样
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS
        ]
        
        tflite_model = converter.convert()
        
        tflite_path = "app/src/main/assets/yolov8n_pose.tflite"
        Path(tflite_path).parent.mkdir(parents=True, exist_ok=True)
        
        with open(tflite_path, 'wb') as f:
            f.write(tflite_model)
        
        file_size = Path(tflite_path).stat().st_size
        print(f"✓ TFLite模型已保存: {tflite_path}")
        print(f"  文件大小: {file_size / 1024 / 1024:.2f} MB")
        
        # 清理
        print("\n5️⃣  清理临时文件...")
        import shutil
        if Path(saved_model_path).exists():
            shutil.rmtree(saved_model_path)
        old_pt = Path("app/src/main/assets/yolov8n_pose.pt")
        if old_pt.exists():
            old_pt.unlink()
        print("✓ 临时文件已清理")
        
        print("\n" + "=" * 70)
        print("✅ 转换完成！TFLite模型已准备就绪")
        print("=" * 70)
        return True
        
    except Exception as e:
        print(f"\n❌ 转换失败: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = convert_via_onnx()
    exit(0 if success else 1)
