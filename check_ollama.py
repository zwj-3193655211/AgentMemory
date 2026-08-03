#!/usr/bin/env python3
import requests

OLLAMA_URL = "http://localhost:11434"
try:
    response = requests.get(f"{OLLAMA_URL}/api/tags", timeout=5)
    if response.status_code == 200:
        print("✅ Ollama 已连接！")
        models = response.json().get('models', [])
        if models:
            print("可用模型:")
            for m in models:
                print(f"  - {m['name']}")
    else:
        print(f"Ollama 响应异常: {response.status_code}")
except Exception as e:
    print(f"❌ 连接失败: {e}")
