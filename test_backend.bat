@echo off
chcp 65001 >nul
cd /d "C:\Users\31936\Desktop\AgentMemory\backend"
"D:\JDK\jdk_21\bin\java.exe" -Dfile.encoding=UTF-8 -cp "target\classes;target\lib\*" com.agentmemory.AgentMemoryApplication
