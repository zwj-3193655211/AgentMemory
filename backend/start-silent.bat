@echo off
REM AgentMemory Silent Startup Script

cd /d "%~dp0"

REM Load .env file
if exist "%~dp0..\.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0..\.env") do (
        if not "%%a"=="" if not "%%b"=="" set "%%a=%%b"
    )
)

REM Defaults
if not defined JAVA_HOME (
    echo [ERROR] JAVA_HOME not set. Please configure .env file.
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"

start /B "" javaw -cp "target/agent-memory-1.0.0-SNAPSHOT.jar;target/lib/*" com.agentmemory.AgentMemoryApplication > nul 2>&1

echo AgentMemory started in background.
