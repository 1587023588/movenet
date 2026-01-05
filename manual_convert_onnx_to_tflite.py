#!/usr/bin/env python3
"""
使用已生成的ONNX文件手动转换为TFLite
"""
import tensorflow as tf
import onnx
from pathlib import Path
import shutil

def manual_onnx_to_tflite():
    print("=" * 70)
    print("手动将ONNX转换为TFLite")
    print("=" * 70)
    
    onnx_file = "yolov8n-pose.onnx"
    
    if not Path(onnx_file).exists():
        print(f"❌ ONNX文件不存在: {onnx_file}")
        print("请先运行 simple_yolo_export.py 生成ONNX文件")
        return False
    
    try:
        print(f"\n1️⃣  加载ONNX模型: {onnx_file}")
        
        # 方法1: 使用onnx-tensorflow
        print("   尝试使用onnx-tf...")
        try:
            from onnx_tf.backend import prepare
        except ImportError:
            print("   ⚠️ onnx-tf未安装，正在安装...")
            import subprocess
            import sys
            result = subprocess.run(
                [sys.executable, "-m", "pip", "install", "onnx-tf"],
                capture_output=True,
                text=True
            )
            if result.returncode == 0:
                from onnx_tf.backend import prepare
            else:
                raise ImportError(f"无法安装onnx-tf: {result.stderr}")
        
        onnx_model = onnx.load(onnx_file)
        tf_rep = prepare(onnx_model)
        
        saved_model_path = "temp_saved_model"
        tf_rep.export_graph(saved_model_path)
        
        print(f"✓ SavedModel已生成: {saved_model_path}")
        
        # 转换为TFLite
        print("\n2️⃣  转换为TFLite...")
        converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_path)
        converter.optimizations = []
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS
        ]
        
        tflite_model = converter.convert()
        
        # 保存TFLite模型
        tflite_path = Path("app/src/main/assets/yolov8n_pose.tflite")
        tflite_path.parent.mkdir(parents=True, exist_ok=True)
        
        with open(tflite_path, 'wb') as f:
            f.write(tflite_model)
        
        file_size = tflite_path.stat().st_size
        print(f"✓ TFLite模型已保存: {tflite_path}")
        print(f"  文件大小: {file_size / 1024 / 1024:.2f} MB")
        
        # 清理临时文件
        if Path(saved_model_path).exists():
            shutil.rmtree(saved_model_path, ignore_errors=True)
        
        # 删除PT文件
        old_pt = Path("app/src/main/assets/yolov8n_pose.pt")
        if old_pt.exists():
            old_pt.unlink()
            print(f"✓ 已删除PT文件: {old_pt}")
        
        print("\n" + "=" * 70)
        print("✅ 转换成功！")
        print("=" * 70)
        return True
            
    except Exception as e:
        print(f"\n❌ 转换失败: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = manual_onnx_to_tflite()
    exit(0 if success else 1)
