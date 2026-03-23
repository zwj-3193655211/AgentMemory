@echo off
REM AgentMemory 后台启动脚本
REM 无窗口后台运行

cd /d "%~dp0"

REM 设置环境
set "JAVA_HOME=D:\JDK\jdk_21"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM 后台启动（无窗口）
start /B "" javaw -cp "target/agent-memory-1.0.0-SNAPSHOT.jar;target/lib/*" com.agentmemory.AgentMemoryApplication > nul 2>&1

echo AgentMemory 已在后台启动
