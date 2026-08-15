# 工单知识入库生命周期（Java 侧）

完整跨系统说明见：

`/Users/sevetar1626/workspace/upupup/myAgent/docs/03-engineering/work-order-knowledge-ingestion.md`

## Java 侧职责

1. 完成工单时校验处理过程、解决方案和修复验证；
2. 在同一 MySQL 事务中更新工单并写 `work_order_knowledge_outbox`；
3. 从设备实例关系表补充可信的设备类别和型号；
4. 发布 `work-order-completed/v2`，但不在 Java 中切分或调用 Embedding；
5. 消费 `knowledge-ingestion-result/v1`，分开记录消息发布状态和知识摄取状态；
6. 通过 `/deviceMaintain/knowledgeIngestionStatus?eventId=...` 查询最终结果。

## 增量迁移

已有数据库执行一次：

```bash
/Applications/Docker.app/Contents/Resources/bin/docker exec -i ep-manage-mysql \
  mysql -uroot -p123456 < docker/mysql/init/07-work-order-knowledge-lifecycle.sql
```

新增工单字段：`root_cause`、`verification_result`、`replaced_parts`、`knowledge_tags`。

新增 Outbox 结果字段：`ingestion_status`、`chunk_count`、`quality_score`、
`quality_issues`、`ingestion_error`、`ingested_at`。

注意：`publish_status=PUBLISHED` 只说明 RabbitMQ 已接收；`ingestion_status=indexed|skipped`
才表示 Agent 已成功激活知识版本。

## 验证

```bash
mvn -pl service/service-device -am \
  -Dtest=WorkOrderCompletionServiceIntegrationTest,WorkOrderKnowledgeResultListenerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

当前覆盖工单与 Outbox 原子提交、重复完成、Outbox 失败回滚、v2 元数据，以及 Agent 最终结果
写回。真实 RabbitMQ + Agent 在线链路仍需在三个服务重启后执行一次前端验收。
