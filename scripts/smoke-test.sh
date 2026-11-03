#!/usr/bin/env bash
# =====================================================================
# let-out-house 冒烟测试（端到端）
#
# 用法：
#   bash scripts/smoke-test.sh                 # 假设服务已启动，只跑用例
#   bash scripts/smoke-test.sh --with-services # 自动拉起 8 个服务，测完关掉
#   bash scripts/smoke-test.sh --skip-llm      # 跳过真实 LLM 对话（省钱/无网时用）
#
# 覆盖链路：
#   登录 → 上传文档(异步) → 消费者解析入库 → 检索命中 → 会话流式回答(引用+用量)
#   → 计费落库 → 对账补偿 → 删除文档 → 数据一致性核对
#
# 退出码：0 全绿 / 1 有用例失败
# 依赖：curl、mysql 客户端、java、python(rag-service/.venv)、redis-cli 可不用
# =====================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="$ROOT/python/rag-service/.venv/Scripts/python.exe"
[ -x "$PY" ] || PY="$ROOT/python/rag-service/.venv/bin/python"

# 本机若有代理，必须脱掉，否则 127.0.0.1 的请求会被代理劫持
CURL() { env -u http_proxy -u HTTP_PROXY -u https_proxy -u HTTPS_PROXY curl -s "$@"; }

MYSQL="mysql --default-character-set=utf8mb4 -h 106.14.90.100 -u loh_app -p666666 -D let_out_house -N -e"
GW="http://127.0.0.1:8080"
RAG="http://127.0.0.1:8002"

WITH_SERVICES=0
SKIP_LLM=0
for a in "$@"; do
  case "$a" in
    --with-services) WITH_SERVICES=1 ;;
    --skip-llm) SKIP_LLM=1 ;;
    *) echo "未知参数: $a"; exit 2 ;;
  esac
done

PASS=0; FAIL=0; FAILED_CASES=()
ok()   { PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); printf '  \033[31m✗\033[0m %s\n' "$1"; }
check(){ if [ "$1" = "$2" ]; then ok "$3"; else bad "$3（期望=$2 实际=$1）"; fi; }
info() { printf '\n\033[36m▶ %s\033[0m\n' "$1"; }

# ---------- 工具 ----------
port_pid() { netstat -ano 2>/dev/null | grep LISTENING | grep ":$1 " | awk '{print $5}' | head -1; }
kill_port() { local p; p=$(port_pid "$1"); [ -n "$p" ] && taskkill //F //PID "$p" >/dev/null 2>&1 || [ -n "$p" ] && kill "$p" 2>/dev/null; }
wait_port() {
  local port=$1 name=$2 n=0
  while [ $n -lt 60 ]; do
    [ -n "$(port_pid "$port")" ] && return 0
    sleep 1; n=$((n+1))
  done
  echo "  ✗ $name 未在端口 $port 就绪"; return 1
}
jq_py() { "$PY" -c "import sys,json;d=json.load(sys.stdin);print(eval(sys.argv[1]))" "$1" 2>/dev/null; }
# rag_service 是相对 import，必须在 python/rag-service 目录下执行
PYRAG() { ( cd "$ROOT/python/rag-service" && "$PY" "$@" ); }

STARTED_PIDS=()
cleanup() {
  info "清理"
  for p in "${STARTED_PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done
  if [ "$WITH_SERVICES" = "1" ]; then
    for port in 8080 8081 8082 8083 8084 8085 8001 8002; do kill_port "$port"; done
  fi
  # 删除本次测试上传的文档
  if [ -n "${TEST_DOC_ID:-}" ]; then
    $MYSQL "delete from document_chunk where document_id=$TEST_DOC_ID" >/dev/null 2>&1
    $MYSQL "delete from document where id=$TEST_DOC_ID" >/dev/null 2>&1
  fi
  rm -f "${TEST_FILE:-}" 2>/dev/null
  echo "  已清理测试数据"
}
trap cleanup EXIT

# =====================================================================
info "0. 环境预检"
# =====================================================================
DB_OK=$($MYSQL "select 1;" 2>/dev/null | tr -d ' \r')
check "${DB_OK:-fail}" "1" "MySQL 连通"

REDIS_OK=$(PYRAG -c "
import redis
from rag_service.config import settings
r=redis.Redis(host=settings.redis_host,port=settings.redis_port,password=settings.redis_password,db=settings.redis_db)
print('OK' if r.ping() else 'FAIL')" 2>&1 | tail -1)
check "${REDIS_OK:-FAIL}" "OK" "Redis 连通"

MILVUS_OK=$(PYRAG -c "
from pymilvus import MilvusClient
from rag_service.config import settings
c=MilvusClient(uri=settings.milvus_uri, token=settings.milvus_token)
print('OK' if c.has_collection('kb_1_demo') else 'NO_COLL')
c.close()" 2>&1 | tail -1)
check "${MILVUS_OK:-FAIL}" "OK" "Milvus 连通且集合存在"

# =====================================================================
if [ "$WITH_SERVICES" = "1" ]; then
info "1. 启动服务"
# =====================================================================
  for j in loh-user-tenant loh-agent-config loh-conversation loh-gateway loh-billing-log loh-task-scheduler; do
    JAR="$ROOT/$j/target/$j-1.0.0-SNAPSHOT.jar"
    if [ ! -f "$JAR" ]; then echo "  ✗ 缺少 $JAR（先执行 mvn -q package -DskipTests）"; exit 1; fi
  done
  ( cd "$ROOT/python/rag-service" && ".venv/Scripts/python.exe" -m uvicorn rag_service.main:app --host 127.0.0.1 --port 8002 > /tmp/smk_rag.log 2>&1 ) &
  ( cd "$ROOT/python/agent-runtime-api" && ".venv/Scripts/python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8001 > /tmp/smk_agent.log 2>&1 ) &
  for j in loh-user-tenant loh-agent-config loh-conversation loh-billing-log loh-task-scheduler loh-gateway; do
    ( env -u SERVER__PORT -u SERVER_PORT java -jar "$ROOT/$j/target/$j-1.0.0-SNAPSHOT.jar" > "/tmp/smk_$j.log" 2>&1 ) &
    STARTED_PIDS+=($!)
  done
  for spec in "8002:rag-service" "8001:agent-runtime" "8081:user-tenant" "8082:agent-config" "8083:conversation" "8085:billing" "8084:task-scheduler" "8080:gateway"; do
    wait_port "${spec%%:*}" "${spec##*:}"
  done
  ok "8 个服务全部就绪"
else
  info "1. 服务检查（模式：复用已启动的服务）"
  for spec in "8002:rag-service" "8001:agent-runtime" "8081:user-tenant" "8082:agent-config" "8083:conversation" "8085:billing" "8080:gateway"; do
    if [ -n "$(port_pid "${spec%%:*}")" ]; then ok "${spec##*:} 在跑"; else bad "${spec##*:} 未启动（端口 ${spec%%:*}）"; fi
  done
  if [ "$FAIL" -gt 0 ]; then echo "  提示：用 --with-services 自动拉起"; fi
fi

# =====================================================================
info "2. 登录"
# =====================================================================
TOKEN=$(CURL -X POST "$GW/api/auth/login" -H "Content-Type: application/json" -H "X-Tenant-Id: 1" \
  -d '{"tenantCode":"platform","username":"admin","password":"Admin@12345"}' | jq_py "d['data']['token']")
if [ -n "${TOKEN:-}" ]; then ok "登录成功（token 长度 ${#TOKEN}）"; else bad "登录失败"; echo; echo "后续用例无法继续"; exit 1; fi
AUTH=(-H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: 1")

# =====================================================================
info "3. 上传文档（异步投递）"
# =====================================================================
STAMP=$(date +%H%M%S)
TEST_FILE="/tmp/smoke_${STAMP}.md"
cat > "$TEST_FILE" << 'MD'
# 冒烟测试文档

第一条 快递柜使用。业主使用小区快递柜存放包裹，超过二十四小时未取的，按每日一元收取占用费。

第二条 电梯使用。装修材料搬运须使用货梯，禁止使用客梯运输建筑材料，违反者按每次二百元收费。
MD
UP=$(CURL -X POST "$GW/api/knowledge/1/documents" "${AUTH[@]}" -F "file=@$TEST_FILE" -w '|%{time_total}')
BODY="${UP%|*}"; ELAPSED="${UP##*|}"
TEST_DOC_ID=$(echo "$BODY" | jq_py "d['data']['id']")
UP_STATUS=$(echo "$BODY" | jq_py "d['data']['parseStatus']")
check "$UP_STATUS" "0" "上传后 parseStatus=0（异步，未阻塞）"
if awk "BEGIN{exit !($ELAPSED < 3)}"; then ok "上传耗时 ${ELAPSED}s < 3s"; else bad "上传耗时 ${ELAPSED}s 过慢"; fi
[ -n "${TEST_DOC_ID:-}" ] && ok "文档已创建 docId=$TEST_DOC_ID" || bad "未获取 docId"

# =====================================================================
info "4. 消费者解析入库"
# =====================================================================
PARSE=""; CHUNKS=0
for i in $(seq 1 30); do
  ROW=$($MYSQL "select concat(parse_status,'/',chunk_count) from document where id=$TEST_DOC_ID;" 2>/dev/null | tr -d ' \r')
  PARSE="${ROW%%/*}"; CHUNKS="${ROW##*/}"
  [ "$PARSE" = "2" ] && break
  sleep 1
done
check "$PARSE" "2" "parse_status 变 2（消费者已处理）"
if [ "${CHUNKS:-0}" -gt 0 ] 2>/dev/null; then ok "chunk_count=$CHUNKS"; else bad "chunk_count 应大于 0"; fi

# =====================================================================
info "5. 检索命中"
# =====================================================================
HITS=$(CURL -X POST "$RAG/api/rag/search" -H "Content-Type: application/json" -H "X-Tenant-Id: 1" \
  -d '{"kbIds":[1],"query":"快递柜超时怎么收费","topK":3}')
HIT_NAMES=$(echo "$HITS" | jq_py "','.join(c['documentName'] for c in d['data']['chunks'])")
if echo "$HIT_NAMES" | grep -q "smoke_${STAMP}.md"; then ok "新文档被检索命中"; else bad "检索未命中新文档（命中：$HIT_NAMES）"; fi

# =====================================================================
if [ "$SKIP_LLM" = "0" ]; then
info "6. 会话流式回答（真实 LLM）"
# =====================================================================
  CONV=$(CURL -X POST "$GW/api/conversation" -H "Content-Type: application/json" "${AUTH[@]}" \
    -d '{"agentId":1,"title":"smoke 测试"}' | jq_py "d['data']")
  if [ -n "${CONV:-}" ]; then ok "创建会话 conversationId=$CONV"; else bad "创建会话失败"; fi
  CURL -N -m 150 -X POST "$GW/api/conversation/$CONV/stream" -H "Content-Type: application/json" "${AUTH[@]}" \
    -d '{"query":"快递柜超时怎么收费"}' > /tmp/smk_sse.txt 2>&1
  END_LINE=$(grep '"type": "end"' /tmp/smk_sse.txt | head -1 | sed 's/^data: //')
  if [ -n "$END_LINE" ]; then
    ok "收到 end 事件"
    FULL_LEN=$(echo "$END_LINE" | jq_py "len(d.get('fullContent') or '')")
    if [ "${FULL_LEN:-0}" -gt 5 ]; then ok "回答长度 $FULL_LEN 字"; else bad "回答为空"; fi
    CITE_N=$(echo "$END_LINE" | jq_py "len(d.get('citations') or [])")
    if [ "${CITE_N:-0}" -gt 0 ]; then ok "引用 $CITE_N 条"; else bad "引用为空（期望命中知识库）"; fi
    TOK_IN=$(echo "$END_LINE" | jq_py "(d.get('tokenUsage') or {}).get('input',0)")
    if [ "${TOK_IN:-0}" -gt 0 ]; then ok "tokenUsage.input=$TOK_IN"; else bad "tokenUsage 缺失"; fi
  else
    bad "未收到 end 事件"; head -3 /tmp/smk_sse.txt
  fi

info "7. 计费流水"
# =====================================================================
  MSG_ID=$($MYSQL "select max(id) from message where conversation_id=$CONV and role='assistant';" 2>/dev/null | tr -d ' \r')
  sleep 1
  LOG_N=$($MYSQL "select count(*) from token_usage_log where message_id=${MSG_ID:-0};" 2>/dev/null | tr -d ' \r')
  check "${LOG_N:-0}" "1" "计费流水已落库（messageId=${MSG_ID:-?}）"

  info "8. 对账补偿"
  RECON=$(CURL -X POST "http://127.0.0.1:8084/api/task/reconcile?days=7&limit=500" -H "X-Tenant-Id: 1" | jq_py "d['data']")
  check "${RECON:-fail}" "0" "对账无缺口（返回 0）"
else
  info "6-8. 已跳过 LLM / 计费 / 对账（--skip-llm）"
fi

# =====================================================================
info "9. 删除文档"
# =====================================================================
DEL=$(CURL -X DELETE "$GW/api/knowledge/1/documents/$TEST_DOC_ID" "${AUTH[@]}" | jq_py "d['code']")
check "${DEL:-fail}" "0" "删除接口返回成功"
LEFT=$($MYSQL "select count(*) from document_chunk where document_id=$TEST_DOC_ID;" 2>/dev/null | tr -d ' \r')
check "${LEFT:-x}" "0" "分片已清空"
TEST_DOC_ID=""   # 已删，cleanup 不用再删

info "10. 数据一致性"
# =====================================================================
DOCS=$($MYSQL "select count(*) from document where deleted=0;" 2>/dev/null | tr -d ' \r')
DOC_COUNT=$($MYSQL "select doc_count from knowledge_base where id=1;" 2>/dev/null | tr -d ' \r')
check "${DOC_COUNT:-x}" "${DOCS:-y}" "kb.doc_count 与真实文档数一致（$DOC_COUNT）"
GAP=$($MYSQL "select count(*) from message m left join token_usage_log u on u.tenant_id=m.tenant_id and u.message_id=m.id
  where m.role='assistant' and m.msg_status=1 and (m.token_input>0 or m.token_output>0) and u.id is null;" 2>/dev/null | tr -d ' \r')
check "${GAP:-x}" "0" "计费流水无缺口"

# =====================================================================
printf '\n\033[1m================ 结果 ================\033[0m\n'
printf '  通过 \033[32m%d\033[0m / 失败 \033[31m%d\033[0m\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf '  失败用例：\n'
  for c in "${FAILED_CASES[@]}"; do printf '    - %s\n' "$c"; done
  exit 1
fi
printf '  \033[32m全部通过 ✅\033[0m\n'
exit 0
