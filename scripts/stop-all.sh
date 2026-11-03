#!/usr/bin/env bash
# =====================================================================
# 停止全部后端服务（按端口杀进程）
#   bash scripts/stop-all.sh
# =====================================================================
set -uo pipefail

C() { printf '\033[%sm%s\033[0m\n' "$1" "$2"; }
port_pid() { netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | head -1; }

C 36 "停止后端服务"
for spec in 8080:gateway 8081:user-tenant 8082:agent-config 8083:conversation \
            8084:task-scheduler 8085:billing 8001:agent-runtime 8002:rag-service; do
  port="${spec%%:*}"; name="${spec##*:}"
  pid=$(port_pid "$port")
  if [ -n "${pid:-}" ]; then
    taskkill //F //PID "$pid" >/dev/null 2>&1 || kill "$pid" 2>/dev/null
    sleep 0.3
    if [ -z "$(port_pid "$port")" ]; then printf '  \033[32m✓\033[0m %-16s %s 已停止\n' "$name" "$port"
    else printf '  \033[31m✗\033[0m %-16s %s 仍在运行（PID %s）\n' "$name" "$port" "$pid"; fi
  else
    printf '  \033[90m-\033[0m %-16s %s 未运行\n' "$name" "$port"
  fi
done
echo
echo "  注意：消费者退出后，队列里未处理的消息会保留，下次启动自动继续消费。"
