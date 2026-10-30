# 前端接口文档（登录 / Agent 配置 / 会话 / 流式对话 / 知识库文档）

> 适用版本：Java 控制面 v1（网关 8080）
> 后端已完成：登录、用户信息、Agent 配置 CRUD、会话 CRUD、SSE 流式对话（含 RAG 引用来源）、知识库文档上传/列表/删除

---

## 一、通用约定

### 1.1 Base URL

```
http://localhost:8080
```

**所有请求都走网关 8080**，不要直连 8081 / 8082 / 8083。网关负责 JWT 验签、注入租户身份、跨域处理。

### 1.2 请求头

| Header | 必填 | 说明 |
|---|---|---|
| `Content-Type` | POST/PUT 必填 | `application/json` |
| `Authorization` | 除登录外全部必填 | `Bearer <token>`，注意 Bearer 后面**有一个空格** |

### 1.3 统一响应体

```json
{
  "code": 0,
  "msg": "success",
  "data": {},
  "traceId": "8da9e228660b48148d37502390fda6ce"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | **0 表示成功**，非 0 表示失败 |
| `msg` | string | 提示信息，失败时给用户看 |
| `data` | any | 业务数据，失败时为 null |
| `traceId` | string | 链路追踪号，报错时提供给后端排查 |

### 1.4 错误码

| code | 含义 | 前端处理建议 |
|---|---|---|
| 0 | 成功 | — |
| 400 | 参数校验失败 | 提示 msg |
| 401 | 未认证 / Token 失效 | 清除 token，跳登录页 |
| 403 | 权限不足 / 租户不匹配 | 提示 msg |
| 404 | 资源不存在 | 提示 msg |
| 409 | 名称重复等冲突 | 提示 msg |
| 500 | 服务异常 | 提示"系统繁忙" |

**⚠️ 特别注意：业务错误时 HTTP 状态码通常是 200，错误信息在 `body.code` 里。**

唯一的例外是**网关层拦截**（没带 token / token 无效），这时 HTTP 状态码是 401。所以前端要**同时处理两处**：

```js
try {
  const res = await axios.post('/api/agent', data)
  if (res.data.code !== 0) {
    // 业务失败：参数错、名称重复、资源不存在
    showError(res.data.msg)
  }
} catch (err) {
  // 只有两种情况会进这里：HTTP 401（网关拦截）、网络错误
  if (err.response?.status === 401) {
    localStorage.clear()
    router.push('/login')
  }
}
```

---

## 二、登录

### POST `/api/auth/login`

**这是唯一不需要 token 的接口。**

请求：

```json
{
  "tenantCode": "platform",
  "username": "admin",
  "password": "Admin@12345"
}
```

响应：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIiwidGVuYW50SWQiOjEsInRlbmFudENvZGUiOiJwbGF0Zm9ybSIsInVzZXJuYW1lIjoiYWRtaW4iLCJpYXQiOjE3OTI4NzI4MjgsImV4cCI6MTc5Mjg4MDAyOH0.xxx",
    "userId": 1,
    "tenantId": 1,
    "tenantCode": "platform",
    "username": "admin",
    "nickname": "平台管理员",
    "roleList": []
  },
  "traceId": "..."
}
```

**Token 有效期 120 分钟。** 建议存 `localStorage`，后续请求由拦截器统一带上。

测试账号：`platform` / `admin` / `Admin@12345`

---

## 三、Agent 配置

### 3.1 创建 Agent

**POST `/api/agent`**

请求：

```json
{
  "agentName": "我的助手",
  "description": "用于测试的助手",
  "systemPrompt": "你是一个专业的企业知识库助手，请严格依据资料回答。",
  "modelName": "qwen-plus",
  "ragEnable": true,
  "maxToken": 2048,
  "temperature": 0.7,
  "topK": 3,
  "knowledgeBaseIds": [1]
}
```

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `agentName` | string | ✅ | 最长 128，**同租户内不可重复** |
| `description` | string | ❌ | 最长 512 |
| `systemPrompt` | string | ✅ | 系统提示词 |
| `modelName` | string | ✅ | 如 `qwen-plus`、`qwen-max` |
| `ragEnable` | boolean | ❌ | 不传默认 `true` |
| `maxToken` | int | ❌ | 1–32000，默认 2048 |
| `temperature` | number | ❌ | 0.00–2.00，默认 0.70 |
| `topK` | int | ❌ | 1–20，默认 3 |
| `knowledgeBaseIds` | number[] | ❌ | 关联的知识库 ID，不存在会报 400 |

响应（`data` 就是新建的 agentId）：

```json
{ "code": 0, "msg": "success", "data": 4, "traceId": "..." }
```

失败示例：

```json
{ "code": 409, "msg": "Agent名称已存在", "data": null, "traceId": "..." }
{ "code": 400, "msg": "部分知识库不存在或不属于当前租户", "data": null, "traceId": "..." }
```

### 3.2 Agent 列表

**GET `/api/agent/list?pageNum=1&pageSize=10&keyword=助手`**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `pageNum` | int | ❌ | 默认 1 |
| `pageSize` | int | ❌ | 默认 10，**上限 100**（超过会被强制截断为 100） |
| `keyword` | string | ❌ | 按 `agentName` 模糊搜索 |

响应：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "list": [
      {
        "agentId": 2,
        "agentName": "测试助手A",
        "description": "联调用",
        "systemPrompt": "你是测试助手A",
        "modelName": "qwen-plus",
        "ragEnable": false,
        "maxToken": 1024,
        "temperature": 0.50,
        "topK": 3,
        "status": 1,
        "knowledgeBaseIds": null,
        "createdAt": "2026-10-25T03:47:30.659"
      }
    ],
    "total": 2,
    "pageNum": 1,
    "pageSize": 10
  },
  "traceId": "..."
}
```

**⚠️ 列表里的 `knowledgeBaseIds` 永远是 `null`** —— 列表不查关联表（避免 N+1 查询）。需要知识库绑定关系时请调详情接口。

### 3.3 Agent 详情

**GET `/api/agent/{agentId}`**

响应：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "agentId": 1,
    "agentName": "默认助手",
    "description": "通用知识库问答助手",
    "systemPrompt": "你是企业内部知识库助手...",
    "modelName": "qwen-plus",
    "ragEnable": true,
    "maxToken": 2048,
    "temperature": 0.70,
    "topK": 3,
    "status": 1,
    "knowledgeBaseIds": [1],
    "createdAt": "2026-10-24T13:46:46.873"
  },
  "traceId": "..."
}
```

**详情才有 `knowledgeBaseIds`**，且是数组（可能为空数组 `[]`）。

不存在时：

```json
{ "code": 404, "msg": "Agent不存在", "data": null, "traceId": "..." }
```

### 3.4 更新 Agent

**PUT `/api/agent/{agentId}`**

**注意：语义是全量覆盖，不是部分更新。** 所有必填字段都必须传，不传的字段会被置空。

请求体与创建完全相同。

响应：

```json
{ "code": 0, "msg": "success", "data": null, "traceId": "..." }
```

**`knowledgeBaseIds` 传 `[]` 表示解绑所有知识库。**

### 3.5 删除 Agent

**DELETE `/api/agent/{agentId}`**

响应：

```json
{ "code": 0, "msg": "success", "data": null, "traceId": "..." }
```

逻辑删除。删除后：
- 列表里看不到
- 详情返回 404
- **同名 Agent 可以重新创建**

---

## 四、会话

### 4.1 创建会话

**POST `/api/conversation`**

请求：

```json
{
  "agentId": 1,
  "title": "关于产品手册的咨询"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `agentId` | number | ✅ | 必须存在且 `status=1` |
| `title` | string | ❌ | 最长 200，不传默认 `"新对话"` |

响应（`data` 是新会话 ID）：

```json
{ "code": 0, "msg": "success", "data": 1, "traceId": "..." }
```

失败示例：

```json
{ "code": 400, "msg": "Agent不存在或已停用", "data": null, "traceId": "..." }
```

**会话归属于当前登录用户**，不需要传 userId，后端从 token 里取。

### 4.2 我的会话列表

**GET `/api/conversation/list?pageNum=1&pageSize=10`**

响应：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "list": [
      {
        "conversationId": 1,
        "agentId": 1,
        "title": "会话一",
        "messageCount": 0,
        "lastMessageAt": null,
        "createdAt": "2026-10-25T16:07:22.877"
      }
    ],
    "total": 1,
    "pageNum": 1,
    "pageSize": 10
  },
  "traceId": "..."
}
```

**只返回当前用户自己的会话**，别人的看不到（后端按 `user_id` 过滤）。

按 `conversationId` 倒序（最新创建的在前）。

### 4.3 历史消息

**GET `/api/conversation/{conversationId}/history?pageNum=1&pageSize=10`**

响应：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "list": [],
    "total": 0,
    "pageNum": 1,
    "pageSize": 10
  },
  "traceId": "..."
}
```

有数据时的单条消息结构：

```json
{
  "messageId": 112,
  "role": "assistant",
  "content": "押金将在租客租期届满并完成房屋交接后，7个工作日内无息退还至原支付账户。",
  "tokenInput": 182,
  "tokenOutput": 28,
  "createdAt": "2026-10-30T22:25:11.031",
  "citations": [
    {
      "index": 1,
      "chunkId": 1,
      "documentId": 1,
      "documentName": "房屋租赁规则说明.pdf",
      "score": 0.7742,
      "snippet": "押金退还：租客在租期届满并完成房屋交接后，押金将在 7 个工作日内无息退还至原支付账户。"
    }
  ]
}
```

`citations` 说明（**与 SSE `end` 事件的 `citations` 结构完全一致，前端可复用同一渲染组件**）：

| 取值 | 含义 | 前端处理 |
|---|---|---|
| `null` | 用户消息，或该回答没有检索（老数据 / RAG 关闭） | 不渲染引用区 |
| `[]` | 检索了但没命中 | 可展示"未检索到相关文档" |
| `[{...}]` | 命中 N 条，按相关度倒序 | 渲染引用列表，`index` 对应正文里的 `[1]` |

`role` 取值：`user`（用户提问）/ `assistant`（模型回答）/ `system`（系统提示）。

消息按 `messageId` **正序**返回（最早的在前面，符合聊天记录习惯）。

会话不存在或不属于当前用户时：

```json
{ "code": 404, "msg": "会话不存在", "data": null, "traceId": "..." }
```

**⚠️ 注意返回的是 404 不是 403** —— 刻意不暴露"这个会话存在但你没权限"。

### 4.4 删除会话

**DELETE `/api/conversation/{conversationId}`**

响应：

```json
{ "code": 0, "msg": "success", "data": null, "traceId": "..." }
```

逻辑删除。**会话下的消息记录不删除**（保留归档）。

---

## 五、建议的 axios 封装

```js
// src/api/request.js
import axios from 'axios'

const request = axios.create({
  baseURL: 'http://localhost:8080',
  timeout: 15000,
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`   // Bearer 后有空格
  }
  return config
})

request.interceptors.response.use(
  (res) => {
    if (res.data.code !== 0) {
      // 业务错误统一提示，同时 reject 让调用方能 catch
      return Promise.reject(new Error(res.data.msg))
    }
    return res.data.data          // 直接返回 data，调用方不用每次 .data.data
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.clear()
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)

export default request
```

用法：

```js
import request from '@/api/request'

// 列表
const page = await request.get('/api/agent/list', { params: { pageNum: 1, pageSize: 10 } })
console.log(page.list, page.total)

// 创建
const agentId = await request.post('/api/agent', { agentName: 'x', systemPrompt: 'y', modelName: 'qwen-plus' })
```

---

## 六、常见坑（前端必看）

| # | 坑 | 说明 |
|---|---|---|
| 1 | `Bearer` 后面少空格 | 写成 `Bearer${token}` 会导致 401，后端收到的 token 带前导空格无法解析 |
| 2 | 只看 HTTP 状态码 | 业务失败时 HTTP 是 200，必须判 `body.code` |
| 3 | 列表取 `knowledgeBaseIds` | 列表里恒为 `null`，要用就调详情 |
| 4 | PUT 只传改动的字段 | PUT 是全量覆盖，必填字段不传会被清空 |
| 5 | `pageSize` 传超大值 | 后端强制上限 100，传 1000 只会拿到 100 条，但 `total` 是对的 |
| 6 | 跨域 | 后端已配 CORS，允许 `http://localhost:1420` 和 `http://tauri.localhost`。如果用了别的端口需要告诉后端加白名单 |
| 7 | `createdAt` 格式 | 后端返回 ISO-8601 字符串（`2026-10-25T16:07:22.877`），需要自己格式化 |

---

## 七、SSE 流式对话（已实现）

```
POST /api/conversation/{conversationId}/stream
Authorization: Bearer <token>
Content-Type: application/json

{ "query": "用户的问题" }
```

响应是 `text/event-stream`，事件格式：

```
data: {"type":"chunk","content":"这","traceId":"xxx"}

data: {"type":"chunk","content":"是","traceId":"xxx"}

data: {"type":"end","fullContent":"这是一段回答","tokenUsage":{"input":182,"output":28},"citations":[...],"traceId":"xxx"}

```

### end 事件字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `fullContent` | string | 完整回答文本，可直接替换流式过程中的拼接结果 |
| `tokenUsage.input` | int | 提示词 token 数（含 RAG 上下文） |
| `tokenUsage.output` | int | 回答 token 数 |
| `citations` | array | **引用来源**，未命中知识库时为 `[]`（不是 null） |
| `traceId` | string | 链路追踪 ID |

### citations 数组元素

```json
{
  "index": 1,
  "chunkId": 1,
  "documentId": 1,
  "documentName": "房屋租赁规则说明.pdf",
  "score": 0.7742,
  "snippet": "押金退还：租客在租期届满并完成房屋交接后，押金将在 7 个工作日内无息退还至原支付账户。"
}
```

| 字段 | 说明 |
|---|---|
| `index` | 引用序号，与回答正文里的 `[1]` `[2]` 对应 |
| `score` | 余弦相似度（0~1），可用于展示"相关度"或用颜色区分 |
| `snippet` | 片段正文（已截断到 120 字），点开可展开原文 |

### error 事件

模型服务异常时：

```
data: {"type":"error","content":"模型服务异常，请稍后重试","traceId":"xxx"}
```

前端收到 error 后应停止 loading 并提示重试，**不要**把 `content` 当回答内容渲染。

前端不能用 `EventSource`（它不支持 POST 和自定义 Header），要用 `fetch` + `ReadableStream`：

```js
const resp = await fetch(`/api/conversation/${id}/stream`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  },
  body: JSON.stringify({ query, stream: true }),
})

const reader = resp.body.getReader()
const decoder = new TextDecoder()
while (true) {
  const { done, value } = await reader.read()
  if (done) break
  const text = decoder.decode(value)
  // 按 \n\n 切分事件，取 data: 后的 JSON
}
```

这块等后端做完我会补一份完整的对接文档。

---

## 八、知识库文档（已实现）

> 服务：`loh-agent-config`（8082），网关路径前缀 `/api/knowledge`。
> 所有接口都需要 `Authorization: Bearer <token>`；`X-Tenant-Id` 由网关从 token 解出并覆盖，前端不必传。
> 返回体统一 `{ code, msg, data, traceId }`，**`code = 0` 才是成功**（HTTP 状态码恒为 200）。

### 8.1 上传文档

**POST `/api/knowledge/{kbId}/documents`**

`Content-Type: multipart/form-data`，字段名固定为 `file`。

```js
const fd = new FormData()
fd.append('file', file)
const resp = await axios.post(`/api/knowledge/${kbId}/documents`, fd, {
  timeout: 300000,   // 必须放大：解析 + 切片 + 向量化是同步的，默认 15s 必超时
  // 不要手动设置 Content-Type，浏览器会自动带 boundary
})
```

限制：扩展名只允许 `pdf / docx / md / txt`；单文件 ≤ 20MB；空文件拒绝。

响应：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "id": 5,
    "docName": "车位租赁与物业费管理办法.md",
    "fileType": "md",
    "fileSize": 1887,
    "chunkCount": 2,
    "parseStatus": 2,
    "failReason": null,
    "createdAt": "2026-10-31T02:27:09.675"
  },
  "traceId": "263db38938f04d5..."
}
```

**关键：解析失败时 `code` 仍然是 0**，靠 `parseStatus` 判断：

| `parseStatus` | 含义 | `chunkCount` | `failReason` | 前端处理 |
|---|---|---|---|---|
| 0 | 待解析 | 0 | null | 显示"排队中"（当前同步实现一般不会停留在这个状态） |
| 1 | 解析中 | 0 | null | 显示 loading |
| 2 | 已完成 | > 0 | null | 提示"已入库 N 个片段" |
| 3 | 失败 | 0 | 失败原因（≤500字） | 提示 `failReason`，不要当接口错误 |

```js
if (data.parseStatus === 2) toast(`已入库 ${data.chunkCount} 个片段`)
else if (data.parseStatus === 3) toast(`解析失败：${data.failReason}`, 'error')
```

同名文件重复上传视为**覆盖**：`id` 不变，旧分片和向量会被清空后重建（不会出现重复文档）。

### 8.2 文档列表

**GET `/api/knowledge/{kbId}/documents?pageNum=1&pageSize=10`**

`pageSize` 上限 100（超过按 100 处理），按 `id` 倒序（最新的在前）。

```json
{
  "code": 0, "msg": "success",
  "data": {
    "list": [
      { "id": 5, "docName": "车位租赁与物业费管理办法.md", "fileType": "md",
        "fileSize": 1887, "chunkCount": 2, "parseStatus": 2,
        "failReason": null, "createdAt": "2026-10-31T02:27:09.675" },
      { "id": 1, "docName": "房屋租赁规则说明.pdf", "fileType": "pdf",
        "fileSize": 10240, "chunkCount": 4, "parseStatus": 2,
        "failReason": null, "createdAt": "2026-10-28T23:01:03.000" }
    ],
    "total": 2, "pageNum": 1, "pageSize": 10
  },
  "traceId": "..."
}
```

`fileSize` 是字节数，前端自己格式化成 KB/MB。知识库不存在时返回 `{"code":404,"msg":"知识库不存在"}`。

### 8.3 删除文档

**DELETE `/api/knowledge/{kbId}/documents/{docId}`**

成功返回 `{"code":0,"msg":"success","data":null}`。

删除会同时清掉：MySQL 里该文档的所有分片、Milvus 里的对应向量、并把知识库 `doc_count` 减 1。删除后该文档内容**立即不再被检索命中**。

文档不属于该知识库、或已被删除 → `{"code":404,"msg":"文档不存在"}`。

### 8.4 前端页面建议

- 上传区：点选 + 拖拽，提交时显示上传进度（`onUploadProgress`），完成后按 `parseStatus` 提示；
- 列表区：文件名 / 大小 / 片段数 / 状态徽标（待解析灰、解析中蓝、完成绿、失败红）+ 删除按钮；
- 失败行：`failReason` 用 tooltip 或行内小字展示，提供"重新上传"（同名覆盖即可）。
