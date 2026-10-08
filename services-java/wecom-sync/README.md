# wecom-sync —— 企微组织架构同步与统一身份（T05）

JDK 17 + Spring Boot 3.3 + Maven。从企微拉取部门树与成员，同步到本地 User/Department 表；把企微 userid 映射为内部权限主体（principal），输出格式与 `contracts/retrieval.md` 的「权限主体」一致。

```bash
mvn -q test                      # 41 个 JUnit 5 用例，全部内存实现，不连外部系统
mvn spring-boot:run              # 默认端口 8020（WECOM_SYNC_PORT），自带虚构演示数据
```

## 设计要点

- **外部系统全部可替换**：`WecomClient`（部门树 / 成员列表 / 成员详情）、`UserRepository`、`DepartmentRepository`、`GrantRepository`（人工授权表）、`AuditSink`（审计出口）都是接口，默认注入内存实现（`FakeWecomClient`、`InMemory*`）。声明同类型的 Bean 即可替换（`@ConditionalOnMissingBean`）。
- **不硬编码密钥**：企微凭据只从环境变量读取，`WecomProperties.toString()` 会打码。当前没有真实企微客户端实现，接入时读取 `WecomProperties` 即可。
- **先拉后写**：一次同步先把企微数据全部拉完再落库，拉取阶段出错（502）时不会产生任何写入。
- **空快照保护**：全量同步拿到 0 个成员、但本地仍有在职用户时直接拒绝（409），避免误判全员离职。
- **手机号脱敏**：只保存 `138****1234` 形式，原始号码不落库。
- **principal 映射规则**
  - `user_id`：内部 ID（`u_10001` 起递增），首次同步时分配，之后不变；离职后重新入职仍沿用原 ID。可用 `IdentityService.wecomUserIdOf` 反查企微 userid。
  - `dept`：成员所在部门的名称（不是路径，以便和 RAGFlow 文档元数据中的 `dept` 对齐）；完整路径保存在 `User.deptPaths`。
  - `roles` / `projects`：只来自人工授权表，没有授权时为 `[]`。
  - `max_sensitivity`：取授权表中的值，没有时取默认值 `L2`（`IDENTITY_DEFAULT_MAX_SENSITIVITY`）。
- **在职判定**：企微 status 1（已激活）和 4（未激活）算在职；2（禁用）、5（退出企业），以及企微中已查不到的成员，都会被置为失效。失效后 `GET /identity/principal` 返回 410，网关拿不到 principal。
- **审计**：只要 principal 的权限字段（`dept` / `roles` / `max_sensitivity` / `projects`）发生变化，就写入 `principal_changed`；新增、离职、重新入职分别写入 `principal_created` / `principal_deactivated` / `principal_reactivated`。只改了姓名、职级或手机号时只更新数据，不写审计。

## 环境变量

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `WECOM_CORP_ID` | 企微 corpid | 空 |
| `WECOM_CORP_SECRET` | 通讯录同步 secret | 空 |
| `WECOM_BASE_URL` | 企微 API 地址 | `https://qyapi.weixin.qq.com` |
| `IDENTITY_DEFAULT_MAX_SENSITIVITY` | 无授权时的密级上限 | `L2` |
| `WECOM_SYNC_PORT` | 服务端口 | `8020` |

## 接口

JSON 字段统一 snake_case。错误体统一为 `{"error": "<code>", "message": "..."}`。

### `POST /identity/sync` 触发同步

请求体可省略，省略时按 `full` 处理。

```json
{ "mode": "full" }
```

```json
{ "mode": "incremental", "user_ids": ["zhangsan", "lisi"] }
```

- `full`：以企微全量成员为准；本地在职但企微已不存在的成员判为离职。
- `incremental`：只处理 `user_ids` 中的成员（通常来自企微通讯录变更回调），部门树每次都全量刷新。`user_ids` 不能为空。

响应 200：

```json
{
  "mode": "full",
  "added": 3,
  "updated": 1,
  "left": 1,
  "unchanged": 5,
  "departments": 5,
  "audit_events": 5,
  "synced_at": "2026-10-08T00:00:00Z"
}
```

`left` 表示本次由在职变为失效的人数（离职、被删除或被禁用）。

| 状态 | error | 场景 |
| --- | --- | --- |
| 400 | `bad_request` | mode 非法、incremental 缺少 user_ids、JSON 格式错误 |
| 409 | `empty_snapshot` | 全量同步拿到 0 个成员，但本地有在职用户 |
| 502 | `wecom_unavailable` | 调用企微失败，本次未写入任何数据 |

### `GET /identity/principal/{wecom_user_id}` 查询权限主体

响应 200（字段与 `contracts/retrieval.md` 一致）：

```json
{
  "user_id": "u_10001",
  "dept": ["融资部", "财务共享"],
  "roles": ["financing_staff"],
  "max_sensitivity": "L3",
  "projects": ["PRJ-2026-CITIC"]
}
```

| 状态 | error | 场景 |
| --- | --- | --- |
| 404 | `not_found` | 未同步过的企微 userid |
| 410 | `principal_inactive` | 已离职或被禁用，身份已失效 |

### `PUT /identity/grants/{wecom_user_id}` 维护人工授权（附加接口）

整体覆盖该成员的授权。`max_sensitivity` 传 `null` 或省略时表示使用默认值。

```json
{ "roles": ["financing_staff"], "max_sensitivity": "L3", "projects": ["PRJ-2026-CITIC"] }
```

响应 200：更新后的 principal（格式同上）。权限字段有变化时会写入 `principal_changed`（`source=grant`）。`max_sensitivity` 不在 L1–L4 范围内时返回 400；成员不存在返回 404，已失效返回 410。

### `GET /identity/audit-events[?wecom_user_id=xxx]` 查询审计事件（附加接口）

```json
{
  "events": [
    {
      "event_type": "principal_changed",
      "wecom_user_id": "zhangsan",
      "user_id": "u_10001",
      "source": "grant",
      "changes": {
        "max_sensitivity": { "before": "L2", "after": "L3" },
        "projects": { "before": [], "after": ["PRJ-2026-CITIC"] }
      },
      "ts": "2026-10-08T00:00:00Z"
    }
  ]
}
```

`source` 取值：`wecom_sync`（同步触发）或 `grant`（人工授权）。默认审计出口是内存实现，后续接入审计模块时替换 `AuditSink` 即可。

## 未完成 / 后续

- 真实企微 HTTP 客户端（access_token 缓存、限流重试）尚未实现，目前只有接口和 fake。
- 企微通讯录变更回调（验签、解密）尚未接入；回调方拿到变更的 userid 后，调用 `incremental` 同步即可。
- 存储和审计都是内存实现，重启即丢失；需要替换为数据库实现和审计模块。
- 人工授权表没有操作人字段，接口也没有鉴权，应只在内网由网关调用。
