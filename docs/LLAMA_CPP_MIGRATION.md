# Ollama → llama.cpp 迁移指南

> 2026-08-10 · AgentMemory 已完全切换至 llama.cpp，Ollama 已卸载

## 为什么切换

| 对比项 | Ollama | llama.cpp (llama-server) |
|--------|--------|--------------------------|
| 生成速度 (Qwen3.5-2B) | ~98 t/s | **~126 t/s**（快 28%） |
| 架构 | llama.cpp 引擎 + Go 封装层 | C++ 原生，无中间层 |
| 显存占用 | 2.74GB 模型 | 1.87GB 模型 |
| 灵活性 | 受限 | 全参数可控 |

## 一、llama.cpp 安装位置与结构

```
D:\llama.cpp\
├── llama-server.exe          # OpenAI 兼容 API 服务（核心）
├── llama-cli.exe             # 命令行交互
├── llama-bench.exe           # 性能基准
├── start-llama-server.bat    # 一键启动脚本（双击即可）
├── llama-server.log          # 运行日志
├── models\
│   └── Qwen3.5-2B-Q8_0.gguf  # 当前模型（unsloth 官方量化）
└── tools\                    # GGUF 修复脚本（处理 Ollama 私有导出时用）
```

## 二、快速开始

### 1. 启动服务

```bat
双击 D:\llama.cpp\start-llama-server.bat
```

脚本自动：检测 GPU（有则 `-ngl 99` 全量 offload）、检测端口 8080 是否已运行、输出重定向到日志。

### 2. 验证服务

```bash
curl http://localhost:8080/health
# 返回 {"status":"ok"}

curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"Qwen3.5-2B-Q8_0","messages":[{"role":"user","content":"你好"}]}'
```

### 3. 手动启动（常用参数）

```bash
# GPU 全量加速 + 关闭思考模式（适合摘要/分类）
D:\llama.cpp\llama-server.exe -m D:\llama.cpp\models\Qwen3.5-2B-Q8_0.gguf \
  -ngl 99 --port 8080 --host 127.0.0.1 --reasoning off

# 开启思考模式（保留推理过程）
D:\llama.cpp\llama-server.exe -m ... --reasoning on

# CPU 模式（无 NVIDIA GPU 时）
D:\llama.cpp\llama-server.exe -m ... --port 8080
```

### 4. 停止服务

```bash
taskkill /F /IM llama-server.exe
# 或关闭运行脚本的窗口
```

## 三、与 Ollama 的 API 差异

| 功能 | Ollama | llama.cpp |
|------|--------|-----------|
| Chat 接口 | `POST /api/chat` | `POST /v1/chat/completions`（OpenAI 标准） |
| 模型列表 | `GET /api/tags` | `GET /v1/models` |
| 健康检查 | 无 | `GET /health` |
| 思考模式 | 请求体 `"think": true` | 启动参数 `--reasoning on/off` 或 `--reasoning-budget N` |
| 流式 | `"stream": true` | 同（SSE 格式一致） |

**兼容性**：llama.cpp 提供 OpenAI 标准接口，绝大多数为 Ollama 写的 OpenAI 风格客户端可直接改 baseUrl 使用。用 Ollama 私有接口（`/api/chat`）的代码需要改用 OpenAI 格式。

## 四、AgentMemory 项目配置（已完成）

| 文件 | 修改内容 |
|------|---------|
| `start.bat` | Ollama 启动区块 → llama.cpp 检测与启动（端口 8080） |
| `stop.bat` | Ollama 停止 → llama-server 停止 |
| `application.conf` | `baseUrl: http://localhost:8080/v1`（OpenAI provider） |
| `.env` | `LLM_BASE=http://localhost:8080/v1`、`LLM_MODEL=Qwen3.5-2B-Q8_0` |
| `embedding_service/config.json` | provider: openai, base: localhost:8080/v1 |
| `HybridClassifierTest.java` | setProvider("openai", "http://localhost:8080/v1") |

**端口约定**：llama.cpp 用 **8080**（项目后端 API 用 8082，避免冲突）。

## 五、获取新模型（替换/增加）

llama.cpp 使用 GGUF 格式模型。推荐从 HuggingFace 下载（国内用 hf-mirror 加速）：

```bash
# 例：下载 Qwen3-4B Q4_K_M（约 2.5GB）
curl -L -o Qwen3-4B-Q4_K_M.gguf \
  "https://hf-mirror.com/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"

# 放入模型目录后用 llama-server 指定即可
```

推荐来源：
- **unsloth**（`unsloth/<模型>-GGUF`）：量化规范，兼容性最好
- **Qwen 官方**（`Qwen/<模型>-GGUF`）：部分模型有官方 GGUF

**注意**：不要使用 Ollama 下载目录里的模型文件（`D:\models\blobs\*`）——那是 Ollama 私有导出的 GGUF，权重布局与 llama.cpp 不兼容（推理会输出乱码）。Ollama 的 `qwen3.5:2b` 与 llama.cpp 的 `Qwen3.5-2B-Q8_0.gguf` 是同一模型的不同导出格式。

## 六、已知影响（其他项目）

| 项目 | 影响 | 处理 |
|------|------|------|
| **AI-VedioToText** | `llm_backend.py` 会执行 `ollama serve` 自动启动，Ollama 删除后其 **ollama provider 会报错**；**deepseek provider 不受影响**（默认主用） | 如需保留本地 LLM 选项：把 `OLLAMA_BASE_URL` 改为 `http://localhost:8080/v1`、`OLLAMA_MODEL` 改为 `Qwen3.5-2B-Q8_0`，并修改 `llm_backend.py` 的 `_ensure_running()`（把 `ollama serve` 换成 llama-server 启动命令） |

## 七、常用工具

```bash
# 命令行直接对话
D:\llama.cpp\llama-cli.exe -m D:\llama.cpp\models\Qwen3.5-2B-Q8_0.gguf -ngl 99

# 性能基准
D:\llama.cpp\llama-bench.exe -m D:\llama.cpp\models\Qwen3.5-2B-Q8_0.gguf -ngl 99 -p 32 -n 128

# Web 界面（服务运行时浏览器打开）
http://localhost:8080
```

## 八、故障排查

| 问题 | 解决 |
|------|------|
| 双击 bat 闪退 | 确认是英文编码脚本（中文注释需 GBK 编码）；看 `llama-server.log` |
| CUDA 不生效（速度 ~12t/s） | 确认 `cudart64_13.dll` 等 CUDA DLL 在 `D:\llama.cpp` 目录（release 包的 CUDA 运行库是独立压缩包） |
| 端口冲突 | 项目后端 8082 / llama.cpp 8080，如有占用改 `--port` |
| 模型加载慢 | Q8_0 全量进显存约 2GB，首次加载数秒属正常 |
| 输出乱码 | 用了 Ollama 私有导出的 GGUF，改用 unsloth/Qwen 官方量化 |
