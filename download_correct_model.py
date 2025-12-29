import urllib.request
import os

# MoveNet MultiPose Lightning 模型的URL（TFHub直接下载）
MODEL_URL = "https://tfhub.dev/google/lite-model/movenet/multipose/lightning/tflite/float16/1?lite-format=tflite"

# 保存路径
SAVE_PATH = "app/src/main/assets/movenet_multipose.tflite"

print("正在下载MoveNet MultiPose Lightning模型...")
print(f"URL: {MODEL_URL}")
print(f"保存到: {SAVE_PATH}")

try:
    # 确保目录存在
    os.makedirs(os.path.dirname(SAVE_PATH), exist_ok=True)
    
    # 添加User-Agent避免被阻止
    req = urllib.request.Request(
        MODEL_URL,
        headers={'User-Agent': 'Mozilla/5.0'}
    )
    
    # 下载文件
    with urllib.request.urlopen(req) as response:
        with open(SAVE_PATH, 'wb') as out_file:
            out_file.write(response.read())
    
    # 检查文件大小
    file_size = os.path.getsize(SAVE_PATH)
    print(f"✓ 下载成功！文件大小: {file_size} bytes ({file_size / 1024 / 1024:.2f} MB)")
    
    # 验证文件大小（MoveNet multipose应该约3-4MB）
    if file_size < 1000000:  # 小于1MB
        print("⚠️ 警告：文件大小异常，可能下载不完整")
    else:
        print("✓ 文件大小正常")
        
except Exception as e:
    print(f"❌ 下载失败: {e}")
    print("\n备选方案：")
    print("请手动下载：")
    print("1. 在浏览器访问: https://tfhub.dev/google/lite-model/movenet/multipose/lightning/1")
    print("2. 向下滚动找到 'Download' 部分")
    print("3. 点击 'TFLite (Float16 quantized)' 链接下载")
    print(f"4. 将下载的 .tflite 文件重命名为 movenet_multipose.tflite")
    print(f"5. 放入 app/src/main/assets/ 文件夹")
