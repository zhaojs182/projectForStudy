# M5 Java 同步联调任务归档

更新时间：2026-08-05  
适用项目：`ep-manage-sys`  
归档结论：`dispatch-contract/v1` 同步 HTTP 纵向联调已完成；本文保留设计约束与验收清单供回归使用

> 本文主体是联调前设计清单。顶部关闭项和末尾完成记录代表本轮验收结论；正文中仍
> 未勾选的项目是后续硬化或扩大回归范围的要求，不影响本轮同步纵向链路关闭。

## 0. 当前实施状态（2026-08-05）

Java 侧同步 HTTP 纵向切片已经实现并完成本地验证，详细修改和证据见
[`JAVA_PRE_INTEGRATION_CHANGELOG.md`](./JAVA_PRE_INTEGRATION_CHANGELOG.md)。

已经完成：

- [x] 工单快照、真实候选维修员快照、创建派单、查询 outcome 四个内部接口；
- [x] `dispatch-contract/v1` Java DTO、枚举、校验和结构化错误；
- [x] `expectedVersion` 数据库条件更新；
- [x] 300 字符幂等键、命令摘要、重放回执和持久化派单审计；
- [x] 真实维修员派单画像、技能、区域、班次、可用性、实时负载和容量；
- [x] trace/event/dispatch/idempotency 关联；
- [x] MySQL 事务、幂等和 20/50/100 并发自动化测试；
- [x] 真实 HTTP 的鉴权、快照、候选、派单、重放、冲突和 outcome 验证；
- [x] 根构建不再默认跳过测试，全模块 `mvn package` 已通过。

联调关闭项：

- [x] 双方冻结字段、枚举、错误码和 300 字符幂等键；
- [x] Agent Mock Contract Test 对齐 Java JSON；
- [x] Agent Java Adapter、Bootstrap 和 Dispatch API 接入；
- [x] 真实人工批准派单、outcome 核验、重放和幂等键冲突链路；
- [x] Java 事务、旧版本冲突及 20/50/100 并发唯一赢家验证。

Redis 检查点和 RabbitMQ ACK/Retry/DLQ 属于后续异步阶段，不作为本轮同步联调的
关闭条件。完成记录见 [`JAVA_PRE_INTEGRATION_CHANGELOG.md`](./JAVA_PRE_INTEGRATION_CHANGELOG.md)。

## 1. 本阶段要达到的结果

本阶段按“契约优先”完成 Java 与 Agent 的同步 HTTP 联调，不包含 Agent 的 RabbitMQ
消费、Redis 跨进程幂等或持久化 Checkpoint。

Java 准备完成后，应能用一个真实工单证明以下链路：

```text
Agent 读取工单与维修员快照
-> 基于冻结快照产生派单决策
-> 提交 expectedVersion + idempotencyKey 的 AssignmentCommand
-> Java 在同一事务中校验并写入
-> Java 返回结构化 AssignmentReceipt
-> Agent 再查询 Java 的 AssignmentOutcome
-> 重放不增加副作用，并发写入只有一个最终赢家
```

Java 侧完成标准：

- [x] `dispatch-contract/v1` 的 DTO、枚举、错误码、字段长度和 JSON 示例已冻结；
- [x] 提供工单快照、候选维修员快照、创建派单、查询 outcome 四项能力；
- [x] Java 使用 Agent 读取到的 `expectedVersion` 做条件更新，而不是只使用锁内最新 version；
- [x] Java 返回结构化业务结果，Agent 不需要解析中文 `message`；
- [x] 相同幂等键重放结果稳定且不新增领取记录；
- [x] 20～100 个并发请求竞争同一工单时，数据库最终只有一个维修员；
- [x] 领取记录与工单更新要么同时提交，要么同时回滚；
- [x] `traceId`、`eventId`、`dispatchId`、`idempotencyKey` 可以串起请求、日志、领取记录和 outcome；
- [x] 人工批准后的派单、version conflict、重放和并发场景已有可重复证据；自动、人工拒绝和无候选继续作为扩展回归集。

## 2. 改造前源码基线（历史）

### 2.1 已有能力，应保留并纳入测试

- `service-device` 是工单业务拥有者，Nacos 服务名为 `service-product`，本地端口为 `8085`；
- `MaintainRecord.version` 已使用 MyBatis-Plus `@Version`；
- `maintain_order_claim.order_id` 和 `request_id` 已有唯一键；
- `claimOrder()` 已在 `@Transactional` 中写领取记录并更新工单；
- 控制器已使用 `order:lock:{orderId}` 的 Redisson 锁，并在锁内重新读取工单；
- 当前幂等逻辑已能区分“同 requestId 同命令”和“同 requestId 不同命令”。

### 2.2 当时阻断联调的问题（均已由 v1 纵向切片处理）

- `getMaintainOrder` 直接复用 `MaintainRecord` 作为请求体，字段类型和可写范围都不适合作为 Agent 合同；
- `claimOrder()` 没有 `expectedVersion` 参数，不能证明写入基于 Agent 读取的冻结快照；
- 成功和失败都主要通过 `Result + 中文 message` 表达，且多数业务失败仍返回 HTTP 200；
- `SUCCESS` 与 `IDEMPOTENT_SUCCESS` 对外结果完全相同，Agent 无法识别重放；
- `ClaimOrderConflictException` 会被通用异常捕获为“系统异常”，没有稳定的 `VERSION_CONFLICT`；
- `tryLock()` 失败、数据库唯一键冲突、状态冲突和系统错误没有独立错误码；
- 工单状态为中文字符串且实体类型是 `Object`，合法状态迁移没有统一定义；
- `maintenanceType` 是 `Object`，工单缺少明确的 `priority`、`region` 和 `requiredSkills`；
- `User` 只有账户信息，没有维修员技能、区域、班次、可用性、负载和容量；
- `Idempotency-Key` 当前只允许 64 字符，与 Agent 侧上限不一致；
- 领取记录没有保存 event、dispatch、trace、命令摘要和结构化处理结果；
- 现有测试只是查询并打印，没有抢单并发、幂等、冲突和回滚断言；
- 根 POM 默认 `<skipTests>true</skipTests>`，普通 `package` 成功不能作为验收证据。

## 3. P0：先冻结 `dispatch-contract/v1`

### 3.1 固定边界类型和公共字段

建议 v1 在 HTTP/JSON 边界统一使用字符串 ID，Java Controller 再显式校验并转换当前数据库的 `Integer` ID。这样不会把现有数据库类型扩散到 Agent 领域模型，也便于未来迁移。禁止在业务层静默截断或自动修复 ID。

所有 DTO 必须包含 `contractVersion: "dispatch-contract/v1"`。命令和回执必须携带以下关联字段：

| 字段 | v1 约束 | Java 行为 |
|---|---|---|
| `traceId` | 必填，最大 64 | 写 MDC、结构化日志和审计记录 |
| `eventId` | 必填，最大 128 | 标识触发本次派单的业务事件 |
| `dispatchId` | 必填，最大 128 | 标识一次 Dispatch Runtime |
| `idempotencyKey` | 必填，建议统一最大 300 | 建唯一键，禁止截断，重复时比较命令摘要 |
| `tenantId` | 必填，最大 64 | v1 若只有单租户，固定为文档化的 `default` 并严格校验，不伪装成已实现多租户 |

`idempotencyKey` 最大长度必须由 Java 和 Agent 共同确认后写入合同常量、Bean Validation 和数据库 DDL。若采用上述 300 字符方案，需把 `request_id VARCHAR(64)` 安全迁移到 `VARCHAR(300)`。

### 3.2 定义工单状态与合法迁移

新增 Java 枚举和唯一映射层，不允许 Controller、Service、SQL 各自比较中文字符串。v1 至少需要以下合同状态：

| 合同状态 | 当前存量值映射 | 是否允许派单 |
|---|---|---:|
| `PENDING_APPROVAL` | `待审批` | 否 |
| `PENDING_DISPATCH` | `已通过`、`待领取` | 是 |
| `ASSIGNED` | `维护中` | 否 |
| `COMPLETED` | 建议存为 `已完成` | 否 |
| `REJECTED` | 审批拒绝的明确存量值 | 否 |

需要同时修正“维修完成后把工单状态写成 `正常`”的问题：`正常` 是设备状态语义，不应作为工单完成状态。

第一版合法迁移至少冻结为：

```text
PENDING_APPROVAL -> PENDING_DISPATCH | REJECTED
PENDING_DISPATCH -> ASSIGNED
ASSIGNED -> COMPLETED
```

- [ ] 为未知数据库状态返回明确的 `INVALID_ORDER_STATE`，不能默认可派单；
- [ ] 状态转换器有单元测试；
- [ ] 派单事务只允许 `PENDING_DISPATCH -> ASSIGNED`。

### 3.3 定义 Agent 专用 DTO

不要直接复用 `MaintainRecord`、`User` 或通用 `Result`。建议新增 `internal.dispatch.v1` 包，并至少提供以下模型。

`OrderSnapshotV1`：

```json
{
  "contractVersion": "dispatch-contract/v1",
  "orderId": "1001",
  "tenantId": "default",
  "deviceId": "501",
  "maintenanceType": "ELECTRICAL",
  "requiredSkills": ["ELECTRICAL"],
  "priority": "NORMAL",
  "region": "CAMPUS_EAST",
  "status": "PENDING_DISPATCH",
  "assignedWorkerId": null,
  "version": 3,
  "snapshotAt": "2026-08-04T20:30:00+08:00"
}
```

`WorkerSnapshotV1`：

```json
{
  "workerId": "21",
  "tenantId": "default",
  "skills": ["ELECTRICAL"],
  "region": "CAMPUS_EAST",
  "shiftStatus": "ON_DUTY",
  "available": true,
  "currentLoad": 1,
  "capacity": 3,
  "snapshotAt": "2026-08-04T20:30:00+08:00"
}
```

`AssignmentCommandV1`：

```json
{
  "contractVersion": "dispatch-contract/v1",
  "traceId": "trace-001",
  "eventId": "event-001",
  "dispatchId": "dispatch-001",
  "idempotencyKey": "dispatch:dispatch-001:assign",
  "tenantId": "default",
  "orderId": "1001",
  "workerId": "21",
  "expectedVersion": 3
}
```

`AssignmentReceiptV1` 必须表达“命令执行回执”，不能冒充最终业务核验：

```json
{
  "contractVersion": "dispatch-contract/v1",
  "receiptStatus": "ACCEPTED",
  "reasonCode": null,
  "orderId": "1001",
  "workerId": "21",
  "expectedVersion": 3,
  "observedVersion": 4,
  "traceId": "trace-001",
  "eventId": "event-001",
  "dispatchId": "dispatch-001",
  "idempotencyKey": "dispatch:dispatch-001:assign"
}
```

`AssignmentOutcomeV1` 必须从 Java 当前业务真相查询，至少返回：

- `outcomeStatus`: `ASSIGNED | PENDING | CONFLICT | FAILED | NOT_FOUND`；
- `orderId`、最终 `assignedWorkerId`、最终 `version` 和工单状态；
- 四个关联 ID；
- 可选的 `reasonCode` 和 `verifiedAt`。

### 3.4 冻结回执、错误码和 HTTP 语义

| HTTP | receipt/status 或错误码 | 语义 | Agent 是否可直接当派单完成 |
|---:|---|---|---:|
| 200 | `ACCEPTED` | 本次命令完成事务提交 | 否，仍查询 outcome |
| 200 | `ALREADY_APPLIED` | 相同幂等键、相同命令已经成功 | 否，仍查询 outcome |
| 400 | `INVALID_REQUEST` | 字段、长度、格式或合同版本错误 | 否 |
| 401/403 | `UNAUTHORIZED` / `FORBIDDEN` | 服务凭据或权限不合法 | 否 |
| 404 | `ORDER_NOT_FOUND` / `WORKER_NOT_FOUND` | 业务对象不存在 | 否 |
| 409 | `VERSION_CONFLICT` | 当前 version 不等于 expectedVersion | 否 |
| 409 | `ORDER_ALREADY_ASSIGNED` | 工单已有其他最终归属 | 否 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 同幂等键对应不同命令 | 否 |
| 422 | `ORDER_NOT_ASSIGNABLE` | 状态不允许派单 | 否 |
| 422 | `WORKER_NOT_ELIGIBLE` | 非维修员、停用、非当班、不可用或超容量 | 否 |
| 423/409 | `ORDER_BUSY_RETRYABLE` | 未获得短锁，可按合同有限重试 | 否 |
| 500/503 | `INTERNAL_ERROR` / `DEPENDENCY_UNAVAILABLE` | 系统或依赖故障，结果可能未知 | 否，必须查询 outcome |

禁止把异常类名、SQL 文本、密码或内部堆栈返回给 Agent。

## 4. P0：补齐真实维修员快照数据

不能用只存在于 Agent Fake Adapter 中的技能、区域和负载声称“真实智能派单”。建议采用以下最小真实模型：

- `users.repairman_profile`：`user_id` 唯一、`tenant_id`、`region_code`、`shift_status`、`available`、`capacity`、`updated_at`；
- `users.repairman_skill`：`user_id + skill_code` 唯一；
- `currentLoad`：由 Device 服务统计该维修员处于 `ASSIGNED/维护中` 的工单数，不在画像表里维护一个容易漂移的计数；
- `requiredSkills`：使用明确映射表把工单 `maintenance_type` 转换为技能代码；
- 工单 `region`：从真实 `deviceinstance.location` 映射到合同中的稳定区域代码；
- `priority`：在 `maintain_record` 增加非空、带默认值的枚举字段，或在 v1 合同中明确固定为 `NORMAL`。固定默认值必须在文档中声明，不能描述为智能推断。

候选维修员查询建议由 Device 服务聚合：ACL 提供维修员身份与画像，Device 结合自己的在途工单计算负载。Java 在执行写入时必须重新检查维修员仍满足硬门禁；不能只相信 Agent 先前读取的候选列表。

第一版最小评分字段必须在合同中二选一并记录：

1. 完整最小版：`skills + region + available + currentLoad/capacity`；
2. 缩减版：仅使用当前确有真实来源的字段，并在演示和报告中明确未参与评分的字段。

若上述数据表、映射和种子数据尚未落地，不得把 Worker Snapshot 标记为真实联调完成。

## 5. P0：实现四个内部接口

建议第一次本地同步切片直接访问：

```text
http://localhost:8085/internal/dispatch/v1
```

在未完成鉴权前不要把这些内部接口加入 Gateway 公网路由。

### 5.1 读取工单冻结快照

```http
GET /internal/dispatch/v1/orders/{orderId}/snapshot
```

- [ ] 一次数据库读取返回 `OrderSnapshotV1`；
- [ ] 包含确定的状态、维修人和 version；
- [ ] 从真实设备数据生成 region，从明确映射生成 requiredSkills；
- [ ] 不返回 `password` 等用户敏感字段；
- [ ] 不存在和状态未知时返回结构化错误。

### 5.2 读取候选维修员快照

```http
GET /internal/dispatch/v1/orders/{orderId}/workers
```

- [ ] 只返回具有维修员角色且账户启用的用户；
- [ ] 返回真实技能、区域、班次、可用性、负载和容量；
- [ ] 所有候选使用同一个 `snapshotAt`；
- [ ] 结果排序稳定，例如按 `workerId` 升序，保证重放可复现；
- [ ] 无候选返回空数组，不用 500 或虚构候选补齐。

### 5.3 创建派单

```http
POST /internal/dispatch/v1/assignments
Content-Type: application/json
Idempotency-Key: <与 body.idempotencyKey 相同>
```

- [ ] Header 与 body 幂等键必须一致；
- [ ] 校验 contractVersion、ID、tenant、长度和 expectedVersion；
- [ ] 校验维修员身份和硬门禁；
- [ ] 在事务内比较工单状态、维修人和 `expectedVersion`；
- [ ] 原子写入领取/幂等记录并条件更新工单；
- [ ] 返回 `AssignmentReceiptV1` 和正确 HTTP 状态；
- [ ] 响应超时或丢失后，同命令重放返回 `ALREADY_APPLIED`。

### 5.4 查询最终 outcome

```http
GET /internal/dispatch/v1/assignments/{dispatchId}/outcome
```

- [ ] 从 MySQL 当前工单和领取/审计记录组装 outcome；
- [ ] 不能从 Redis、内存状态或之前的 HTTP 200 推断；
- [ ] 最终维修员和 version 与命令一致时才返回 `ASSIGNED`；
- [ ] 已被其他维修员领取时返回 `CONFLICT` 并给出结构化 reasonCode；
- [ ] dispatch 不存在时返回 `NOT_FOUND`，避免无限重试。

## 6. P0：重构派单事务与幂等记录

### 6.1 expectedVersion 必须进入数据库条件

当前 `claimOrder(orderId, repairmanId, requestId)` 应替换或新增 Agent 专用方法，例如：

```java
AssignmentReceipt assign(AssignmentCommandV1 command);
```

核心更新必须等价于：

```sql
UPDATE maintain_record
SET miantain_id = :workerId,
    status = '维护中',
    version = version + 1
WHERE id = :orderId
  AND version = :expectedVersion
  AND miantain_id IS NULL
  AND status IN ('已通过', '待领取');
```

受影响行数必须等于 1。若为 0，要重新读取并稳定区分：不存在、version conflict、状态不可派单或已被领取。

Redisson 只用于减少竞争，不能替代这个条件更新、唯一键和事务。即使未获得锁或 Redis 不可用，也不能绕过数据库校验写入。

### 6.2 扩展领取/幂等审计记录

建议在 `maintain_order_claim` 中增加或拆出 `dispatch_assignment` 表，至少保存：

- `tenant_id`、`order_id`、`repairman_id`；
- `request_id/idempotency_key` 唯一；
- `event_id`、`dispatch_id`、`trace_id`；
- `expected_version`、`result_version`；
- `command_hash`，用于识别“同 key 不同命令”；
- `receipt_status`、`reason_code`；
- `created_at`、`updated_at`。

幂等规则必须固定：

| 情况 | 结果 | 新增副作用 |
|---|---|---:|
| 同 key + 同 command，首次执行成功 | `ACCEPTED` | 1 |
| 同 key + 同 command，再次请求 | `ALREADY_APPLIED` | 0 |
| 同 key + 不同 command | `IDEMPOTENCY_KEY_CONFLICT` | 0 |
| 不同 key + 同 order 并发 | 一个成功，其余 conflict | 仅 1 |

如果记录先插入、工单更新后失败，整个事务必须回滚；不能留下“看似已处理”的幂等记录。

### 6.3 数据库变更必须可重复执行

- [ ] 新增版本化迁移 SQL，不只修改 `docker/mysql/init`；
- [ ] 注意已有 `mysql-data` volume 不会重新执行初始化脚本；
- [ ] 迁移前检查重复 `order_id`、`request_id` 和非法状态；
- [ ] 迁移后验证唯一索引、字段长度、默认值和非空约束；
- [ ] 提供联调种子数据：一个可派工单、两个真实维修员及其技能/区域/容量；
- [ ] 提供清理或重置测试数据的安全步骤。

## 7. P0：关联 ID、日志、鉴权与配置

### 7.1 关联与日志

- [ ] Controller 校验并把 `traceId` 放入 MDC；
- [ ] 日志至少包含 trace、event、dispatch、idempotency、order、worker、expectedVersion、observedVersion、receiptStatus；
- [ ] 不记录 JWT、服务密钥、密码和完整敏感请求；
- [ ] 数据库提交成功后再打印 `ACCEPTED`；
- [ ] HTTP 接收、命令回执和 outcome 查询使用不同事件名，避免把 HTTP 200 误记为业务完成。

### 7.2 内部接口访问控制

本地个人项目采用仅本机直连，不额外配置服务间共享密钥。若未来部署到共享网络或
生产环境，应在边界处补充服务身份认证，并明确：

- [ ] 缺失/错误凭据返回 401 或 403；
- [ ] 只允许 Agent 调用内部写接口；
- [ ] 普通用户 JWT 不能借此绕过维修员和工单规则；
- [ ] 日志中对凭据脱敏；
- [ ] `application-dev.yaml` 只引用环境变量，不提交真实密钥。

### 7.3 本地联调配置

- [ ] 明确 Agent 使用直连 `8085` 还是 Gateway；v1 推荐先直连内部接口；
- [ ] 固定时区为 `Asia/Shanghai`，JSON 时间使用带时区 ISO-8601；
- [ ] 提供健康检查，能区分应用存活与 MySQL/Redis/Nacos 依赖是否就绪；
- [ ] 启动日志明确打印合同版本和内部接口基地址，但不打印密钥。

## 8. P0：必须补齐的自动化测试

测试必须有断言并可重复运行；不能依赖人工观察日志。涉及 MySQL 唯一键、事务和并发的测试优先使用与本地一致的 MySQL 8，而不是用行为不同的内存数据库替代。

### 8.1 合同与接口测试

- [ ] 四个 DTO 的序列化/反序列化与 JSON 示例一致；
- [ ] 枚举大小写、必填字段、ID 格式和最大长度验证；
- [ ] 不支持的 contractVersion 返回稳定错误；
- [ ] 每个业务错误码对应正确 HTTP 状态；
- [ ] receipt 与 outcome 字段满足合同，不泄漏实体多余字段；
- [ ] Agent Mock Server 使用的样例与 Java Controller 测试共用或相互校验。

### 8.2 事务和业务规则测试

- [ ] 正常派单：工单、领取记录、version 同时更新；
- [ ] expectedVersion 过期：返回 `VERSION_CONFLICT`，数据库零副作用；
- [ ] 不可派状态：返回 `ORDER_NOT_ASSIGNABLE`，数据库零副作用；
- [ ] 不合格维修员：返回 `WORKER_NOT_ELIGIBLE`，数据库零副作用；
- [ ] 领取记录插入后模拟更新失败：事务回滚，两张表均无残留；
- [ ] 未知状态和不存在对象均有确定结果；
- [ ] outcome 只按数据库最终状态判断。

### 8.3 幂等和并发测试

- [ ] 同 key + 同 command 连续重放至少 10 次，只有一条领取记录，后续为 `ALREADY_APPLIED`；
- [ ] 同 key + 不同 order/worker/expectedVersion，稳定返回 `IDEMPOTENCY_KEY_CONFLICT`；
- [ ] 20、50、100 个不同 key 同时抢同一工单，各跑多轮；
- [ ] 每轮只允许一个 `ACCEPTED`，数据库只有一个最终维修员和一条有效领取记录；
- [ ] 其他请求返回明确 conflict 或 retryable，不得误报成功；
- [ ] 模拟“事务已提交但 HTTP 响应丢失”，重试后不增加副作用且 outcome 可查；
- [ ] 收集成功数、冲突数、异常数、数据库记录数和耗时，生成测试报告。

### 8.4 测试命令

根 POM 当前默认跳过测试，验收时必须显式开启：

```bash
mvn -pl service/service-device -am test -DskipTests=false
mvn -pl service/service-device -am package -DskipTests=false
```

最终应移除根 POM 的默认 `skipTests=true`，或至少增加一个 CI profile，使合同和并发测试无法被普通构建静默跳过。

## 9. 第一次联调的数据与验收场景

### 9.1 固定数据

- 工单 A：`PENDING_DISPATCH`、无维修员、version 可知、技能和区域有真实来源；
- Worker A：技能匹配、同区域、当班、可用且未超容量；
- Worker B：至少有一个可解释的评分差异，但仍是合法候选；
- Worker C（测试用）：不满足硬门禁，用于证明非法候选不会写入；
- 每轮测试前记录工单、领取记录和 version 基线。

### 9.2 Java 必须通过的场景

| 场景 | 预期 receipt/outcome | 数据库验收 |
|---|---|---|
| 自动选择 Worker A | `ACCEPTED` -> `ASSIGNED` | A 成为唯一维修员，version + 1 |
| 人工批准后提交 | `ACCEPTED` -> `ASSIGNED` | 与批准的 worker 一致 |
| 人工拒绝 | Java 不收到写命令 | 零副作用 |
| 无候选 | Java 不收到写命令 | 零副作用 |
| 旧 expectedVersion | `VERSION_CONFLICT` | 零副作用 |
| 相同事件/命令重放 | `ALREADY_APPLIED` -> `ASSIGNED` | 副作用计数不增加 |
| 不同事件并发抢同一工单 | 一个 `ASSIGNED`，其余 conflict | 不覆盖赢家 |
| 响应丢失后查询 | outcome 返回真实最终状态 | 不靠原 HTTP 响应判断 |

## 10. 实施顺序与阻断门槛

按以下顺序执行，前一门槛未通过时不要开始下一项：

1. [x] 冻结合同字段、枚举、错误码、HTTP 语义和幂等键长度；
2. [x] 确定并落地真实维修员最小数据模型与评分字段；
3. [x] 编写 DTO、状态映射和 JSON 合同测试；
4. [x] 编写数据库脚本和固定联调数据；
5. [x] 实现工单与候选维修员快照接口；
6. [x] 重构带 `expectedVersion` 的事务派单和结构化 Receipt；
7. [x] 实现只读取 MySQL 真相的 Outcome 接口；
8. [x] 加入 trace、审计、内部凭据和异常映射；
9. [x] 完成事务、幂等、20～100 并发和响应丢失测试；
10. [x] 与 Agent Mock Contract Test 对齐并通过；
11. [x] 启动 Java 与 Agent，跑通同步 HTTP 真实纵向切片；
12. [x] 保存 receipt、outcome、数据库结果和测试报告作为联调证据。

以下任一情况存在时，Java 侧仍未达到真实联调条件：

- Agent 仍需解析中文 message；
- 命令没有 expectedVersion，或数据库更新条件没有使用它；
- Worker 字段来自临时 Fake 数据但被描述成真实快照；
- HTTP 200 被直接当作派单完成；
- 重放或并发测试没有数据库断言；
- 构建仍默认跳过所有关键测试；
- outcome 来自缓存或内存，而不是 MySQL 当前状态。

## 11. 本阶段暂不做的事项

同步 HTTP 纵向切片完成后，以下事项仍不属于本轮 Java 阻断项：

- RabbitMQ 真实派单事件消费、ACK、Retry 和 DLQ；
- Redis 跨进程事件幂等和 Agent 短租约；
- 持久化 LangGraph Checkpoint 与进程重启恢复；
- Planner、Subagent、MCP 等 Agent 能力；
- Elasticsearch 搜索投影修复；
- 全平台生产级安全、监控和容量治理。

这些事项不能因同步联调完成而被宣称为已完成；后续按“Redis -> RabbitMQ ->
持久化恢复 -> 故障注入”的顺序继续 M5/M7。
