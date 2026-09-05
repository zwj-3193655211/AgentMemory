@echo off
rem standalone backend launcher (logs to ..\backend-runtime.log)
chcp 65001 >nul
cd /d "%~dp0"
if not defined DATABASE_PASSWORD set "DATABASE_PASSWORD=agentmemory"
if not defined JAVA_HOME set "JAVA_HOME=D:\JDK\jdk_21"
echo [%date% %time%] backend starting >> "%~dp0..\backend-runtime.log"
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -cp "target\classes;target\lib\*" com.agentmemory.AgentMemoryApplication >> "%~dp0..\backend-runtime.log" 2>&1
echo [%date% %time%] backend exited, code=%errorlevel% >> "%~dp0..\backend-runtime.log"
