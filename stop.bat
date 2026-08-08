@echo off
setlocal enabledelayedexpansion
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

REM 停止端口 11434 (Ollama) - 询问用户是否关闭
netstat -aon | findstr :11434 | findstr LISTENING >nul 2>&1
if !errorlevel! equ 0 (
    echo.
    set /p STOP_OLLAMA="检测到 Ollama (端口 11434) 正在运行，是否一并关闭？(y/N): "
    if /i "!STOP_OLLAMA!"=="y" (
        for /f "tokens=5" %%a in ('netstat -aon ^| findstr :11434 ^| findstr LISTENING') do (
            taskkill /F /PID %%a >nul 2>&1
            echo ✓ Ollama 已关闭
        )
    ) else (
        echo 保留 Ollama 运行（下次启动更快）。
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
