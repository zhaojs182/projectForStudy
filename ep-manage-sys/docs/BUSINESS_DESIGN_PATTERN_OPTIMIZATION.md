# Java 业务设计模式优化方案

## 1. 文档目的

本文梳理 `ep-manage-sys` 中真正具有业务必要性的设计模式优化方向。目标不是增加设计模式数量，而是解决当前已经存在的业务一致性、规则重复、状态失控、代码重复和跨服务失败问题，并为后续实现与测试提供依据。

线程池隔离方案单独记录在 [定时任务线程池隔离设计](SCHEDULED_TASK_POOL_ISOLATION_DESIGN.md)，本文不重复讨论线程池参数。

## 2. 优先级总览

| 优先级 | 业务 | 推荐模式或架构模式 | 当前主要问题 | 是否建议近期实现 |
|---|---|---|---|---|
| P0 | 工单审批、抢单、派单、完成 | 状态机 + 事件处理策略 | 状态字符串散落，非法跳转难以统一阻止 | 已完成 |
| P0 | 维修员候选过滤与写前校验 | Specification 组合规格 | 资格规则重复，读写标准可能漂移 | 是 |
| P1 | 两类 Outbox 发布 | 模板流程 + 消息策略 | Publisher 可靠发布流程高度重复 | 已完成，并复用于设备领用Saga |
| P1 | 设备领用跨服务更新 | Saga + 补偿 + Outbox | ACL成功、Device失败时跨服务数据不一致 | 已完成 |
| P1 | JWT、权限和异常响应 | 拦截器/代理 + 权限策略 + 注解 | Controller重复解析身份、远程查权限和写响应 | 是 |
| P1 | 通知和ES索引同步 | 领域事件 + 发布订阅 + Outbox | 数据库成功但消息发送失败时状态不一致 | 是 |
| P2 | 外部服务调用边界 | Adapter/Gateway + Facade | Controller和业务服务直接依赖Feign或消息实现 | 随上述重构实施 |

## 3. 工单生命周期：状态机 + 事件处理策略

### 3.1 当前问题

工单状态目前主要以中文自由字符串存在，并在多个类中直接比较或赋值：

```java
order.setStatus("待领取");
order.setStatus("已拒绝");
order.setStatus("维护中");
order.setStatus("已完成");
```

主要位置包括：

- `DispatchTriggerService`：审批通过、拒绝和抢单截止时间初始化；
- `MaintainRecordServiceImpl`：人工抢单；
- `DispatchTransactionService`：Agent派单；
- `WorkOrderCompletionService`：完成维修；
- `MaintainRecordMapper`：SQL中直接判断状态字符串。

这种实现存在以下风险：

1. 任意业务代码都可以直接修改状态；
2. 状态拼写错误只能在运行时发现；
3. 审批、人工抢单、Agent派单和完成操作可能使用不同的合法状态判断；
4. 新增取消、重新打开、暂停等状态后，条件分支会继续散落；
5. 很难统一审计“谁在什么状态下触发了什么事件”。

### 3.2 建议状态模型

```java
public enum MaintainOrderStatus {
    PENDING_APPROVAL("待审批"),
    WAITING_FOR_CLAIM("待领取"),
    IN_PROGRESS("维护中"),
    COMPLETED("已完成"),
    REJECTED("已拒绝");

    private final String databaseValue;
}
```

业务操作表达为事件：

```java
public enum MaintainOrderEvent {
    APPROVE,
    REJECT,
    CLAIM,
    AUTO_ASSIGN,
    COMPLETE
}
```

第一阶段建议使用显式迁移表，不必立即为每个状态建立一个庞大的 State 类：

```text
PENDING_APPROVAL
  ├── APPROVE      -> WAITING_FOR_CLAIM
  └── REJECT       -> REJECTED

WAITING_FOR_CLAIM
  ├── CLAIM        -> IN_PROGRESS
  └── AUTO_ASSIGN  -> IN_PROGRESS

IN_PROGRESS
  └── COMPLETE     -> COMPLETED
```

### 3.3 推荐职责

```java
public interface OrderStateMachine {
    MaintainOrderStatus transition(
            MaintainOrderStatus current,
            MaintainOrderEvent event);
}
```

状态机只回答“能否迁移以及目标状态是什么”，不同业务事件的副作用由处理策略负责：

```java
public interface OrderTransitionHandler {
    MaintainOrderEvent supports();

    TransitionResult handle(
            MaintainRecord order,
            TransitionContext context);
}
```

建议的处理器：

- `ApproveOrderHandler`：设置审批人、审批时间和抢单截止时间；
- `RejectOrderHandler`：保存拒绝结果；
- `ClaimOrderHandler`：绑定维修员并生成唯一领取记录；
- `AutoAssignOrderHandler`：执行Agent派单条件更新和审计；
- `CompleteOrderHandler`：保存维修结果并写知识 Outbox。

处理器不能绕过数据库条件更新、乐观锁和唯一键。状态机解决规则集中问题，数据库仍是并发写入的最终防线。

### 3.4 测试要求

- 待审批可以审批通过；
- 待审批可以拒绝；
- 待审批不能直接完成；
- 待领取可以人工抢单；
- 待领取可以由Agent派单；
- 维护中可以完成；
- 已完成和已拒绝都是终态，不能继续迁移；
- 并发抢单和自动派单仍只有一个成功者；
- 非法迁移返回稳定错误码，而不是只返回中文字符串。

## 4. 维修员资格判断：Specification 组合规格模式

### 4.1 当前问题

派单候选查询和最终写入都需要判断：

- 是否属于同一租户；
- 维修员是否启用；
- 当前是否可用；
- 是否在岗；
- 实时负载是否小于容量；
- 区域是否匹配；
- 技能是否匹配。

候选查询位于 `DispatchQueryService#getEligibleWorkers`，写前硬门禁位于 `DispatchTransactionService#workerEligibilityFailure`。两边存在相似但独立的规则，未来增加资格条件时容易只修改其中一处。

### 4.2 推荐结构

```java
public interface WorkerEligibilityRule {
    EligibilityResult evaluate(EligibilityContext context);
}
```

每条规则独立：

```text
TenantRule
ActiveRule
AvailabilityRule
ShiftRule
CapacityRule
RegionRule
SkillRule
```

组合策略按顺序执行：

```java
public final class WorkerEligibilityPolicy {

    private final List<WorkerEligibilityRule> rules;

    public EligibilityResult evaluate(EligibilityContext context) {
        for (WorkerEligibilityRule rule : rules) {
            EligibilityResult result = rule.evaluate(context);
            if (!result.eligible()) {
                return result;
            }
        }
        return EligibilityResult.accepted();
    }
}
```

结果必须携带结构化原因，不能只返回 `boolean`：

```java
public record EligibilityResult(
        boolean eligible,
        ReasonCode reasonCode,
        String failedRule) {
}
```

### 4.3 关键边界

候选查询可以复用该 Policy 过滤维修员，但最终派单事务必须重新查询实时负载并再次执行所有硬门禁。

```text
候选读取：提供一个可能合格的冻结候选集
最终写入：根据数据库当前状态重新校验并条件更新
```

复用的是规则定义，不是复用几秒前的校验结果。

Specification 不能用来掩盖当前候选查询的 N+1 SQL。负载和技能应先批量查询，再构造 `EligibilityContext`，不应通过线程池并行执行每个维修员的SQL。

### 4.4 测试要求

建议使用参数化测试：

| 场景 | 预期结果 |
|---|---|
| 维修员不存在 | `WORKER_NOT_FOUND` |
| 跨租户 | `WORKER_NOT_ELIGIBLE` |
| 未启用 | `WORKER_NOT_ELIGIBLE` |
| 不可用 | `WORKER_NOT_ELIGIBLE` |
| 不在岗 | `WORKER_NOT_ELIGIBLE` |
| 容量已满 | `WORKER_NOT_ELIGIBLE` |
| 区域不匹配 | `WORKER_NOT_ELIGIBLE` |
| 技能不匹配 | `WORKER_NOT_ELIGIBLE` |
| 全部通过 | `ACCEPTED` |

还需要一项一致性测试：候选查询使用的规则集合与写前硬门禁使用的规则集合必须相同。

## 5. Outbox发布：模板流程 + 消息策略

### 5.1 当前问题

`DispatchOutboxPublisher` 和 `WorkOrderKnowledgeOutboxPublisher` 的可靠发布流程几乎相同：

```text
查询待发布记录
-> 条件认领
-> 构建RabbitMQ消息
-> 发送并等待Confirm
-> 标记PUBLISHED
-> 失败时计算退避并标记FAILED
```

主要区别只有：

- Outbox实体和Mapper；
- Exchange和Routing Key；
- Payload、事件ID和状态回写方式。

如果两处继续独立演化，容易出现一条链路补了超时、退避或日志，另一条遗漏。

### 5.2 推荐结构

优先采用组合，不强制使用继承：

```java
public final class ReliableOutboxPublisher<T> {

    private final OutboxStore<T> store;
    private final OutboxMessageStrategy<T> messageStrategy;

    public void publishBatch(int batchSize) {
        // 固定模板：find -> claim -> send -> confirm -> mark
    }
}
```

存储端口：

```java
public interface OutboxStore<T> {
    List<T> findPublishable(Instant now, Instant staleBefore, int limit);
    boolean claim(T event, Instant now, Instant staleBefore);
    void markPublished(T event);
    void markFailed(T event, Instant nextRetryAt, String error);
}
```

消息策略：

```java
public interface OutboxMessageStrategy<T> {
    String eventId(T event);
    byte[] payload(T event);
    String exchange();
    String routingKey();
}
```

具体实现：

```text
DispatchOutboxStore
DispatchOutboxMessageStrategy
KnowledgeOutboxStore
KnowledgeOutboxMessageStrategy
```

### 5.3 不要过度抽象

只抽取已重复的可靠发布流程，不要把所有消息业务做成使用反射和大量配置驱动的万能框架。业务差异较大时，保留具体实现比强行复用更清晰。

### 5.4 测试要求

- 两类消息使用正确的Exchange和Routing Key；
- Confirm ACK后才标记 `PUBLISHED`；
- NACK、Returned和超时都标记 `FAILED`；
- 退避时间计算保持不变；
- 同一记录只有认领成功的实例可以发送；
- 重构前后事件ID和Payload合同不变；
- 服务重启后过期 `PUBLISHING` 事件仍能被重新认领。

## 6. 设备领用：Saga + 补偿 + Outbox

### 6.1 当前问题

设备领用目前跨越 ACL 和 Device 两个服务：

```text
ACL保存用户-设备关系
-> Feign调用Device
-> Device把设备状态改为“使用”
```

如果第一步成功、Feign超时或Device服务失败，就会形成：

```text
ACL：设备已经属于用户
Device：设备仍然闲置
```

解绑流程存在同类问题。线程池、重试注解或本地事务都不能单独解决跨服务一致性。

### 6.2 推荐状态

关系表可增加显式状态：

```text
PENDING_BIND
ACTIVE
PENDING_UNBIND
FAILED
```

### 6.3 推荐流程

绑定：

```text
ACL事务
  ├── 创建PENDING_BIND关系
  └── 写DeviceBindRequested Outbox

Device幂等消费
  ├── 条件更新设备状态为“使用”
  └── 发布DeviceBindSucceeded/Failed

ACL幂等消费结果
  └── PENDING_BIND -> ACTIVE/FAILED
```

解绑采用对应的 `DeviceUnbindRequested` 流程。重试耗尽后进入人工处理或补偿任务，不允许永久停留在中间状态而无监控。

### 6.4 是否必须使用Saga

如果项目继续保留微服务边界并要求跨服务故障恢复，Saga有真实必要性。

如果这是一个流量不高、可以调整服务边界的项目，更简单的方案可能是让设备领用关系和设备状态由同一服务、同一数据库事务拥有。不要为了使用Saga而保留不合理的数据归属。

### 6.5 测试要求

- Device正常时最终变为 `ACTIVE`；
- Device超时后事件可以重试；
- 重复消息不会重复绑定；
- 设备已经被其他用户绑定时返回稳定冲突；
- 结果消息丢失后能够通过重放或对账收敛；
- 补偿不会删除已经由其他合法流程更新的数据；
- 中间状态有超时扫描和告警。

## 7. 权限与当前用户：拦截器/代理 + 策略 + 自定义注解

### 7.1 当前问题

多个 Controller 手动读取 `Authorization`、调用 `JwtUtils.parseJwt`、提取用户ID，再通过 Feign 查询权限并手写错误响应。这导致：

- 未登录、Token非法和无权限的返回语义不统一；
- Controller依赖ACL远程调用细节；
- 容易遗漏鉴权；
- 测试每个接口都需要重复构造身份逻辑；
- 当前还存在打印完整Token的安全风险。

### 7.2 首选方案

长期首选 Spring Security 和方法级授权。若暂不迁移，可先建立项目内的统一边界：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String value();
}
```

使用方式：

```java
@RequirePermission("dispatch:auto:trigger")
@PostMapping("/{orderId}/auto-dispatch")
public Result<?> triggerAutoDispatch(
        @CurrentUser LoginUser user,
        @PathVariable Integer orderId) {
    // 这里只处理业务
}
```

职责拆分：

```text
TokenAuthenticationInterceptor
  -> 校验Token并建立可信Principal

CurrentUserArgumentResolver
  -> 把Principal注入Controller参数

PermissionInterceptor/AOP
  -> 读取@RequirePermission
  -> 调用PermissionEvaluator

PermissionEvaluator
  -> 封装本地/ACL权限查询策略
```

### 7.3 安全边界

- 用户ID必须来自可信Token，不能来自请求体；
- 权限服务超时默认拒绝高风险写操作；
- 401、403和503应明确区分；
- 不在日志中输出完整Token；
- 自定义注解不能绕过网关和服务端双重鉴权；
- 幂等是业务语义，不建议用一个通用 `@Idempotent` 隐藏所有数据库规则。

### 7.4 测试要求

- 未登录返回401；
- Token非法或过期返回401；
- 已登录但无权限返回403；
- 权限依赖不可用时返回503或既定失败语义；
- 鉴权失败时业务方法没有执行；
- 请求体中的伪造用户ID不起作用；
- 不同权限注解映射到正确的权限码。

## 8. 通知与ES同步：领域事件 + 发布订阅 + Outbox

### 8.1 当前问题

设备新增、更新和删除目前采用：

```text
更新MySQL
-> Controller直接发送RabbitMQ消息
```

如果数据库成功而RabbitMQ发送失败，ES索引会与MySQL不一致。审批通知和维修员刷新也存在相似的“业务写入后直接发送”逻辑。

### 8.2 推荐流程

```text
设备业务事务
  ├── 更新deviceinstance
  └── 写DeviceChangedEvent Outbox

Outbox Publisher
  -> RabbitMQ

消费者
  ├── Search服务更新ES
  ├── Message服务推送WebSocket
  └── 未来其他订阅者
```

领域服务只表达“设备发生了什么变化”，不直接知道所有下游消费者。这一方案组合了：

- Domain Event（领域事件）；
- Observer/Publish-Subscribe（观察者/发布订阅）；
- Transactional Outbox；
- Idempotent Consumer（幂等消费者）。

### 8.3 事件合同建议

```java
public record DeviceChangedEventV1(
        String schemaVersion,
        String eventId,
        String tenantId,
        Integer deviceId,
        Integer version,
        String eventType,
        Instant occurredAt) {
}
```

事件中保留版本号，Search消费者只接受不小于当前索引版本的事件，避免旧消息覆盖新数据。

### 8.4 测试要求

- 数据库事务失败时不产生可发布事件；
- RabbitMQ不可用时事件保留在Outbox；
- Broker恢复后事件最终发布；
- 重复事件不会产生重复业务副作用；
- 旧版本事件不能覆盖新版本ES文档；
- 删除事件重复消费仍是幂等成功；
- 消费失败进入有界重试和DLQ。

## 9. Adapter/Gateway与Facade边界

在实施上述优化时，可以把外部依赖封装为业务端口：

```text
AclAuthorizationGateway
DeviceStatusGateway
NotificationGateway
SearchIndexEventGateway
```

业务服务依赖接口，Feign、RabbitMQ和本地Fake作为不同Adapter。这样可以：

- 避免Controller直接依赖Feign和RabbitTemplate；
- 单元测试使用Fake Adapter；
- 统一超时、错误码、重试和日志；
- 替换通信方式时不改核心业务规则。

不要为每个Mapper再套一层无业务语义的Facade，也不要建立只做一行转发的接口。只有外部系统边界、跨服务协议或确实需要替换的实现才值得建立Adapter。

## 10. 不建议为了展示而增加的模式

### Singleton

Spring Bean默认已经由容器按单例管理，无需自行实现双重检查锁单例。

### 简单工厂/抽象工厂

当前普通Service、Mapper和DTO没有复杂创建过程，增加工厂只会多一层跳转。只有存在多种真正可替换的派单、通知或存储实现时才考虑工厂。

### Builder

简单DTO、record或字段很少的对象不需要统一套Builder。只有参数多、存在必填/可选组合且构造过程容易出错时才有价值。

### 为Controller参数校验建立责任链

格式和长度校验优先使用 Bean Validation。责任链或Specification只用于有组合价值的业务规则。

### 通用 `@Retry` 和 `@Idempotent`

重试必须区分临时故障与永久业务失败；幂等必须依赖业务键、命令摘要和数据库唯一约束。一个注解不能替代这些业务设计。

### 为每个调用增加Facade或Adapter

如果一个接口只有一个实现、没有外部边界、测试也不需要替换，额外抽象通常没有收益。

## 11. 推荐实施顺序

### 第一阶段：纯业务规则，风险最低

1. 建立工单状态枚举和显式迁移表；
2. 为现有审批、抢单、Agent派单和完成流程补齐状态迁移测试；
3. 抽取维修员资格 Specification；
4. 批量查询维修员负载和技能，消除 N+1；
5. 验证候选过滤与写前硬门禁规则一致。

### 第二阶段：可靠消息重构

1. 完成定时任务线程池基线和隔离测试；
2. 抽取两类 Outbox 共同发布模板；
3. 把设备索引同步和关键通知改为 Transactional Outbox；
4. 增加消费幂等、重试和DLQ；
5. 增加事件积压和旧版本覆盖监控。

### 第三阶段：跨服务和通用边界

1. 统一当前用户、权限和异常响应；
2. 根据微服务边界决定设备领用采用Saga还是收归单服务事务；
3. 把Feign和RabbitMQ调用封装为业务Gateway；
4. 补充跨服务故障、恢复和对账测试。

## 12. 最适合作为下一项实现的方案

线程池隔离之后，优先推荐实现：

```text
工单状态机
+
维修员资格Specification
```

原因：

- 直接覆盖审批、人工抢单、Agent派单和完成四条核心链路；
- 能防止真实非法状态和规则漂移；
- 主要是纯Java业务代码，单元测试成本低；
- 不要求修改Docker组件或引入新的中间件；
- 可以用非法迁移矩阵、规则参数化测试和并发测试证明效果；
- 相比只增加工厂或Builder，更有项目和面试价值。

## 13. 面试口述版本

> 我没有为了展示而给每个类套设计模式，而是先识别真实变化点。工单生命周期用状态机集中约束合法迁移，再用事件处理策略封装审批、抢单、派单和完成的副作用；维修员资格使用Specification组合租户、班次、容量、区域和技能规则，并在候选读取和最终写入阶段复用同一套规则定义。两个Outbox Publisher采用模板流程加消息策略统一Confirm、失败退避和状态回写。跨服务设备领用则根据边界选择Saga补偿或收归单服务事务。所有模式都要求有非法迁移、规则参数化、消息故障和跨服务恢复测试，不以增加类数量作为优化结果。
