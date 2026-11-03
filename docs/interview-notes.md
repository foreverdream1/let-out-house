# 项目一页纸（面试用）

> let-out-house：企业级 RAG 知识库问答平台　｜　Java 控制面 + Python 执行面

---

## 1. 一句话介绍

私有化部署的企业知识库问答系统：上传文档自动解析入库，提问时先检索知识库再让大模型作答，**回答下方展示引用来源**，全过程 Token 用量可计费、可审计、可对账。

---

## 2. 架构

```
                    ┌──────────── 前端（Vue3 + Tauri）────────────┐
                    │  登录 / 会话 / 文档管理 / 账单               │
                    └───────────────────┬─────────────────────────┘
                                        │ HTTP + SSE
                              ┌─────────▼──────────┐
                              │  gateway 8080      │ JWT 校验、租户注入、路由
                              └─────────┬──────────┘
        ┌───────────────┬───────────────┼───────────────┬────────────────┐
   user-tenant      agent-config    conversation    billing-log     task-scheduler
     8081              8082            8083            8085              8084
   登录/RBAC        Agent/知识库/文档   会话/SSE流       计费/账单      对账补偿(定时)
        │               │ 上传            │                ▲                │
        │               │ ① XADD          │ ③ 上报用量 ─────┘                │
        │        ┌──────▼──────┐          │ ② 调 SSE                        │
        │        │   Redis     │          │                                 │
        │        │   Stream    │          │                                 │
        │        └──────┬──────┘          │                                 │
        │               │ 消费             │                                 │
        │        ┌──────▼──────────────────▼──────┐                          │
        │        │  rag-service 8002（Python）     │◄───── 对账补录 ──────────┘
        │        │  解析/切片/向量化/检索           │
        │        └──────┬───────────────┬──────────┘
        │               │ 向量           │ 分片元数据
        │        ┌──────▼──────┐  ┌─────▼──────┐   ┌──────────────────┐
        │        │   Milvus    │  │   MySQL    │   │ agent-runtime    │
        │        │  向量检索    │  │  业务数据   │   │ 8001 组装LLM调用  │
        │        └─────────────┘  └────────────┘   └────────┬─────────┘
        │                                                    │
        └────────────────────────────────  智谱 glm-4-flash / bge-m3 embedding
```

**为什么 Java + Python 双栈**：Java 擅长权限、事务、配置管理（控制面）；Python 的文档解析和向量生态更成熟（执行面）。契约以 Python 侧 OpenAPI 为源，Java 侧 springdoc 仅供本地调试。

---

## 3. 四个值得讲的设计

### ① RAG 引用链路（差异化亮点）

不只是"检索+回答"，而是**引用可回溯**：

```
检索片段 → 拼进 system prompt（带 [1][2] 编号）→ 模型回答里自然带 [1]
        → end 事件返回 citations[{index, documentName, score, snippet}]
        → 写入 message.citations(JSON) → 刷新历史仍能看到来源
```

**关键取舍**：`message` 表加 `citations JSON` 列而不是拆关联表——引用只用于展示，不做关系查询，一列足够；出参用 `JsonNode` 而非 `String`，避免前端收到转义后的字符串。

### ② 上传异步化（性能）

原方案：上传 → 同步等解析（大 PDF 3~30 秒，可能顶到 300 秒超时）。
现方案：上传 → 落库(待解析) → `XADD` 投递 → **立即返回**。

- 实测：热态 **0.15 秒**返回（冷启动 1.1 秒是 JVM 预热）
- 可靠性五件套：消费组 `XGROUP`、处理完才 `XACK`、失败计数重试 3 次、超限进死信队列、`XAUTOCLAIM` 认领僵尸消息
- 失败可见：`parse_status` 三态（0 待解析 / 2 完成 / 3 失败）+ `fail_reason`

### ③ 计费的三道保险（最能体现工程思维）

计费是旁路能力，**不能影响主链路，也不能账错**：

| 问题 | 方案 |
|---|---|
| 重复上报 | 幂等键 `uk(tenant_id, message_id)` + 前置查询双保险 |
| 计费服务挂了 | 上报包 `try-catch`，只记 WARN，对话照常完成 |
| 丢了流水怎么办 | 定时对账任务：`INSERT ... SELECT` 从 `message` 表补录缺口，`ON DUPLICATE KEY UPDATE` 保证自身幂等，Redis 锁防多实例重复 |

实测结果：对账任务补回过 **54 条**历史缺口，之后每 30 分钟巡检一次，日志 `对账补偿：无缺口`。

### ④ 租户隔离（三层）

```
JWT 里的 tenantId → 网关强制覆盖 X-Tenant-Id（防前端伪造）
                 → TenantContext（ThreadLocal）
                 → MyBatis 拦截器自动拼 tenant_id / 平台表进白名单跳过
Python 侧无框架兜底：所有 SQL 手写 tenant_id，跨表 JOIN 两张表都带条件
数据库层：所有唯一键前缀 tenant_id
```

---

## 4. 踩过并解决的坑（真实性证据）

| 坑 | 现象 | 根因 |
|---|---|---|
| 向量入库成功但检索查不到 | 文档传上去、Milvus 也有数据，就是搜不到 | `update_milvus_pks` 元组解包反了 → `SET milvus_pk=chunk_id WHERE id=milvus_pk`，一行没更新，且不报错 |
| RAG 关闭时对话报错 | `UnboundLocalError: MOCK_REPLY` | 函数内给同名变量赋值，Python 视其为局部变量 |
| 内部 HTTP 调用莫名 502 | `Expecting value: line 1 column 1` | 本机代理劫持 127.0.0.1，httpx 需 `trust_env=False` |
| 上传后状态没回写 | `fail_reason` 一直是旧的 | MyBatis-Plus `updateById` 忽略 null 字段，需 `UpdateWrapper.set(x, null)` |
| 循环导入 | `cannot import _fail from partially initialized module` | service 反向依赖 router；工具函数应下沉到 clients 层 |
| 平台表查询报 Unknown column | `model_price` 无 tenant_id | 租户拦截器给所有表拼条件，需维护忽略白名单 |
| 定时任务无租户上下文 | `TenantMissingException` | 无 HTTP 请求，需 `@InterceptorIgnore(tenantLine=true)` |

---

## 5. 可量化的成果

| 指标 | 数值 |
|---|---|
| 服务数 | 6 个 Java + 2 个 Python |
| 数据库表 | 15 张（V1~V8 迁移脚本可重放） |
| 上传响应（异步） | **0.15s**（改造前同步等待 3~30s） |
| 检索命中排序 | 目标文档稳定排第一（相似度 0.69~0.77） |
| 计费对账 | 补回 54 条缺口，巡检 0 缺口 |
| 自动化验证 | `scripts/smoke-test.sh` 22 项端到端断言，一条命令全绿 |

---

## 6. 高频追问 & 回答要点

**Q：为什么不用 LangChain / LlamaIndex 一把梭？**
A：调研过。这类框架抽象层厚，出问题难定位，且我们的核心逻辑（切片策略、检索过滤、引用透传、计费）都要自己控制细节。最终只用了 pymilvus + httpx 直连，代码量更小、行为完全可预期。

**Q：为什么用 Redis Stream 而不是 RabbitMQ / Kafka？**
A：当前规模（单机、千级文档）不需要独立 broker 的运维成本；Stream 已提供消费组、ACK、pending 重投、死信能力，够用。若将来要多消费组广播或百万级堆积，再换 Kafka，接口层（投递/消费）已隔离，替换成本可控。

**Q：向量和元数据不一致怎么办？**
A：顺序上先写 MySQL 拿 `chunk_id`，再插 Milvus 并把 `milvus_pk` 回写 MySQL，检索时用 `milvus_pk` 回查正文。一致性靠三点：① 覆盖上传先按 `chunk_id` 删旧向量再重建；② 逻辑删除 `document.deleted` 会让 JOIN 失效，删了文档的向量即使残留也不会被引用；③ 冒烟测试里专门断言 `sum(milvus_pk=0)=0`。

**Q：如何保证模型不胡编？**
A：prompt 里明确要求"资料中没有的信息必须回答知识库中未找到"；实测知识库外问题会回答"未找到相关资料"而不是编造。后续可加引用校验（回答里的 `[n]` 必须存在于 citations）。

**Q：这套东西的瓶颈在哪？**
A：embedding 接口限流（硅基流动免费档）是入库瓶颈，已做分批 16 条 + 429 重试；检索侧 Milvus 单集合无压力；LLM 首字延迟是体验瓶颈，免费档 GLM-4-Flash 约 1~2 秒首字。

---

## 7. 如果继续做

1. **多租户压测与索引优化**（`document_chunk` 已有 `(tenant_id, kb_id)` 索引，需验证十万级分片下的检索 P99）
2. **混合检索**：向量 + 关键字（BM25）融合，提升专有名词命中率
3. **引用校验**：校验模型回答中的 `[n]` 是否真在 citations 里，做幻觉检测
4. **可观测**：接入 Prometheus（actuator 已开）+ 链路追踪（traceId 已贯通）
