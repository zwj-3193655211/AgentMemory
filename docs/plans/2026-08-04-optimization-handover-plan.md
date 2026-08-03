# AgentMemory 优化方案 B - 交接实施计划

> 创建日期: 2026-08-04
> 更新日期: 2026-08-04（接手后完成增量压缩和组件拆分）
> 状态: **进行中（约 85% 完成，剩余运行时测试和可选增强）**
> 用途: 交接给其他开发者继续执行
> 前置文档: [2026-08-04-optimization-plan-b.md](2026-08-04-optimization-plan-b.md)

---

## 📊 当前进度总览

| 模块 | 状态 | 完成度 | 提交记录 |
|------|------|--------|----------|
| 模块 5: 前端架构优化 | ✅ 完成 | 100% | `2a465ac`, `81ca562` |
| 模块 3: Pi Agent 支持 | ✅ 完成 | 100% | `652ad49` |
| 模块 2: SSE 实时更新 | ✅ 完成 | 100% | `694175d` |
| 模块 4: 压缩算法优化 | ✅ 完成 | 100% | `52b2865`, `b005cc8` |
| 模块 1: 仪表盘可视化 | ✅ 完成 | 100% | 随模块 5/2 提交 |
| 构建优化 | ✅ 完成 | 100% | `936ed09` |
| 运行时功能测试 | ⏸️ 待执行 | 0% | 需要数据库 |

### 已提交的 commit（完整）

```
936ed09 build: vite 代码分割优化
81ca562 refactor(frontend): 拆分 Search/Compression/Settings 为独立组件
b005cc8 feat: 增量压缩 - 只处理新增消息并与历史摘要合并
52b2865 feat: 上下文压缩算法优化 - 语义聚类/多级摘要/自适应窗口
8f85bbe docs: 添加优化方案B交接实施计划
694175d feat: 添加 SSE 实时数据更新
652ad49 feat: 添加 Pi Agent 监控支持
2a465ac refactor(frontend): 拆分 Dashboard 和 Sessions 为独立组件
9731c4c docs: 添加完整优化方案B实施计划
```

---

## ✅ 已完成工作详情

### 1. 前端组件拆分（模块 5，部分）

**新增文件**:
- `frontend/src/views/Dashboard.vue` (215行) — 仪表盘：统计卡片 + 4 个 ECharts 图表 + 最近会话
- `frontend/src/views/Sessions.vue` (210行) — 会话列表 + 消息详情，自管理消息加载和导出
- `frontend/src/components/StatCard.vue` (141行) — 可复用统计卡片
- `frontend/src/services/sse.ts` (130行) — SSE 客户端服务

**App.vue**: 2598 行 → 1961 行（-24.5%）

**类型扩展**: `frontend/src/types/index.ts` 的 `Stats` 接口新增：
```typescript
dailySessions?: Array<{ date: string; count: number }>
dailyMessages?: Array<{ date: string; count: number }>
agentDistribution?: Array<{ agentType: string; count: number }>
memoryDistribution?: Array<{ type: string; count: number }>
```

### 2. Pi Agent 支持（模块 3，完成）

- `AgentDetectorService.java`: 新增 `detectAgent("Pi Agent", "pi", ".pi", "agent", "sessions")`
- `FileWatcherService.java`:
  - `processJsonlLine` 新增 `"pi"` 分支
  - 新增 `parsePiMessage()` — 解析 Pi JSONL（session/message 行类型）
  - 新增 `extractPiMessageText()` — 从 content 数组提取 type=text 部分
  - 新增 `piSessionMetaCache` — 缓存 sessionId + cwd（复用 CodexSessionMeta record）

**Pi 日志格式参考**:
```
路径: ~/.pi/agent/sessions/--<编码cwd>--/*.jsonl
session 行: {"type":"session","id":"...","cwd":"...","timestamp":"..."}
message 行: {"type":"message","id":"...","message":{"role":"user|assistant|toolResult","content":[{"type":"text","text":"..."}]}}
目录编码: D:\Desktop_Archive\AgentMemory → --D--Desktop_Archive-AgentMemory--
```

### 3. SSE 实时更新（模块 2，完成）

**后端**:
- 新增 `StatsEventBroadcaster.java` — SSE 广播服务（事件驱动 + 30s 心跳 + 2s 防抖）
- `ApiServer.java`: 新增 `/api/events` 端点（SseHandler）；线程池改为 `newCachedThreadPool()`
- `FileWatcherService.java`: `saveMessage` 后调用 `notifyNewMessage()`

**前端**:
- `services/sse.ts`: EventSource 封装（自动重连、指数退避最多 10 次、事件订阅）
- `App.vue`: `stats_update` 事件 → `loadStatsOnly()`（轻量刷新）；头部实时状态指示灯

### 4. 压缩算法优化（模块 4，部分，⚠️ 未提交）

**新增 `backend/.../service/SemanticCompressor.java`**（工作区中，未提交）:
- `compressBySemanticCluster()` — 语义聚类：贪心算法按余弦相似度 0.82 阈值分簇，大簇保留首尾消息 + 中间省略占位
- `generateMultiLevelSummary()` — Map-Reduce 多级摘要：30 条/块 → 子摘要 → 递归合并
- `calculateAdaptiveWindowSize()` — 自适应窗口：从最新消息往前累加重要性至覆盖 80%
- `scoreImportance()` — 重要性评分：log10(长度) + 关键词命中 + user 角色 ×1.2

**`SessionCompressionService.java` 修改**（工作区中，未提交）:
- 新增 `semanticCompressor` 字段及构造函数初始化
- switch 新增 `SEMANTIC` 和 `MULTI_LEVEL` 两个压缩类型分支
- 新增 `applyAdaptiveSlidingWindow()` 方法
- `determineCompressionType`: 消息数 > 阈值×5 时自动选 MULTI_LEVEL

**前端**: 压缩类型 radio 新增「语义聚类」「多级摘要」选项（工作区中，未提交）

⚠️ **注意**: 模块 4 的代码已完成并通过 `mvn compile` 和 `npm run build` 验证，但**尚未 git 提交**。接手者第一件事应提交这些改动。

---

## 📋 剩余任务实施计划

### 任务 0: 提交模块 4 遗留代码（5 分钟）⚠️ 最先做

```bash
cd AgentMemory
git status  # 应看到 SemanticCompressor.java (新文件) 和 SessionCompressionService.java, App.vue (修改)
git add -A
git commit -m "feat: 上下文压缩算法优化 - 语义聚类/多级摘要/自适应窗口

- 新增 SemanticCompressor: 语义聚类压缩、Map-Reduce 多级摘要、重要性评分
- SessionCompressionService: 新增 SEMANTIC/MULTI_LEVEL 压缩类型
- 自适应滑动窗口: 保留覆盖 80% 重要性的消息
- 前端压缩类型新增语义聚类/多级摘要选项"
git push origin main
```

**验证**:
```bash
cd backend && mvn compile -q && echo OK
cd ../frontend && npm run build 2>&1 | grep built
```

---

### 任务 1: 增量压缩（模块 4 剩余，约 4 小时）

**目标**: 压缩时只处理自上次压缩以来的新增消息，与已有摘要合并，避免重复处理。

**背景**: 当前 `compressSessionInternal()` 每次都取全部消息重新压缩。长会话频繁压缩时浪费 LLM token。

#### 1.1 数据库迁移（30 分钟）

新建 `database/migrate_add_incremental_compression.sql`:

```sql
-- session_summaries 表增加增量压缩字段
ALTER TABLE session_summaries
    ADD COLUMN IF NOT EXISTS last_compressed_count INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS incremental_summary TEXT;

COMMENT ON COLUMN session_summaries.last_compressed_count IS '上次压缩时的消息总数';
COMMENT ON COLUMN session_summaries.incremental_summary IS '新增消息的增量摘要';
```

执行方式：通过 `database/init.sql` 追加或手动 `psql -f` 执行（参考现有 migrate_*.sql 的执行方式）。

#### 1.2 后端实现（2.5 小时）

修改 `SessionCompressionService.java`:

1. 新增方法 `compressSessionIncremental(Connection conn, String sessionId, List<String> allMessages)`:
   - 查询 `session_summaries` 获取该会话最新版本的 `last_compressed_count`（无则视为 0）
   - 新增消息 = `allMessages.subList(lastCompressedCount, allMessages.size())`
   - 若新增消息 < 增量阈值（建议 20 条，可配置 `compression.incrementalThreshold`），跳过
   - 对新增消息调用 `semanticCompressor.generateMultiLevelSummary(newMessages)` 生成增量摘要
   - 合并摘要：调用 `llmClient.summarize(List.of(oldSummary, incrementalSummary))` 生成新总摘要
   - 更新 `session_summaries`: summary、last_compressed_count、version+1

2. 在 `compressSessionInternal()` 中接入：
   ```java
   case "INCREMENTAL" -> {
       compressedMessages = applyAdaptiveSlidingWindow(messages);
       summary = compressSessionIncremental(conn, sessionId, messages);
   }
   ```

3. `saveSessionSummary()` 需同步保存 `last_compressed_count`（检查该方法现有 SQL，添加新字段）。

**关键代码位置**:
- `SessionCompressionService.java` 的 `compressSessionInternal()`（约 229 行）
- `saveSessionSummary()`（约 380 行）

#### 1.3 配置外置（30 分钟）

参考 P1-002 的既有模式：
- `application.conf` 添加 `compression.incrementalThreshold = 20`
- `ApplicationConfig.java` 添加字段 + getter
- `SessionCompressionService` 读取

#### 1.4 前端（30 分钟）

`App.vue` 压缩类型 radio 添加 `<el-radio label="INCREMENTAL">增量压缩</el-radio>`。

#### 1.5 验证

- 构造 100+ 条消息的测试会话
- 首次全量压缩 → 检查 `last_compressed_count = 100`
- 追加 30 条消息 → 增量压缩 → 检查只处理了 30 条（日志）
- 检查摘要包含旧+新内容

---

### 任务 2: Settings.vue 和 Search.vue 拆分（模块 5 剩余，约 3 小时）

**目标**: App.vue 从 1961 行降至 ~800 行。

参考 Dashboard.vue/Sessions.vue 的拆分模式：

#### 2.1 Settings.vue（1.5 小时）

App.vue 中 `activeMenu === 'settings'` 对应的模板块（LLM 配置、清理配置、Agent 管理、Embedding 模型等，约 400 行模板）抽取为 `frontend/src/views/Settings.vue`。

涉及的状态/方法（从 App.vue script 迁移）:
- `llmConfig`, `llmProviders`, `newLLMProvider`, `showAddLLMProvider`
- `autoCleanup`, `cleanupDays`, `cleaningUp`
- `newAgent`, `showAddAgentDialog`, `addingAgent`
- 方法: `saveLLMConfig`, `testLLMConnection`, `updateLLMConfig`, `saveCleanupConfig`, `cleanupNow`, `addCustomAgent`, `deleteAgent`, `loadLLMProviders` 等

⚠️ 注意: Embedding 模型下载相关（`embeddingModels`, `downloadPollingInterval` 等）也在设置页，需一并迁移。

#### 2.2 Search.vue（1 小时）

`activeMenu === 'search'` 的搜索结果块 + `searchQuery`, `searchResults`, `searching`, `handleSearch` 迁移。搜索框在 header 中，保留在 App.vue，通过事件 emit 给 Search.vue 或用 provide/inject。

#### 2.3 Compression.vue（可选，30 分钟）

`activeMenu === 'compression'` 会话摘要页（约 150 行模板 + `compressionStats`, `compressionConfig`, `sessionSummaries`, `summaryDetail`, `sessionMessages` 及相关方法）。

#### 2.4 验证

```bash
cd frontend && npx vue-tsc --noEmit && npm run build
```

---

### 任务 3: 仪表盘增强（模块 1 剩余，约 3 小时，P2 可选）

#### 3.1 词云图（2 小时）

- 安装依赖: `npm install echarts-wordcloud`
- 后端新增 API: `GET /api/stats/keywords` — 从 messages 表统计高频词（简单实现：对最近 1000 条消息 content 分词计数；中文分词可简单按 2-gram 或引入 ansj_seg 依赖）
- Dashboard.vue 添加第 5 个图表卡片：词云

```typescript
import 'echarts-wordcloud'
// series: [{ type: 'wordCloud', data: [{name, value}], sizeRange: [12, 40] }]
```

#### 3.2 会话时长统计（1 小时）

- 后端 `/api/stats` 增加: 平均每会话消息数、最长会话、今日新增会话/消息
- Dashboard 统计卡片区增加「今日新增」卡片

---

### 任务 4: 测试与发布（约 6 小时）

#### 4.1 功能测试清单

- [ ] Pi Agent: 启动后端，确认检测到 Pi（日志 `检测到 N 个 Agent`），确认 Pi 会话消息入库（`SELECT * FROM messages WHERE agent_type='pi'`）
- [ ] SSE: 打开前端，确认头部「实时」指示灯绿色；用 CLI agent 发一条消息，确认仪表盘数字 5 秒内自动刷新
- [ ] 语义聚类压缩: 对 50+ 条消息的会话手动触发 SEMANTIC 压缩，检查摘要质量和簇数量日志
- [ ] 多级摘要: 对 200+ 条消息的会话触发 MULTI_LEVEL 压缩
- [ ] 增量压缩: 按任务 1.5 验证
- [ ] 仪表盘 4 图表正常渲染，统计卡片点击跳转正常
- [ ] 会话页: 列表、筛选、消息详情、导出正常

#### 4.2 单元测试（原计划 v2.4.0 任务，可同步进行）

- `SemanticCompressorTest`: 聚类算法（构造固定向量）、重要性评分、自适应窗口边界
- `StatsEventBroadcasterTest`: 注册/广播/断开清理
- 前端 `sse.test.ts`: mock EventSource

#### 4.3 文档更新

- `README.md`: 版本号 → v3.0.0，新增功能说明（实时更新、Pi 支持、新压缩算法）
- `CHANGELOG.md`: 新增 v3.0.0 条目
- 更新 `docs/plans/2026-08-04-optimization-plan-b.md` 状态为完成

---

## ⚠️ 注意事项（重要）

### 环境陷阱

1. **npm devDependencies 不安装**: 本机 `NODE_ENV=production`，`npm install` 会跳过 devDependencies 导致构建失败（vitest 缺失）。必须执行：
   ```bash
   cd frontend && npm install --include=dev
   ```

2. **write 工具路径**: 本环境 write/read 工具需用 `D:/...` 盘符路径，`/d/...` 格式会静默失败。

3. **Java 文件字符串字面量**: 用脚本批量编辑 Java 文件时注意 `\n` 转义——Python 三引号中的 `"\\n"` 会变成实际换行，导致 "未结束的字符串文字" 编译错误。文件是混合换行（CRLF+LF）。

### 架构约定

1. **压缩不删除原始消息**: `compressSessionInternal` 第 4 步已明确注释——压缩仅生成摘要，保留全部原始数据。不要在任何压缩类型中调用 `markMessagesAsDeleted`。

2. **SSE 防抖**: `notifyNewMessage` 有 2 秒防抖，高频消息不会刷爆前端。修改时注意保留。

3. **agentType vs parserType**: `watchDirectory(agentType, parserType, dir)` 两者分离。Pi 的两者都是 "pi"。SetupHandler 的 switch 有 default 兜底，新增 agent 通常无需改它。

4. **前端组件通信**: 新拆分的组件遵循「props 下行、emit 上行」，页面级数据（sessions/stats）由 App.vue 统一加载后经 props 传入（参考 Dashboard/Sessions）。

### 已知遗留问题（非本次范围）

- `App.vue` 仍有 ~1900 行（含 chat 页面 ChatView 引用、设置页），任务 2 完成后应 ~800 行
- `FileWatcherService.java` 53KB，过大，未来可再拆分各 parser 为独立类
- 构建产物 chunk > 500KB 警告：可通过 vite 配置 `build.chunkSizeWarningLimit` 或路由级代码分割解决

---

## 🚀 建议执行顺序

```
任务 0 (提交遗留) → 任务 1 (增量压缩) → 任务 2 (组件拆分)
→ 任务 4.1 (功能测试) → 任务 3 (仪表盘增强, 可选) → 任务 4.2/4.3 (测试+发布)
```

预计剩余工时: **约 16 小时（2 天）**

---

**交接人**: 前序开发
**接手人**: （待填写）
**仓库**: https://github.com/zwj-3193655211/AgentMemory


---

## 🔄 后续执行记录（2026-08-04 第二批次）

### 已完成

1. **任务 0**: 提交模块 4 遗留代码（SemanticCompressor + SessionCompressionService 修改）— commit `52b2865`
2. **任务 1**: 增量压缩 — commit `b005cc8`
   - `INCREMENTAL` 压缩类型：只处理自上次压缩以来的新增消息，与历史摘要 LLM 合并
   - `session_summaries.last_compressed_count` 字段（含迁移脚本 + 建表语句）
   - 配置 `compression.incrementalThreshold=20`
3. **任务 2**: Search/Compression/Settings 组件拆分 — commit `81ca562`
   - 新增 `Search.vue` (41行)、`Compression.vue` (544行)、`Settings.vue` (855行)
   - App.vue: 1961 → 591 行（-70%）
   - 修复 Search 图标与组件命名冲突（`Search as SearchIcon`）
   - 子组件自行管理数据加载（onMounted），移除 App.vue 中的 ~1200 行已迁移代码
4. **构建优化**: commit `936ed09`
   - vite manualChunks 函数式配置（rolldown 兼容）
   - chunk 拆分：echarts / element-plus / vendor
5. **静态验证**: 后端 `mvn compile` 通过；前端 `vue-tsc --noEmit` 通过；76 个单元测试全部通过；`npm run build` 通过

### 验证说明（重要）

- 增量压缩 SQL 参数校验: INSERT 10 字段 = 10 个参数 ✅
- `last_compressed_count` 在建表语句、迁移脚本、查询 SQL 三处一致 ✅
- SSE 端点: 前端 `${API_BASE}/events` ↔ 后端 `/api/events` 匹配 ✅
- **运行时测试未执行**（PostgreSQL 未启动，Docker Desktop 不可用）

### 剩余工作

1. **任务 4.1 功能测试**（需要数据库，约 2-3 小时）:
   - 启动 PostgreSQL: `docker compose up -d`（或手动 `psql` 执行 `database/migrate_add_incremental_compression.sql`）
   - 启动后端: `start.bat` 或 `backend/start.bat`
   - 按本文档「任务 4.1 功能测试清单」逐项验证
2. **任务 3 仪表盘增强**（可选，P2）:
   - 词云图: 安装 `echarts-wordcloud`，后端 `GET /api/stats/keywords`
   - 会话时长统计: 后端 `/api/stats` 增强
3. **任务 4.2/4.3 测试与发布**:
   - 单元测试: SemanticCompressorTest / StatsEventBroadcasterTest / sse.test.ts
   - 文档: README 版本 v3.0.0、CHANGELOG
