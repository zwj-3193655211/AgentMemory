@echo off
setlocal enabledelayedexpansion
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
if not defined DATABASE_PASSWORD set "DATABASE_PASSWORD=agentmemory"
if not defined BACKEND_PORT set "BACKEND_PORT=8082"
if not defined FRONTEND_PORT set "FRONTEND_PORT=5175"
if not defined EMBEDDING_PORT set "EMBEDDING_PORT=8100"

echo [INFO] JAVA_HOME: %JAVA_HOME%

echo [1/5] Cleaning up old processes...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%BACKEND_PORT% ^| findstr LISTENING 2^>nul') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%FRONTEND_PORT% ^| findstr LISTENING 2^>nul') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%EMBEDDING_PORT% ^| findstr LISTENING 2^>nul') do taskkill /F /PID %%a >nul 2>&1

echo [2/6] Checking Docker...
docker info >nul 2>&1
if !errorlevel! equ 0 goto docker_already_running

echo [INFO] Docker Desktop is not running. Starting...
start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"
echo [INFO] Waiting for Docker to start (max 60s)...
set DOCKER_WAIT=0

:docker_wait_loop
timeout /t 3 /nobreak >nul
set /a DOCKER_WAIT+=3
docker info >nul 2>&1
if !errorlevel! equ 0 goto docker_ready
if !DOCKER_WAIT! geq 60 (
    echo [ERROR] Docker Desktop failed to start within 60 seconds.
    echo [INFO] Please start Docker Desktop manually and try again.
    pause
    exit /b 1
)
echo [INFO] Still waiting... !DOCKER_WAIT!s
goto docker_wait_loop

:docker_ready
echo [INFO] Docker started successfully.
goto docker_done

:docker_already_running
echo [INFO] Docker is already running.

:docker_done

echo [3/6] Starting PostgreSQL...
docker-compose up -d

echo [INFO] Waiting for PostgreSQL to be ready...
set PG_WAIT=0
:pg_wait_loop
timeout /t 2 /nobreak >nul
set /a PG_WAIT+=2
docker exec agentmemory-db pg_isready -U postgres >nul 2>&1
if !errorlevel! equ 0 goto pg_ready
if !PG_WAIT! geq 30 (
    echo [ERROR] PostgreSQL failed to start within 30 seconds.
    pause
    exit /b 1
)
echo [INFO] Still waiting... !PG_WAIT!s
goto pg_wait_loop
:pg_ready
echo [INFO] PostgreSQL is ready.

REM [Ollama] 检查并启动（供 LLM 推理加速，若已运行则跳过）
set OLLAMA_EXE=D:\Ollma\ollama.exe
if not exist "%OLLAMA_EXE%" set "OLLAMA_EXE=ollama"
netstat -ano | findstr :11434 | findstr LISTENING >nul 2>&1
if !errorlevel! equ 0 (
    echo [INFO] Ollama 已在运行 (端口 11434)，跳过启动。
) else (
    echo [4.5/6] 启动 Ollama...
    start "Ollama" cmd /k "chcp 65001 >nul && \"%OLLAMA_EXE%\" serve"
    echo [INFO] 等待 Ollama 就绪 (端口 11434)...
    set OLLAMA_WAIT=0
    :ollama_wait_loop
    timeout /t 2 /nobreak >nul
    set /a OLLAMA_WAIT+=2
    netstat -ano | findstr :11434 | findstr LISTENING >nul 2>&1
    if !errorlevel! equ 0 goto ollama_ready
    if !OLLAMA_WAIT! geq 30 (
        echo [WARN] Ollama 30 秒内未就绪，继续启动其他服务（LLM 压缩可能不可用）。
        goto ollama_skip
    )
    echo [INFO] 等待 Ollama... !OLLAMA_WAIT!s
    goto ollama_wait_loop
    :ollama_ready
    echo [INFO] Ollama 已就绪 (端口 11434)。
    :ollama_skip
)

echo [4/6] Starting Embedding Service...
start "Embedding" cmd /k "chcp 65001 >nul && cd /d "%~dp0embedding_service" && python embed_server.py"

echo [5/6] Starting Backend...
start "Backend" cmd /k "chcp 65001 >nul && set "JAVA_HOME=%JAVA_HOME%" && set "DATABASE_PASSWORD=%DATABASE_PASSWORD%" && cd /d "%~dp0backend" && "%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -cp "target\classes;target\lib\*" com.agentmemory.AgentMemoryApplication"

echo [6/6] Starting Frontend...
start "Frontend" cmd /k "chcp 65001 >nul && cd /d "%~dp0frontend" && npm run dev"

echo.
echo ========================================
echo   Started! 窗口会保持打开以便查看日志
echo ========================================
echo    Frontend:  http://localhost:%FRONTEND_PORT%
echo    Backend:   http://localhost:%BACKEND_PORT%
echo    Embedding: http://localhost:%EMBEDDING_PORT%
echo ========================================
pause