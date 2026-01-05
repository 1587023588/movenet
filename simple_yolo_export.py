#!/usr/bin/env python3
"""
使用Ultralytics官方导出YOLOv8为TFLite (简化版)
"""
from ultralytics import YOLO
from pathlib import Path
import shutil

def export_yolo_tflite():
    print("=" * 70)
    print("YOLOv8 Pose 导出为 TFLite")
    print("=" * 70)
    
    try:
        print("\n1️⃣  加载模型...")
        model = YOLO('yolov8n-pose.pt')
        print("✓ 模型加载成功")
        
        print("\n2️⃣  尝试直接导出为TFLite...")
        print("   (这会自动处理所有转换)")
        
        # 尝试直接导出为TFLite
        try:
            result = model.export(format='tflite', imgsz=320)
            print(f"\n✅ 直接导出成功: {result}")
            
            # 复制到assets
            src = Path(result)
            dst = Path("app/src/main/assets/yolov8n_pose.tflite")
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(src, dst)
            
            print(f"✓ 已复制到: {dst}")
            
            # 删除PT文件
            old_pt = Path("app/src/main/assets/yolov8n_pose.pt")
            if old_pt.exists():
                old_pt.unlink()
            
            print("\n✅ 导出完成!")
            return True
            
        except Exception as e:
            print(f"   ⚠️ 直接导出失败: {e}")
            print("\n3️⃣  尝试导出为SavedModel格式...")
            
            # 导出为SavedModel
            result = model.export(format='saved_model', imgsz=320)
            print(f"✓ SavedModel导出成功: {result}")
            
            print("\n4️⃣  使用TensorFlow将SavedModel转换为TFLite...")
            try:
                import tensorflow as tf
                
                converter = tf.lite.TFLiteConverter.from_saved_model(result)
                converter.target_spec.supported_ops = [
                    tf.lite.OpsSet.TFLITE_BUILTINS,
                    tf.lite.OpsSet.SELECT_TF_OPS
                ]
                
                tflite_model = converter.convert()
                
                dst = Path("app/src/main/assets/yolov8n_pose.tflite")
                dst.parent.mkdir(parents=True, exist_ok=True)
                
                with open(dst, 'wb') as f:
                    f.write(tflite_model)
                
                print(f"✓ TFLite模型已保存: {dst}")
                
                # 清理
                import shutil
                if Path(result).exists():
                    shutil.rmtree(result, ignore_errors=True)
                old_pt = Path("app/src/main/assets/yolov8n_pose.pt")
                if old_pt.exists():
                    old_pt.unlink()
                
                print("\n✅ 转换完成!")
                return True
                
            except ImportError:
                print("   ❌ TensorFlow未安装，无法继续转换")
                return False
            
    except Exception as e:
        print(f"\n❌ 出错: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = export_yolo_tflite()
    exit(0 if success else 1)
