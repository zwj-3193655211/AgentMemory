# AgentMemory 语义识别模型评估报告

> 评估日期：2025-03-21
> 目标：为 AgentMemory 选择最佳内置语义识别模型

---

## 1. 候选模型调研

### 1.1 Qwen3.5 系列（最新，2026年3月发布）⭐ 重点测试

| 模型 | 参数量 | 模型大小 | 上下文 | 特点 |
|------|--------|---------|--------|------|
| Qwen3.5-0.8B | 0.8B | ~1.6GB | 262K | 最小Qwen3.5，原生多模态 |
| Qwen3.5-2B | 2B | ~4GB | 262K | 平衡型，原生多模态 |

**优点：** 
- 最新架构（2026年3月发布）
- 原生多模态（图文视频）
- 超长上下文 262K tokens
- 200+ 语言支持
- 混合架构（Gated Delta + MoE）

**缺点：** 新发布，社区验证较少

### 1.2 Qwen3 系列（2025年4月发布）

| 模型 | 参数量 | 模型大小 | 上下文 | 特点 |
|------|--------|---------|--------|------|
| Qwen3-0.6B | 0.6B | ~1.2GB | 32K | 最小Qwen3，多语言支持 |
| Qwen3-1.7B | 1.7B | ~3.4GB | 32K | 平衡型，性能更好 |

**优点：** 成熟稳定，多语言支持好，Apache 2.0 许可
**缺点：** 1.7B版本CPU加载较慢

### 1.2 Qwen2.5 系列

| 模型 | 参数量 | 模型大小 | 上下文 | 特点 |
|------|--------|---------|--------|------|
| Qwen2.5-0.5B-Instruct | 0.5B | ~1GB | 128K | 指令跟随最佳 |
| Qwen2.5-1.5B-Instruct | 1.5B | ~3GB | 32K | 之前测试超时 |

**优点：** 成熟稳定，文档完善，社区活跃
**缺点：** 1.5B版本CPU加载慢

### 1.3 SmolLM2 系列（Hugging Face）

| 模型 | 参数量 | 模型大小 | 特点 |
|------|--------|---------|------|
| SmolLM2-135M-Instruct | 135M | ~270MB | 极小，移动端 |
| SmolLM2-360M-Instruct | 360M | ~720MB | 端侧最佳 |
| SmolLM2-1.7B-Instruct | 1.7B | ~3.4GB | 质量最佳 |

**优点：** 专为端侧优化，11T tokens 训练，质量领先
**缺点：** 360M版本对中文支持可能较弱

### 1.4 Gemma 3 系列（Google）

| 模型 | 参数量 | 模型大小 | 上下文 | 特点 |
|------|--------|---------|--------|------|
| Gemma 3 1B | 1B | 0.5GB(int4) | 128K | 超轻量，超长上下文 |

**优点：** 最小资源占用，128K超长上下文，Google支持
**缺点：** 仅文本，输出较简单

### 1.5 DeepSeek R1 Distilled（推理专用）

| 模型 | 参数量 | 模型大小 | 特点 |
|------|--------|---------|------|
| DeepSeek-R1-Distill-Qwen-1.5B | 1.5B | ~1.1GB | 推理最佳，数学逻辑强 |

**优点：** 推理能力超越 GPT-4o，数学/逻辑任务强
**缺点：** 非多语言优化

### 1.6 传统NLP方案（对照）

| 方案 | 参数量 | 模型大小 | 特点 |
|------|--------|---------|------|
| bge-small-zh-v1.5 | 33M | ~100MB | Embedding专用 |
| GLiNER-small | 166M | ~200MB | NER专用 |
| 规则匹配 | 0 | 0 | 无模型依赖 |

---

## 2. 评估维度

| 维度 | 权重 | 说明 |
|------|------|------|
| **启动速度** | 25% | 首次加载模型时间 |
| **推理速度** | 20% | 单次提取耗时 |
| **内存占用** | 20% | 运行时内存需求 |
| **提取质量** | 25% | 语义理解准确性 |
| **中文支持** | 10% | 对中文的处理能力 |

---

## 3. 测试计划

### 3.1 测试环境

```
OS: Windows 10/11
CPU: Intel/AMD x64 (无GPU)
RAM: 16GB+
Python: 3.10+
```

### 3.2 测试用例

#### 用例1：错误纠正提取

```json
输入: "用户遇到了PyInstaller打包错误：ModuleNotFoundError: No module named pycparser。解决方案：pip install pycparser"
期望输出:
{
  "type": "ERROR_CORRECTION",
  "title": "PyInstaller打包缺少pycparser模块",
  "tags": ["error", "pyinstaller", "packaging"],
  "extracted": {
    "problem": "ModuleNotFoundError: No module named pycparser",
    "cause": "缺少pycparser依赖",
    "solution": "pip install pycparser"
  }
}
```

#### 用例2：用户偏好提取

```json
输入: "我喜欢用中文交流，代码注释也用中文，请记住这一点"
期望输出:
{
  "type": "USER_PROFILE",
  "title": "用户偏好中文交流",
  "tags": ["preference", "language"],
  "extracted": {
    "preference": "中文交流和代码注释",
    "category": "language"
  }
}
```

#### 用例3：最佳实践提取

```json
输入: "在Windows上使用PyInstaller打包时，建议使用conda环境指定的方式：conda run -n env_name pyinstaller，这样可以确保使用正确的Python环境"
期望输出:
{
  "type": "BEST_PRACTICE",
  "title": "Windows PyInstaller打包最佳实践",
  "tags": ["practice", "pyinstaller", "windows"],
  "extracted": {
    "scenario": "Windows上PyInstaller打包",
    "practice": "使用conda run -n env_name pyinstaller指定环境"
  }
}
```

#### 用例4：项目上下文提取

```json
输入: "这是一个React+TypeScript项目，使用Vite构建，状态管理用Zustand"
期望输出:
{
  "type": "PROJECT_CONTEXT",
  "title": "React+TS项目上下文",
  "tags": ["react", "typescript", "vite"],
  "extracted": {
    "project_name": "",
    "tech_stack": ["React", "TypeScript", "Vite", "Zustand"],
    "key_info": "React+TypeScript项目，Vite构建，Zustand状态管理"
  }
}
```

#### 用例5：技能沉淀提取

```json
输入: "部署Docker容器的步骤：1. 编写Dockerfile 2. 构建镜像 docker build -t name . 3. 运行容器 docker run -d -p 80:80 name"
期望输出:
{
  "type": "SKILL",
  "title": "Docker容器部署流程",
  "tags": ["skill", "docker", "deployment"],
  "extracted": {
    "skill_name": "Docker容器部署",
    "steps": ["编写Dockerfile", "构建镜像", "运行容器"],
    "prerequisites": ["Docker已安装"]
  }
}
```

#### 用例6：无效内容（应跳过）

```json
输入: "好的，我明白了"
期望输出:
{
  "type": "SKIP",
  "reason": "无实质性内容"
}
```

#### 用例7：项目经验提取

```json
输入: "PyInstaller打包经验：1) PyInstaller默认使用系统Python而非当前激活的虚拟环境，需要用conda run -n env_name pyinstaller来指定环境；2) funasr、modelscope等库需要--collect-all参数收集所有数据文件，否则会缺少version.txt等资源文件；3) 包含torch等大型ML库的打包会生成300MB+的exe文件，这是正常的。"
期望输出:
{
  "type": "PROJECT_EXPERIENCE",
  "title": "PyInstaller打包经验总结",
  "tags": ["pyinstaller", "packaging", "python"],
  "extracted": {
    "experience": "PyInstaller打包三要点",
    "lessons": [
      "用conda run -n env_name pyinstaller指定虚拟环境",
      "funasr等库需--collect-all参数",
      "ML库打包300MB+是正常的"
    ],
    "related_technologies": ["PyInstaller", "conda", "torch"]
  }
}
```

### 3.3 性能测试脚本

```python
# test_model_performance.py
import time
import json
import psutil
import os

def test_model(model_name, test_cases):
    """测试模型性能"""
    results = {
        "model": model_name,
        "startup_time": 0,
        "memory_usage_mb": 0,
        "test_results": []
    }
    
    # 1. 测试启动时间
    start = time.time()
    # ... 加载模型 ...
    results["startup_time"] = time.time() - start
    
    # 2. 测试内存占用
    results["memory_usage_mb"] = psutil.Process().memory_info().rss / 1024 / 1024
    
    # 3. 测试各用例
    for case in test_cases:
        case_start = time.time()
        # ... 执行提取 ...
        inference_time = time.time() - case_start
        # 评估准确性
        results["test_results"].append({
            "case_id": case["id"],
            "inference_time_ms": inference_time * 1000,
            "accuracy_score": 0  # 人工评分 0-100
        })
    
    return results
```

---

## 4. 评估结果模板

| 模型 | 启动时间 | 推理速度 | 内存占用 | 提取质量 | 中文支持 | 综合评分 |
|------|---------|---------|---------|---------|---------|---------|
| Qwen3-0.6B | ?s | ?ms | ?MB | ?/100 | ?/10 | ? |
| Qwen2.5-0.5B | ?s | ?ms | ?MB | ?/100 | ?/10 | ? |
| SmolLM2-360M | ?s | ?ms | ?MB | ?/100 | ?/10 | ? |
| Gemma 3 1B | ?s | ?ms | ?MB | ?/100 | ?/10 | ? |
| DeepSeek R1 1.5B | ?s | ?ms | ?MB | ?/100 | ?/10 | ? |
| 规则匹配(基准) | 0s | <1ms | 0MB | 40/100 | 8/10 | - |

---

## 5. 最终方案

根据评估结果，AgentMemory 将提供三级模式：

### 5.1 轻量模式（默认）
- **模型**: bge-small-zh-v1.5 + 规则匹配
- **启动**: <5秒
- **内存**: ~200MB
- **适用**: 无需安装LLM的快速部署

### 5.2 本地LLM模式
- **模型**: [待评估确定]
- **启动**: 10-30秒
- **内存**: 1-4GB
- **适用**: 需要高质量提取的离线场景

### 5.3 API模式（可选）
- **服务**: GLM-4 / DeepSeek / Qwen API
- **启动**: <5秒
- **依赖**: 网络连接 + API Key
- **适用**: 最高质量提取，接受网络依赖

---

## 6. 决策记录

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-03-21 | 淘汰 Qwen2.5-1.5B float32 | CPU加载超时(>5分钟)，模型太大(6GB) |
| 2026-03-21 | 采用三级模式 | 兼顾可移植性与质量 |
| 2026-03-21 | 增加项目经验测试用例 | 用户需求，记录实践经验 |
| - | - | - |

---

## 7. 测试执行记录

### 7.1 执行进度

| 时间 | 阶段 | 状态 | 备注 |
|------|------|------|------|
| 2026-03-21 15:25 | 开始测试 | ✅ | 启动test_model_evaluation.py |
| 2026-03-21 15:25 | 规则匹配 | ✅ 完成 | 6/6正确，平均推理<1ms |
| 2026-03-21 15:28 | Qwen2.5-1.5B加载 | ✅ 完成 | 加载成功，开始推理 |
| 2026-03-21 15:30+ | LLM推理测试 | ⏳ 进行中 | CPU推理较慢，等待中 |

### 7.2 规则匹配测试结果（已完成）

| 用例ID | 用例名称 | 期望类型 | 实际类型 | 结果 | 推理时间 |
|--------|---------|---------|---------|------|---------|
| error_correction | 错误纠正提取 | ERROR_CORRECTION | ERROR_CORRECTION | ✅ | 0.63ms |
| user_profile | 用户偏好提取 | USER_PROFILE | USER_PROFILE | ✅ | 0.04ms |
| best_practice | 最佳实践提取 | BEST_PRACTICE | BEST_PRACTICE | ✅ | 0.10ms |
| project_context | 项目上下文提取 | PROJECT_CONTEXT | PROJECT_CONTEXT | ✅ | 0.10ms |
| skill | 技能沉淀提取 | SKILL | SKILL | ✅ | 0.21ms |
| skip | 无效内容跳过 | SKIP | SKIP | ✅ | 0.01ms |
| project_experience | 项目经验提取 | PROJECT_EXPERIENCE | PROJECT_EXPERIENCE | ✅ | 0.81ms |

**规则匹配总结：**
- 类型识别正确率：**7/7 (100%)**
- 平均推理时间：0.27ms
- 内存占用：几乎为0
- 启动时间：0秒

### 7.3 LLM模型测试结果（已完成）

| 模型 | 加载时间 | 内存占用 | 类型正确率 | 平均推理时间 | 状态 |
|------|---------|---------|-----------|-------------|------|
| Qwen2.5-0.5B | 220秒 | 2.3GB | 2/7 (28.6%) | 55-82秒 | ❌ 准确率低 |
| Qwen3-0.6B | 1155秒 | 1.2GB | 2/7 (28.6%) | 91-106秒 | ❌ 准确率低，加载慢 |
| Qwen3.5-0.8B | 603秒 | 3.6GB | 1/7 (14.3%) | 40-126秒 | ❌ 准确率最低 |
| SmolLM2-360M | - | - | 未完成 | - | ❌ 测试中断 |

**详细测试结果：**

#### Qwen2.5-0.5B-Instruct
```
错误纠正提取: ✅ OK (63.5s)
用户偏好提取: ✅ OK (78.2s)
最佳实践提取: ❌ SKIP (期望 BEST_PRACTICE)
项目上下文提取: ❌ SKIP (期望 PROJECT_CONTEXT)
技能沉淀提取: ❌ SKIP (期望 SKILL)
无效内容跳过: ❌ ERROR_CORRECTION (期望 SKIP)
项目经验提取: ❌ SKIP (期望 PROJECT_EXPERIENCE)
正确率: 2/7 (28.6%)
```

#### Qwen3-0.6B
```
错误纠正提取: ❌ SKIP (期望 ERROR_CORRECTION)
用户偏好提取: ✅ OK (92.2s)
最佳实践提取: ❌ SKIP (期望 BEST_PRACTICE)
项目上下文提取: ❌ SKIP (期望 PROJECT_CONTEXT)
技能沉淀提取: ❌ BEST_PRACTICE (期望 SKILL)
无效内容跳过: ✅ OK (101.7s)
项目经验提取: ❌ SKIP (期望 PROJECT_EXPERIENCE)
正确率: 2/7 (28.6%)
```

#### Qwen3.5-0.8B
```
错误纠正提取: ❌ PROJECT_CONTEXT (期望 ERROR_CORRECTION)
用户偏好提取: ❌ SKIP (期望 USER_PROFILE)
最佳实践提取: ❌ SKIP (期望 BEST_PRACTICE)
项目上下文提取: ✅ OK (47.9s)
技能沉淀提取: ❌ PROJECT_CONTEXT (期望 SKILL)
无效内容跳过: ❌ USER_PROFILE (期望 SKIP)
项目经验提取: ❌ PROJECT_CONTEXT (期望 PROJECT_EXPERIENCE)
正确率: 1/7 (14.3%)
```

**LLM测试结论：**
- 小模型(<1B)语义理解能力严重不足
- 正确率远低于规则匹配(100% vs 14-29%)
- CPU推理时间过长(40-126秒/条)，不可接受
- 模型倾向于输出 SKIP 或 PROJECT_CONTEXT（保守策略）
- 不适合作为内置语义识别方案

---

## 8. 失败尝试记录

### 8.1 Qwen3.5-0.8B 下载失败

**时间：** 2026-03-21 15:07
**错误：** 网络超时，多次重试失败
```
Error while downloading from https://cas-bridge.xethub.hf.co/...: The read operation timed out
Trying to resume download...
```
**原因：** 网络不稳定，模型较大(~1.6GB)
**处理：** 跳过该模型，先测试已有缓存的模型

### 8.2 Qwen2.5-1.5B 首次加载超时

**时间：** 2026-03-21 早些时候
**错误：** CPU加载 float32 模型超时(>5分钟)
**原因：** 
- float32精度占用内存大(~6GB)
- CPU加载速度慢
- 未使用量化模型
**处理：** 
- 方案1：使用更小的模型(0.5B/0.6B)
- 方案2：使用GGUF量化格式
- 方案3：规则匹配作为默认方案

### 8.3 bge-small-zh 首次下载失败

**时间：** 2026-03-21
**错误：** 模型未缓存，首次下载超时
**处理：** 设置 HF_ENDPOINT='https://hf-mirror.com' 使用国内镜像

### 8.4 Qwen2.5-1.5B CPU推理过慢

**时间：** 2026-03-21 15:25-18:30
**错误：** 模型加载成功(~3秒)，但CPU推理极慢，测试超时
**原因：**
- 1.5B参数模型，float32精度约6GB内存
- CPU推理速度约1-2 tokens/秒
- 单个测试用例需要生成300+ tokens，耗时数分钟
- 7个测试用例预计需要30分钟+
**处理：** 放弃CPU测试该模型，规则匹配作为默认方案

### 8.5 Qwen2.5-0.5B 下载超时

**时间：** 2026-03-21 15:38
**错误：** 网络超时
```
Error while downloading from https://cas-bridge.xethub.hf.co/...: The read operation timed out
Trying to resume download...
```
**处理：** 测试被终止

---

## 9. 最终结论

### 9.1 测试结果汇总

| 模型 | 启动时间 | 内存占用 | 类型正确率 | 平均推理 | 状态 |
|------|---------|---------|-----------|---------|------|
| **规则匹配** | 0秒 | ~0MB | **7/7 (100%)** | **0.27ms** | ✅ **推荐** |
| Qwen2.5-0.5B | 220秒 | 2.3GB | 2/7 (28.6%) | 63秒 | ❌ 准确率低 |
| Qwen3-0.6B | 1155秒 | 1.2GB | 2/7 (28.6%) | 98秒 | ❌ 准确率低 |
| Qwen3.5-0.8B | 603秒 | 3.6GB | 1/7 (14.3%) | 68秒 | ❌ 准确率最低 |

### 9.2 推荐方案

**规则匹配作为默认方案：**

| 指标 | 值 |
|------|-----|
| 启动时间 | 0秒 |
| 内存占用 | 几乎为0 |
| 类型正确率 | **100% (7/7)** |
| 推理速度 | **<1ms** |
| 可移植性 | 极高（无依赖） |

**核心发现：**
1. **规则匹配完胜**：100%准确率 vs LLM 14-29%
2. **小模型不可用**：<1B参数模型语义理解能力严重不足
3. **CPU推理瓶颈**：即使小模型也需要40-126秒/条
4. **可移植性**：规则匹配无需下载模型，开箱即用

**适用场景：**
- 快速部署
- 资源受限环境
- 无GPU环境
- 需要高可移植性

**后续优化方向：**
1. 规则匹配增加更多模式（复杂场景）
2. 可选接入外部LLM API（GLM/DeepSeek）提升精度
3. 用户有GPU时可启用本地LLM模式（需>3B参数模型）

---

## 附录：参考资源

### 开源语义识别项目（可借鉴）

#### 1. Google LangExtract ⭐ 推荐

**GitHub:** https://github.com/google/langextract

**特点：**
- Google 官方开源，Apache 2.0 许可
- 支持本地 LLM（通过 Ollama）
- 支持云端 API（Gemini、OpenAI）
- **精确源文本定位**：每个提取结果映射到原文位置
- **交互式可视化**：生成 HTML 可视化
- **长文档优化**：分块并行处理，多次扫描提高召回率
- **Few-shot 学习**：只需几个示例定义提取任务

**使用示例：**
```python
import langextract as lx

result = lx.extract(
    text_or_documents=input_text,
    prompt_description="提取错误信息和解决方案",
    examples=[...],
    model_id="gemma2:2b",  # 本地 Ollama
    model_url="http://localhost:11434"
)
```

**借鉴价值：**
- 提取结果结构设计
- Few-shot prompt 模板
- 本地/云端混合架构

#### 2. semantic-llama

**GitHub:** https://github.com/cmungall/semantic-llama

**特点：** 使用 LLM 提取语义信息的知识提取工具

#### 3. NERRE (Named Entity Recognition & Relation Extraction)

**GitHub:** https://github.com/lbnlp/NERRE

**特点：** 从科学文本提取结构化关系数据，输出 JSON

---

### 模型资源

- [Qwen3 HuggingFace](https://huggingface.co/collections/Qwen/qwen3)
- [SmolLM2 HuggingFace](https://huggingface.co/collections/HuggingFaceTB/smolvlm)
- [Gemma 3 HuggingFace](https://huggingface.co/google/gemma-3-1b-it)
- [DeepSeek R1](https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B)
- [Top Tiny LLMs 2025](https://datawizz.ai/blog/top-tiny-open-source-language-models-in-early-2025)
