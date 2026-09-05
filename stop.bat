@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ========================================
echo AgentMemory stop services
echo ========================================
echo.

echo Stopping application services...
call :stop_port 8082 "Java backend"
call :stop_port 8100 "Embedding service"
call :stop_port 5175 "Vite frontend"
call :stop_port 5173 "Legacy frontend"

echo.
echo Stopping PostgreSQL...
where docker >nul 2>&1
if errorlevel 1 goto docker_missing
docker info >nul 2>&1
if errorlevel 1 goto docker_unavailable
docker compose version >nul 2>&1
if errorlevel 1 goto use_legacy_compose
docker compose stop postgres >nul 2>&1
if errorlevel 1 goto postgres_failed
echo PostgreSQL stopped.
goto postgres_done

:use_legacy_compose
docker-compose version >nul 2>&1
if errorlevel 1 goto docker_missing
docker-compose stop postgres >nul 2>&1
if errorlevel 1 goto postgres_failed
echo PostgreSQL stopped.
goto postgres_done

:docker_missing
echo Docker or Docker Compose was not found. PostgreSQL was skipped.
goto postgres_done

:docker_unavailable
echo Docker is not running. PostgreSQL was skipped.
goto postgres_done

:postgres_failed
echo PostgreSQL could not be stopped.

:postgres_done
netstat -aon | findstr :8080 | findstr LISTENING >nul 2>&1
if errorlevel 1 goto llama_not_running

echo.
set "STOP_LLAMA="
set /p "STOP_LLAMA=llama-server is running (port 8080). Stop it too? [y/N]: "
if /i not "%STOP_LLAMA%"=="y" goto llama_kept

echo Stopping llama-server...
for /f "tokens=5" %%P in ('netstat -aon ^| findstr :8080 ^| findstr LISTENING') do call :kill_pid %%P "llama-server" 8080
goto llama_done

:llama_kept
echo llama-server was kept running.
goto llama_done

:llama_not_running
echo llama-server is not running.

:llama_done
echo.
echo ========================================
echo All AgentMemory services have been stopped.
echo ========================================
echo.
echo Run start.bat to start the services again.
echo.
pause
exit /b 0

:stop_port
set "PORT_FOUND=0"
for /f "tokens=5" %%P in ('netstat -aon ^| findstr :%~1 ^| findstr LISTENING') do call :kill_pid %%P "%~2" %~1
if "!PORT_FOUND!"=="0" echo %~2 is not running.
exit /b 0

:kill_pid
if defined KILLED_PID_%~1 exit /b 0
set "KILLED_PID_%~1=1"
set "PORT_FOUND=1"
echo Stopping %~2 PID %~1 on port %~3...
taskkill /F /PID %~1 >nul 2>&1
if errorlevel 1 echo Could not stop %~2 PID %~1.
if not errorlevel 1 echo %~2 stopped.
exit /b 0
