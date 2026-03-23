# AgentMemory - 本地 Agent 语义化记忆引擎

> 自动捕获、持久化、语义化检索所有 CLI Agent 对话

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16+-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

## 🎯 核心功能

- ✅ **自动捕获**: 后台监控，无需用户操作
- ✅ **多 Agent 支持**: iFlow CLI、Claude Code、OpenClaw、Qwen/Qoder
- ✅ **实时存储**: 每条消息即时入库，断电不丢数据
- ✅ **语义检索**: 基于 pgvector 的向量搜索
- ✅ **高并发**: 会话级锁，支持 100+ 并发会话
- ✅ **智能提取**: 自动分类记忆（错误纠正、用户偏好、最佳实践等）

## 📊 性能指标

- **消息处理**: 1000+ 条/秒
- **并发会话**: 100+ 同时处理
- **向量查询**: <100ms（10k 记忆）
- **内存占用**: <200MB（100 个活跃会话）

## 🚀 快速开始

### 1. 安装依赖

```bash
# 安装 PostgreSQL 16 + pgvector
# Windows: https://www.postgresql.org/download/windows/
# 或使用 Docker:
docker run -d --name agentmemory-db \
  -e POSTGRES_DB=agent_memory \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# 初始化数据库
psql -U postgres -d agent_memory -f database/init.sql
```

### 2. 构建项目

```bash
cd backend
mvn clean package
```

### 3. 配置

编辑 `backend/src/main/resources/application.conf`:

```hocon
database {
    type = "postgresql"
    url = "jdbc:postgresql://localhost:5432/agent_memory"
    user = "postgres"
    password = "${DATABASE_PASSWORD}"  # 从环境变量读取
}

embedding {
    baseUrl = "http://localhost:8100"
}
```

### 4. 启动 Embedding 服务

```bash
# Python embedding 服务
cd embedding-service
pip install -r requirements.txt
python app.py
```

### 5. 运行

```bash
# Windows
start.bat

# Linux/Mac
./start.sh

# 或者手动运行
cd backend
java -jar target/agentmemory-*.jar
```

### 6. 访问

- **Web 界面**: http://localhost:8080
- **API 文档**: http://localhost:8080/api/stats

## 📁 目录结构

```
AgentMemory/
├── backend/                    # Java 后端
│   ├── src/main/
│   │   ├── java/com/agentmemory/
│   │   │   ├── AgentMemoryApplication.java    # 主入口
│   │   │   ├── api/                           # API 服务
│   │   │   ├── config/                        # 配置管理
│   │   │   ├── model/                         # 数据模型
│   │   │   ├── service/                       # 业务逻辑
│   │   │   └── launcher/                      # 启动器
│   │   └── resources/
│   │       ├── application.conf               # 应用配置
│   │       └── logback.xml                    # 日志配置
│   └── pom.xml
├── frontend/                   # Vue 前端（可选）
├── database/                   # 数据库脚本
│   └── init.sql
├── embedding-service/          # Python Embedding 服务
├── CODE_REVIEW.md             # 代码审查报告
├── PROJECT_PLAN.md            # 项目规划
└── README.md
```

## 🔧 支持的 Agent

| Agent | 监控路径 | 状态 | 特性 |
|-------|---------|------|------|
| iFlow CLI | `~/.iflow/projects/` | ✅ 已支持 | 工具调用提取 |
| Claude Code | `~/.claude/` | ✅ 已支持 | 思考过程过滤 |
| OpenClaw | `~/.openclaw/` | ✅ 已支持 | CWD 提取 |
| Qwen/Qoder | `~/.qwen/projects/` | ✅ 已支持 | 内部思考过滤 |

## 🏗️ 技术栈

### 后端
- **Java 17** - 编程语言
- **Maven** - 构建工具
- **PostgreSQL 16** - 数据库
- **pgvector** - 向量扩展
- **HikariCP** - 连接池
- **Jackson** - JSON 处理
- **SLF4J + Logback** - 日志

### 前端
- **Vue 3** - 框架
- **Element Plus** - UI 组件
- **Axios** - HTTP 客户端
- **ECharts** - 数据可视化

## 🎨 记忆类型

系统自动将对话内容分类为：

1. **ERROR_CORRECTION** - 错误纠正
   - 问题描述
   - 原因分析
   - 解决方案

2. **USER_PROFILE** - 用户偏好
   - 编码风格
   - 工具偏好
   - 习惯设置

3. **BEST_PRACTICE** - 最佳实践
   - 设计模式
   - 代码规范
   - 性能优化

4. **PROJECT_CONTEXT** - 项目上下文
   - 技术栈
   - 架构设计
   - 关键决策

5. **SKILL** - 技能沉淀
   - 操作步骤
   - 前置条件
   - 注意事项

## 📖 API 示例

### 获取统计信息

```bash
curl http://localhost:8080/api/stats
```

响应:
```json
{
  "agents": 4,
  "sessions": 128,
  "messages": 15234,
  "errors": 156,
  "profiles": 89,
  "practices": 234,
  "contexts": 45,
  "skills": 67
}
```

### 搜索记忆

```bash
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -d '{"query": "如何优化数据库查询？", "limit": 20}'
```

## ⚙️ 配置说明

### 数据库配置

```hocon
database {
    type = "postgresql"  # 或 "sqlite"
    url = "jdbc:postgresql://localhost:5432/agent_memory"
    user = "postgres"
    password = "${DATABASE_PASSWORD}"  # 环境变量
    poolSize = 10  # 连接池大小
}
```

### 监控配置

```hocon
watcher {
    pollInterval = 1000  # 文件检测间隔（毫秒）
    batchSize = 100      # 批量处理大小
}
```

### 内存配置

```hocon
memory {
    retention {
        days = 14  # 数据保留天数
    }
}
```

### API 配置

```hocon
api {
    port = 8080  # API 服务端口
}
```

## 🔍 故障排查

### 数据库连接失败

```bash
# 检查 PostgreSQL 是否运行
psql -U postgres -d agent_memory

# 检查 pgvector 是否安装
psql -U postgres -d agent_memory -c "SELECT extversion FROM pg_extension WHERE extname = 'vector';"
```

### Embedding 服务不可用

```bash
# 检查服务状态
curl http://localhost:8100/health

# 查看日志
tail -f embedding-service/app.log
```

### 文件监控不工作

```bash
# 检查日志目录是否存在
ls -la ~/.iflow/projects/
ls -la ~/.claude/

# 查看监控日志
tail -f ~/.agentmemory/logs/agentmemory.log | grep FileWatcher
```

## 📈 性能优化

### 已实施的优化

1. **并发处理**: 会话级锁，支持 100+ 并发
2. **线程池优化**: 核心线程数 8，最大 20
3. **文件级锁**: 防止并发处理冲突
4. **连接池管理**: HikariCP 高性能连接池

### 建议的优化

1. **添加向量索引**（1h）
   ```sql
   CREATE INDEX idx_error_corrections_embedding_hnsw
   ON error_corrections USING hnsw (embedding vector_cosine_ops);
   ```

2. **修复 N+1 查询**（2-3h）
   - 使用 UPSERT 合并操作
   - 预期性能提升 3 倍

3. **HttpClient 优化**（1h）
   - 显式配置连接池
   - 使用 HTTP/2

## 🧪 测试

```bash
# 运行单元测试
cd backend
mvn test

# 运行集成测试
mvn verify

# 生成测试报告
mvn jacoco:report
```

## 📚 相关文档

- [代码审查报告](CODE_REVIEW.md) - 详细的代码审查结果
- [项目规划](PROJECT_PLAN.md) - 开发路线图
- [Bug 报告](BUG_REPORT.md) - 已知问题和限制

## 🤝 贡献指南

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

MIT License - 详见 [LICENSE](LICENSE) 文件

## 🙏 致谢

- [Claude Code](https://claude.ai/code) - AI 编程助手
- [PostgreSQL](https://www.postgresql.org/) - 开源数据库
- [pgvector](https://github.com/pgvector/pgvector) - 向量相似度搜索

---

**版本**: 2.0.0
**最后更新**: 2026-03-23
**维护者**: AgentMemory Team
