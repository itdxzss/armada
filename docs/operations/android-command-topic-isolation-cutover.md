# dev-1 Android 命令 Topic 隔离切换手册

## 适用范围与已确认授权

- 目标主机：`ubuntu@ec2-65-2-123-53.ap-south-1.compute.amazonaws.com`。
- Armada Compose：`/home/app/armada-deploy/docker-compose.rds.yml`，service 为 `backend`，container 为 `armada-backend`。
- Zhuan Compose：`/home/app/whatsapp-android-zhuan-deploy/src/deploy/docker-compose.yml`，service/container 为 `whatsapp-android-zhuan`。
- Zhuan 受保护配置：`/home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml`。
- Kafka 使用 `SSL`；dev-1 已有 `apache/kafka:3.8.0` 镜像。
- 用户已允许 dev-1 停机切换，并允许丢弃旧 topic 中未消费的命令。
- 旧 `protocol.android.commands.v1` 必须保留；本次不迁移、不回灌、不删除任何 Kafka topic。

私钥只能使用本地路径 `/Users/daishuaishuai/IdeaProjects/测试pem/dev-1.pem`，不得复制到服务器、仓库、日志或交付包。

## 1. 切换前记录与停机

先记录切换时间、当前两个容器的镜像和本地两个仓库的 commit，写入 Harness 变更记录。把停服维护窗口的 epoch 毫秒记为 `cutover_epoch_ms`，后续 SQL 中的 `:cutover_epoch_ms` 均绑定该值。不得记录 broker、数据库/Redis 凭据、账号凭据、手机号或消息正文。

停止 Armada backend 和 Zhuan，避免切换期间继续写入旧 topic 或由新旧配置同时消费：

```bash
ssh -T -i '/Users/daishuaishuai/IdeaProjects/测试pem/dev-1.pem' \
  ubuntu@ec2-65-2-123-53.ap-south-1.compute.amazonaws.com \
  'cd /home/app/armada-deploy && sudo docker compose -f docker-compose.rds.yml stop backend && cd /home/app/whatsapp-android-zhuan-deploy/src/deploy && sudo docker compose stop whatsapp-android-zhuan'
```

Armada publisher 会使用 outbox 行中已经保存的 `kafka_topic`，所以仅停止旧 consumer、丢弃 Kafka 中的旧积压还不够。两个服务均停止后，先查询旧 topic 上尚可发送的 Android outbox 行：

```sql
SELECT command_id, command_type, aggregate_type, aggregate_id, status
FROM protocol_command_outbox
WHERE protocol_backend = 'ANDROID'
  AND kafka_topic = 'protocol.android.commands.v1'
  AND deleted_at IS NULL
  AND status IN (0, 1)
ORDER BY id;
```

用户已授权丢弃旧 topic 未完成命令。记录受影响的 command/aggregate ID 和行数后，在同一维护窗口把这些 `PENDING(0)`/`LOCKED(1)` 行改为 `CANCELED(4)`，防止新版本重启后继续向旧 topic 生产：

```sql
START TRANSACTION;

UPDATE protocol_command_outbox
SET status = 4,
    locked_by = NULL,
    locked_at = NULL,
    last_error = 'canceled by Android command topic split 2026-07-17',
    updated_at = :cutover_epoch_ms
WHERE protocol_backend = 'ANDROID'
  AND kafka_topic = 'protocol.android.commands.v1'
  AND deleted_at IS NULL
  AND status IN (0, 1);

SELECT ROW_COUNT() AS canceled_legacy_outbox_rows;
COMMIT;
```

`CANCELED` 行不会再被 dispatcher 扫描。逐条核对关联业务状态；这些命令不得记为成功，如业务表仍处于等待态，应按受影响 ID 记录为本次获准丢弃并单独人工收敛。再次查询上述条件必须返回零行，才可继续启动新版本。不得把旧行改写到新 topic，否则可能绕过原命令族的业务重试/幂等边界。

## 2. 创建并核对三个 Kafka topic

在 dev-1 shell 内从已停止的 Armada 容器读取 broker 列表到变量；不要输出变量值。创建临时 SSL client 配置：

```bash
ARMADA_KAFKA_BROKERS="$(sudo docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' armada-backend | sed -n 's/^KAFKA_BOOTSTRAP_SERVERS=//p')"
ARMADA_KAFKA_CLIENT_CONFIG="$(mktemp)"
printf 'security.protocol=SSL\n' >"${ARMADA_KAFKA_CLIENT_CONFIG}"
```

逐一创建 4 分区 topic，复制因子使用 MSK broker 默认值：

```bash
sudo docker run --rm --network host \
  -v "${ARMADA_KAFKA_CLIENT_CONFIG}:/tmp/client.properties:ro" \
  apache/kafka:3.8.0 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server "${ARMADA_KAFKA_BROKERS}" --command-config /tmp/client.properties \
  --create --if-not-exists --topic protocol.android.lifecycle.commands.v1 --partitions 4

sudo docker run --rm --network host \
  -v "${ARMADA_KAFKA_CLIENT_CONFIG}:/tmp/client.properties:ro" \
  apache/kafka:3.8.0 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server "${ARMADA_KAFKA_BROKERS}" --command-config /tmp/client.properties \
  --create --if-not-exists --topic protocol.android.message.commands.v1 --partitions 4

sudo docker run --rm --network host \
  -v "${ARMADA_KAFKA_CLIENT_CONFIG}:/tmp/client.properties:ro" \
  apache/kafka:3.8.0 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server "${ARMADA_KAFKA_BROKERS}" --command-config /tmp/client.properties \
  --create --if-not-exists --topic protocol.android.group-join.commands.v1 --partitions 4
```

用 `kafka-topics.sh --describe` 分别检查三个 topic，必须都是 `PartitionCount: 4`。consumer group 验证完成前保留临时 SSL 文件。

## 3. 迁移 Zhuan 受保护配置

先在同一受保护目录创建固定名称且不覆盖的备份：

```bash
sudo cp -p -n \
  /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml \
  /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml.before-topic-split-20260717
```

只替换旧 `commandtopic`、`consumergroup`、`concurrency` 三项；其他配置保持原值：

```bash
sudo perl -0pi -e '
s/^commandtopic\s*=.*$/lifecyclecommandtopic = "protocol.android.lifecycle.commands.v1"\nlifecycleconsumergroup = "whatsapp-server-feature-android-armada-lifecycle"\nlifecycleconcurrency = 4\n\nmessagecommandtopic = "protocol.android.message.commands.v1"\nmessageconsumergroup = "whatsapp-server-feature-android-armada-message"\nmessageconcurrency = 4\n\ngroupjoincommandtopic = "protocol.android.group-join.commands.v1"\ngroupjoinconsumergroup = "whatsapp-server-feature-android-armada-group-join"\ngroupjoinconcurrency = 4/m;
s/^consumergroup\s*=.*\n//m;
s/^concurrency\s*=.*\n//m;
' /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml
```

只输出九项非敏感配置做核对：

```bash
grep -E '^(lifecycle|message|groupjoin)(commandtopic|consumergroup|concurrency)[[:space:]]*=' \
  /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml
grep -E '^(commandtopic|consumergroup|concurrency)[[:space:]]*=' \
  /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml
```

第一条应输出九行，第二条必须无输出。不要显示完整 TOML。

## 4. 部署与健康检查

从本地 Armada 仓库先部署 Zhuan，再部署 backend：

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
./armada-deploy/deploy-test.sh --zhuan -y
./armada-deploy/deploy-test.sh --be -y
```

核对容器状态和安全启动字段：

```bash
ssh -T -i '/Users/daishuaishuai/IdeaProjects/测试pem/dev-1.pem' \
  ubuntu@ec2-65-2-123-53.ap-south-1.compute.amazonaws.com \
  'docker ps --filter name=whatsapp-android-zhuan --filter name=armada-backend --format "{{.Names}}|{{.Status}}"; docker logs --since 10m whatsapp-android-zhuan 2>&1 | grep -E "commandFamily|commandTopic|consumerGroup|adapter started" | tail -n 80'
```

两个容器必须 up/healthy；Zhuan 应出现 lifecycle、message、group-join 三组配置且并发均为 4，不得出现旧共享命令 topic。

## 5. Consumer group 验证与临时文件清理

在保留 broker 变量和 SSL 配置的同一个 dev-1 shell 中执行：

```bash
for ARMADA_COMMAND_GROUP in \
  whatsapp-server-feature-android-armada-lifecycle \
  whatsapp-server-feature-android-armada-message \
  whatsapp-server-feature-android-armada-group-join
do
  sudo docker run --rm --network host \
    -v "${ARMADA_KAFKA_CLIENT_CONFIG}:/tmp/client.properties:ro" \
    apache/kafka:3.8.0 /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server "${ARMADA_KAFKA_BROKERS}" --command-config /tmp/client.properties \
    --describe --group "${ARMADA_COMMAND_GROUP}"
done
```

每个 group 必须只分配到同名命令族 topic，且四个分区全部出现。旧 `whatsapp-server-feature-android-armada` group 在切换后不得收到新记录。

验证完成后，仅在变量非空且指向普通文件时删除临时文件：

```bash
if [ -n "${ARMADA_KAFKA_CLIENT_CONFIG}" ] && [ -f "${ARMADA_KAFKA_CLIENT_CONFIG}" ]; then
  rm -f -- "${ARMADA_KAFKA_CLIENT_CONFIG}"
fi
```

## 6. 数据与业务验收

记录切换时间对应的 `cutover_epoch_ms`，查询切换后 Android outbox 路由：

```sql
SELECT command_type, kafka_topic, COUNT(*) AS row_count
FROM protocol_command_outbox
WHERE protocol_backend = 'ANDROID'
  AND created_at >= :cutover_epoch_ms
GROUP BY command_type, kafka_topic
ORDER BY command_type, kafka_topic;
```

只允许以下映射：

```text
account.online.requested  -> protocol.android.lifecycle.commands.v1
account.offline.requested -> protocol.android.lifecycle.commands.v1
message.send.requested    -> protocol.android.message.commands.v1
group.join.requested      -> protocol.android.group-join.commands.v1
```

旧 topic 行数必须为零：

```sql
SELECT COUNT(*) AS legacy_rows
FROM protocol_command_outbox
WHERE protocol_backend = 'ANDROID'
  AND created_at >= :cutover_epoch_ms
  AND kafka_topic = 'protocol.android.commands.v1';
```

还必须跨越切换时间检查所有历史行，确保旧 topic 不存在重启后仍可发送的状态；仅检查 `created_at >= :cutover_epoch_ms` 不足以发现切换前遗留的 PENDING/LOCKED 行：

```sql
SELECT COUNT(*) AS legacy_dispatchable_rows
FROM protocol_command_outbox
WHERE protocol_backend = 'ANDROID'
  AND kafka_topic = 'protocol.android.commands.v1'
  AND deleted_at IS NULL
  AND status IN (0, 1);
```

`legacy_rows` 与 `legacy_dispatchable_rows` 都必须为零。

业务验收顺序：

1. 向离线 Android 账号发送营销消息，确认 `message.send_result_reported.reasonCode=ACCOUNT_OFFLINE`，Armada attempt 进入失败终态，message group offset 继续推进。
2. 让离线 Android 账号执行进群，确认 `group.join_result_reported.reasonCode=ACCOUNT_NOT_ONLINE`，进群结果状态收敛。
3. 阻塞或积压 message 命令后发送批量上线，确认只有 message group lag 上升，lifecycle offset 继续推进，账号独立到达 ONLINE。
4. 在 Harness 记录耗时、group lag 快照、受影响账号 ID 和最终状态；不得记录凭据、代理详情、消息正文或完整手机号。

## 7. 回滚

回滚顺序固定如下：

1. 停止 Armada backend 和 Zhuan。
2. 恢复切换前记录的 Zhuan/Armada 镜像或 commit。
3. 用备份覆盖恢复 Zhuan TOML：

   ```bash
   sudo cp -p \
     /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml.before-topic-split-20260717 \
     /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml
   ```

4. 先启动并确认 Zhuan 正常，再启动 Armada backend。
5. 验证旧 consumer group 和旧 topic 的消费恢复。

回滚时不得把三个新 topic 的消息复制回旧 topic；这些未处理消息已获准丢弃。不得删除旧 topic，也不得删除三个新 topic。回滚结果和使用的镜像/commit 必须写入 Harness 变更记录。
