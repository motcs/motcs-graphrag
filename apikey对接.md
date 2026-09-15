# API Key 对接全流程文档

> 适用服务：motcs-graphrag（GraphRAG 知识库问答服务，百度千帆 AI）
> 本文档描述 **API Key 从申请、分发到对接调用的完整流程**，供超管与调用方（第三方系统/小程序）对照使用。

---

## 1. 总体流程

```
超管登录 ──▶ 生成 API Key（申请） ──▶ 人工分发（线下/群聊交付明文） ──▶ 调用方对接
（/auth/v1/login）   （/auth/v1/api-keys）                         （/keys/v1/**）
                                                                        │
                                                                        ▼
                                                          携带 Key 调对话/历史接口
                                                          （X-API-Key / Bearer）
```

| 阶段 | 谁来做 | 接口 | 说明 |
|------|--------|------|------|
| ① 登录 | 超管 | `POST /auth/v1/login` | Basic Auth，换取 `x-token`（管理端专用，调用方不需要） |
| ② 申请 | 超管 | `POST /auth/v1/api-keys` | 备注/租户/系统类型必填，明文 Key **仅返回一次** |
| ③ 分发 | 超管 | —（人工） | 线下/IM 交付 `sk-...` 明文，建议同时告知绑定的租户与系统类型 |
| ④ 对接 | 调用方 | `POST /keys/v1/chat` 等 | 请求头携带 Key，即可对话/查历史，**无需登录** |

**权限边界（核心）**：API Key **只能访问 `/keys/v1/**` 专属接口**（对话 + 历史查询/删除），
无法访问 `/documents/v1/**`、`/auth/v1/**` 等管理/业务接口（返回 403）；
反之，登录用户也无法访问 `/keys/v1/**`（返回 403）。两套认证通道完全隔离。

---

## 1.5 部署前置：使用监控表（仅首次部署）

API Key 使用监控依赖一张 MySQL 表 `api_key_usage`（首次部署时按实体自动建表），功能：

- 记录每次 `/keys/v1/chat` 对话消耗的 token：输入（prompt）、输出（completion）、总计（total）
- 记录调用方用户编码、会话 ID、使用的模型、调用时间
- 管理端按 Key 查看汇总（调用次数 + token 总量）与每次对话明细（时间倒序分页）
- Key 停用期间不产生新记录，历史记录保留

## 2. 第一步：超管登录（管理端）

> 仅超管需要登录；调用方（持 Key 方）**不需要**登录，直接携带 Key 调接口即可。

```bash
# Basic Auth：用户名:密码 做 Base64
curl -X POST https://ai.xxxx.com/api/auth/v1/login \
  -H "Authorization: Basic ........"
```

登录成功返回（`token` 即后续管理接口的 `x-token`，有效期 2 小时，每次调用自动续期）：

```json
{ "token": "0f8fad5b-d9cb-469f-a165-70867728950e", "expires": 7200, "lastAccessTime": 1757390400 }
```

同时响应头会下发 `Set-Cookie: XSRF-TOKEN=...`（httpOnly=false，前端可读）。
管理端后续 **POST** 请求需要带两个头：

| 请求头 | 值 | 用途 |
|--------|-----|------|
| `x-token` | 登录返回的 token | 登录态认证 |
| `X-CSRF-TOKEN` | cookie 里的 `XSRF-TOKEN` 值 | POST 防跨站校验（GET 不需要） |

---

## 3. 第二步：申请 API Key（生成）

### 3.1 界面方式

登录管理界面 → 左侧导航「API Key 管理」（文件管理下方、知识图谱上方）→ 填写 **备注（必填）/ 租户编码（必填）/ 系统类型（必填）** → 生成。

### 3.2 接口方式

```bash
# 注意：需携带管理端 x-token + X-CSRF-TOKEN（POST 校验 CSRF）
curl -X POST https://ai.xxxx.com/api/auth/v1/api-keys \
  -H "Content-Type: application/json" \
  -H "x-token: <上一步登录返回的token>" \
  -H "X-CSRF-TOKEN: <XSRF-TOKEN cookie值>" \
  -d '{"name":"人大小程序对接","tenantCode":"410725","systemType":"congress"}'
```

**请求字段（均必填，缺一返回 400）**：

| 字段 | 必填 | 说明 |
|------|------|------|
| `name` | ✅ | 用途备注（如对接方名称/系统名），**不填不允许创建** |
| `tenantCode` | ✅ | 绑定的租户编码（对话/上传文档的归属），**禁止填 `0`**（0 是超管全局租户） |
| `systemType` | ✅ | 绑定的系统类型（如 `congress` / `other`），与租户共同决定检索的知识库范围 |

**响应（明文 Key 仅此一次）**：

```json
{
  "id": 3,
  "name": "人大小程序对接",
  "key": "sk-4rc.......",
  "prefix": "sk-4rcENZ",
  "tenantCode": "410725",
  "systemType": "congress",
  "enabled": true,
  "createdTime": "2026-09-09T10:30:00"
}
```

> ⚠️ **务必立即保存 `key` 明文**：服务端只存 SHA-256 哈希，明文**仅创建时返回这一次**，
> 关闭/刷新后无法再查看。丢失只能删除重建。

### 3.3 查询、启用/停用与撤销

```bash
# 列表（仅掩码，不含明文）
curl https://ai.xxxx.com/api/auth/v1/api-keys -H "x-token: <token>"

# 启用/停用（关闭后该 Key 临时失效——携带它的请求立即 401，可随时重新开启）
curl -X PUT https://ai.xxxx.com/api/auth/v1/api-keys/{id}/enabled \
  -H "Content-Type: application/json" -H "x-token: <token>" \
  -d '{"enabled": false}'        # false=停用，true=启用

# 撤销（永久删除，撤销后携带该 Key 的请求立即失效）
curl -X DELETE https://ai.xxxx.com/api/auth/v1/api-keys/{id} -H "x-token: <token>"
```

> **停用 vs 撤销**：停用是临时失效（可重新开启，历史对话与用量保留）；
> 撤销是永久删除（Key 记录被移除）。日常回收权限优先用停用。

### 3.4 使用监控（额度 / token 消耗）

每次该 Key 调用 `POST /keys/v1/chat` 完成对话后，系统自动记录消耗的 token
（输入 prompt / 输出 completion / 总计 total），供超管监控额度：

```bash
# 汇总：调用次数 + 总 token 消耗（输入/输出/总计）
curl https://ai.xxxx.com/api/auth/v1/api-keys/{id}/usage-summary -H "x-token: <token>"
# → {"totalCalls":12,"promptTokens":22130,"completionTokens":370,"totalTokens":22500}

# 明细：每次对话的 token 消耗（标准 Pageable 分页，按时间倒序）
curl "https://ai.xxxx.com/api/auth/v1/api-keys/{id}/usage?page=0&size=20&sort=createdTime,desc" \
  -H "x-token: <token>"
```

管理界面「API Key 管理」页可直接查看：每行显示「调用次数 / 总 token」，
点「用量」查看每次对话的明细（时间、用户、会话、模型、输入/输出/总 token）。

---

## 4. 第三步：分发（人工）

- 将 `key` 明文、绑定的 `tenantCode`、`systemType` 线下/IM 交付给调用方负责人；
- 建议同时交付本文档第 5 节的对接说明与示例；
- 若需回收权限：删除/撤销该 Key，调用立即 401。

---

## 5. 第四步：调用方对接（/keys/v1/**）

### 5.1 认证方式（二选一，放在请求头）

```http
X-API-Key: sk-4rc.......
# 或
Authorization: Bearer sk-4rc.......
```

| 情况 | 结果 |
|------|------|
| Key 无效 / 缺失 | `401 {"code":401,"message":"未登录或登录已过期"}` |
| 登录用户（x-token）访问 `/keys/v1/**` | `403 {"code":403,"message":"无权限访问"}` |
| 有效 Key 访问 `/documents/v1/**` 等管理接口 | `403`（Key 无 ADMIN 角色） |

> API Key 请求**自动豁免 CSRF**，无需携带 cookie / X-CSRF-TOKEN。

### 5.2 探测可用模型（公开接口，无需认证）

```bash
curl https://ai.xxxx.com/api/ai/v1/provider
# → {"models":["deepseek-v3.2","deepseek-v3.2-think","deepseek-v4-flash-0731"],
#    "name":"百度千帆","defaultModel":"deepseek-v3.2","provider":"baidu"}
```

### 5.3 AI 对话（SSE 流式）—— 核心接口

```bash
curl -N -X POST https://ai.xxxx.com/api/keys/v1/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-4rc......." \
  -d '{"question":"公司本周重点工作是什么","userId":"test0101","sessionId":"sess-001","model":"deepseek-v3.2"}'
```

**请求体字段**：

| 字段 | 必填 | 说明 |
|------|------|------|
| `question` | ✅ | 问题内容 |
| `userId` | ✅ | 调用方用户编码（对话记录归属） |
| `sessionId` | ❌ | 会话 ID，不传自动生成（响应 `session` 事件里返回） |
| `model` | ❌ | 模型名，不传用服务端默认（`deepseek-v3.2`） |

> **租户编码 / 系统类型无需传**：由 API Key 绑定值自动赋值，检索范围 = **租户 0 的默认文档 + 本 Key 租户的自定义文档**，两者冲突时**租户自定义优先**。

**SSE 事件格式**（与 `/documents/v1/query` 一致）：

```
data:{"type":"session","sessionId":"sess-001"}
data:{"type":"sources","sources":[{...引用文档列表...}]}
data:{"type":"reasoning","text":"思考过程..."}
data:{"type":"content","text":"回答片段..."}
（多条 content 累积即完整回答）
```

### 5.4 对话历史查询 / 删除（按 Key 隔离）

**所有历史接口只返回本 Key 创建的对话**（存储字段 `api_key_id`），带其它 Key 查不到。

```bash
# ① 会话列表（语义化查询接口；userId/tenantCode/systemType 可选，不传不过滤）
#    标准 Pageable 分页：page（从0开始）/ size / sort，可加 sort=createTime,desc
curl "https://ai.xxxx.com/api/keys/v1/conversations?userId=test0101&tenantCode=410725&systemType=congress&page=0&size=10&sort=createTime,desc" \
  -H "X-API-Key: sk-4rc......."

# ② 会话列表（简版路由，等价于 ①）
curl "https://ai.xxxx.com/api/keys/v1?page=0&size=10" -H "X-API-Key: sk-4rc......."

# ③ 按会话 ID 查消息
curl "https://ai.xxxx.com/api/keys/v1/session?sessionId=sess-001" -H "X-API-Key: sk-4rc......."

# ④ 删除单个会话（非本 Key 创建的返回 404，不删任何数据）
curl -X DELETE "https://ai.xxxx.com/api/keys/v1/session/sess-001" -H "X-API-Key: sk-4rc......."

# ⑤ 批量删除（Body 为 sessionId 数组，只删本 Key 的，返回实际删除数）
curl -X DELETE "https://ai.xxxx.com/api/keys/v1/batch" \
  -H "Content-Type: application/json" -H "X-API-Key: sk-..." \
  -d '["sess-001","sess-002"]'
```

**接口汇总**：

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| `POST` | `/keys/v1/chat` | 仅 API Key | AI 对话（SSE），租户/系统由 Key 绑定值自动赋值 |
| `GET` | `/keys/v1/conversations` | 仅 API Key | 会话列表（按 key + userId + tenantCode + systemType 搜索，标准 Pageable 分页） |
| `GET` | `/keys/v1` | 仅 API Key | 会话列表（简版，与 conversations 等价） |
| `GET` | `/keys/v1/session` | 仅 API Key | 按 sessionId 查询消息（仅本 Key 创建的） |
| `DELETE` | `/keys/v1/session/{sessionId}` | 仅 API Key | 删除单个会话（仅本 Key 创建的，否则 404） |
| `DELETE` | `/keys/v1/batch` | 仅 API Key | 批量删除（Body: sessionId 数组，返回实际删除数） |

### 5.5 部署域名与 Nginx 代理

**服务部署域名**：`https://ai.xxxx.com/`（生产环境，HTTPS）

服务对外经 Nginx 统一加 `api` 前缀：**后端接口路径是 `/keys/v1/**` 形态（资源名在前、版本号在后）**，Nginx 将 `/api/keys/v1/**` 剥离 `/api` 后转发到 `/keys/v1/**`。调用方访问地址（本文档所有 curl 示例均按此域名+前缀编写）：

```
https://ai.xxxx.com/api/keys/v1/chat          → POST
https://ai.xxxx.com/api/keys/v1/conversations → GET
https://ai.xxxx.com/api/ai/v1/provider        → GET（公开）
https://ai.xxxx.com/api/auth/v1/api-keys      → POST（超管生成 Key）
```

---

## 6. 权限矩阵总览

| 路径 | 公开 | 仅超管登录（x-token） | 仅 API Key（X-API-Key） |
|------|------|----------------------|-------------------------|
| `/`、`/index.html`、静态资源 | ✅ | — | — |
| `/auth/v1/login` | ✅ | — | — |
| `/ai/v1/provider`（模型探测） | ✅ | ✅ | ✅ |
| `/auth/v1/**`（Key 管理） | — | ✅ | ❌ 403 |
| `/documents/v1/**`（文档/管理/登录态对话） | — | ✅ | ❌ 403 |
| `/keys/v1/**`（Key 专属对话 + 历史） | — | ❌ 403 | ✅ |

---

## 7. 常见问题（FAQ）

**Q1：带 Key 请求返回 401？**
Key 不存在/被删除/未启用，或请求头写错（应为 `X-API-Key` 或 `Authorization: Bearer`）。确认 Key 有效并处于 `enabled=true`。

**Q2：带 Key 请求返回 403？**
访问了非 `/keys/v1/**` 的接口（如 `/documents/v1/**`）。API Key 只能访问专属接口，业务功能请走 `POST /keys/v1/chat`。

**Q3：登录用户访问 `/keys/v1/**` 返回 403？**
预期行为：Key 专属接口只认 API Key 认证，登录态（ADMIN 角色）不可用，测试时请用 Key。

**Q4：生成 Key 报 400「均必填」？**
备注 / 租户编码 / 系统类型三个字段都必须非空；JSON 请求体不要带 BOM（UTF-8 with BOM 会导致字段解析为空）。

**Q5：生成 Key 报 400「不允许绑定租户 0」？**
`tenantCode=0` 是超管全局租户，API Key 必须绑定具体租户（如 `410725`）。

**Q6：`/keys/v1/conversations` 查询为空？**
① 该 Key 还没有对话记录（先调 `/keys/v1/chat` 产生会话）；② 对话记录异步落库，流结束后立即查询可能短暂为空，稍等重试；③ 传了过滤条件（userId/tenantCode/systemType）时需与记录完全匹配。

**Q7：对话检索不到知识库内容？**
确认 Key 绑定的 `tenantCode`/`systemType` 下已上传文档（超管上传的文档对所有租户开放，会作为默认知识库被检索）；检索范围为「租户 0 默认文档 + 本租户自定义文档」。

**Q8：SSE 请求需要特殊处理吗？**
需要流式读取：curl 用 `-N` 保持流；前端用 `EventSource`/`fetch + ReadableStream`；**流结束前不要断开连接**，否则对话记录不会落库（服务端按「客户端取消」处理，不保存）。

---

## 8. 安全与运营注意事项

- **明文 Key 只出现一次**：服务端仅存哈希，丢失需删除重建；分发走可信渠道（IM/内部系统），勿放公共代码仓库。
- **按 Key 隔离**：不同小程序/系统用不同 Key，历史记录严格按 Key 隔离；可单独撤销任一 Key，互不影响。
- **对话记录归属**：记录保存 `api_key_id`，同一 Key 下按 `userId`/`sessionId` 区分用户与会话。
- **租户数据边界**：Key 绑定的租户决定知识库检索范围；超管全局文档（租户 0）作为默认库对所有租户开放，自定义文档与默认文档冲突时以租户自定义为准。
- **监控**：可通过管理界面查看 Key 列表，结合服务日志（`对话记录已保存: id=..., userId=...`）核对各 Key 的调用情况。
