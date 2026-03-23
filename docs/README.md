# AgentMemory 开发文档

> 本目录包含项目的完整开发历程

## 📖 主要文档

### [DEVELOPMENT.md](DEVELOPMENT.md) ⭐

**完整的开发历程记录**（一本通），包含所有开发阶段：

#### 六大开发阶段

1. **项目概述** - 技术栈、架构、API 设计
2. **第一阶段：项目启动** (2026-03-20)
   - 核心功能定义（五大记忆库）
   - 架构设计
   - 支持的 Agent
3. **第二阶段：语义模型调研** (2025-03-21)
   - 候选模型评估（Qwen、SmolLM2）
   - 测试结果对比
   - 最终决策：bge-small-zh-v1.5
4. **第三阶段：问题发现** (2026-03-22)
   - 20个问题详细记录（P0/P1/P2）
   - SessionProcessor 全局锁
   - 线程池泄漏
   - N+1 查询问题
5. **第四阶段：代码审查与优化** (2026-03-23)
   - 并发重构（10-100倍提升）
   - 数据库优化（33%提升）
   - 向量索引（10-1000倍提升）
6. **第五阶段：当前状态** (2026-03-23)
   - 启动方式
   - 配置说明
   - 常见问题
7. **第六阶段：测试验证** (2026-03-20)
   - 测试用例（TC1-TC4）
   - 验证方法
   - 测试结果记录

#### 附录

- 性能指标对比（v1.0 vs v2.0）
- 版本历史
- 数据库表结构
- 依赖版本

---

## 📊 性能指标对比

| 指标 | v1.0.0 | v2.0.0 | 提升 |
|------|--------|--------|------|
| 并发会话数 | 10 | 100+ | **10x** |
| 消息保存速度 | 300/s | 500/s | **67%** |
| 向量搜索 (1k条) | ~100ms | ~1ms | **100x** |

---

## 🎯 快速导航

### 新手开发者
1. 先读 [DEVELOPMENT.md](DEVELOPMENT.md) 的"项目概述"和"第五阶段：当前状态"
2. 了解架构和启动方式
3. 运行测试用例（第六阶段）

### 了解决策过程
1. 从 [DEVELOPMENT.md](DEVELOPMENT.md) 的"第二阶段"开始
2. 需要详细信息时查看 [SEMANTIC_MODEL_EVALUATION.md](SEMANTIC_MODEL_EVALUATION.md)
3. 读"第三阶段"了解踩坑经验

### 性能优化参考
直接查看 [DEVELOPMENT.md](DEVELOPMENT.md) 的"第四阶段"

### 测试验证
查看 [DEVELOPMENT.md](DEVELOPMENT.md) 的"第六阶段：测试验证"

## 🔍 快速导航

### 新手入门
1. 阅读 [../README.md](../README.md) - 快速开始使用
2. 查看 [../CHANGELOG.md](../CHANGELOG.md) - 了解版本更新

### 开发者
1. 阅读 [CODE_REVIEW.md](CODE_REVIEW.md) - 了解代码质量和架构
2. 查看 [PROJECT_PLAN.md](PROJECT_PLAN.md) - 了解项目规划
3. 参考 [OPTIMIZATION_GUIDE.md](OPTIMIZATION_GUIDE.md) - 进行性能优化

### 问题排查
1. 查看 [BUG_REPORT.md](BUG_REPORT.md) - 已知问题和解决方案
2. 检查日志: `~/.agentmemory/logs/agentmemory.log`
3. 查看 API 状态: `curl http://localhost:8080/api/stats`

## 📊 文档统计

- 总文档数: 6 个
- 总大小: 约 76KB
- 最后更新: 2026-03-22

## 💡 贡献指南

如果您想贡献代码或文档：
1. 遵循现有代码风格
2. 更新相关文档
3. 添加测试用例
4. 提交 Pull Request

---

**维护者**: AgentMemory Team
**最后更新**: 2026-03-22
