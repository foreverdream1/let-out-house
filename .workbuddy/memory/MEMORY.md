# let-out-house 项目长期约定

## 定位
私有化企业 RAG + Agent 知识库问答平台。Java 控制面（SpringCloud Alibaba）+ Python 执行面（FastAPI/LangGraph，3 个独立服务）。不做多 Agent 协作、可视化编排、通用工作流、K8s、应用市场。

## 工程坐标
- 根目录：`D:/maven-project/let-out-house`
- groupId：`com.lethouse`，version：`1.0.0-SNAPSHOT`，包名 `com.lethouse.*`
- 模块：`loh-common-starter`、`loh-gateway`、`loh-user-tenant`、`loh-agent-config`、`loh-conversation`、`loh-task-scheduler`、`loh-billing-log`
- 父 POM：`let-out-house-parent`

## 版本矩阵（锁定，禁止单独升级其中任意一个）
JDK21 · Spring Boot 3.2.12 · Spring Cloud 2023.0.3 · Spring Cloud Alibaba 2023.0.3.2 · MyBatis-Plus 3.5.7 · jjwt 0.12.5 · Lombok 1.18.34 · springdoc 2.3.0
（SCA 2023.0.x 线只对齐 Boot 3.2.x，混用 Boot 3.5 会 NoSuchMethodError）

## 环境
- 本机 Windows：MySQL 8.0.45(3306) 已运行；WSL 承载 Redis；Milvus 在远端 Ubuntu
- Maven 全局 settings.xml 已配阿里云 mirror，localRepository=`D:\apache-maven\apache-maven-3.9.12\mvn_repo`
- Python 3.11 由 uv 管理（不用系统 Python 3.13/3.14）
- 沙箱限制：无法调用 `wsl.exe`、PowerShell 工具；WSL 内操作需用户手动执行

## 数据库约定
- 库 `let_out_house`，utf8mb4 / utf8mb4_0900_ai_ci
- 账号分离：`loh_app`(DML) / `loh_migrate`(DDL) / `loh_ro`(只读)，禁止 root 跑业务
- `deleted` 用 BIGINT，删除时写自身 id 并参与唯一键，规避逻辑删除撞唯一约束
- 所有唯一键带 `tenant_id` 前缀
- `sys_permission.tenant_id=0` 表示平台内置权限字典，鉴权查询条件固定为 `tenant_id IN (0, current)`
- 向量只存 Milvus，MySQL 只存 `document_chunk` 元数据 + `milvus_pk`
- JDBC 连接必须带 `allowPublicKeyRetrieval=true`（caching_sha2_password）

## 契约与通信
- 契约源在 Python 侧（OpenAPI3），Java 侧 springdoc 仅本地调试
- 三 Header 强制：`X-Tenant-Id`、`X-Request-Id`、`X-Trace-Id`
- MQ 三队列：rag-doc-parse、agent-long-task、task-callback，各配死信队列

## DeepBridge 用法（位于 D:\WorkBuddy\2026-08-19-14-17-43\deepbridge）
- 只用于：契约 diff 守卫（parse/diff）、LangChain @tool 骨架生成
- 禁止用其 spring-controller/spring-service 模板生成 Java 代码（会破坏分层与 common-starter 规范）

## 接口契约基线（2026-10-21 拍板，唯一事实源）
- 范围：IM 社交模块整体砍掉，本期只做 RAG + Agent + RBAC
- 返回体：`{ code, msg, data, traceId }`，字段名是 **msg 不是 message**；**code=0 表示成功**
- 路径风格：`/api/{资源}`，**不带 /v1 前缀**
- 登录：`POST /api/auth/login`，入参 `tenantCode + username + password`（因 uk_tenant_username 允许跨租户同名）
- Header 三件套：X-Tenant-Id / X-Request-Id / X-Trace-Id
- 认证：JWT + ApiKey 双认证
- SSE 事件：`chunk`（分片）/ `end`（含 tokenUsage）/ `error`
- 对外流式：`POST /api/conversation/{conversationId}/stream`，body 传 query
- Python 内部流式：`POST /api/agent/runtime/chat/stream`
- MQ 三队列：rag-doc-parse / agent-long-task / task-callback，字段 docId+filePath+requestId+traceId
- JDK 21（不入文档里的 17）

## 前端边界（2026-10-31 用户明确要求）
- 用户有专门的前端伙伴，**我完全不修改 fronted/ 下任何代码**
- 我的职责：后端接口 + 契约文档（contracts/frontend-api.md）+ 验证
- 需要前端配合时，只输出接口契约与调用示例，不动前端文件
