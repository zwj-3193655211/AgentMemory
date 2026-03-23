@echo off
chcp 65001 >nul

echo ========================================
echo   AgentMemory Startup Script
echo ========================================

cd /d "%~dp0"

echo [1/5] Cleaning up old processes...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :5173 ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8100 ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

echo [2/5] Starting PostgreSQL...
docker-compose up -d

echo [3/5] Starting Embedding Service (port 8100)...
start "AgentMemory-Embedding" cmd /k "cd /d "%~dp0embedding_service" && python embed_server.py"

echo [4/5] Starting Backend (port 8080)...
start "AgentMemory-Backend" cmd /k "chcp 65001 >nul && cd /d "%~dp0backend" && set "JAVA_HOME=D:\JDK\jdk_21" && set "PATH=%JAVA_HOME%\bin;%PATH%" && set "DATABASE_PASSWORD=agentmemory123" && java -Dfile.encoding=UTF-8 -Dconsole.encoding=UTF-8 -cp "target\classes;target\lib\*" com.agentmemory.AgentMemoryApplication"

echo [5/5] Starting Frontend (port 5173)...
start "AgentMemory-Frontend" cmd /k "cd /d "%~dp0frontend" && npm run dev"

echo.
echo ========================================
echo   Started!
echo ========================================
echo    Frontend:  http://localhost:5173
echo    Backend:   http://localhost:8080
echo    Embedding: http://localhost:8100
echo ========================================
pause