@echo off
chcp 65001 >nul
REM AgentMemory Backend Startup Script

echo ========================================
echo AgentMemory - Local Agent Memory Engine
echo ========================================

REM Load .env file
if exist "%~dp0..\.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0..\.env") do (
        if not "%%a"=="" if not "%%b"=="" set "%%a=%%b"
    )
)

REM Defaults
if not defined JAVA_HOME (
    echo [ERROR] JAVA_HOME not set. Please configure .env file.
    pause
    exit /b 1
)
if not defined DATABASE_PASSWORD set "DATABASE_PASSWORD=agentmemory123"

set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Check Java
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java not found. Check JAVA_HOME: %JAVA_HOME%
    pause
    exit /b 1
)

echo [INFO] Java version:
java -version 2>&1 | findstr "version"

REM Maven path (if configured)
if defined MAVEN_HOME set "PATH=%MAVEN_HOME%\bin;%PATH%"

cd /d "%~dp0"

REM Check if compile needed
if not exist "target\classes\com\agentmemory\AgentMemoryApplication.class" (
    echo [INFO] First run, compiling...
    call mvn clean compile -q
    if %errorlevel% neq 0 (
        echo [ERROR] Compile failed
        pause
        exit /b 1
    )
)

echo [INFO] Starting AgentMemory...
echo ========================================
java -Dfile.encoding=UTF-8 -Dconsole.encoding=UTF-8 -cp "target\classes;target\lib/*" com.agentmemory.AgentMemoryApplication

pause
