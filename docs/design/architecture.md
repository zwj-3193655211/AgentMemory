# AgentMemory 系统设计文档

---

## 1. 架构设计

### 1.1 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        AgentMemory                             │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐    HTTP     ┌──────────────┐    JDBC      │
│  │   Frontend   │◄───────────►│    Backend   │◄───────────►│
│  │   (Vue 3)    │             │   (Java 17)  │             │
│  └──────────────┘             └───────┬──────┘             │
│                                        │                    │
│  ┌──────────────┐    HTTP     ┌───────▼──────┐             │
│  │ Embedding    │───────────►│   Services    │             │
│  │  Service     │             │   Layer      │             │
│  │  (Python)    │             └───────┬──────┘             │
│  └──────────────┘                     │                      │
│                                       ▼                      │
│  ┌───────────────────────────────────────────────────────────┐│
│  │              PostgreSQL + pgvector                        ││
│  │  ┌─────────┬─────────┬─────────┬─────────┬─────────────┐││
│  │  │sessions │messages │  error  │ practice│   skills    │││
│  │  │         │         │corrections│        │             │││
│  │  │         │         │           │         │profiles    │││
│  │  │         │         │           │         │contexts    │││
│  │  └─────────┴─────────┴─────────┴─────────┴─────────────┘││
│  └───────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 模块划分

| 模块 | 职责 | 技术栈 |
|------|------|--------|
| **Frontend** | 用户界面展示 | Vue 3 + Element Plus |
| **ApiServer** | HTTP API 服务 | Java 内置 HttpServer |
| **FileWatcherService** | 文件监控服务 | Java WatchService |
| **MemoryService** | 记忆管理服务 | Java |
| **MemoryClassifier** | 记忆分类器 | Java |
| **MemoryExtractor** | 记忆提取器 | Java |
| **DatabaseService** | 数据库服务 | HikariCP |
| **EmbeddingClient** | 向量嵌入客户端 | HTTP Client |
| **CleanupService** | 数据清理服务 | Scheduled Task |
| **Embedding Service** | 向量嵌入服务 | Python Flask |

### 1.3 核心服务关系图

```
                    AgentMemoryApplication
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
   ApiServer         FileWatcherService    CleanupService
         │                   │                   │
         │                   ▼                   │
         │            MemoryService              │
         │                   │                   │
         │         ┌─────────┼─────────┐         │
         │         ▼         ▼         ▼         │
         │  MemoryClassifier MemoryExtractor EmbeddingClient
         │                   │                   │
         ▼                   ▼                   ▼
   ┌─────────────────────────────────────────────────────────┐
   │                    DatabaseService                      │
   │                         │                              │
   ▼                         ▼                              │
┌───────────────────────────────────────────────────────────┐│
│                  PostgreSQL Database                      ││
└───────────────────────────────────────────────────────────┘│
```

---

## 2. 目录结构

```
backend/
├── src/main/java/com/agentmemory/
│   ├── api/                    # API 层
│   │   ├── ApiServer.java      # HTTP 服务器
│   │   ├── ChatHandler.java    # 聊天接口
│   │   └── SetupHandler.java   # 设置接口
│   ├── service/                # 服务层
│   │   ├── DatabaseService.java      # 数据库服务
│   │   ├── FileWatcherService.java   # 文件监控服务
│   │   ├── MemoryService.java        # 记忆管理服务
│   │   ├── MemoryClassifier.java    # 记忆分类器
│   │   ├── MemoryExtractor.java      # 记忆提取器
│   │   ├── EmbeddingClient.java      # 嵌入客户端
│   │   ├── CleanupService.java       # 清理服务
│   │   ├── SessionCompressionService.java
│   │   ├── AgentDetectorService.java
│   │   ├── CrushDatabaseWatcher.java
│   │   ├── WorkBuddyWatcher.java
│   │   └── ScheduledServiceBase.java
│   ├── model/                  # 数据模型
│   │   └── Message.java        # 消息模型
│   ├── config/                 # 配置
│   │   └── ApplicationConfig.java
│   ├── launcher/               # 启动器
│   │   ├── AgentLauncher.java
│   │   └── CommandLineLauncher.java
│   ├── util/                   # 工具类
│   │   ├── CleanupGarbage.java
│   │   └── CleanupPrefix.java
│   ├── AgentInfo.java          # Agent 信息
│   └── AgentMemoryApplication.java  # 主入口
├── src/main/resources/
│   ├── application.conf        # 配置文件
│   └── logback.xml             # 日志配置
└── pom.xml                     # Maven 配置
```

---

## 3. 关键类设计

### 3.1 AgentMemoryApplication（主入口）

| 方法 | 功能 | 参数 | 返回值 |
|------|------|------|--------|
| `start()` | 启动应用 | 无 | void |
| `stop()` | 停止应用 | 无 | void |
| `expandHomePath(String)` | 展开路径中的 ~ | path: String | Path |

### 3.2 ApiServer（API 服务）

| 方法 | 功能 | 参数 | 返回值 |
|------|------|------|--------|
| `start()` | 启动 HTTP 服务 | 无 | void |
| `stop()` | 停止 HTTP 服务 | 无 | void |
| `sendJson(HttpExchange, Object)` | 发送 JSON 响应 | exchange, data | void |
| `sendError(HttpExchange, int, String)` | 发送错误响应 | exchange, code, message | void |

### 3.3 DatabaseService（数据库服务）

| 方法 | 功能 | 参数 | 返回值 |
|------|------|------|--------|
| `init()` | 初始化数据库连接 | 无 | void |
| `saveMessage(Message)` | 保存消息 | message | void |
| `saveSessionIfNotExists(...)` | 保存会话（不存在时） | sessionId, agentType, projectPath, title | void |
| `getConnection()` | 获取数据库连接 | 无 | Connection |
| `close()` | 关闭连接池 | 无 | void |

### 3.4 MemoryService（记忆服务）

| 方法 | 功能 | 参数 | 返回值 |
|------|------|------|--------|
| `processMessage(...)` | 处理消息提取记忆 | sessionId, content, agentType | void |
| `processMessageWithContext(...)` | 带上下文处理消息 | sessionId, fullContext, agentType | void |
| `saveMemory(...)` | 保存记忆到数据库 | memory, type, sessionId, agentType, embedding | void |
| `searchSimilar(...)` | 搜索相似记忆 | query, type, limit | List\<String\> |
| `isDuplicate(String, MemoryType)` | 检查是否重复 | title, type | boolean |

### 3.5 MemoryClassifier（记忆分类器）

| 方法 | 功能 | 参数 | 返回值 |
|------|------|------|--------|
| `classify(String)` | 分类消息内容 | content | MemoryType |
| `isWorthRemembering(String, MemoryType)` | 判断是否值得保存 | content, type | boolean |
| `extractTags(String)` | 提取标签 | content | List\<String\> |

### 3.6 FileWatcherService（文件监控服务）

| 方法 | 功能 | 参数 | 返回值 |
|------|------|------|--------|
| `watchDirectory(...)` | 监控目录 | agentType, parserType, directory | void |
| `rescanDirectory(...)` | 重新扫描目录 | directory, agentType, parserType | void |
| `stop()` | 停止监控 | 无 | void |
| `bufferMessage(Message, String)` | 缓冲消息批量处理 | message, agentType | void |

---

## 4. 数据库设计

### 4.1 核心表结构

#### 4.1.1 sessions 表（会话表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(100) | PRIMARY KEY | 会话ID |
| agent_type | VARCHAR(50) | | Agent 类型 |
| project_path | TEXT | | 项目路径 |
| message_count | INTEGER | DEFAULT 0 | 消息数量 |
| expires_at | TIMESTAMP | | 过期时间 |
| deleted | BOOLEAN | DEFAULT false | 软删除标记 |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 更新时间 |

#### 4.1.2 messages 表（消息表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(100) | PRIMARY KEY | 消息ID |
| session_id | VARCHAR(100) | REFERENCES sessions(id) | 会话ID |
| role | VARCHAR(20) | NOT NULL | 角色（user/assistant） |
| content | TEXT | | 消息内容 |
| raw_json | JSONB | | 原始JSON数据 |
| embedding | vector(512) | | 向量嵌入 |
| deleted | BOOLEAN | DEFAULT false | 软删除标记 |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |

#### 4.1.3 五大记忆库表

**error_corrections（错误纠正库）**

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(100) | PRIMARY KEY | ID |
| title | VARCHAR(500) | NOT NULL | 标题 |
| problem | TEXT | NOT NULL | 问题描述 |
| cause | TEXT | | 原因分析 |
| solution | TEXT | NOT NULL | 解决方案 |
| example | TEXT | | 示例 |
| tags | TEXT[] | | 标签 |
| embedding | vector(512) | | 向量嵌入 |
| deleted | BOOLEAN | DEFAULT false | 软删除标记 |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |

**best_practices（最佳实践库）**

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(100) | PRIMARY KEY | ID |
| title | VARCHAR(500) | NOT NULL | 标题 |
| scenario | TEXT | NOT NULL | 适用场景 |
| practice | TEXT | NOT NULL | 实践内容 |
| tags | TEXT[] | | 标签 |
| embedding | vector(512) | | 向量嵌入 |
| deleted | BOOLEAN | DEFAULT false | 软删除标记 |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |

**skills（技能沉淀库）**

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(100) | PRIMARY KEY | ID |
| title | VARCHAR(500) | NOT NULL | 标题 |
| skill_type | VARCHAR(100) | | 技能类型 |
| description | TEXT | | 描述 |
| tags | TEXT[] | | 标签 |
| embedding | vector(512) | | 向量嵌入 |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |

**user_profiles（用户画像库）**

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(100) | PRIMARY KEY | ID |
| title | VARCHAR(500) | NOT NULL | 标题 |
| category | VARCHAR(100) | | 分类 |
| items | JSONB | | 偏好项 |
| embedding | vector(512) | | 向量嵌入 |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |

**project_contexts（项目上下文库）**

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | VARCHAR(100) | PRIMARY KEY | ID |
| title | VARCHAR(500) | NOT NULL | 标题 |
| project_path | TEXT | NOT NULL | 项目路径 |
| tech_stack | TEXT[] | | 技术栈 |
| embedding | vector(512) | | 向量嵌入 |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |

### 4.2 ER 图

```
┌──────────────┐        ┌──────────────┐
│   sessions   │1      *│   messages   │
├──────────────┤        ├──────────────┤
│ id (PK)      │───────►│ session_id   │
│ agent_type   │        │ content      │
│ project_path │        │ embedding    │
│ message_cnt  │        └──────────────┘
└──────────────┘                 │
                                 │
          ┌──────────────────────┼──────────────────────┐
          ▼                      ▼                      ▼
   ┌───────────────┐    ┌───────────────┐    ┌───────────────┐
   │error_corrections│   │best_practices│   │    skills     │
   ├───────────────┤    ├───────────────┤    ├───────────────┤
   │ title         │    │ title         │    │ title         │
   │ problem       │    │ scenario      │    │ skill_type    │
   │ solution      │    │ practice      │    │ description   │
   │ embedding     │    │ embedding     │    │ embedding     │
   └───────────────┘    └───────────────┘    └───────────────┘
          │                      │                      │
          ▼                      ▼                      ▼
   ┌───────────────┐    ┌───────────────┐
   │ user_profiles │    │project_contexts│
   ├───────────────┤    ├───────────────┤
   │ title         │    │ title         │
   │ category      │    │ project_path  │
   │ items         │    │ tech_stack    │
   │ embedding     │    │ embedding     │
   └───────────────┘    └───────────────┘
```

### 4.3 索引设计

| 表名 | 索引名 | 字段 | 类型 |
|------|--------|------|------|
| sessions | idx_sessions_agent | agent_type | BTREE |
| sessions | idx_sessions_deleted | deleted | BTREE |
| messages | idx_messages_session | session_id | BTREE |
| messages | idx_messages_embedding | embedding | HNSW |
| error_corrections | idx_error_tags | tags | GIN |
| error_corrections | idx_error_embedding | embedding | HNSW |
| best_practices | idx_practice_tags | tags | GIN |
| best_practices | idx_practice_embedding | embedding | HNSW |
| skills | idx_skill_tags | tags | GIN |
| skills | idx_skill_embedding | embedding | HNSW |

---

## 5. API 接口设计

### 5.1 会话管理接口

| API | 方法 | 功能 |
|-----|------|------|
| `/api/sessions` | GET | 获取会话列表 |
| `/api/sessions/{id}` | GET | 获取会话详情 |
| `/api/sessions/{id}/export` | GET | 导出会话 |

**GET /api/sessions**

请求参数：
| 参数 | 类型 | 说明 |
|------|------|------|
| limit | int | 每页数量（默认200） |
| offset | int | 偏移量 |
| agent | string | Agent类型过滤 |

响应示例：
```json
[
  {
    "id": "session-xxx",
    "agentType": "claude",
    "projectPath": "my-project",
    "messageCount": 15,
    "createdAt": "2026-05-21T10:00:00Z"
  }
]
```

### 5.2 消息管理接口

| API | 方法 | 功能 |
|-----|------|------|
| `/api/messages/{sessionId}` | GET | 获取会话消息 |

### 5.3 搜索接口

**POST /api/search**

请求体：
```json
{
  "query": "如何优化数据库查询",
  "top_k": 10,
  "type": "all"
}
```

响应示例：
```json
{
  "results": [
    {
      "id": "xxx",
      "title": "数据库查询优化技巧",
      "type": "best_practice",
      "similarity": 0.92,
      "content": "..."
    }
  ]
}
```

### 5.4 记忆库 CRUD 接口

| API | 方法 | 功能 |
|-----|------|------|
| `/api/errors` | CRUD | 错误纠正 |
| `/api/profiles` | CRUD | 用户画像 |
| `/api/practices` | CRUD | 最佳实践 |
| `/api/contexts` | CRUD | 项目上下文 |
| `/api/skills` | CRUD | 技能沉淀 |

### 5.5 统计接口

**GET /api/stats**

响应示例：
```json
{
  "totalSessions": 150,
  "totalMessages": 3500,
  "totalErrors": 45,
  "totalPractices": 32,
  "totalSkills": 28,
  "activeAgents": 4
}
```

---

## 6. 关键业务流程

### 6.1 消息处理流程

```
文件变更 → FileWatcherService → 解析消息 → 缓冲批量处理 → 分类提取 → 语义去重 → 向量生成 → 存储到数据库
    │                                          │
    └──────────────────────────────────────────┘
                     ↓
              MemoryService
                     │
         ┌───────────┴───────────┐
         ▼                       ▼
    MemoryClassifier      EmbeddingClient
         │                       │
         └───────────┬───────────┘
                     ↓
              MemoryExtractor
                     │
                     ↓
              五大记忆库
```

### 6.2 语义搜索流程

```
用户查询 → ApiServer → EmbeddingClient(生成向量) → DatabaseService(向量检索) → 返回结果
```

### 6.3 定时任务流程

```
┌─────────────────────────────────────────────────────────────────┐
│                        定时任务调度                            │
├─────────────────────────────────────────────────────────────────┤
│  CleanupService: 每小时清理过期数据                             │
│  SessionCompressionService: 每2小时压缩长会话                   │
│  FileWatcherService: 每5秒刷新消息缓冲                         │
│  FilePositionPersistence: 每10分钟持久化文件读取位置            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 7. 部署架构

### 7.1 本地开发环境

```
┌─────────────────────────────────────────────┐
│  开发主机                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │  Frontend│  │  Backend │  │ Embedding│  │
│  │  :5173   │  │  :8080   │  │  :8100   │  │
│  └──────────┘  └────┬─────┘  └──────────┘  │
│                     │                      │
│                     ▼                      │
│              ┌──────────┐                   │
│              │ PostgreSQL│                  │
│              │  :5500   │                   │
│              └──────────┘                   │
└─────────────────────────────────────────────┘
```

### 7.2 Docker 部署

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    ports:
      - "5500:5432"
    volumes:
      - ./data/postgres:/var/lib/postgresql/data
  
  backend:
    build: ./backend
    ports:
      - "8080:8080"
    depends_on:
      - postgres
  
  frontend:
    build: ./frontend
    ports:
      - "5173:5173"
  
  embedding:
    build: ./embedding_service
    ports:
      - "8100:8100"
```

---

**文档版本**: v1.0  
**创建日期**: 2026-05-21  
**作者**: [你的姓名]  
**班级**: [你的班级]