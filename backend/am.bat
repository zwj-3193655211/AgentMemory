@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

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

set "PROJECT_DIR=%~dp0"
set "LIB_DIR=%PROJECT_DIR%target\lib"
set "CP=%PROJECT_DIR%target\classes"

for %%f in ("%LIB_DIR%\*.jar") do set "CP=!CP!;%%f"

java -cp "!CP!" com.agentmemory.launcher.CommandLineLauncher %*

endlocal