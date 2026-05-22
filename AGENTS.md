# AGENTS.md - AgentMemory Developer Guide

> Documentation for AI agents working on the AgentMemory codebase

**Version**: 2.1.0
**Last Updated**: 2026-04-20

---

## 📋 Quick Reference

| Item | Value |
|------|-------|
| **Java Version** | 17+ |
| **Frontend** | Vue 3 + Element Plus + Vite |
| **Database** | PostgreSQL 16 + pgvector (port 5500) |
| **Embedding** | Python Flask + bge-small-zh-v1.5 |
| **API Port** | 8080 |
| **Web Port** | 5173 |
| **Embedding Port** | 8100 |

---

## 🚀 Essential Commands

### Starting All Services

```bash
# Windows - starts all services (DB, backend, frontend, embedding)
start.bat

# Linux/Mac
./start.sh

# Stop services
# Windows: taskkill /F /FI "WINDOWTITLE eq AgentMemory-*"
# Linux/Mac: ./stop.sh
```

### Individual Service Commands

```bash
# 1. Database (required first)
docker-compose up -d

# 2. Backend (Java)
cd backend
mvn clean package -DskipTests
java -jar target/agent-memory-1.0.0-SNAPSHOT.jar

# 3. Frontend (Vue)
cd frontend
npm install
npm run dev

# 4. Embedding Service (Python)
cd embedding_service
pip install -r requirements.txt
python embed_server.py
```

### Testing API

```bash
# Health check
curl http://localhost:8080/api/health

# Stats
curl http://localhost:8080/api/stats

# Search
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -d '{"query": "数据库优化", "top_k": 5}'
```

---

## 📁 Project Structure

```
AgentMemory/
├── backend/                    # Java 17 + Maven
│   ├── src/main/java/com/agentmemory/
│   │   ├── AgentMemoryApplication.java    # Main entry
│   │   ├── api/ApiServer.java             # HTTP API (Javalin-style)
│   │   ├── service/                       # Core services
│   │   │   ├── DatabaseService.java       # DB operations
│   │   │   ├── MemoryService.java         # Memory management
│   │   │   ├── MemoryClassifier.java      # Memory categorization
│   │   │   ├── MemoryExtractor.java       # Structure extraction
│   │   │   ├── EmbeddingClient.java       # Vector embeddings
│   │   │   ├── FileWatcherService.java    # File monitoring
│   │   │   ├── SessionProcessor.java      # Session handling
│   │   │   ├── SessionCompressionService.java
│   │   │   ├── CleanupService.java        # Auto-cleanup
│   │   │   └── ScheduledServiceBase.java  # Base for scheduled tasks
│   │   ├── launcher/                      # Entry points
│   │   ├── model/                         # Data models
│   │   └── config/ApplicationConfig.java  # Configuration
│   ├── pom.xml
│   └── start.bat
├── frontend/                   # Vue 3 + Element Plus + Vite
│   ├── src/
│   │   ├── App.vue             # Main app component
│   │   ├── services/api.ts     # API service layer
│   │   ├── composables/        # Vue composables
│   │   └── views/              # Page views
│   ├── package.json
│   └── vite.config.ts
├── embedding_service/          # Python Flask
│   ├── embed_server.py         # Embedding API
│   └── requirements.txt
├── database/
│   ├── init.sql                # Schema initialization
│   ├── add_triggers.sql        # DB triggers
│   └── migrate_*.sql           # Migrations
├── docker-compose.yml          # PostgreSQL + pgvector
└── start.bat / start.sh        # Start scripts
```

---

## 🔧 Code Patterns

### Java Service Pattern (Scheduled Services)

All scheduled services extend `ScheduledServiceBase` using template method pattern:

```java
public class CleanupService extends ScheduledServiceBase {
    public CleanupService(DatabaseService db) {
        super("cleanup", 60 * 60 * 1000); // 1 hour interval
    }
    
    @Override
    protected void execute() {
        // Cleanup logic here
    }
}
```

### Concurrent Processing

- **Session-level locking**: Use `ReentrantLock` per session (not synchronized)
- **Thread pool**: Bounded (core=8, max=20) - avoids thread explosion
- **File-level locks**: Prevent concurrent file processing

### Database Access

- **Connection pool**: HikariCP with configurable pool size
- **SQL injection prevention**: Table name whitelist in MemoryService
- **Triggers**: Auto-update message counts (avoids N+1 queries)

### Vector Search

- Uses pgvector with HNSW index for 10-1000x performance
- Embedding dimension: 512 (bge-small-zh-v1.5)

### Hybrid Classification System

AgentMemory uses a three-tier hybrid classification system:

```
┌─────────────────────────────────────────────────────────────┐
│                    Hybrid Classification                     │
├─────────────────────────────────────────────────────────────┤
│  1. Rule Classification (Fast)                              │
│     └─ Keyword matching, pattern recognition                │
│                                                              │
│  2. Vector Classification (Semantic)                        │
│     └─ Embedding similarity via pgvector                    │
│                                                              │
│  3. LLM Classification (Deep Understanding)                 │
│     └─ Context-aware classification via Ollama              │
│                                                              │
│  Result: Confidence-based aggregation                       │
└─────────────────────────────────────────────────────────────┘
```

**Key Classes:**
- `HybridMemoryClassifier`: Main orchestrator
- `RuleClassifier`: Keyword-based classification
- `VectorClassifier`: Semantic similarity classification
- `LLMClient`: LLM provider integration (Ollama/OpenAI)

**Configuration:**
```java
LLMClient llmClient = new LLMClient();
llmClient.setProvider("ollama", "http://localhost:11434", null, "qwen3.5:2b");

HybridMemoryClassifier classifier = new HybridMemoryClassifier(dbService, embeddingClient, llmClient);
```

---

## 🎯 Supported Agents

| Agent | Log Path | Format |
|-------|----------|--------|
| Claude Code | `~/.claude/` | JSON |
| iFlow CLI | `~/.iflow/projects/` | Markdown |
| Qwen/Qoder | `~/.qwen/projects/` | Markdown |
| OpenClaw | `~/.openclaw/` | Multi-line JSON |
| Codex CLI | `~/.codex/sessions/` | JSONL Event Stream |

---

## 🗄️ Database Schema

### Core Tables

```sql
-- Sessions
CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    agent_type TEXT,
    project_path TEXT,
    message_count INTEGER DEFAULT 0,
    expires_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Messages
CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    session_id TEXT REFERENCES sessions(id),
    role TEXT NOT NULL,
    content TEXT,
    raw_json JSONB,
    embedding vector(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### Five Memory Tables

| Table | Purpose | Retention |
|-------|---------|-----------|
| `error_corrections` | Problem-solution records | 30 days |
| `user_profiles` | User preferences, habits | Permanent |
| `best_practices` | Successful solutions | 30 days |
| `project_contexts` | Tech stack, decisions | Permanent |
| `skills` | Methods, processes | Permanent |

---

## ⚙️ Configuration

Main config: `backend/src/main/resources/application.conf`

```hocon
database {
    type = "postgresql"
    url = "jdbc:postgresql://localhost:5432/agent_memory"
    user = "agentmemory"
    password = "${DATABASE_PASSWORD}"
    poolSize = 10
}

embedding {
    baseUrl = "http://localhost:8100"
}

memory.retention.days = 14
api.port = 8080
```

Environment variables: `.env` (copy from `.env.example`)

---

## 🐛 Common Issues

| Issue | Solution |
|-------|----------|
| Database connection failed | Check Docker: `docker ps \| grep agentmemory-db` |
| Messages not captured | Verify agent log paths in config |
| Search not working | Start embedding service on port 8100 |
| Port 5500 in use | Check PostgreSQL not running on 5432 |

---

## 📚 Key Files Reference

| File | Purpose |
|------|---------|
| `backend/src/main/java/com/agentmemory/service/DatabaseService.java:1` | DB operations |
| `backend/src/main/java/com/agentmemory/service/MemoryService.java:1` | Memory management |
| `backend/src/main/java/com/agentmemory/api/ApiServer.java:1` | HTTP endpoints |
| `backend/src/main/java/com/agentmemory/service/SessionProcessor.java:1` | Session processing |
| `database/init.sql:1` | Schema |
| `docker-compose.yml:1` | Database config |

---

## 🔄 Development Workflow

1. **Start database first**: `docker-compose up -d`
2. **Build backend**: `cd backend && mvn clean package -DskipTests`
3. **Run backend**: `java -jar target/agent-memory-1.0.0-SNAPSHOT.jar`
4. **Run frontend**: `cd frontend && npm run dev`
5. **Test**: `curl http://localhost:8080/api/health`

---

## 📖 Additional Documentation

- [README.md](README.md) - User guide and quick start
- [DEVELOPMENT.md](DEVELOPMENT.md) - Full development history and testing
- [CHANGELOG.md](CHANGELOG.md) - Version history
- [CODE_REVIEWS.md](CODE_REVIEWS.md) - Code review reports