# ep-manage-sys 接口与链路测试报告

基线测试日期：2026-08-01  
最新回归：2026-08-05  
测试方式：不依赖前端，经 Gateway、HTTP、WebSocket/STOMP、MySQL、Redis、RabbitMQ 和 Elasticsearch 进行真实链路验证。测试数据已清理。

> 本文第 2～6 节保留 2026-08-01 的全系统基线结果。2026-08-05 已针对其中的
> 并发抢单 P0 完成 Agent 专用派单纵向切片修复和回归；其他历史问题不能据此视为已修复。

## 0. 2026-08-05 Java-Agent 派单回归

| 验证项 | 结果 |
|---|---|
| 合同 | `dispatch-contract/v1` DTO、枚举、错误码和 300 字符幂等键已对齐 |
| 本地直连 | Agent 可直接访问 Java 派单合同，无额外共享密钥配置 |
| 真实派单 | 人工批准 `91001` 后返回 `ACCEPTED`，最终 outcome 为 `ASSIGNED` |
| 最终状态 | 工单 `92003` 的 version 从 0 变为 1，维修员为 `91001` |
| 幂等重放 | 同命令新 trace 返回 `ALREADY_APPLIED`，version 保持 1 |
| 键冲突 | 同 `dispatchId` 不同命令返回 `REJECTED / IDEMPOTENCY_KEY_CONFLICT` |
| 并发 | 20、50、100 并发均只有一个 `ACCEPTED`、一条领取记录和一次 version 增长 |
| 自动化 | Java 合同测试 2 项、事务集成测试 5 项通过；Agent 全量 68 passed、1 skipped |

本次修复使用 `expectedVersion + 未分配条件 + 合法状态` 的数据库条件更新、唯一领取
记录和同事务审计作为最终防线。旧前端 `/deviceMaintain/getMaintainOrder` 仍保留兼容，
上述并发结论仅适用于 `/internal/dispatch/v1/assignments` 新链路。

## 1. 测试环境

| 组件 | 地址/端口 | 结果 |
|---|---:|---|
| Gateway | 8200 | 正常，已补启动 |
| ACL | 8083 | 正常 |
| Approval | 8084 | 正常 |
| Device（注册名 `service-product`） | 8085 | 正常 |
| Message | 8086 | 正常 |
| Search | 8187 | 正常 |
| Nacos / MySQL / Redis / RabbitMQ / ES | 8848 / 3308 / 6379 / 5672 / 9200 | 正常 |

首次注册调用暴露 MySQL 8 错误 `Public Key Retrieval is not allowed`。已在各服务 dev/test JDBC URL 添加 `allowPublicKeyRetrieval=true`，同时指定 `serverTimezone=Asia/Shanghai`；IDEA 中重启服务后即可使用源码配置。

Gateway 单模块启动时因本地缺少 `common-util:1.0-SNAPSHOT` 失败。先执行 `mvn -pl service-gateway -am install -DskipTests` 后启动成功。

## 2. 链路结果

| 链路 | 覆盖内容 | 结果 |
|---|---|---|
| 用户 | 注册、重复注册、登录、JWT 用户信息、角色、更新资料、在线列表 | 主流程通过 |
| 设备基础数据 | 类别增查改删、型号增查改删、实例增查改删 | 主流程通过 |
| 设备归属 | 分配设备、Feign 更新设备状态 | 通过 |
| 搜索 | 新增设备 -> RabbitMQ -> ES、关键词检索、删除索引 | 新增/删除通过，更新同步失败 |
| 报修 | 创建、列表、详情、我的报修 | 通过 |
| 工单 | 审批、抢单、我的维修、完成 | 单请求通过，并发抢单业务失败 |
| 乐观锁 | 同一 version 连续审批 | 第一次成功、第二次被拒绝，符合预期 |
| 维修员申请 | ACL 入库 -> RabbitMQ -> Approval -> Feign 授角色 | 通过，但审批逻辑过弱 |
| RabbitMQ | 设备增删、申请、用户通知、刷新队列 | 消费完成，测试后队列无积压 |
| WebSocket | 直连/网关握手、JWT CONNECT、非法连接、聊天落库、历史查询 | 通过，时间映射有缺陷 |
| 网关 | 显式路由、Nacos 自动发现路由 | 可用，但自动路由扩大暴露面 |

## 3. 已确认问题

### P0：安全与核心业务正确性

1. **密码明文泄露**：注册/登录使用明文密码，登录和用户信息响应返回密码，在线用户 Redis 值也包含密码。仓库虽有 MD5 工具但未使用；MD5 本身也不适合密码。应改为 BCrypt/Argon2，并让密码字段永不序列化。
2. **并发抢单无法保证唯一成功者（旧入口基线问题）**：2026-08-01 测试中，两个并发请求抢同一工单均返回成功，最终后一个维修员覆盖前一个，version 从 0 变为 2。2026-08-05 新增的 Agent 专用入口已通过条件更新、唯一键、事务和并发测试修复；旧前端入口仍保留，不能把修复结论扩大到它。
3. **缺少统一鉴权授权**：多数设备、工单、管理接口无需 JWT 或角色即可调用。Gateway 没有全局认证过滤器，`roleRequest` 也没有形成接口级权限控制。

### P1：一致性和错误处理

4. **设备更新不会同步 ES**：更新代码发送到 `device.instance.exchange` / `device.instance.increase`，而声明的常量/绑定使用另一组名称。MySQL 更新后 ES 仍为旧状态和位置。
5. **MQ 不具备可靠业务闭环**：配置了 publisher confirm/return，但没有 outbox、本地消息表、消费幂等、显式重试/死信和补偿。数据库成功、消息失败或重复消费时会不一致。
6. **JWT 异常返回 HTTP 500 和堆栈**：缺失 token 调用户信息接口不是 401，而是 500 并暴露异常信息。还不支持常见 `Bearer <token>` 约定，JWT key 硬编码在源码。
7. **乐观锁依赖客户端传 version**：携带旧 version 时冲突能被拒绝；但不少更新调用可不带 version，不能据此宣称所有审批/状态更新天然并发安全或幂等。
8. **无正式状态机**：状态是自由字符串，审批、抢单、完成缺少允许迁移校验，可能从任意状态跳到任意状态。

### P2：接口与数据设计

9. **主键类型不匹配**：实体 ID 是 `Integer`，MyBatis-Plus 默认分布式 ID 生成后出现大负数。改成 `Long`，或数据库自增并显式 `IdType.AUTO`。
10. **接口方法未约束**：大量增删改使用裸 `@RequestMapping`，GET 也可能修改数据；缺少统一参数校验、异常处理和响应格式。
11. **搜索策略不稳定**：普通关键词和状态可命中，但完整序列号/位置字符串未命中。需要为 ID、序列号、状态配置 `keyword`/多字段 mapping，并按字段选择 term/match 查询。
12. **搜索不存在返回 200 空体**：`GET /search/{id}` 在不存在时应返回 404 或统一业务错误。
13. **聊天时间丢失时分秒**：MyBatis XML 将 `create_time` 映射成 JDBC `DATE`，历史消息只剩日期，应使用 `TIMESTAMP`。
14. **审批服务 HTTP 层为空**：`/approval/test` 为 404；实际仅有自动消费逻辑，且非空原因就通过，资格校验被注释。
15. **自动发现路由暴露内部路径**：`/service-acl/**`、`/service-message/**` 等可以绕过规划的显式路由，应关闭 discovery locator 或建立白名单。
16. **在线状态粒度错误**：整个 `online:users` Hash 共用 TTL，任一用户登录会续期全部在线数据；没有登出和每用户心跳清理。
17. **命名与映射瑕疵**：`miantainId` 拼写错误、`avaatar` 映射疑似拼错、服务模块名与注册名不一致，降低维护性。

## 4. 测试本身的现状

仓库只有少量 Spring Boot 测试，主要是查询后打印，没有有效断言；根 POM 配置了 `skipTests=true`。因此 Maven 构建成功不能证明业务链路正确。本次测试发现的并发抢单、ES 更新和密码泄露，现有测试均无法拦截。

建议补三层测试：

1. Controller/Service 单元测试：状态迁移、参数校验、JWT 异常和脱敏。
2. Testcontainers 集成测试：MySQL、Redis、RabbitMQ、Elasticsearch 的真实交互。
3. Gateway 端到端测试：注册登录、设备录入搜索、完整工单、维修员申请、STOMP 聊天；测试数据按唯一前缀创建并自动清理。

## 5. 推荐修复顺序

1. 密码哈希与响应脱敏、Gateway/Spring Security 统一认证授权、统一 401/403。
2. 抢单改成“锁内重读 + 状态校验 + 带 version 条件更新”，更新行数为 1 才成功；增加幂等/唯一约束。
3. 修正 ES 更新路由并设计 MQ outbox、消费幂等、重试和死信。
4. 统一 REST 方法、Result、校验、全局异常和状态枚举/状态机。
5. 改 Long 主键、时间映射和命名，补 Flyway/Liquibase。
6. 补自动化链路测试、日志 traceId、指标和告警。

## 6. 本轮测试数据清理

已按精确 ID/用户名删除：1 个 `apitest` 用户、2 条用户角色、1 条维修员申请、1 条聊天消息、2 条测试工单；测试设备/型号/类别已通过 API 删除，Redis 在线项已删除，ES 测试文档不存在。未删除原有业务数据。
