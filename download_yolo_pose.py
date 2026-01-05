#!/usr/bin/env python3
"""
下载YOLOv8 Pose nano模型
"""
import shutil
from pathlib import Path
from ultralytics import YOLO

def download_yolo_pose():
    print("=" * 60)
    print("YOLOv8 Pose模型下载工具")
    print("=" * 60)
    
    # 创建资源目录
    assets_dir = Path("app/src/main/assets")
    assets_dir.mkdir(parents=True, exist_ok=True)
    
    try:
        print("\n📥 下载YOLOv8n-pose模型...")
        model = YOLO('yolov8n-pose.pt')
        print("✓ 模型下载成功")
        print(f"  模型: YOLOv8 Nano Pose")
        print(f"  任务: 姿态检测 (17个COCO关键点)")
        
        print("\n📁 查找模型文件...")
        # 查找已下载的pt文件
        import glob
        search_patterns = [
            str(Path.home() / ".cache/ultralytics/**/*.pt"),
            str(Path.home() / ".ultralytics/**/*.pt"),
            "yolov8n-pose.pt",
            "runs/pose/train*/*.pt"
        ]
        
        found_path = None
        for pattern in search_patterns:
            matches = glob.glob(pattern, recursive=True)
            for match in matches:
                if 'yolov8n-pose' in match or 'yolov8n_pose' in match:
                    found_path = match
                    break
            if found_path:
                break
        
        if not found_path and Path("yolov8n-pose.pt").exists():
            found_path = "yolov8n-pose.pt"
        
        if found_path:
            print(f"✓ 找到模型: {found_path}")
            
            # 复制到assets
            dest = assets_dir / "yolov8n_pose.pt"
            shutil.copy(found_path, dest)
            file_size = dest.stat().st_size
            print(f"\n✅ 模型已保存到: {dest}")
            print(f"   文件大小: {file_size / 1024 / 1024:.2f} MB")
            
            print("\n📝 下一步:")
            print("   1. 修改PoseDetector.kt加载此模型")
            print("   2. 使用TensorFlow Lite处理推理")
            print("   3. 解析YOLO输出并映射到关键点")
            return True
        
    except Exception as e:
        print(f"\n❌ 错误: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = download_yolo_pose()
    exit(0 if success else 1)
    exit(0 if success else 1)
