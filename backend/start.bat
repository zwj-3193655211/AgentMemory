@echo off
chcp 65001 >nul
REM AgentMemory 启动脚本 (Windows)

echo ========================================
echo AgentMemory - 本地 Agent 语义化记忆引擎
echo ========================================

REM 设置 JAVA_HOME (根据你的安装路径调整)
set "JAVA_HOME=D:\JDK\jdk_21"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM 设置数据库密码
set "DATABASE_PASSWORD=agentmemory123"

REM 检查 Java
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] 未找到 Java，请安装 JDK 17+
    pause
    exit /b 1
)

echo [INFO] Java 版本:
java -version 2>&1 | findstr "version"

REM 设置 Maven 路径 (如果需要)
set "MAVEN_HOME=D:\apache-maven-3.9.11"
set "PATH=%MAVEN_HOME%\bin;%PATH%"

REM 进入项目目录
cd /d "%~dp0"

REM 检查是否需要编译
if not exist "target\classes\com\agentmemory\AgentMemoryApplication.class" (
    echo [INFO] 首次运行，正在编译...
    call mvn clean compile -q
    if %errorlevel% neq 0 (
        echo [ERROR] 编译失败
        pause
        exit /b 1
    )
)

REM 运行程序
echo [INFO] 启动 AgentMemory...
echo ========================================
java -Dfile.encoding=UTF-8 -Dconsole.encoding=UTF-8 -cp "target\classes;target\lib/*" com.agentmemory.AgentMemoryApplication

pause
