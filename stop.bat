@echo off
chcp 65001 >nul
echo ========================================
echo   AgentMemory 停止服务脚本
echo ========================================
echo.

echo [检查进程]
echo 正在查找AgentMemory相关进程...

tasklist | findstr java.exe >nul
if %errorlevel% == 0 (
    echo 找到Java进程，正在终止...
    taskkill /F /IM java.exe >nul 2>&1
    echo ✓ Java后端进程已终止
) else (
    echo 未找到Java后端进程
)

tasklist | findstr node.exe >nul
if %errorlevel% == 0 (
    echo 找到Node.js进程，正在终止...
    taskkill /F /IM node.exe >nul 2>&1
    echo ✓ Node.js前端进程已终止
) else (
    echo 未找到Node.js前端进程
)

echo.
echo ========================================
echo   所有服务已停止
echo ========================================
echo.
echo 💡 提示：如需重新启动，请运行 start.bat
echo.
pause
