# `service-device` 定时任务线程池隔离设计

## 1. 结论

`service-device` 当前有三个 `@Scheduled(fixedDelay = ...)` 定时任务：

1. `ExpiredClaimWindowScanner`：扫描超过人工抢单窗口的工单并生成自动派单事件；
2. `DispatchOutboxPublisher`：把自动派单 Outbox 事件可靠发布到 RabbitMQ；
3. `WorkOrderKnowledgeOutboxPublisher`：把已完成工单的知识事件可靠发布到 RabbitMQ。

调整前项目没有配置 Spring 调度线程池。Spring Boot 3.1 默认调度池大小为 `1`，因此三个任务会竞争同一条调度线程。两个 Outbox 发布器又会逐条同步等待 RabbitMQ Publisher Confirm，单条最长等待 `5s`，所以 RabbitMQ 变慢时可能连带推迟抢单超时扫描。

本设计采用两阶段方案：

- **第一阶段（已实施）**：把 Spring 调度池从 `1` 调整为 `3`，让三个现有任务可以同时运行；不改业务代码和一致性语义。
- **第二阶段（压测证明需要后再实施）**：调度线程只负责触发，把两个 Outbox 的发布工作分别放入有界、独立的执行器；不直接无限增加线程。

不要在抢单事务、候选维修员 N+1 查询或 RabbitMQ Listener 内部随意增加线程池。这些问题应分别通过数据库条件更新、批量 SQL 和 Listener Container 参数解决。

## 2. 当前实现与风险

### 2.1 三个任务

| 任务 | 当前周期 | 单批上限 | 是否包含阻塞 I/O | 业务重要性 |
|---|---:|---:|---|---|
| 超时抢单扫描 | `30s` | `100` | MySQL读写 | 高，决定普通工单何时转自动派单 |
| 自动派单 Outbox | `2s` | `50` | MySQL + RabbitMQ Confirm | 最高，影响派单时效 |
| 工单知识 Outbox | `2s` | `50` | MySQL + RabbitMQ Confirm | 中，不应拖慢在线派单 |

相关代码：

- `service/service-device/src/main/java/com/schoolwork/epsys/device/dispatch/trigger/ExpiredClaimWindowScanner.java`
- `service/service-device/src/main/java/com/schoolwork/epsys/device/dispatch/trigger/DispatchOutboxPublisher.java`
- `service/service-device/src/main/java/com/schoolwork/epsys/device/knowledge/WorkOrderKnowledgeOutboxPublisher.java`

### 2.2 最坏阻塞时间

两个 Publisher 当前在循环中执行：

```java
correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
```

在默认参数下，理论最坏时间为：

```text
50条/批 × 5秒/条 = 250秒/批
```

这不是正常吞吐预期，但它说明故障场景下单个 Publisher 可能长时间占用调度线程。如果调度池只有一个线程，另外两个任务只能等待。

### 2.3 为什么不是直接创建大量线程

发布任务属于 I/O 型任务，但它同时消耗：

- RabbitMQ Channel/连接与 Broker Confirm 能力；
- MySQL连接和 Outbox 状态更新能力；
- 下游 Agent 或知识摄取服务的消费能力。

线程数增长会把压力转移到连接池、Broker和下游，并可能打乱事件处理顺序。因此本项目首先需要的是**故障隔离**，不是盲目追求并行发送。

## 3. 第一阶段：共享调度池从1调整为3

### 3.1 实现方式

公共配置已加入 `service/service-device/src/main/resources/application.yaml`，因此 dev、test 以及以后新增的 profile 都会继承相同的调度池配置：

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 3
      thread-name-prefix: flowfix-scheduler-
      shutdown:
        await-termination: true
        await-termination-period: 30s
```

测试环境通过 `application-test.yaml` 仅覆盖关闭等待时间，避免测试进程因为异常的长任务等待过久：

```yaml
spring:
  task:
    scheduling:
      shutdown:
        await-termination-period: 5s
```

各参数含义如下：

| 参数 | 当前值 | 含义 |
|---|---:|---|
| `pool.size` | `3` | 最多使用3条调度线程执行到期的 `@Scheduled` 方法 |
| `thread-name-prefix` | `flowfix-scheduler-` | 统一线程名前缀，便于日志、JStack和监控定位 |
| `shutdown.await-termination` | `true` | 应用关闭时等待正在执行的任务结束 |
| `shutdown.await-termination-period` | dev等环境 `30s`，test `5s` | 关闭时的最长等待时间，避免无限等待 |

Spring Boot 3.1 官方文档确认可通过 `spring.task.scheduling.pool.size` 和 `thread-name-prefix` 配置自动创建的 `ThreadPoolTaskScheduler`：

- <https://github.com/spring-projects/spring-boot/blob/v3.1.12/spring-boot-project/spring-boot-docs/src/docs/asciidoc/features/task-execution-and-scheduling.adoc>

### 3.2 为什么当前不手写调度线程池

当前需求只涉及线程数、线程名和优雅停机，Spring Boot 自动配置已经完整覆盖。由 Spring 管理 `ThreadPoolTaskScheduler` 还能统一处理 Bean 初始化和应用关闭，不需要项目自行维护 `ScheduledThreadPoolExecutor` 生命周期。

“不要使用 `Executors` 创建线程池”通常是指不要直接采用 `Executors.newCachedThreadPool()`、`newFixedThreadPool()` 等隐藏无界线程数或近似无界队列的快捷工厂，并不表示不能使用 Spring 管理的线程池。`ThreadPoolTaskScheduler` 内部本身就是对 JDK `ScheduledThreadPoolExecutor` 的封装。

如果以后需要自定义 `ErrorHandler`、线程工厂、上下文传播或多个不同的调度器，可以显式声明 `ThreadPoolTaskScheduler` Bean。若目标是隔离耗时业务，则不应仅替换共享调度器，而应按第4节保留轻量调度器，并为不同业务建立独立、有界的工作执行器。

### 3.3 为什么初始值是3

当前只有三个定时任务，而且三者都是 `fixedDelay`：同一个任务要等上一次执行完成后才计算下一次延迟，不需要为同一个任务预留多个调度线程。

初始值取 `3` 的目的，是允许三个不同任务同时执行：

```text
flowfix-scheduler-1 -> 超时抢单扫描
flowfix-scheduler-2 -> 自动派单 Outbox（可能等待 Confirm）
flowfix-scheduler-3 -> 工单知识 Outbox（可能等待 Confirm）
```

线程与任务不是永久绑定关系，上图只表示一次可能的并发执行。`pool.size=3` 属于共享池隔离：它解决当前三个任务互相完全串行的问题，但未来新增第四个慢任务后仍可能产生竞争。

### 3.4 第一阶段不修改的内容

- 不给三个类添加 `@Async`；
- 不修改 Outbox 的数据库认领、重试和幂等逻辑；
- 不改变 `fixedDelay` 周期；
- 不并行发布同一批事件；
- 不调整 Hikari、RabbitMQ 或 Redisson 连接池；
- 不引入 ShedLock。当前数据库认领更新和唯一事件 ID 仍是多实例防重的最终防线。

### 3.5 优点与边界

优点：

- 改动小，可快速回滚；
- 不改变业务执行顺序和事务边界；
- RabbitMQ慢时，超时工单扫描仍有机会按时执行；
- 线程名称便于通过日志、JStack和监控识别。

边界：

- 自动派单 Publisher 自身仍是逐条等待 Confirm，单个任务吞吐没有提高；
- 三个任务仍共享一个池，不是资源上的绝对隔离；
- 多实例部署时，每个实例都有自己的调度池，线程总数等于 `实例数 × 3`。

## 4. 第二阶段：为两个 Outbox 建立独立有界执行器

只有出现以下压测证据时才进入第二阶段：

- Outbox 待发布数量持续增长；
- `created_at -> published_at` 的 P95/P99 延迟超过业务目标；
- RabbitMQ偶发慢 Confirm 仍明显影响调度触发；
- 共享调度池3线程仍出现持续饱和。

### 4.1 目标结构

```text
共享TaskScheduler（只做短触发）
        |
        +--> 超时抢单扫描（短数据库任务）
        |
        +--> DispatchOutboxTrigger
        |          |
        |          +--> dispatchOutboxExecutor（独立、有界）
        |
        +--> KnowledgeOutboxTrigger
                   |
                   +--> knowledgeOutboxExecutor（独立、有界）
```

核心派单与知识沉淀使用不同执行器，知识事件积压不能耗尽自动派单的工作线程。

### 4.2 初始参数建议

| 执行器 | core/max | 队列 | 原因 |
|---|---:|---:|---|
| `dispatchOutboxExecutor` | `1/1` | `1` | 保持派单事件顺序，先验证隔离，不贸然并行发送 |
| `knowledgeOutboxExecutor` | `1/1` | `1` | 知识链路允许独立变慢，但不能无限积压内存任务 |

这里队列只保存“执行一批”的任务，不保存每一条 Outbox 事件。真正的可靠队列仍然是 MySQL Outbox 表。

线程池队列不能代替 Outbox：进程重启后内存任务会消失，而数据库中的 `PENDING/FAILED` 事件可以重新扫描。

### 4.3 推荐类职责

```text
DispatchOutboxTrigger
  - 保留 @Scheduled
  - 判断本实例是否已有一批正在执行
  - 提交一次 runBatch()

DispatchOutboxWorker
  - 查询、认领并发布一批派单事件
  - 不包含 @Scheduled

KnowledgeOutboxTrigger
  - 保留 @Scheduled
  - 提交一次知识事件批任务

KnowledgeOutboxWorker
  - 查询、认领并发布一批知识事件
```

Trigger 和 Worker 必须拆成不同 Spring Bean。如果在同一个对象中通过 `this.xxx()` 调用 `@Async` 方法，会绕过 Spring代理，异步不会生效。为了让并发和拒绝行为更显式，本项目更推荐直接注入命名的 `TaskExecutor` 并调用 `execute()`，而不是依赖 `@Async`。

### 4.4 防止重复提交批任务

每个 Trigger 使用 `AtomicBoolean` 只防止**同一实例**重复提交：

```java
if (!running.compareAndSet(false, true)) {
    // 上一批仍在运行，本轮跳过并记录指标
    return;
}
try {
    executor.execute(() -> {
        try {
            worker.runBatch();
        } finally {
            running.set(false);
        }
    });
} catch (RejectedExecutionException exception) {
    running.set(false);
    // 记录拒绝；事件仍在数据库，等待下一轮扫描
}
```

`AtomicBoolean` 不是分布式锁。跨实例防重仍依赖现有的：

- `claimForPublishing(...)` 条件更新；
- Outbox 唯一 `event_id`；
- 消费端幂等。

### 4.5 拒绝策略

不要使用无界队列，也不要静默使用 `DiscardPolicy`。

推荐行为是：

1. 队列已满时拒绝本轮批任务；
2. 记录结构化日志和计数指标；
3. 不修改尚未认领的 Outbox 数据；
4. 等下一次定时扫描继续处理。

由于可靠状态在数据库中，“跳过一次触发”比无限堆积内存任务更安全。

### 4.6 什么时候再增加 Publisher 并行度

只有单线程 Publisher 已成为稳定瓶颈，并确认以下资源仍有余量时，才考虑从 `1` 增加到 `2`：

- Hikari 活跃连接和等待时间；
- RabbitMQ Channel/Confirm 延迟；
- Broker CPU、内存和队列堆积；
- Agent消费速率；
- 同一工单事件的顺序与幂等测试。

优先考虑批量或异步 Confirm，通常比简单增加阻塞线程更高效。

## 5. 监控指标

没有指标就不能证明线程池优化有效。至少记录：

### 5.1 调度及时性

```text
claim_timeout_trigger_lag
= 自动派单事件创建时间 - claim_deadline
```

目标示例：P95 不超过 `scan-delay-ms + 5s`。最终目标应由压测基线决定。

### 5.2 Outbox指标

- `outbox_pending_count{type=dispatch|knowledge}`；
- `outbox_oldest_pending_age_seconds`；
- `outbox_publish_duration`；
- `outbox_publish_success_total`；
- `outbox_publish_failure_total`；
- `outbox_retry_total`。

### 5.3 线程池指标

- 活跃线程数；
- 队列长度；
- 已完成任务数；
- 拒绝次数；
- 单批处理耗时；
- 服务关闭时未完成任务数。

日志至少携带 `eventId`、`dispatchId`、`orderId`、`retryCount` 和线程名，不输出密码、Token或完整敏感 Payload。

## 6. 测试方案

### 6.1 建立基线

先使用当前默认单调度线程执行一次测试，记录：

- 50条派单 Outbox；
- 50条知识 Outbox；
- 10条已超过 `claim_deadline` 的待领取工单；
- 正常 RabbitMQ 和慢 Confirm/RabbitMQ不可用两种场景。

记录工单超时触发延迟、两个 Outbox 的发布耗时、线程名和数据库连接使用情况。

### 6.2 第一阶段验收

加入 `pool.size=3` 后重复完全相同的数据集，验证：

1. 两个 Publisher 同时阻塞时，超时扫描仍能执行；
2. 工单超时事件没有重复业务副作用；
3. Outbox 最终进入 `PUBLISHED` 或按退避规则进入 `FAILED`；
4. RabbitMQ恢复后所有失败事件能够重新发布；
5. JStack/日志中能看到 `flowfix-scheduler-` 前缀；
6. 关闭服务时最多等待配置的30秒，不出现无限挂起。

核心断言不是单纯 TPS 变高，而是：

> RabbitMQ或知识链路变慢时，核心抢单超时扫描不再被同一个调度线程长时间阻塞。

### 6.3 第二阶段验收

若实现独立执行器，再增加：

1. 知识执行器饱和时，派单执行器仍可发布；
2. 同一 Trigger 不会并发提交多个批任务；
3. 拒绝批任务不会把数据库事件错误标记为已发布；
4. 进程在批处理中被终止后，`PUBLISHING` 超时事件能重新认领；
5. 执行器队列始终有界；
6. 多实例同时扫描时仍只有幂等业务结果。

### 6.4 建议对比表

| 指标 | 单线程基线 | 调度池3线程 | 独立执行器（若实施） |
|---|---:|---:|---:|
| 抢单超时触发 P95 | 待测 | 待测 | 待测 |
| 派单 Outbox 发布 P95 | 待测 | 待测 | 待测 |
| 知识 Outbox 发布 P95 | 待测 | 待测 | 待测 |
| 最大活跃线程 | 待测 | 待测 | 待测 |
| 最大队列长度 | 不适用 | 调度器内部 | 待测 |
| 重复业务结果 | 待测 | 必须为0 | 必须为0 |
| 拒绝次数 | 不适用 | 不适用 | 待测 |

## 7. 实施顺序与回滚

### 7.1 实施顺序

1. 补充基线数据和慢 Confirm 故障测试；
2. 只增加 `spring.task.scheduling` 配置；
3. 重复测试并比较指标；
4. 如果调度延迟问题消失且 Outbox 不积压，到此结束；
5. 如果 Outbox 仍持续积压，再实现两个独立有界执行器；
6. 最后才评估异步 Confirm 或提高单个 Publisher 并行度。

### 7.2 回滚

第一阶段仅为配置变更，删除 `spring.task.scheduling` 配置即可恢复默认行为。第二阶段应保留同步 `runBatch()` 能力和开关，使执行器异常时可以退回同步调度；数据库 Outbox 记录不得因回滚丢失。

## 8. 不采用的方案

### 为每条 Outbox 事件创建一个 `CompletableFuture`

拒绝。它会让一批50条事件瞬间并发访问 RabbitMQ和数据库，异常传播、线程来源、背压和顺序都不清晰。

### 使用 `Executors.newCachedThreadPool()`

拒绝。线程数无界，故障时可能耗尽内存和连接池。

### 在线程池中反复轮询数据库

拒绝。调度器已经负责周期触发，工作线程只执行一批后退出，避免永久占用线程。

### 用线程池代替 Outbox

拒绝。线程池只解决进程内调度和隔离，不能保证宕机恢复、消息不丢和跨实例幂等。

### 一开始就引入分布式调度锁

暂不采用。当前数据库条件认领和事件唯一键已经保障最终防重。只有多实例扫描造成了可测量的数据库压力时，再评估 ShedLock 或任务分片。

## 9. 面试口述版本

> `service-device` 有抢单超时扫描、自动派单 Outbox 和知识 Outbox 三个定时任务。Spring Boot 默认调度池只有一个线程，而两个 Publisher 会同步等待 RabbitMQ Confirm，因此下游变慢可能阻塞核心抢单扫描。我没有直接堆线程，而是先把共享调度池调整为3并增加线程命名和优雅停机，用故障压测验证调度延迟。如果发布端仍积压，再把两个 Outbox 拆到各自的有界单线程执行器，内存队列只保存批任务，可靠状态仍由数据库 Outbox、条件认领和消费幂等保证。这样解决的是故障隔离和背压问题，而不是为了使用线程池而使用线程池。
