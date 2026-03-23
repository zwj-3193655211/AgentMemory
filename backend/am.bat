@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

set "JAVA_HOME=D:\JDK\jdk_21"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "PROJECT_DIR=%~dp0"
set "LIB_DIR=%PROJECT_DIR%target\lib"
set "CP=%PROJECT_DIR%target\classes"

for %%f in ("%LIB_DIR%\*.jar") do set "CP=!CP!;%%f"

java -cp "!CP!" com.agentmemory.launcher.CommandLineLauncher %*

endlocal