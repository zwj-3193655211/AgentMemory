#!/bin/bash

echo "========================================"
echo "   AgentMemory 一键启动脚本"
echo "========================================"
echo

# 设置数据库密码
export DATABASE_PASSWORD=agentmemory123

# 检查是否在正确的目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -d "backend" ] || [ ! -d "frontend" ]; then
    echo "❌ 错误：请在 AgentMemory 根目录下运行此脚本"
    exit 1
fi

echo "[1/3] 启动数据库..."
echo "正在启动 PostgreSQL..."
docker-compose up -d
echo

# 启动后端
echo "[2/3] 启动后端服务..."
echo "正在启动Java后端服务（端口8080）..."
cd "$SCRIPT_DIR/backend"
java -Dfile.encoding=UTF-8 -cp target\classes:target\lib/* com.agentmemory.AgentMemoryApplication &
BACKEND_PID=$!
echo "✓ 后端服务已启动 (PID: $BACKEND_PID)"
cd "$SCRIPT_DIR"

# 等待后端启动
echo "等待后端服务初始化..."
sleep 5

# 启动前端
echo
echo "[3/3] 启动前端服务..."
echo "正在启动Vue前端服务（端口5173）..."
cd "$SCRIPT_DIR/frontend"
npm run dev &
FRONTEND_PID=$!
echo "✓ 前端服务已启动 (PID: $FRONTEND_PID)"
cd "$SCRIPT_DIR"

echo
echo "========================================"
echo "   启动完成！"
echo "========================================"
echo
echo "🌐 访问地址："
echo "   前端界面: http://localhost:5173"
echo "   后端API:  http://localhost:8080"
echo
echo "📖 使用说明："
echo "   - 按 Ctrl+C 停止服务"
echo "   - 数据库密码: agentmemory123"
echo
echo "🔄 停止服务："
echo "   - 运行: kill $BACKEND_PID $FRONTEND_PID"
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