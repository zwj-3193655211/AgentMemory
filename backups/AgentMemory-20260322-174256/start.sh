#!/bin/bash

echo "========================================"
echo "   AgentMemory 一键启动脚本"
echo "========================================"
echo

# 设置数据库密码
export DATABASE_PASSWORD=agentmemory123

# 检查是否在正确的目录
if [ ! -d "backend" ] || [ ! -d "frontend" ]; then
    echo "❌ 错误：请在 AgentMemory 根目录下运行此脚本"
    exit 1
fi

echo "[环境准备]"
echo "✓ 数据库密码已设置"
echo

# 启动后端
echo "[1/2] 启动后端服务..."
echo "正在启动Java后端服务（端口8080）..."
cd backend
mvn exec:java -Dexec.mainClass="com.agentmemory.AgentMemoryApplication" &
BACKEND_PID=$!
echo "✓ 后端服务已启动 (PID: $BACKEND_PID)"
cd ..

# 等待后端启动
echo "等待后端服务初始化..."
sleep 5

# 启动前端
echo
echo "[2/2] 启动前端服务..."
echo "正在启动Vue前端服务（端口3000）..."
cd frontend
npm run dev &
FRONTEND_PID=$!
echo "✓ 前端服务已启动 (PID: $FRONTEND_PID)"
cd ..

echo
echo "========================================"
echo "   启动完成！"
echo "========================================"
echo
echo "🌐 访问地址："
echo "   前端界面: http://localhost:3000"
echo "   后端API:  http://localhost:8080"
echo
echo "📖 使用说明："
echo "   - 详细启动指南请查看 README_STARTUP.md"
echo "   - 按 Ctrl+C 停止服务"
echo "   - 数据库密码: agentmemory123"
echo
echo "🔄 重启服务："
echo "   - 如需重启，请先运行: kill $BACKEND_PID $FRONTEND_PID"
echo
echo "========================================"

# 保存PID到文件，方便后续停止服务
echo $BACKEND_PID > .backend_pid
echo $FRONTEND_PID > .frontend_pid

echo "进程ID已保存到 .backend_pid 和 .frontend_pid"
echo "按 Ctrl+C 或运行以下命令停止服务："
echo "  kill $BACKEND_PID $FRONTEND_PID"

# 等待用户中断
wait
