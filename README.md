# AgentMemory - 本地 Agent 语义化记忆引擎

> 自动捕获、持久化、语义化检索所有 CLI Agent 对话

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16+-blue.svg)](https://www.postgresql.org/)
[![pgvector](https://img.shields.io/badge/pgvector-0.5.0+-green.svg)](https://github.com/pgvector/pgvector)

## 🎯 一句话介绍

自动监控你的 AI 编程助手（Claude Code、iFlow、Qwen 等）的对话，提取有价值的信息，支持语义搜索。

## 📋 前置依赖

| 依赖 | 版本要求 | 用途 |
|------|---------|------|
| Java (JDK) | 17+ | 后端服务 |
| Python | 3.8+ | Embedding 服务 |
| Node.js | 18+ | 前端服务 |
| Docker | 最新版 | PostgreSQL 数据库 |

## 🚀 5 分钟快速开始

### 1️⃣ 克隆项目

```bash
git clone https://github.com/zwj-3193655211/AgentMemory.git
cd AgentMemory
```

### 2️⃣ 配置环境

```bash
# 复制环境变量模板
cp .env.example .env

# 编辑 .env，设置你的 JAVA_HOME 路径
# 例如：JAVA_HOME=C:\Program Files\Java\jdk-21
```

### 3️⃣ 安装依赖

```bash
# Embedding 服务依赖
cd embedding_service
pip install -r requirements.txt
cd ..

# 前端依赖
cd frontend
npm install
cd ..
```

### 4️⃣ 启动服务

```bash
# Windows
start.bat

# Linux/Mac
./start.sh
```

### 5️⃣ 访问界面

- **Web 界面**: http://localhost:5173
- **API 服务**: http://localhost:8080
- **Embedding**: http://localhost:8100

## ✨ 主要功能

| 功能 | 说明 |
|------|------|
| 📂 自动监控 | 后台监控 CLI Agent 的会话日志 |
| 🗄️ 实时存储 | 每条消息即时入库，断电不丢数据 |
| 🔍 语义搜索 | 基于向量的智能搜索，找相关内容 |
| 🏷️ 自动分类 | 自动分类为：错误纠正、最佳实践、技能等 |
| 🧹 自动清理 | 14 天后自动清理过期数据 |

## 📊 支持的 Agent

| Agent | 监控路径 | 状态 |
|-------|---------|------|
| Claude Code | `~/.claude/` | ✅ 已支持 |
| iFlow CLI | `~/.iflow/projects/` | ✅ 已支持 |
| Qwen/Qoder | `~/.qwen/projects/` | ✅ 已支持 |
| OpenClaw | `~/.openclaw/` | ✅ 已支持 |

## 📖 使用示例

### 查看统计信息

```bash
curl http://localhost:8080/api/stats
```

### 语义搜索

```bash
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -d '{"query": "如何优化数据库查询？"}'
```

### 查看日志

```bash
# 应用日志
tail -f ~/.agentmemory/logs/agentmemory.log

# Docker 日志
docker logs -f agentmemory-db
```

## ⚙️ 配置说明

主要配置文件：`backend/src/main/resources/application.conf`

```hocon
database {
    type = "postgresql"
    url = "jdbc:postgresql://localhost:5432/agent_memory"
    user = "agentmemory"
    password = "${DATABASE_PASSWORD}"  # 从环境变量读取
    poolSize = 10
}

embedding {
    baseUrl = "http://localhost:8100"
}

memory {
    retention {
        days = 14  # 数据保留天数
    }
}

api {
    port = 8080
}
```

## 🔧 常用操作

### 启动服务

```bash
# Windows
start.bat

# Linux/Mac
./start.sh
```

### 停止服务

```bash
# Windows
taskkill /F /FI "WINDOWTITLE eq AgentMemory-*"

# Linux/Mac
./stop.sh
```

### 查看数据库

```bash
# 进入数据库
psql -U postgres -d agent_memory

# 查看表
\dt

# 查询消息
SELECT * FROM messages ORDER BY created_at DESC LIMIT 10;
```

## 📚 更多文档

- [更新日志](CHANGELOG.md) - 版本更新和优化记录
- [开发文档](docs/) - 代码审查、项目规划等

## 🐛 常见问题

### Q: 数据库连接失败？

**A**: 检查 Docker 容器是否运行：
```bash
docker ps | grep agentmemory-db
```

### Q: 找不到 Agent？

**A**: 确认 Agent 的日志路径是否正确，检查配置文件中的 `agents` 部分。

### Q: 搜索结果不准确？

**A**: 需要启动 Embedding 服务：
```bash
cd embedding_service
pip install -r requirements.txt
python embed_server.py
```

### Q: 如何备份数据？

**A**:
```bash
# 备份数据库
pg_dump -U postgres agentmemory > backup.sql

# 备份整个数据目录
docker exec agentmemory-db pg_dumpall -U agentmemory > backup.sql
```

## 📄 许可证

MIT License - 详见 [LICENSE](LICENSE) 文件

---

**版本**: 2.1.0
**最后更新**: 2026-03-23
**问题反馈**: [GitHub Issues](https://github.com/yourname/AgentMemory/issues)

---

## 🔄 项目状态

**当前版本**: v2.1.0 (后端重构完成)

**重构进度**: 35% 完成
- ✅ 后端重构: 100% (减少 426 行代码，-46%)
- 🔄 后端优化: 40% (P0/P1 问题修复中)
- ⏸️ 前端重构: 0% (预计减少 1900 行重复代码)

**详细报告**: [项目重构总体状态](docs/plans/2026-03-23-project-refactor-status.md)

**代码质量**: ⭐⭐⭐⭐ (4/5)
