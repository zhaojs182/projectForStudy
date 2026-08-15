# Java-Agent 同步联调完成报告

更新时间：2026-08-05  
范围：FlowFix Agent M5 `dispatch-contract/v1` 同步 HTTP 纵向联调

## 1. 联调结论

`service-device` 已具备可供 FlowFix Agent 调用的 `dispatch-contract/v1` 纵向切片：

```text
GET 工单快照
-> GET 真实候选维修员快照
-> POST expectedVersion + idempotencyKey 派单命令
-> MySQL 条件更新 + 唯一领取记录 + 事务提交
-> 结构化 Receipt
-> GET MySQL 最终 Outcome
```

当前 Java 接口基地址：

```text
http://localhost:8085/internal/dispatch/v1
```

内部接口未加入 Gateway 路由，供本机运行的 FlowFix Agent 直连；个人项目的本地
联调不额外配置服务间共享密钥。

## 2. 代码调整

### 2.1 新增版本化合同

新增 `device.dispatch.v1` 包，包含：

- `OrderSnapshotV1`、`WorkerSnapshotV1`；
- `AssignmentCommandV1`、`AssignmentReceiptV1`、`AssignmentOutcomeV1`；
- 工单状态、优先级、回执、outcome 和业务原因码枚举；
- Bean Validation 字段校验；
- 关联 ID 只允许字母、数字以及 `._:/-`，防止控制字符污染日志；
- 统一 `dispatch-contract/v1`、`default` 租户和 300 字符幂等键约束；
- 中文存量状态到英文合同状态的唯一映射；
- Agent 专用异常处理，不再要求解析中文通用 `Result.message`。

边界 ID 使用数字字符串，进入 Java 后显式校验并转换为当前数据库的 `Integer`。

### 2.2 新增内部接口

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/health` | 合同版本与接口存活检查 |
| GET | `/orders/{orderId}/snapshot` | 读取工单、设备、状态和 version 冻结快照 |
| GET | `/orders/{orderId}/workers` | 读取稳定排序的真实合法候选快照 |
| POST | `/assignments` | 提交带 expectedVersion 的幂等派单命令 |
| GET | `/assignments/{dispatchId}/outcome` | 从 MySQL 当前状态核验最终 outcome |

创建派单会校验 Header 和 body 中的 `Idempotency-Key` 必须一致。

### 2.3 重构事务与并发边界

新增数据库条件更新：

```sql
UPDATE maintain_record
SET miantain_id = :workerId,
    status = '维护中',
    version = version + 1
WHERE id = :orderId
  AND tenant_id = :tenantId
  AND version = :expectedVersion
  AND miantain_id IS NULL
  AND status IN ('已通过', '待领取');
```

关键变化：

- Agent 快照中的 `expectedVersion` 真正进入 SQL 条件；
- 受影响行数必须为 1，否则返回 version/state/assigned 冲突；
- 工单更新、唯一领取记录和成功审计在同一事务提交；
- Redisson 只减少同工单竞争，数据库条件更新、唯一键和事务仍是最终防线；
- 相同幂等键按语义命令 SHA-256 摘要判断相同命令或 key 冲突；
- trace 变化不改变命令摘要，因此响应丢失后的新 trace 重试仍可返回 `ALREADY_APPLIED`；
- 失败尝试也写入结构化审计，使 version conflict 和硬门禁拒绝可通过 dispatchId 查询 outcome。

### 2.4 新增真实维修员派单数据

新增：

- `repairman_dispatch_profile`：租户、区域、班次、可用性、容量和 active；
- `repairman_dispatch_skill`：维修员与技能代码；
- `currentLoad`：实时统计该维修员的 `维护中` 工单，不保存漂移计数；
- 工单 `tenant_id` 和 `priority`；
- 工单技能从 `maintenance_type` 规范化，区域从真实设备 `location` 规范化。

Java 写入前会重新检查维修员画像、班次、可用性、容量、技能和区域，不直接信任 Agent 先前读取的候选列表。

当前边界：维修员派单画像由 Device 服务拥有，后续应在 ACL 审批维修员时同步启用/停用画像；本次尚未新增跨服务自动同步链路。

### 2.5 关联和日志

- 写命令保存 `traceId`、`eventId`、`dispatchId` 和 `idempotencyKey`；
- receipt 日志包含 order、worker、expected/observed version、status 和 reasonCode；
- outcome 只读取 MySQL 工单和派单审计，不使用 Redis/内存推断业务成功。

## 3. 修复的现有问题

| 问题 | 修复 |
|---|---|
| 抢单只使用锁内最新 version | 新 Agent 命令强制提交并校验快照 `expectedVersion` |
| 成功与幂等成功返回相同中文文本 | 分别返回 `ACCEPTED` 和 `ALREADY_APPLIED` |
| version、状态、唯一键和锁竞争无法稳定区分 | 新增结构化 receipt/reasonCode 和 HTTP 语义 |
| HTTP 200 被误当作业务完成 | 新增独立 outcome 查询并读取 MySQL 最终状态 |
| 幂等键只有 64 字符 | 数据库与 Java 合同统一为最大 300 字符 |
| 维修员评分字段来自 Fake | 新增真实画像、技能和实时工单负载 |
| 工单完成时错误写入设备状态“正常” | 工单完成改为 `已完成`，状态语义分离 |
| 测试环境 Redisson 对无密码 Redis 错误执行 AUTH | test profile 增加明确的无密码 Redisson 配置 |
| 根 POM 默认跳过全部测试 | 移除 `<skipTests>true</skipTests>` |
| `service-util` 重复声明 Redis 依赖 | 删除重复依赖，消除 Maven 模型警告 |

旧的前端 `/deviceMaintain/getMaintainOrder` 接口暂时保留以兼容原页面；FlowFix Agent 不得调用旧接口。

## 4. 数据库脚本

新增：

- `docker/mysql/init/03-dispatch-contract-v1.sql`：合同 v1 表结构迁移；
- `docker/mysql/demo/dispatch-v1-seed.sql`：本地固定工单和维修员数据。

当前运行中的 MySQL 已实际应用 `02-maintain-order-claim.sql` 和 `03-dispatch-contract-v1.sql`，因为已有 Docker volume 不会重新执行初始化目录。

固定数据：

| ID | 用途 |
|---|---|
| 工单 `92001` | 正常派单与重放 |
| 工单 `92002` | 构造 Worker B 的真实在途负载 |
| 工单 `92003` | 硬门禁和 version conflict |
| Worker `91001` | 合法候选，负载 0 |
| Worker `91002` | 合法候选，负载 1 |
| Worker `91003` | 区域/技能不匹配的非法候选 |

Java 单侧真实 HTTP 验证后、Java-Agent 联调前，数据库状态为：

- 工单 `92001`：`维护中`，维修员 `91001`，version `1`；
- 工单 `92001`：唯一领取记录数 `1`；
- 工单 `92003`：仍为 `待领取`、维修员为空、version `0`；
- `dispatch-http-001`：`ACCEPTED`；
- `dispatch-http-ineligible`：`REJECTED / WORKER_NOT_ELIGIBLE`；
- `dispatch-http-stale`：`VERSION_CONFLICT`。

随后 Java-Agent 联调使用工单 `92003` 完成真实派单，其最终状态已变为：维修员
`91001`、`维护中`、version `1`。联调后的状态以第 8 节为准。

## 5. 自动化验证

执行命令：

```bash
mvn -pl service/service-device -am test -DskipTests=false
mvn -pl service/service-device -am package -DskipTests=false
mvn package
```

结果：

- `DispatchContractsTest`：2 项通过；
- `DispatchTransactionServiceIntegrationTest`：5 项通过；
- 并发参数：20、50、100；
- 每轮并发均断言只有一个 `ACCEPTED`、一条领取记录和一次 version 增长；
- 幂等重放断言只有一个业务副作用；
- 旧 expectedVersion 断言零业务副作用；
- Java 全模块带测试打包成功。

## 6. 真实 HTTP 验证结果

使用 `service-product:8085` 和本地 Docker MySQL/Redis/Nacos 完成：

| 场景 | HTTP | 业务结果 |
|---|---:|---|
| 缺失内部凭据 | 401 | `UNAUTHORIZED` |
| 健康检查 | 200 | `dispatch-contract/v1` |
| 工单快照 | 200 | `PENDING_DISPATCH`、version `0` |
| 候选维修员 | 200 | Worker A load 0、Worker B load 1；非法 Worker C 被过滤 |
| 首次派单 | 200 | `ACCEPTED`、observedVersion `1` |
| 相同命令新 trace 重放 | 200 | `ALREADY_APPLIED`、observedVersion `1` |
| 成功 outcome | 200 | `ASSIGNED`、worker `91001`、version `1` |
| 非法维修员 | 422 | `REJECTED / WORKER_NOT_ELIGIBLE` |
| 非法维修员 outcome | 200 | `FAILED / WORKER_NOT_ELIGIBLE`，工单零副作用 |
| 旧 expectedVersion | 409 | `VERSION_CONFLICT`，observedVersion `0` |
| 冲突 outcome | 200 | `CONFLICT / VERSION_CONFLICT`，工单零副作用 |

## 7. 本轮完成边界与后续事项

本轮同步 HTTP 联调已关闭：双方已对齐 `dispatch-contract/v1`、300 字符幂等键、
`dispatchId` outcome 查询语义和真实 Java Adapter，并跑通人工审批后的真实写入、
最终状态核验、同命令重放及同 ID 不同命令冲突。

以下事项不属于“同步 HTTP 联调完成”的声明，继续作为后续演进项：

1. ACL 维修员审批与 Device 派单画像的自动同步；
2. 自动派单、人工拒绝、无候选等更多真实数据场景的持续回归；
3. Redis 跨进程检查点和多实例一致性；
4. RabbitMQ overload event、outcome-aware ACK、Retry 和 DLQ；
5. 数据库脚本升级为 Flyway/Liquibase 自动版本管理；
6. 旧前端抢单入口迁移到同一结构化事务能力。

因此准确表述是：“Java-Agent 同步派单纵向链路已完成联调并验证”；不要扩大为
“RabbitMQ 异步联调完成”或“完整 M5 生产化完成”。

## 8. 2026-08-05 Java-Agent 真实联调记录

本轮 Agent 侧完成并验证：

- 新增 `dispatch-contract/v1` Java HTTP Adapter；
- Agent outcome 查询参数由错误的 `idempotencyKey` 对齐为 `dispatchId`；
- Java 未提供的 distance/SLA 不再由 Agent 伪造，评分仅按实际可用维度重新归一化；
- 新增 start、resume、retry、status、history API；
- 新增 MockTransport 合同测试，Agent 全量测试结果为 `68 passed, 1 skipped`；
- Agent ready 检查已覆盖 Java dispatch health。
- 联调完成后重新执行 `mvn -q -pl service/service-device -am test -DskipTests=false`，结果通过。

真实链路结果：

| 字段 | 结果 |
|---|---|
| dispatchId | `integration-agent-20260805-01` |
| eventId | `integration-event-20260805-01` |
| orderId | `92003` |
| 冻结版本 | `0` |
| 候选 | `91001`、`91002`，同分 |
| 安全分流 | `AWAITING_APPROVAL` |
| 人工选择 | `91001` |
| Java receipt | `ACCEPTED`，observedVersion `1` |
| Java outcome | `ASSIGNED`，version `1` |
| Agent 最终状态 | `AUDITED` |
| 同命令新 trace 重放 | `ALREADY_APPLIED`，version 保持 `1` |
| 同 dispatchId 不同命令 | `REJECTED / IDEMPOTENCY_KEY_CONFLICT` |
| Agent 状态/历史查询 | `audited / assigned / version 1`，10 个检查点 |

此次联调产生了真实本地演示数据副作用：工单 `92003` 当前已分配给 `91001`，version 已从 `0` 增至 `1`。未新增基础组件，继续复用 ep-manage-sys Compose 栈中的现有服务。
