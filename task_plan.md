# Task Plan: 首次使用初始化页面

## Goal
为 AgentMemory 添加首次使用初始化向导页面，用户首次使用时完成：
1. **消息导入** - 从 Claude Desktop 导出文件导入历史会话
2. **模型选择** - 选择 Embedding 模型用于语义搜索

## Project Context
- **Frontend**: Vue 3 + Element Plus + TypeScript
- **Backend**: Java HTTP Server + SQLite/PostgreSQL
- **Embedding Service**: 独立服务在端口 8100
- **现有架构**: 无路由，使用 `activeMenu` 动态组件切换

---

## Phase 1: 需求分析与数据设计 ✅ complete
- [x] 探索前端项目结构
- [x] 理解后端 API 能力
- [x] 分析消息导入的数据格式
- [x] 设计首次使用状态存储

---

## Phase 2: 后端 API 实现 ✅ in_progress
- [ ] 添加初始化状态检查 API
- [ ] 添加消息导入 API (支持 JSON 文件批量导入)
- [ ] 添加配置存储 API (模型选择等)
- [ ] 添加首次使用完成标记 API

---

## Phase 3: 前端初始化页面开发
- [ ] 创建 `Setup.vue` 初始化页面组件
- [ ] 实现步骤 1: 欢迎与介绍
- [ ] 实现步骤 2: 消息导入 (文件上传)
- [ ] 实现步骤 3: 模型选择
- [ ] 实现步骤 4: 完成确认

---

## Phase 4: 状态管理与路由集成
- [ ] 添加初始化状态检测 (App.vue 启动时)
- [ ] 修改 App.vue 根据状态显示 Setup 或主界面
- [ ] 完成初始化后的页面切换逻辑

---

## Phase 5: 测试验证
- [ ] 测试首次访问显示初始化页面
- [ ] 测试消息导入功能
- [ ] 测试模型选择功能
- [ ] 测试完成后跳转到主界面

---

## Key Files
### New Files
- `frontend/src/views/Setup.vue` - 初始化向导页面
- `backend/src/main/java/com/agentmemory/api/SetupHandler.java` - 初始化 API

### Modified Files
- `frontend/src/App.vue` - 添加初始化状态检测
- `backend/src/main/java/com/agentmemory/api/ApiServer.java` - 注册新 API
- `backend/src/main/java/com/agentmemory/config/ApplicationConfig.java` - 配置管理

---

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 使用动态组件而非 Vue Router | 与现有架构一致 |
| 消息格式支持 Claude Desktop JSON 导出 | 用户最常用的导出格式 |
| 初始化状态存储在配置文件 | 简单可靠，无需数据库改造 |

---

## Errors Encountered
None yet.

---

## Progress
- [Phase 1] 需求分析: 80% complete
- [Phase 2] 后端 API: 100% complete
- [Phase 3] 前端页面: 0% complete
- [Phase 4] 集成: 100% complete
- [Phase 5] 测试: 0% complete