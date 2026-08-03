@echo off
chcp 65001 >nul
echo ================================
echo   AgentMemory 停止服务脚本
echo ================================
echo.

echo [检查进程]
echo 正在查找AgentMemory相关进程...

REM 停止端口 8082 (Java后端)
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8082 ^| findstr LISTENING') do (
    echo 找到后端进程 (PID: %%a)，正在终止...
    taskkill /F /PID %%a >nul 2>&1
    if !errorlevel! == 0 (
        echo ✓ Java后端进程已终止 (端口 8082)
    ) else (
        echo 未找到端口 8082 的进程
    )
)

REM 停止端口 8100 (Embedding服务)
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8100 ^| findstr LISTENING') do (
    echo 找到Embedding服务进程 (PID: %%a)，正在终止...
    taskkill /F /PID %%a >nul 2>&1
    if !errorlevel! == 0 (
        echo ✓ Embedding服务进程已终止 (端口 8100)
    ) else (
        echo 未找到端口 8100 的进程
    )
)

REM 停止端口 5173 (Node.js前端)
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :5173 ^| findstr LISTENING') do (
    echo 找到前端进程 (PID: %%a)，正在终止...
    taskkill /F /PID %%a >nul 2>&1
    if !errorlevel! == 0 (
        echo ✓ Node.js前端进程已终止 (端口 5173)
    ) else (
        echo 未找到端口 5173 的进程
    )
)

REM 停止端口 5500 (PostgreSQL)
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :5500 ^| findstr LISTENING') do (
    echo 找到数据库进程 (PID: %%a)，正在终止...
    taskkill /F /PID %%a >nul 2>&1
    if !errorlevel! == 0 (
        echo ✓ PostgreSQL数据库进程已终止 (端口 5500)
    ) else (
        echo 未找到端口 5500 的进程
    )
)

echo.
echo ================================
echo   所有服务已停止
echo ================================
echo.
echo 💡 提示：如需重新启动，请运行 start.bat
echo.
pause
