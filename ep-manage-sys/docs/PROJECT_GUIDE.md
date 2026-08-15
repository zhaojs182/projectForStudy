# ep-manage-sys 项目速览

> 基于 2026-08-05 的源码与联调结果整理。Java 仓库定位为“微服务设备运维与工单协作平台”；FlowFix Agent 位于相邻 `myAgent` 项目，通过版本化内部 HTTP 合同与本项目协作，两者不要混写成同一个代码仓库。

## 1. 30 秒讲清项目

这是一个基于 Spring Cloud Alibaba 的设备运维系统。系统按网关、权限、设备、审批、消息和搜索拆成 6 个可启动应用，通过 Nacos 做注册发现、Gateway 统一入口、OpenFeign 同步调用，RabbitMQ 传递维修员申请、设备索引和通知事件。MySQL 保存用户、设备和工单，Redis 保存在线用户并给 Redisson 分布式锁提供存储，Elasticsearch 提供设备搜索，WebSocket/STOMP 负责实时聊天和业务通知。

核心业务链路是：用户注册登录 -> 录入与分配设备 -> 用户发起报修 -> 管理员审批 -> 维修人员领取/Agent 派单 -> 完成维修；旁路还包括维修员资格申请、设备索引同步和聊天通知。Java-Agent 已完成 `dispatch-contract/v1` 同步纵向联调。

## 2. 技术架构

```text
客户端 / API 测试
        |
        v
Gateway :8200 ---- Nacos :8848
  |        |         |         |          |
  v        v         v         v          v
ACL      Device   Approval   Message    Search
:8083    :8085     :8084      :8086     :8187
  |         |         |          |          |
 users DB devices DB users DB  users DB  Elasticsearch
  |         |         |          ^          ^
  +---------+--- OpenFeign ------+          |
            +------ RabbitMQ ----------------+
            +------ Redis / Redisson

FlowFix Agent ---- 直连 :8085 /internal/dispatch/v1
       |             工单/候选快照、派单命令、最终 outcome
       +---- expectedVersion + Idempotency-Key
```

基础设施由根目录 `compose.yaml` 提供：MySQL 8（宿主机 3308）、Redis 7、RabbitMQ 3.13、Nacos 2.2.3、Elasticsearch 8.7.1。启动方法见 `LOCAL_DEVELOPMENT.md`。

## 3. Maven 模块与职责

| 模块 | 类型 | 主要职责 |
|---|---|---|
| `model` | 公共模型 | ACL、设备、工单、派单审计/画像、聊天、搜索文档、通知等 DTO/实体 |
| `common/common-util` | 公共工具 | `Result`、JSON 响应、JWT、MD5 等 |
| `service-client` | Feign 契约 | ACL、设备、消息服务之间的同步调用接口 |
| `service-gateway` | 启动应用 | Nacos 发现、负载均衡、路由、CORS、WebSocket 转发 |
| `service/service-acl` | 启动应用 | 用户、角色、权限、设备归属、在线用户、维修员申请 |
| `service/service-device` | 启动应用 | 设备类别/型号/实例、维修工单、旧抢单流程及 `dispatch-contract/v1` 派单合同 |
| `service/service-approval` | 启动应用 | 消费维修员申请事件并自动审批、授予角色；HTTP 控制器当前为空 |
| `service/service-message` | 启动应用 | WebSocket/STOMP 聊天、在线列表、历史消息、业务推送、MQ 通知消费 |
| `service/service-search` | 启动应用 | Elasticsearch 设备索引、搜索、MQ 索引同步 |

注意：`service-device` 在 Nacos 中注册名为 `service-product`，命名和模块职责不一致，容易增加理解与路由成本。

## 4. 数据边界

| 存储 | 主要数据 |
|---|---|
| MySQL `users` | `user`、`roles`、`permissions`、用户角色/权限关系、设备归属、维修员申请、聊天消息 |
| MySQL `devices` | 设备类别、型号、实例、维修工单、维修员派单画像/技能、领取与派单审计 |
| Redis | `online:users` Hash；Redisson 工单锁 `order:lock:{orderId}` |
| RabbitMQ | 维修员申请、设备索引增删、用户通知、维修列表刷新 |
| Elasticsearch | `device_instance` 设备实例搜索文档 |

目前跨服务关联主要靠业务 ID 和 Feign 查询，没有数据库外键；这符合服务独立存储的方向，但需要应用层校验和补偿来防止孤儿数据。

## 5. 按模块整理接口

除表中明确标注的方法外，大量接口使用未限制 HTTP 方法的 `@RequestMapping`，因此 GET/POST 等都可能命中。下表写的是建议语义，不代表源码已经严格限制。

### ACL / 用户权限

网关前缀主要为 `/user`、`/admin`、`/role`、`/deviceToUser`。

| 建议方法与路径 | 作用 | 关键输入 |
|---|---|---|
| `POST /user/register` | 注册，并默认授予普通用户角色 | `username,password,phonenum` |
| `POST /user/login` | 登录、签发 JWT、写在线用户到 Redis | `username,password` |
| `GET /user/getUserInfo` | 获取当前用户 | `Authorization: <token>` |
| `GET /user/getUserRole` | 获取当前用户角色 ID | JWT |
| `POST /user/apply` | 申请维修员，写库并发送 RabbitMQ | JWT + `RepairmanApplication` |
| `PUT /user/updateUserInfo` | 更新当前用户资料 | JWT + `User` |
| `GET /admin/roleRequest` | 检查用户是否拥有权限 | `userId,permissionId` |
| `GET /admin/getUsernameById` | 按用户 ID 查用户名，供 Feign 调用 | `userId` |
| `POST /role/addUsertoRole3` | 给用户添加指定角色 | `userId,roleId` |
| `POST /deviceToUser/addDeviceToUser` | 分配设备并调用设备服务改状态 | `userId,deviceId` |
| `DELETE /deviceToUser/removeDeviceFromUser` | 解除设备归属并改状态 | `userId,deviceId` |
| `GET /deviceToUser/getDevicesByUserId` | 分页查用户设备，并 Feign 补全设备信息 | `userId,pageNum,pageSize` |

`PermissionController` 当前没有接口。

### Device / 设备与工单

| 建议方法与路径 | 作用 | 关键输入 |
|---|---|---|
| `GET /device/category/list` | 类别列表 | JWT |
| `POST /device/category/add` | 新增类别 | JWT + `Devicecategory` |
| `PUT /device/category/update` | 更新类别 | JWT + `Devicecategory` |
| `DELETE /device/category/delete` | 删除类别 | JWT + `id` |
| `GET /deviceModel/getInfoList` | 按类别分页查型号 | `categoryId,pageNum,pageSize` |
| `POST /deviceModel/addDeviceModel` | 无 ID 时新增、有 ID 时更新型号 | `Devicemodel` |
| `DELETE /deviceModel/deleteDeviceModel` | 删除型号 | `id` |
| `POST /deviceInstance/updateDeviceInstanceStatus` | 更新实例状态，主要供 Feign 调用 | `deviceId,status` |
| `POST /deviceInstance/getDeviceInstanceListById` | 按 ID 列表批量查询 | `List<Integer>` |
| `GET /deviceInstance/getBriefInfoList` | 分页查设备实例 | `id,pageNum,pageSize` |
| `GET /deviceInstance/getTrueDetailInfoList/{id}` | 设备详情 | 路径 `id` |
| `DELETE /deviceInstance/deviceTrueDeleteService/{id}` | 删除设备并发 MQ 删除索引 | 路径 `id` |
| `POST /deviceInstance/updateDeviceInstance` | 无 ID 时新增、有 ID 时更新设备 | `Deviceinstance` |
| `GET /deviceMaintain/getMaintainRecord` | 所有工单分页 | `pageNum,pageSize` |
| `GET /deviceMaintain/getMaintainRecordById` | 工单详情 | `id` |
| `POST /deviceMaintain/createMaintainRecord` | 创建报修单并通知审批人员 | `MaintainRecord` |
| `PUT /deviceMaintain/approvalMaintainRecord` | 审批工单，乐观锁字段来自请求体 | `MaintainRecord` |
| `POST /deviceMaintain/getMaintainOrder` | 维修员抢单，使用 Redisson 锁 | `MaintainRecord` |
| `GET /deviceMaintain/getMyMaintainOrder` | 用户发起的工单 | `userId,pageNum,pageSize` |
| `GET /deviceMaintain/getMyRepairOrder` | 维修员领取的工单 | `userId,pageNum,pageSize` |
| `PUT /deviceMaintain/updateMyRepairOrder` | 完成维修 | `MaintainRecord` |

Agent 专用内部接口直连 `service-product:8085`，不经过 Gateway：

| 方法与路径 | 作用 |
|---|---|
| `GET /internal/dispatch/v1/health` | 验证本地派单合同版本 |
| `GET /internal/dispatch/v1/orders/{orderId}/snapshot` | 读取工单、设备、状态和 version 冻结快照 |
| `GET /internal/dispatch/v1/orders/{orderId}/workers` | 读取稳定排序且通过硬门禁的候选维修员 |
| `POST /internal/dispatch/v1/assignments` | 带 `expectedVersion` 与幂等键执行条件派单 |
| `GET /internal/dispatch/v1/assignments/{dispatchId}/outcome` | 从 MySQL 核验最终派单结果 |

接口仅用于本机 Java-Agent 联调，不额外配置服务间共享密钥。写命令要求 Header
与 body 中的 `Idempotency-Key` 一致；合同版本固定为 `dispatch-contract/v1`。

### Approval / 维修员审批

`/approval` 控制器为空，没有可用 HTTP 接口。实际逻辑在 `ApplyMessageListener`：消费维修员申请 -> 非空原因即自动通过 -> 更新申请状态 -> Feign 调 ACL 添加维修员角色。资格审核代码目前被注释，不是完整审批系统。

### Message / WebSocket 与通知

| 方法与路径 | 作用 |
|---|---|
| `GET /chat/getOnlineUser` | 读取 Redis 中所有在线用户 |
| `GET /chat/getAllABMessage` | 查询两名用户之间的历史消息 |
| STOMP `/app/chat/chatBackData` | 保存并转发聊天消息；类和方法路径会组合 |
| `POST /msg/sendToUser` | 向 `/user/{name}/queue/messages` 单播 |
| `ANY /msg/sendToTopic4Maintain` | 向审批人员广播刷新事件 |
| `ANY /msg/sendToTopic4MaintainRecord` | 向维修人员广播刷新事件 |
| WebSocket `/ws` | STOMP 握手入口，CONNECT 阶段解析 JWT |

网关没有显式 `/msg/**` 路由；当前可通过自动发现路径 `/service-message/msg/**` 访问，这也暴露了路由边界问题。

### Search / 设备搜索

| 方法与路径 | 作用 |
|---|---|
| `GET /search?keyword=...` | ES 搜索 ID，再 Feign 回源设备详情 |
| `POST /search` | 直接写设备文档 |
| `GET /search/{id}` | 按 ID 获取文档 |
| `DELETE /search/{id}` | 按 ID 删除文档 |

`DeviceMqListener` 消费设备新增/删除事件维护索引。新增和删除链路正常，设备更新事件目前使用了错误的 exchange/routing key，因此索引会变旧。

## 6. 核心业务链路

### 用户登录链路

1. ACL 校验用户名密码。
2. `JwtUtils` 生成含用户 ID、用户名和过期时间的 JWT。
3. 整个 `User` 对象写入 Redis `online:users`。
4. 客户端后续将原始 token 放入 `Authorization`。

当前问题：密码明文存储且会出现在响应、Redis 中；密钥硬编码；缺失或非法 token 返回 500；没有全局鉴权/角色授权。

### 设备录入、分配与搜索链路

1. 维护类别和型号，新增设备实例。
2. Device 保存 MySQL 后发送 RabbitMQ 新增事件。
3. Search 消费事件并写 Elasticsearch。
4. ACL 分配设备给用户，Feign 调 Device 将状态改为“使用”。
5. 搜索先查 ES，再 Feign 回源 MySQL 得到详情。

当前问题：更新事件路由配置错误导致 ES 文档陈旧；中文/标识符字段的 mapping 和查询策略不足，精确序列号未必命中；Integer 主键配合 MyBatis-Plus 默认分布式 ID 会产生负数/溢出风险。

### 报修、审批、抢单、完成链路

1. 用户创建 `MaintainRecord`，Message 广播审批列表刷新。
2. 审批接口设置审批时间和状态，MyBatis-Plus `@Version` 做乐观锁；同时 Feign 查用户名并经 MQ 推送通知。
3. 旧前端入口仍通过 Redisson 锁执行维修员抢单，暂时保留兼容。
4. Agent 新链路先读取冻结快照和真实候选，再提交 `expectedVersion + idempotencyKey`；Java 用 version、未分配状态和合法工单状态做 SQL 条件更新，并在同一事务写唯一领取记录与审计。
5. Agent 使用 `dispatchId` 查询 MySQL outcome，不把 HTTP 200 直接视为业务完成。
6. 完成接口设置结束时间，并把工单状态写为“已完成”，与设备“正常”状态分离。

2026-08-05 回归已验证新链路在 20、50、100 并发下只有一个 `ACCEPTED`、一条领取
记录和一次 version 增长；同命令重放返回 `ALREADY_APPLIED`。该结论仅适用于
`/internal/dispatch/v1/assignments`，旧前端入口仍需后续统一迁移。

### Java-Agent 同步派单链路

1. Agent 读取工单和候选维修员冻结快照。
2. 确定性评分若触发安全分流，则暂停等待人工批准。
3. Agent 提交版本化派单命令，Java 重新执行维修员技能、区域、班次、可用性和容量硬门禁。
4. Java 返回结构化 receipt；Agent 再读取 outcome 并记录状态历史与审计。
5. 响应丢失或重试时，相同语义命令不增加业务副作用；同 ID 不同命令稳定返回冲突。

真实联调已跑通人工批准 `91001`、`ACCEPTED -> ASSIGNED`、version `0 -> 1`、
`ALREADY_APPLIED` 重放和 `IDEMPOTENCY_KEY_CONFLICT`。Redis 跨进程检查点与 RabbitMQ
ACK/Retry/DLQ 不在本轮同步联调范围内。

### 维修员申请链路

1. 用户携 JWT 提交申请，ACL 入库并投递 RabbitMQ。
2. Approval 消费申请，目前只要原因非空就自动通过。
3. Approval Feign 调 ACL 添加维修员角色。

当前问题：没有可靠的人工/规则审核、消费者幂等、死信队列或补偿；重复消息可能重复插入角色关系。

### 实时消息链路

1. 客户端连接 `/ws`，STOMP CONNECT Header 携带 JWT。
2. 服务将 JWT 用户名绑定为 Principal。
3. 客户端向 `/app/chat/chatBackData` 发消息。
4. 消息写 MySQL，再单播/广播到 STOMP 订阅地址。

当前问题：Mapper 将 DATETIME 按 JDBC `DATE` 映射，历史消息丢失时分秒；在线状态按一个 Redis Hash 整体续期，不是用户级心跳；缺少登出和可靠离线清理。

## 7. 建议阅读源码顺序

1. `service-gateway/src/main/resources/application.yaml`：先理解入口和服务名。
2. `UserController` 与 `JwtUtils`：理解身份模型。
3. `device.dispatch.v1`：理解版本化合同、事务派单、幂等、硬门禁与 outcome。
4. `DeviceMaintainController`：理解旧前端工单链路及兼容边界。
5. `DeviceInstanceInfo` + `DeviceMqListener`：理解 MySQL/RabbitMQ/ES 最终一致性。
6. `ApplyMessageListener`：理解维修员资格事件链路。
7. `ChatMessageController` + WebSocket 配置：理解 STOMP 鉴权和消息路由。
8. `service-client`：串起 Java 微服务间同步调用。

## 8. 当前成熟度判断

项目具备可运行的微服务骨架、多条真实跨中间件链路，以及已验证的 Java-Agent 同步
派单纵向切片。派单新链路已经补上明确 HTTP 合同、数据库 CAS、幂等审计和并发测试；
但全平台仍缺统一鉴权、密码安全、完整状态机、MQ 可靠性、ES 一致性、可观测性和
版本化数据库迁移。准确定位应是“完成关键纵向链路验证的设备运维与受控 Agent 协作
项目”，而不是“生产级智能 Agent 平台”。
