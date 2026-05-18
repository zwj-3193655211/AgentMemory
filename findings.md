# Findings: 首次使用初始化页面

## 技术栈确认
- **Frontend**: Vue 3 + Composition API + TypeScript + Element Plus
- **Backend**: Java HTTP Server (自定义 HttpServer，非 Spring)
- **数据库**: SQLite (开发) / PostgreSQL (生产) + 可选 pgvector

## 关键发现

### 1. 消息导入格式
用户从 Claude Desktop 导出的会话 JSON 格式：
```json
{
  "sessions": [{
    "id": "uuid",
    "agentType": "claude-code",
    "messages": [
      { "role": "user", "content": "..." },
      { "role": "assistant", "content": "..." }
    ]
  }]
}
```

### 2. Embedding 模型 API
- 后端已有 `/api/embedding/models` (GET) - 获取模型列表
- 后端已有 `/api/embedding/model` (POST) - 切换模型
- Embedding 服务运行在端口 8100

### 3. 初始化状态存储方案
选项：
1. **配置文件** - `backend/src/main/resources/application.conf` 添加 `initialized` 字段
2. **数据库表** - 新建 `app_config` 表
3. **前端 localStorage** - 仅前端有效，不够可靠

**选择**: 方案 1 (配置文件)，因为这是 Java 项目，已有配置管理。

### 4. 消息导入 API 设计
```
POST /api/import          - 批量导入会话 JSON 文件
  Body: { "sessions": [...] }
  Response: { "imported": 5, "failed": 0 }

POST /api/setup/complete - 标记初始化完成
  Body: { "modelId": "xxx" }
  Response: { "status": "ok" }

GET  /api/setup/status   - 获取初始化状态
  Response: { "initialized": false, "hasData": false }
```

### 5. 前端页面流程
1. 欢迎页 - 介绍应用功能
2. 消息导入页 - 文件上传组件
3. 模型选择页 - 下拉选择 Embedding 模型
4. 完成页 - 确认并跳转到主界面

## 实现优先级
1. 后端 API (SetupHandler.java)
2. 前端 Setup.vue
3. App.vue 集成