@echo off
chcp 65001 >nul

echo ========================================
echo   AgentMemory Startup Script
echo ========================================

cd /d "%~dp0"

REM Load .env file (simple approach)
if exist "%~dp0.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0.env") do (
        if not "%%a"=="" if not "%%b"=="" set "%%a=%%b"
    )
)

REM Defaults
if not defined JAVA_HOME set "JAVA_HOME=D:\JDK\jdk_21"
if not defined DATABASE_PASSWORD set "DATABASE_PASSWORD=agentmemory123"
if not defined BACKEND_PORT set "BACKEND_PORT=8080"
if not defined FRONTEND_PORT set "FRONTEND_PORT=5173"
if not defined EMBEDDING_PORT set "EMBEDDING_PORT=8100"

echo [INFO] JAVA_HOME: %JAVA_HOME%

echo [1/5] Cleaning up old processes...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%BACKEND_PORT% ^| findstr LISTENING 2^>nul') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%FRONTEND_PORT% ^| findstr LISTENING 2^>nul') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%EMBEDDING_PORT% ^| findstr LISTENING 2^>nul') do taskkill /F /PID %%a >nul 2>&1

echo [2/5] Starting PostgreSQL...
docker-compose up -d

echo [3/5] Starting Embedding Service...
start "AgentMemory-Embedding" cmd /k "cd /d "%~dp0embedding_service" && python embed_server.py"

echo [4/5] Starting Backend...
set "PATH=%JAVA_HOME%\bin;%PATH%"
start "AgentMemory-Backend" cmd /k "chcp 65001 >nul && cd /d "%~dp0backend" && set "JAVA_HOME=%JAVA_HOME%" && set "PATH=%JAVA_HOME%\bin;%PATH%" && set "DATABASE_PASSWORD=%DATABASE_PASSWORD%" && java -Dfile.encoding=UTF-8 -Dconsole.encoding=UTF-8 -cp "target\classes;target\lib\*" com.agentmemory.AgentMemoryApplication"

echo [5/5] Starting Frontend...
start "AgentMemory-Frontend" cmd /k "cd /d "%~dp0frontend" && npm run dev"

echo.
echo ========================================
echo   Started!
echo ========================================
echo    Frontend:  http://localhost:%FRONTEND_PORT%
echo    Backend:   http://localhost:%BACKEND_PORT%
echo    Embedding: http://localhost:%EMBEDDING_PORT%
echo ========================================
pause