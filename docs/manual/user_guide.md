# AgentMemory 用户手册

---

## 目录

1. 介绍
2. 系统要求
3. 安装步骤
4. 启动服务
5. 使用指南
6. API 接口
7. 常见问题
8. 附录

---

## 1. 介绍

### 1.1 什么是 AgentMemory

AgentMemory 是一个**本地 Agent 语义化记忆引擎**，能够自动监控、捕获、持久化和检索所有 CLI Agent 的对话历史。

### 1.2 核心功能

| 功能 | 说明 |
|------|------|
| 📂 自动监控 | 实时监控多个 Agent 的会话日志目录 |
| 🗄️ 持久存储 | 将消息实时存入数据库，断电不丢失 |
| 🔍 语义搜索 | 基于向量的智能检索，找相关内容 |
| 🏷️ 自动分类 | 自动分类为五大记忆库 |
| 🧹 自动清理 | 14天后自动清理过期数据 |

### 1.3 支持的 Agent

| Agent | 监控路径 |
|-------|---------|
| Claude Code | `~/.claude/` |
| iFlow CLI | `~/.iflow/projects/` |
| Qwen/Qoder | `~/.qwen/projects/` |
| OpenClaw | `~/.openclaw/` |
| Nanobot | `~/.nanobot/` |
| Crush CLI | `~/.crush/crush.db` |

---

## 2. 系统要求

### 2.1 硬件要求

| 项目 | 最低配置 | 推荐配置 |
|------|----------|----------|
| CPU | 双核 | 四核以上 |
| 内存 | 4GB | 8GB以上 |
| 存储 | 10GB | 50GB以上 |

### 2.2 软件依赖

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | 后端服务运行环境 |
| Python | 3.8+ | Embedding 服务 |
| Node.js | 18+ | 前端构建 |
| Docker | 最新版 | PostgreSQL 数据库 |

---

## 3. 安装步骤

### 3.1 克隆项目

```bash
git clone https://github.com/zwj-3193655211/AgentMemory.git
cd AgentMemory
```

### 3.2 配置环境变量

```bash
# 复制环境变量模板
copy .env.example .env

# 编辑 .env 文件，设置数据库密码
# 推荐设置: POSTGRES_PASSWORD=agentmemory
```

### 3.3 安装依赖

```bash
# 安装 Embedding 服务依赖
cd embedding_service
pip install -r requirements.txt
cd ..

# 安装前端依赖
cd frontend
npm install
cd ..
```

---

## 4. 启动服务

### 4.1 一键启动（推荐）

```bash
# Windows
start.bat

# Linux/Mac
./start.sh
```

### 4.2 分步启动

```bash
# 1. 启动数据库
docker-compose up -d

# 2. 启动 Embedding 服务
cd embedding_service
python embed_server.py

# 3. 启动后端服务
cd backend
mvn clean package -DskipTests
java -jar target/agent-memory-1.0.0-SNAPSHOT.jar

# 4. 启动前端服务
cd frontend
npm run dev
```

### 4.3 验证启动

```bash
# 检查数据库
docker ps | findstr agentmemory-db

# 检查 API 服务
curl http://localhost:8080/api/health

# 检查 Embedding 服务
curl http://localhost:8100/health
```

---

## 5. 使用指南

### 5.1 访问界面

| 服务 | URL |
|------|-----|
| 前端界面 | http://localhost:5173 |
| API 文档 | http://localhost:8080 |

### 5.2 五大记忆库

#### 5.2.1 错误纠正库

存储编程过程中遇到的错误及其解决方案。

- **问题**：遇到的错误现象
- **原因**：错误产生的原因分析
- **解决方案**：如何解决这个问题
- **示例**：相关代码示例

#### 5.2.2 用户画像库

存储用户的偏好设置和习惯。

- **偏好类别**：用户喜欢的技术栈、工具等
- **配置习惯**：常用的配置选项

#### 5.2.3 最佳实践库

存储经过验证的优秀解决方案。

- **适用场景**：什么情况下使用
- **实践内容**：具体怎么做
- **理论依据**：为什么这样做更好

#### 5.2.4 项目上下文库

存储项目的技术栈和关键决策。

- **项目路径**：项目所在位置
- **技术栈**：使用的技术列表
- **架构决策**：重要的设计决策

#### 5.2.5 技能沉淀库

存储编程技能和方法。

- **技能名称**：技能的名称
- **技能类型**：技能分类
- **步骤说明**：如何掌握该技能

### 5.3 语义搜索

在搜索框中输入查询词，系统会返回相似的记忆内容。

**搜索技巧**：
- 使用自然语言提问
- 关键词要明确
- 支持中文和英文

### 5.4 数据管理

#### 5.4.1 查看统计信息

访问 `/api/stats` 可以查看系统统计：

```bash
curl http://localhost:8080/api/stats
```

#### 5.4.2 导出数据

支持将数据导出为 JSON 或 CSV 格式：

```bash
# 导出错误纠正记录
curl http://localhost:8080/api/errors/export?format=csv

# 导出会话
curl http://localhost:8080/api/sessions/export
```

---

## 6. API 接口

### 6.1 基础接口

| API | 方法 | 说明 |
|-----|------|------|
| `/api/health` | GET | 健康检查 |
| `/api/stats` | GET | 统计信息 |
| `/api/search` | POST | 语义搜索 |

### 6.2 会话接口

| API | 方法 | 说明 |
|-----|------|------|
| `/api/sessions` | GET | 获取会话列表 |
| `/api/sessions/{id}` | GET | 获取会话详情 |
| `/api/messages/{sessionId}` | GET | 获取消息列表 |

### 6.3 记忆库接口

| API | 方法 | 说明 |
|-----|------|------|
| `/api/errors` | CRUD | 错误纠正 |
| `/api/profiles` | CRUD | 用户画像 |
| `/api/practices` | CRUD | 最佳实践 |
| `/api/contexts` | CRUD | 项目上下文 |
| `/api/skills` | CRUD | 技能沉淀 |

### 6.4 使用示例

#### 6.4.1 语义搜索

```bash
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -d '{"query": "如何优化数据库查询？", "top_k": 5}'
```

#### 6.4.2 获取会话列表

```bash
curl http://localhost:8080/api/sessions?limit=10&agent=claude
```

#### 6.4.3 获取统计信息

```bash
curl http://localhost:8080/api/stats
```

---

## 7. 常见问题

### 7.1 数据库连接失败

**问题**：启动后端时提示数据库连接失败

**解决方案**：
```bash
# 检查 Docker 是否运行
docker ps

# 如果数据库容器未运行，启动它
docker-compose up -d

# 检查数据库端口
netstat -ano | findstr :5500
```

### 7.2 搜索功能不生效

**问题**：搜索没有返回结果

**解决方案**：
```bash
# 检查 Embedding 服务是否运行
curl http://localhost:8100/health

# 如果未运行，启动服务
cd embedding_service
python embed_server.py
```

### 7.3 前端无法访问

**问题**：访问 http://localhost:5173 失败

**解决方案**：
```bash
# 检查端口是否被占用
netstat -ano | findstr :5173

# 重新启动前端
cd frontend
npm run dev
```

### 7.4 消息不显示

**问题**：Agent 已经有对话，但系统没有显示

**解决方案**：
1. 确认 Agent 的日志路径正确配置
2. 检查日志文件格式是否为 JSONL
3. 查看后端日志确认是否有解析错误

### 7.5 内存占用过高

**问题**：服务运行一段时间后内存占用过高

**解决方案**：
- 调整 JVM 堆内存参数
- 检查是否有内存泄漏
- 定期重启服务

---

## 8. 附录

### 8.1 配置文件说明

配置文件位置：`backend/src/main/resources/application.conf`

主要配置项：

```hocon
api.port=8080

database {
    type = "postgresql"
    url = "jdbc:postgresql://localhost:5500/agentmemory"
    user = "agentmemory"
    password = "${DATABASE_PASSWORD}"
    poolSize = 10
}

embedding {
    baseUrl = "http://localhost:8100"
    dimension = 512
}

memory {
    retention {
        days = 14
    }
}
```

### 8.2 日志说明

日志目录：`~/.agentmemory/logs/`

| 日志文件 | 说明 |
|----------|------|
| `agentmemory.log` | 应用主日志 |
| `agentmemory-error.log` | 错误日志 |

### 8.3 停止服务

```bash
# Windows
taskkill /F /FI "WINDOWTITLE eq AgentMemory-*"

# Linux/Mac
./stop.sh
```

---

**文档版本**: v1.0  
**创建日期**: 2026-05-21  
**作者**: [你的姓名]  
**班级**: [你的班级]