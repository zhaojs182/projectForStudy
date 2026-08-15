# 业务设计模式实现完成报告

## 1. 完成范围

本次已完成并接入真实业务链路：

1. 工单状态机 + 事件处理策略；
2. Outbox 模板流程 + 消息策略；
3. 设备领用 Saga + 补偿。

实现不是独立 Demo。审批、人工抢单、自动派单、维修完成、设备领用和解绑入口均已接入新组件。

## 2. 工单状态机 + 事件处理策略

核心实现位于 `service-device/.../device/order`。

状态迁移集中定义在 `OrderStateMachine`：

```text
待审批 --APPROVE--> 待领取
待审批 --REJECT----> 已拒绝
待领取 --MANUAL_CLAIM/AUTO_ASSIGN--> 维护中
维护中 --COMPLETE--> 已完成
```

旧数据状态 `已通过` 兼容映射为 `待领取`。不在迁移表中的事件会抛出
`InvalidOrderTransitionException`，避免工单从待审批直接完成等非法跳转。

`OrderLifecycleService` 负责“状态机选择目标状态 + 按事件选择处理策略”。具体策略包括：

- `ApproveOrderEventHandler`：审批人、审批时间、抢单截止时间；
- `RejectOrderEventHandler`：拒绝审批信息；
- `ManualClaimOrderEventHandler`：人工抢单维修员；
- `AutoAssignOrderEventHandler`：自动派单维修员；
- `CompleteOrderEventHandler`：维修过程、方案、根因、验收结果、配件、标签和结束时间。

接入点：

- `DispatchTriggerService`：审批通过/拒绝和自动派单扫描前判断；
- `MaintainRecordServiceImpl`：人工抢单；
- `DispatchTransactionService`：Agent 自动派单；
- `WorkOrderCompletionService`：工单完成及知识 Outbox。

并发正确性仍由原有条件更新、乐观锁和唯一键兜底，状态机不替代数据库并发控制。

## 3. Outbox 模板流程 + 消息策略

通用组件位于 `common/rabbit_util/.../mq/outbox`：

- `ReliableOutboxPublisher`：固定 `find -> claim -> send -> confirm/return -> mark` 流程；
- `OutboxStore<T>`：封装不同 Outbox 表的查询、认领和状态回写；
- `OutboxMessageStrategy<T>`：封装事件 ID 和 RabbitMQ 消息发送差异。

模板统一处理：

- 批量查询可发布记录；
- 原子认领，避免多实例重复发布；
- 等待 RabbitMQ Publisher Confirm；
- 检查 mandatory return，防止消息不可路由却被误标为成功；
- 成功标记 `PUBLISHED`；
- 失败标记 `FAILED`，记录截断后的错误，并进行最长 300 秒的指数退避；
- `PUBLISHING` 超时后允许其他实例重新认领。

已迁移到模板的发布链路：

- 自动派单：`DispatchOutboxStore` + `DispatchOutboxMessageStrategy`；
- 工单知识：`WorkOrderKnowledgeOutboxStore` + `WorkOrderKnowledgeMessageStrategy`；
- Saga 请求：`DeviceBindingOutboxStore` + `DeviceBindingRequestMessageStrategy`；
- Saga 结果：`DeviceBindingResultOutboxStore` + `DeviceBindingResultMessageStrategy`。

因此这里确实是“模板方法思想 + 策略模式”，但使用组合和泛型实现，没有通过抽象父类继承。

## 4. 设备领用 Saga + 补偿

### 4.1 正向流程

```text
客户端 bind/unbind（可传 Idempotency-Key）
  -> service-acl 本地事务：关系进入 PENDING_* + 写请求 Outbox
  -> RabbitMQ 请求事件
  -> service-device 本地事务：条件更新设备状态 + 写命令幂等记录 + 写结果 Outbox
  -> RabbitMQ 结果事件
  -> service-acl 根据 requestId 完成关系状态
```

绑定成功时：

```text
ACL: PENDING_BIND -> ACTIVE
Device: 闲置 -> 使用
```

解绑成功时：

```text
ACL: 删除绑定关系
Device: 使用 -> 闲置
```

### 4.2 补偿规则

- 绑定失败：ACL 将 `PENDING_BIND` 补偿为 `FAILED`，不授予设备所有权；
- 解绑失败：ACL 将 `PENDING_UNBIND` 补偿回 `ACTIVE`；
- 重复请求消息：`device_binding_command.request_id` 唯一键和命令记录保证幂等；
- 重复或过期结果：ACL 只处理与当前关系 `request_id` 匹配的结果；
- 同一设备并发领用：`devicetousers.device_id` 唯一键阻止两个用户同时占有；
- Device 侧以条件 SQL 更新 `闲置 -> 使用` 或 `使用 -> 闲置`，状态冲突返回失败结果。

API 不再通过 ACL 事务内的同步 Feign 调用同时修改两个数据库。领用/解绑会返回
`PENDING`、`ACTIVE` 或 `CONFLICT`；只有 `ACTIVE` 关系会通过所有权校验。

### 4.3 消息与开关

消息合同放在 `model/.../shared`，版本为 `device-binding/v1`。RabbitMQ 拓扑为：

- Exchange：`flowfix.device-binding`；
- 请求队列：`flowfix.device.binding.requests`；
- 结果队列：`flowfix.acl.device.binding.results`。

开发环境通过 `device-binding.saga.enabled: true` 启用监听器和发布任务；测试环境关闭后台任务，测试直接调用业务服务，避免异步线程干扰断言。

## 5. 数据库迁移

迁移文件：`docker/mysql/init/08-device-binding-saga.sql`。

主要变更：

- `users.devicetousers` 增加绑定状态、请求 ID、失败原因和更新时间；
- 对 `device_id` 和 `request_id` 增加唯一约束；
- 新增 `users.device_binding_outbox`；
- 新增 `devices.device_binding_command`；
- 新增 `devices.device_binding_result_outbox`。

本地 Docker MySQL 已完成迁移。执行迁移前已检查现有绑定中不存在重复 `device_id`，已有绑定按默认值保留为 `ACTIVE`。

## 6. 测试结果

执行命令：

```bash
mvn -pl service/service-device,service/service-acl -am test
```

结果：`BUILD SUCCESS`，共 21 个测试，0 失败、0 错误。

新增覆盖：

- 状态机合法迁移、旧状态兼容、非法迁移；
- Outbox confirm 成功与发送失败退避；
- Device 侧绑定/解绑、幂等、状态冲突；
- ACL 侧绑定成功、重复请求、绑定失败补偿、重试、解绑失败补偿、解绑成功。

原有自动派单并发、抢单窗口、工单知识生命周期测试也全部通过。

## 7. 本地复测建议

1. 重启 `service-acl` 和 `service-device`，确保加载本次代码；
2. 请求领用接口时固定传入一个 `Idempotency-Key`，重复请求验证返回同一进行中请求；
3. 观察请求 Outbox、Device 命令表、结果 Outbox 和最终关系状态；
4. 将目标设备预先设置为非“闲置”，验证关系最终进入 `FAILED`；
5. 将 RabbitMQ 临时停用，验证 Outbox 保留为 `FAILED` 并按退避时间重试，恢复后最终发布。

注意：迁移脚本面向一次性部署，不应对同一数据库重复执行。现有正在运行的 Java 进程需要重启后才会加载新实现。
