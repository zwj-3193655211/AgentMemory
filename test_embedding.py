#!/usr/bin/env python3
"""测试 embedding 服务"""
import requests
import json

EMBED_URL = "http://localhost:8100/embed"

# 测试不同的请求格式
test_cases = [
    {"text": "测试文本"},
    {"texts": ["测试文本"]},
    {"text": "Vue.js 前端框架"},
]

for i, payload in enumerate(test_cases, 1):
    print(f"\n【测试 {i}】: {payload}")
    try:
        response = requests.post(EMBED_URL, json=payload, timeout=10)
        print(f"状态码: {response.status_code}")
        print(f"响应: {response.json()}")
    except Exception as e:
        print(f"错误: {e}")
