# 简历与面试整理：ep-manage-sys

## 1. 结论先行

FlowFix 目前由两个相邻项目共同组成：`ep-manage-sys` 负责 Java 微服务、工单真相和
最终写入，`myAgent` 负责 FastAPI、确定性派单、LangGraph 固定状态图、HITL、Tool
Guard、RAG 与 Java HTTP Adapter。简历和面试必须明确两个仓库的职责，不要让人误以为
Python Agent 代码位于本仓库。

2026-08-05 已完成 `dispatch-contract/v1` 同步纵向联调：真实人工审批派单返回
`ACCEPTED -> ASSIGNED`，数据库 version 从 0 增至 1；同命令重放返回
`ALREADY_APPLIED`，同 ID 不同命令返回 `IDEMPOTENCY_KEY_CONFLICT`。Java 并发测试
覆盖 20、50、100 个竞争请求并验证唯一赢家。Redis 跨进程恢复和 RabbitMQ
ACK/Retry/DLQ 尚未接入，不能写成已完成。

## 2. 推荐项目名称和简介

**FlowFix 智能工单协作与受控 Agent 派单平台｜Java + Python 双项目**

Java 侧基于 Spring Cloud Alibaba 构建设备运维微服务，Python 侧以确定性决策、
LangGraph 固定工作流和人工审批实现受控派单；双方通过版本化内部 HTTP 合同交换冻结
快照、幂等派单命令、结构化回执和数据库最终 outcome。

## 3. 可直接替换到简历的 bullets

- 按网关、权限、设备、审批、消息、搜索拆分 6 个 Spring Boot 应用，使用 Nacos 完成服务注册发现、Gateway 统一路由、OpenFeign 跨服务调用，MySQL 分库维护用户域与设备域数据。
- 为 Agent 设计 `dispatch-contract/v1`，提供工单/候选快照、派单命令和 outcome 接口；使用 `expectedVersion + 未分配状态 + 合法状态` 条件更新、唯一领取记录和事务审计保证最终写入一致性。
- 将确定性评分、LangGraph 固定状态图与 HITL 接入真实 Java Adapter，跑通人工审批后的 `ACCEPTED -> ASSIGNED`、数据库 outcome 核验、幂等重放和命令冲突链路。
- 补充 20/50/100 并发、过期 version、非法维修员和重放测试，验证新派单入口只有一个赢家、一次 version 增长和一条领取记录；Java 合同/事务测试共 7 项通过。
- 通过 RabbitMQ 解耦维修员申请审批、设备 Elasticsearch 索引增删和业务通知；实现 WebSocket/STOMP JWT 握手鉴权、用户单播/主题广播、聊天消息持久化与 Redis 在线状态管理。
- 搭建 MySQL、Redis、RabbitMQ、Nacos、Elasticsearch 的共享 Docker Compose 环境，完成 Java-Agent 真实 HTTP 联调及用户、设备、MQ、搜索和 WebSocket 基线链路验证。

“保证唯一领取”的结论只能限定到 Agent 专用 `/internal/dispatch/v1/assignments` 新入口；
旧前端抢单接口仍在兼容，不能把结论扩大到所有写入口。

## 4. 原简历亮点核验

| 原表述 | 核验 | 建议 |
|---|---|---|
| Spring Cloud 工单/审批/抢单/流程 | 部分真实 | 保留，名称改成设备运维工单；当前没有正式流程引擎 |
| Redisson 保证同一工单互斥抢单 | 旧入口仍不应这样表述；新 Agent 入口已有数据库最终防线 | 限定为“新入口通过条件更新和唯一键验证唯一领取” |
| DB 乐观锁与幂等保障 | 新 Agent 入口已有 `expectedVersion`、命令摘要和持久化审计 | 可以写，但必须限定 `dispatch-contract/v1` 范围 |
| RabbitMQ Java-Python 事件协作 | Java 事件真实，Python 无证据 | 改为 Java 微服务间异步事件 |
| 消费幂等、日志追踪、失败重试 | 无完整实现 | 删除，或完成 outbox/幂等表/DLX 后再写 |
| FastAPI Agent、确定性派单、HITL | 位于相邻 `myAgent`，已有代码、测试和真实 Java Adapter | 可写，并明确双项目边界 |
| RAG 混合检索、重排、引用校验 | 位于 `myAgent`，有固定集与评测报告 | 只写已有实现和实测口径 |
| RAGAS/LangSmith 评测 | 当前仍无完成证据 | 删除，避免虚构指标 |
| 自由 ReAct/多 Agent 自主写入 | 当前采用固定 StateGraph 与受控 Tool，不是自由 Agent | 按真实架构表述为“受控工作流” |

## 5. 面试 90 秒项目介绍

“我做的是一个 Java 微服务与 Python Agent 协作的设备运维平台。Java 侧拆成网关、
ACL、设备、审批、消息和搜索 6 个应用，拥有工单数据库和最终写权限；Agent 侧不直接
写库，而是读取冻结的工单与维修员快照，经过确定性评分和 LangGraph 固定工作流，必要
时暂停等待人工审批，再通过版本化内部合同提交派单。

联调中我重点解决了‘有分布式锁不等于业务唯一’的问题：写入 SQL 同时约束
expectedVersion、未分配状态和合法工单状态，受影响行数必须为 1，并把唯一领取记录、
命令摘要和审计放进同一事务。Agent 收到 receipt 后还会按 dispatchId 查询 MySQL
outcome，不把 HTTP 200 当完成。最终人工审批链路完成真实写入；同命令重放不增加
副作用，20、50、100 并发测试都只有一个赢家。下一步是把同样的 outcome 语义接到
RabbitMQ ACK/Retry/DLQ 和 Redis 跨进程恢复。”

## 6. 高频追问与答题要点

### 为什么拆成这些服务？

按业务能力拆分：ACL 管身份权限，Device 管设备和工单，Message 管连接与推送，Search 管检索投影，Approval 管申请审批，Gateway 管入口。当前 Approval 过薄、Device 同时承担设备和工单较重；如果规模不大，模块化单体可能成本更低，这是项目现阶段的真实权衡。

### Gateway、Nacos、Feign 的请求链是什么？

客户端请求 8200，Gateway 根据显式路由或发现路由匹配服务名，LoadBalancer 从 Nacos 实例列表选地址。服务内部 Feign 同样按服务名发现实例。当前 discovery locator 会额外暴露 `/service-name/**`，生产上应关闭并只留白名单路由。

### 加了 Redisson 锁为什么还会重复抢单？

锁只负责降低同工单竞争，最终正确性由数据库保证。新 Agent 入口使用包含
`expectedVersion`、`miantain_id IS NULL` 和合法状态的条件更新，受影响行数必须为 1；
唯一领取记录和事务避免重复副作用。20、50、100 并发测试均验证只有一个赢家。旧前端
入口尚未统一迁移，所以回答时要说明边界。

### 乐观锁怎么工作？和分布式锁怎样选择？

MyBatis-Plus `@Version` 将更新变为类似 `UPDATE ... SET version=version+1 WHERE id=? AND version=?`。它适合冲突较少的更新；分布式锁适合需要串行执行的跨步骤临界区。二者都不能替代业务状态检查和幂等。当前项目已验证旧 version 会失败，但调用方不传 version 时保障不完整。

### RabbitMQ 如何保证数据库和消息一致？

当前只配置 publisher confirm/return，不足以保证业务一致性。更完整方案是同一数据库事务写业务数据和 outbox，后台投递并记录状态；消费者使用事件 ID/业务唯一键幂等，配置有界重试和死信队列，失败后告警或补偿。不能在简历上把计划方案说成已经实现。

### Elasticsearch 为什么会和 MySQL 不一致？

ES 是 MySQL 的异步搜索投影，天然是最终一致。当前更新事件还存在 exchange/routing key 拼写不一致，导致消息没有到正确队列。修复后仍需 outbox、幂等消费、版本字段和定时对账/重建索引来处理丢失、乱序和重复。

### WebSocket 如何鉴权和定向推送？

HTTP 握手升级后，客户端 STOMP CONNECT 携 JWT，拦截器解析用户名并设置 Principal。服务可向 `/user/{username}/queue/messages` 单播，客户端订阅 `/user/queue/messages`；业务广播走 `/topic/**`。当前聊天发送地址实际是类与方法组合后的 `/app/chat/chatBackData`。

### JWT 当前有哪些安全问题？

密钥硬编码、密码明文、用户对象未脱敏、缺 token 返回 500、没有全局过滤器与角色授权，也没有刷新/吊销设计。改进应优先使用 BCrypt/Argon2、DTO 脱敏、环境变量/密钥管理、Spring Security Gateway 鉴权、统一 401/403，并设计短期 access token 与刷新/退出策略。

### 如果重新设计状态流转？

将状态改成枚举并定义允许迁移，如 `待审批 -> 待领取 -> 维修中 -> 已完成`，拒绝分支单独处理；Service 层集中校验操作者角色、当前状态和 version，数据库条件更新做最终防线，写状态变更日志供审计。接口不应允许客户端任意提交状态字符串。

### 为什么不用前端也能测试？

HTTP 接口可通过脚本/测试框架直接调用；JWT token 从登录响应提取并带到后续请求；RabbitMQ 通过队列状态和下游数据验证；ES 通过文档/搜索验证；WebSocket 使用标准握手和 STOMP 帧验证。最终应把这些步骤固化成 Testcontainers + 端到端测试，而不是依赖人工点页面。

## 7. 改进路线与简历升级条件

### 第一阶段：同步派单纵向切片（已完成）

- 密码 BCrypt/Argon2、响应 DTO 脱敏、统一 JWT 鉴权授权与异常响应。
- 新 Agent 入口已完成状态/version 条件更新和 20～100 并发唯一赢家测试。
- 修复设备更新 MQ 路由、聊天 TIMESTAMP、Long 主键。
- 关闭网关自动发现路由，只开放明确白名单。

当前可以写：“在 Agent 专用派单入口通过数据库条件更新、唯一键与事务保证同一工单
唯一领取，并以 20～100 并发测试验证。”密码、统一鉴权、ES 更新和旧入口迁移仍未完成。

### 第二阶段：达到“可靠微服务项目”

- 使用 Flyway/Liquibase 管理表结构；统一 REST、DTO、校验、异常、状态机。
- outbox + 消费幂等 + 重试/DLX + 对账，补 ES 重建能力。
- Testcontainers 集成测试和 Gateway E2E；去掉根 POM `skipTests`。
- 接入 traceId、结构化日志、Micrometer/Prometheus/Grafana 和告警。

完成后才可以写：“设计可靠事件链路，通过 outbox、幂等消费和死信补偿保证最终一致性。”

### 第三阶段：Agent 异步可靠性

Agent 已作为相邻独立项目运行，并完成同步 Java Adapter 联调。下一阶段接入 Redis
跨进程 Checkpoint，以及 RabbitMQ overload event、outcome-aware ACK、Retry/DLQ 和
故障注入；这些能力完成并留存测试证据后才能写入简历。

## 8. 面试前必须能现场回答的数字

- 6 个可启动 Spring Boot 应用；5 类基础设施。
- 2 个 MySQL 业务库：`users`、`devices`。
- 主要完整链路：用户、设备/索引、工单、维修员申请、WebSocket 消息。
- 新派单链路并发结果：20、50、100 个请求竞争时各只有一个 `ACCEPTED`、一条领取记录和一次 version 增长。
- 真实联调结果：`ACCEPTED -> ASSIGNED`、version `0 -> 1`；重放为 `ALREADY_APPLIED`。

不要死背“高并发、高可用、生产级”等没有压测和部署证据的词。准备一张架构图、一次并发失败复现、一次修复前后对比，比堆技术名词更有说服力。
