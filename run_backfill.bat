@echo off
chcp 65001 >nul
cd /d "C:\Users\31936\Desktop\AgentMemory\backend"
set "JDBC_URL=jdbc:postgresql://localhost:5500/agentmemory"
set "DB_USER=agentmemory"
set "DB_PASSWORD=agentmemory"
"D:\JDK\jdk_21\bin\java.exe" -Dfile.encoding=UTF-8 -cp "target\classes;target\lib\*" com.agentmemory.ErrorCorrectionBackfill
