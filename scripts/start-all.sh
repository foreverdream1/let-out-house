#!/usr/bin/env bash
# =====================================================================
# 一键启动全部后端服务（8 个）
#
#   bash scripts/start-all.sh          # 启动（已在跑的会跳过）
#   bash scripts/start-all.sh --force  # 先杀掉占用端口的进程再启动
#
# 日志：logs/<服务名>.log
# 停止：bash scripts/stop-all.sh
# =====================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGDIR="$ROOT/logs"
mkdir -p "$LOGDIR"

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

C() { printf '\033[%sm%s\033[0m\n' "$1" "$2"; }
port_pid() { netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | head -1; }
kill_port() { local p; p=$(port_pid "$1"); [ -n "${p:-}" ] && taskkill //F //PID "$p" >/dev/null 2>&1; }

check_env() {
  C 36 "0. 环境检查"
  java -version 2>&1 | head -1 | sed 's/^/  /'
  local py="$ROOT/python/rag-service/.venv/Scripts/python.exe"
  [ -x "$py" ] || { C 31 "  ✗ 缺少 rag-service 的 venv（先 cd python/rag-service && uv sync）"; exit 1; }
  "$py" -V | sed 's/^/  /'
  "$py" -c "
import redis,pymysql
from rag_service.config import settings
redis.Redis(host=settings.redis_host,port=settings.redis_port,password=settings.redis_password,db=settings.redis_db).ping()
pymysql.connect(host=settings.mysql_host,port=settings.mysql_port,user=settings.mysql_user,password=settings.mysql_password,database=settings.mysql_db).close()
print('  Redis / MySQL 连通 OK')" 2>/dev/null || {
    ( cd "$ROOT/python/rag-service" && "$py" -c "
import redis,pymysql
from rag_service.config import settings
redis.Redis(host=settings.redis_host,port=settings.redis_port,password=settings.redis_password,db=settings.redis_db).ping()
pymysql.connect(host=settings.mysql_host,port=settings.mysql_port,user=settings.mysql_user,password=settings.mysql_password,database=settings.mysql_db).close()
print('  Redis / MySQL 连通 OK')" ) || C 31 "  ✗ Redis 或 MySQL 连不上，检查 106.14.90.100 与 .env"
  }
}

start_py() { # name dir port module
  local name=$1 dir=$2 port=$3 module=$4
  if [ -n "$(port_pid "$port")" ]; then C 33 "  ~ $name 已在 $port 运行，跳过"; return; fi
  ( cd "$dir" && ".venv/Scripts/python.exe" -m uvicorn "$module" --host 127.0.0.1 --port "$port" > "$LOGDIR/$name.log" 2>&1 ) &
  C 32 "  ✓ $name 启动中（$port）"
}

start_java() { # name port
  local name=$1 port=$2 jar="$ROOT/$1/target/$1-1.0.0-SNAPSHOT.jar"
  if [ -n "$(port_pid "$port")" ]; then
    if [ "$FORCE" = "1" ]; then kill_port "$port"; sleep 1; C 33 "  ~ $name 占用 $port，已强杀重启"; else C 33 "  ~ $name 已在 $port 运行，跳过"; return; fi
  fi
  [ -f "$jar" ] || { C 31 "  ✗ 缺少 $jar（先 mvn -o clean package -DskipTests）"; return 1; }
  ( env -u SERVER__PORT -u SERVER_PORT java -jar "$jar" > "$LOGDIR/$name.log" 2>&1 ) &
  C 32 "  ✓ $name 启动中（$port）"
}

wait_ports() {
  local spec port n
  for spec in "$@"; do
    port="${spec%%:*}"
    n=0
    while [ $n -lt 90 ]; do
      [ -n "$(port_pid "$port")" ] && break
      sleep 1; n=$((n+1))
    done
    if [ -n "$(port_pid "$port")" ]; then
      printf '  \033[32m✓\033[0m %-16s %s 就绪\n' "${spec##*:}" "$port"
    else
      printf '  \033[31m✗\033[0m %-16s %s 未就绪（看 logs/%s.log）\n' "${spec##*:}" "$port" "${spec##*:}"
    fi
  done
}

check_env

C 36 "1. 启动 Python 执行面"
start_py rag-service       "$ROOT/python/rag-service"       8002 rag_service.main:app
start_py agent-runtime-api "$ROOT/python/agent-runtime-api" 8001 app.main:app

C 36 "2. 启动 Java 控制面"
start_java loh-user-tenant   8081
start_java loh-agent-config  8082
start_java loh-conversation  8083
start_java loh-billing-log   8085
start_java loh-task-scheduler 8084
start_java loh-gateway       8080

C 36 "3. 等待就绪"
wait_ports 8002:rag-service 8001:agent-runtime 8081:user-tenant 8082:agent-config \
           8083:conversation 8085:billing 8084:task-scheduler 8080:gateway

echo
C 36 "4. 健康检查"
CURL() { env -u http_proxy -u HTTP_PROXY curl -s -m 5 "$@"; }
printf '  rag-service   : %s\n' "$(CURL http://127.0.0.1:8002/health)"
printf '  agent-runtime : %s\n' "$(CURL http://127.0.0.1:8001/health)"
LOGIN=$(CURL -X POST http://127.0.0.1:8080/api/auth/login -H "Content-Type: application/json" -H "X-Tenant-Id: 1" \
  -d '{"tenantCode":"platform","username":"admin","password":"Admin@12345"}')
if echo "$LOGIN" | grep -q '"code":0'; then
  printf '  网关登录      : \033[32m成功\033[0m\n'
else
  printf '  网关登录      : \033[31m失败\033[0m %s\n' "$LOGIN"
fi

echo
C 32 "全部就绪。"
echo "  日志: $LOGDIR/"
echo "  停止: bash scripts/stop-all.sh"
echo "  验证: bash scripts/smoke-test.sh"
echo "  前端: cd fronted && pnpm dev   → http://localhost:1420"
