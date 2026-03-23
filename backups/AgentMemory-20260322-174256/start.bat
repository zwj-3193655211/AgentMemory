@echo off
chcp 65001 >nul
echo ========================================
echo   AgentMemory 一键启动脚本
echo ========================================
echo.

echo [环境准备]
echo 设置数据库密码...
set DATABASE_PASSWORD=agentmemory123

echo.
echo [1/2] 启动后端服务...
echo 正在启动Java后端服务（端口8080）...
cd backend
start "AgentMemory-Backend" cmd /k "mvn exec:java -Dexec.mainClass=\"com.agentmemory.AgentMemoryApplication\""

echo.
echo [2/2] 启动前端服务...
echo 正在启动Vue前端服务（端口3000）...
cd ..\frontend
start "AgentMemory-Frontend" cmd /k "npm run dev"

echo.
echo ========================================
echo   启动完成！
echo ========================================
echo.
echo 🌐 访问地址：
echo    前端界面: http://localhost:3000
echo    后端API:  http://localhost:8080
echo.
echo 📖 使用说明：
echo    - 详细启动指南请查看 README_STARTUP.md
echo    - 关闭窗口即可停止服务
echo    - 数据库密码: agentmemory123
echo.
echo ========================================
pause
