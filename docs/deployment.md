# 部署与启动手册

> 适用版本：1.0.0-SNAPSHOT　｜　最后验证：2026-11-02

---

## 一、端口与进程清单

### Java 控制面（Spring Boot 3.2.12 / JDK 21）

| 模块 | 端口 | 职责 | 备注 |
|---|---|---|---|
| `loh-gateway` | 8080 | 统一入口、JWT 鉴权、租户注入 | **最后启动** |
| `loh-user-tenant` | 8081 | 登录、用户、租户、RBAC | |
| `loh-agent-config` | 8082 | Agent 配置、知识库、**文档上传** | 依赖 Redis（投递解析任务） |
| `loh-conversation` | 8083 | 会话、消息、SSE 流式对话 | 依赖 agent-runtime 与 billing |
| `loh-task-scheduler` | 8084 | 定时任务（计费对账补偿） | 依赖 Redis 做分布式锁 |
| `loh-billing-log` | 8085 | Token 计费、账单查询 | |

### Python 执行面（Python 3.11 / uv）

| 服务 | 端口 | 职责 |
|---|---|---|
| `agent-runtime-api` | 8001 | 组装 prompt、调用 LLM、SSE 输出、引用透传 |
| `rag-service` | 8002 | 检索、文档解析入库、**Redis Stream 消费者** |

### 中间件

| 组件 | 地址 | 用途 |
|---|---|---|
| MySQL 8.0 | `106.14.90.100:3306` | 业务库 `let_out_house` |
| Redis | `106.14.90.100:6379` | 缓存 + 文档解析队列（Stream） |
| Milvus | 远端 Ubuntu | 向量库，集合 `kb_1_demo` 等 |
| Embedding | 硅基流动 `BAAI/bge-m3`（1024 维） | 文本向量化 |
| LLM | 智谱 `glm-4-flash`（OpenAI 兼容） | 对话生成 |

---

## 二、启动顺序

### 方式 A：一键脚本（推荐）

```bash
bash scripts/start-all.sh          # 起 8 个服务，已在跑的自动跳过
bash scripts/start-all.sh --force  # 端口被占时先杀掉再起
bash scripts/stop-all.sh           # 全部停止
```

- 日志统一写到 `logs/<服务名>.log`（如 `logs/rag-service.log`）
- 脚本会先做环境检查（Java/Python 版本、Redis/MySQL 连通）
- 启动完成后自动打印 8 个服务就绪状态 + `/health` + 网关登录验证

> 注意：服务是当前终端派生的后台进程，**关掉那个终端可能会一起退出**。要长期运行请单独开一个终端挂着，或用 `nohup`。

### 方式 B：手动逐个启动（排查问题时用）



> 顺序有讲究：Python 两个先起（Java 启动即要连），gateway 最后起（对外入口）。

```bash
# ① rag-service（必须先起：conversation 的检索、agent-config 的队列消费都靠它）
cd python/rag-service && .venv/Scripts/python.exe -m uvicorn rag_service.main:app --port 8002

# ② agent-runtime-api
cd python/agent-runtime-api && .venv/Scripts/python.exe -m uvicorn app.main:app --port 8001

# ③~⑦ Java 服务（任一顺序）
java -jar loh-user-tenant/target/loh-user-tenant-1.0.0-SNAPSHOT.jar
java -jar loh-agent-config/target/loh-agent-config-1.0.0-SNAPSHOT.jar
java -jar loh-conversation/target/loh-conversation-1.0.0-SNAPSHOT.jar
java -jar loh-billing-log/target/loh-billing-log-1.0.0-SNAPSHOT.jar
java -jar loh-task-scheduler/target/loh-task-scheduler-1.0.0-SNAPSHOT.jar

# ⑧ gateway（最后）
java -jar loh-gateway/target/loh-gateway-1.0.0-SNAPSHOT.jar
```

### 一键验证（推荐每次改动后跑）

```bash
bash scripts/smoke-test.sh --with-services   # 自动起 8 个服务 + 跑 22 项端到端断言 + 关服务
bash scripts/smoke-test.sh                   # 服务已在跑，只跑用例
bash scripts/smoke-test.sh --skip-llm        # 不调真实 LLM（省额度/无网）
```

全绿输出 `通过 22 / 失败 0`，退出码 0；任一失败退出码 1 并列出失败用例。

---

## 三、环境变量

### Java 侧（可用环境变量覆盖 yml 默认值）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LOH_DB_USER` / `LOH_DB_PASSWORD` | `loh_app` / `666666` | 业务 DML 账号 |
| `LOH_REDIS_PASSWORD` | `666666` | Redis 密码 |
| `LOH_STORAGE_DIR` | `D:/maven-project/let-out-house/data/documents` | 上传文件落盘目录（Java 与 rag-service 需同机共享） |

**注意**：本机 shell 若设置了 `SERVER_PORT` / `SERVER__PORT`，会覆盖各服务的 `server.port` 导致端口串台。启动前建议 `env -u SERVER__PORT -u SERVER_PORT java -jar ...`（冒烟脚本已处理）。

### Python 侧（`.env` 文件，`LOH_` 前缀 + 小写字段名）

`python/rag-service/.env`：

```ini
LOH_MYSQL_USER=loh_ro            # 只读：检索回查正文
LOH_MYSQL_PASSWORD=666666
LOH_MYSQL_WRITE_USER=loh_app     # 写：分片落库、状态回写
LOH_MYSQL_WRITE_PASSWORD=666666
LOH_REDIS_HOST=106.14.90.100
LOH_REDIS_PORT=6379
LOH_REDIS_PASSWORD=666666
LOH_STREAM_KEY=loh:stream:rag-doc-parse
LOH_CONSUMER_GROUP=rag-workers
LOH_CONSUMER_MAX_RETRY=3
```

`python/agent-runtime-api/.env`：

```ini
LOH_LLM_API_KEY=<智谱 key>
LOH_LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
LOH_LLM_MODEL=glm-4-flash        # 注意：glm-4.7-flash 实测被限流(429/1302)
LOH_RAG_SERVICE_BASE_URL=http://127.0.0.1:8002
```

---

## 四、数据库初始化

按序执行 `sql/V1 ~ V8`，执行账号用 `loh_migrate`（DDL 专用）：

| 脚本 | 内容 |
|---|---|
| `V1__init_tenant_rbac.sql` | 租户、用户、角色、权限 |
| `V2__align_permission_paths.sql` | 权限路径对齐 |
| `V3__init_kb_agent.sql` | 知识库、Agent 配置、Agent-知识库关联 |
| `V4__init_conversation.sql` | 会话、消息 |
| `V5__document_chunk.sql` | 文档、分片 |
| `V6__embedding_model_bge_m3.sql` | embedding 模型元数据 |
| `V7__message_citations.sql` | `message.citations JSON` |
| `V8__billing.sql` | `model_price`、`token_usage_log` + 单价初始化 |

三个账号分工（已在 V1 建立）：

- `loh_app`：业务 DML（Java 与 rag-service 写操作都走它）
- `loh_migrate`：仅 DDL
- `loh_ro`：只读（rag-service 检索回查）

---

## 五、构建

```bash
# 全量安装到本地仓库
mvn -o clean install -DskipTests

# 只打某个服务
mvn -o clean package -DskipTests -pl loh-agent-config

# Python 依赖（uv 管理）
cd python/rag-service && uv sync
cd python/agent-runtime-api && uv sync
```

**坑**：`mvn clean` 会删掉 `target/*.jar`，跑服务前必须 `package`（否则脚本报"缺少 jar"）。

---

## 六、常见问题排查

| 现象 | 原因 | 处理 |
|---|---|---|
| Python 服务报 `ModuleNotFoundError: rag_service` | 工作目录不对 | 必须 `cd python/rag-service` 再启 uvicorn |
| 上传后 `parseStatus` 一直 0 | rag-service 没起 / 消费者未启动 | 看 rag-service 日志有无 `[consumer]已启动`；`XPENDING` 查积压 |
| 检索命中不到刚上传的文档 | `document_chunk.milvus_pk` 为 0，回查失败 | 查 `select sum(milvus_pk=0)`，需重跑解析 |
| LLM 报 429 code=1302 | 智谱免费档限流 | 换 `glm-4-flash`，或错峰 |
| 内部 HTTP 调用报 `Expecting value: line 1 column 1` | 本机代理劫持了 127.0.0.1 | httpx 加 `trust_env=False`；命令行加 `env -u http_proxy` |
| `Unknown column 'tenant_id'` | 平台级表（如 `model_price`）被租户拦截器拼条件 | 加入 `CustomTenantLineHandler.IGNORE_TABLES` |
| `TenantMissingException`（定时任务） | 无 HTTP 上下文 | Mapper 方法加 `@InterceptorIgnore(tenantLine="true")` |
| 定时任务不触发 | 缺 `@EnableScheduling` | 检查启动类，且日志看 `scheduling-1` 线程 |
| 网关返回 404（新路径） | yml 改了但 jar 没重打 | 重新 `package` 网关 |

---

## 七、目录约定

```
contracts/          # 前端接口契约（唯一事实源，改接口必须同步这里）
sql/                # 按版本号顺序的迁移脚本
scripts/            # 冒烟测试等开发脚本
data/documents/     # 上传文件落盘（Java 与 Python 共享）
python/rag-service/         # 检索 + 入库 + 队列消费
python/agent-runtime-api/   # LLM 调用 + SSE
loh-*/              # 六个 Java 服务 + common-starter
```
