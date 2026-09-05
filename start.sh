#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 读取简单的 KEY=VALUE 配置；不 source .env，避免 Windows 路径中的反斜杠被 Shell 修改。
load_env_file() {
    local env_file="$1"
    [[ -f "$env_file" ]] || return 0
    while IFS='=' read -r key value; do
        [[ -z "$key" || "$key" == \#* ]] && continue
        key="${key%%[[:space:]]*}"
        [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
        value="${value%$'\r'}"
        value="${value#\"}"
        value="${value%\"}"
        if [[ -z "${!key+x}" ]]; then
            export "$key=$value"
        fi
    done < "$env_file"
}

load_env_file "$SCRIPT_DIR/.env"

BACKEND_PORT="${BACKEND_PORT:-8082}"
FRONTEND_PORT="${FRONTEND_PORT:-5175}"
EMBEDDING_PORT="${EMBEDDING_PORT:-8100}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-agentmemory}"
export DATABASE_PASSWORD
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-$DATABASE_PASSWORD}"

if ! command -v docker >/dev/null 2>&1; then
    echo "[ERROR] Docker 未安装或不在 PATH 中。"
    exit 1
fi
if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
else
    echo "[ERROR] 未找到 Docker Compose。"
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] 未找到 Java，请安装 JDK 21+ 并配置 PATH。"
    exit 1
fi
if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
    echo "[ERROR] 未找到 Python 3。"
    exit 1
fi
if ! command -v npm >/dev/null 2>&1; then
    echo "[ERROR] 未找到 npm，请安装 Node.js 18+。"
    exit 1
fi

PYTHON_BIN="$(command -v python3 || command -v python)"
BACKEND_PID=""
EMBEDDING_PID=""
FRONTEND_PID=""

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    echo
    echo "正在停止 AgentMemory 子进程..."
    for pid in "$FRONTEND_PID" "$EMBEDDING_PID" "$BACKEND_PID"; do
        [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
    done
    wait 2>/dev/null || true
    exit "$status"
}
trap cleanup EXIT INT TERM

wait_for_postgres() {
    local elapsed=0
    until docker exec agentmemory-db pg_isready -U agentmemory -d agentmemory >/dev/null 2>&1; do
        if (( elapsed >= 30 )); then
            echo "[ERROR] PostgreSQL 30 秒内未就绪。"
            "${COMPOSE[@]} logs --tail=50 postgres || true"
            return 1
        fi
        sleep 2
        elapsed=$((elapsed + 2))
        echo "[INFO] 等待 PostgreSQL... ${elapsed}s"
    done
}

if [[ ! -d "$SCRIPT_DIR/backend/target/classes" || ! -d "$SCRIPT_DIR/backend/target/lib" ]]; then
    echo "[ERROR] 未找到后端构建产物。请先执行："
    echo "        cd backend && mvn clean package -DskipTests"
    exit 1
fi
if [[ ! -d "$SCRIPT_DIR/frontend/node_modules" ]]; then
    echo "[ERROR] 未找到前端依赖。请先执行："
    echo "        cd frontend && npm install"
    exit 1
fi

printf '%s\n' "========================================" "   AgentMemory 一键启动脚本" "========================================"
echo "[1/4] 启动 PostgreSQL..."
"${COMPOSE[@]}" up -d postgres
wait_for_postgres

echo "[2/4] 启动 Embedding 服务 (端口 ${EMBEDDING_PORT})..."
(
    cd "$SCRIPT_DIR/embedding_service"
    EMBED_PORT="$EMBEDDING_PORT" "$PYTHON_BIN" embed_server.py
) &
EMBEDDING_PID=$!

echo "[3/4] 启动 Java 后端 (端口 ${BACKEND_PORT})..."
(
    cd "$SCRIPT_DIR/backend"
    java -Dfile.encoding=UTF-8 -cp "target/classes:target/lib/*" com.agentmemory.AgentMemoryApplication
) &
BACKEND_PID=$!

echo "[4/4] 启动 Vue 前端 (端口 ${FRONTEND_PORT})..."
(
    cd "$SCRIPT_DIR/frontend"
    npm run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT"
) &
FRONTEND_PID=$!

printf '%s\n' "" "========================================" "   启动完成" "========================================"
echo "前端:     http://localhost:${FRONTEND_PORT}"
echo "后端 API: http://localhost:${BACKEND_PORT}"
echo "Embedding: http://localhost:${EMBEDDING_PORT}"
echo ""
echo "按 Ctrl+C 停止前端、后端和 Embedding 服务；数据库容器保持运行。"
echo "Windows 用户请运行 stop.bat 停止服务。"
echo ""
echo "进程 PID: backend=${BACKEND_PID}, embedding=${EMBEDDING_PID}, frontend=${FRONTEND_PID}"

wait
