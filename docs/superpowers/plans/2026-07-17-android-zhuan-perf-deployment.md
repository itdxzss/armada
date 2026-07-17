# Android Zhuan Perf Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 把 Android Zhuan 部署到新机并接入第二套 Armada，确保 Redis key、Kafka topic 和 MySQL schema 与第一套及 Web 协议隔离。

**Architecture:** Zhuan 直接连接第二套 Redis Cluster、共享 MSK 和第二套 RDS；Redis 使用 android-zhuan-perf: 前缀，Kafka 使用三个 armada.perf.protocol.android.* topic，MySQL 使用 whatsapp_android_zhuan_perf schema。第二套 Armada 只增加 Android URL 并切换三个 Android topic；现有 Web 配置和第一套环境都不改。

**Tech Stack:** Go、go-redis/v9、Docker Compose、AWS MSK SSL、Redis Cluster TLS、RDS MySQL、Spring Boot deployment templates。

---

## Fixed Values

~~~text
Android host: 172.31.40.84 / ec2-3-111-245-182.ap-south-1.compute.amazonaws.com
Armada perf host: 172.31.5.135 / ec2-3-110-124-52.ap-south-1.compute.amazonaws.com
Redis prefix: android-zhuan-perf:
MySQL schema: whatsapp_android_zhuan_perf
Android URL: http://172.31.40.84:8001

Lifecycle topic: armada.perf.protocol.android.lifecycle.commands.v1
Message topic: armada.perf.protocol.android.message.commands.v1
Group-join topic: armada.perf.protocol.android.group-join.commands.v1

Lifecycle group: armada-perf-android-zhuan-lifecycle-v1
Message group: armada-perf-android-zhuan-message-v1
Group-join group: armada-perf-android-zhuan-group-join-v1
~~~

Each topic is 12 partitions, RF3, min.insync.replicas=2, seven-day retention, unclean leader election disabled, and max message size 1 MiB. Results reuse the existing second-environment perf account/message/group event topics.

Secrets remain only in mode-0600 target-host configuration. Do not commit or print PEM contents, DB passwords, Redis credentials, or broker endpoints.

### Task 1: Make Zhuan Safe for the Shared Redis Cluster

**Files:**
- Modify internal/configs/configs.go
- Modify internal/configs/configs_test.go
- Create internal/db/redis_client.go
- Create internal/db/redis_client_test.go
- Modify internal/db/redis.go
- Create internal/db/redis_test.go
- Modify internal/armada/context_store.go
- Modify internal/armada/publish_once.go
- Modify internal/armada/message_state.go
- Modify internal/armada/join_state.go
- Modify internal/armada/start.go
- Modify corresponding internal/armada/*_test.go files

- [ ] **Step 1: Add failing config/client tests**

Add this Redis contract:

~~~go
type RedisConfig struct {
    Mode         string `toml:"mode"`
    Addr         string `toml:"addr"`
    Username     string `toml:"username"`
    Pass         string `toml:"pass"`
    DB           int    `toml:"db"`
    TLS          bool   `toml:"tls"`
    KeyPrefix    string `toml:"keyprefix"`
    MaxRetries   int    `toml:"maxretries"`
    PoolSize     int    `toml:"poolsize"`
    MinIdleConns int    `toml:"minidleconns"`
}
~~~

Tests must prove:

- empty mode still creates the old standalone client;
- cluster mode creates redis.ClusterClient with TLS 1.2 and ACL username/password;
- cluster mode rejects DB other than 0 and blank/non-colon-terminated prefix.

Run and observe RED:

~~~bash
go test ./internal/configs ./internal/db -run 'Test.*Redis' -count=1
~~~

- [ ] **Step 2: Implement UniversalClient initialization**

Use redis.UniversalClient globally. Build redis.Client for standalone and redis.ClusterClient for cluster. InitRedis pings before publishing the global client; GetRedis returns redis.UniversalClient; KeyPrefix returns the configured prefix.

Do not hardcode the perf prefix in generic code. Generic cluster validation requires a nonempty prefix ending in colon; deployment config later requires the exact android-zhuan-perf: value.

- [ ] **Step 3: Prefix every Redis operation**

All db.Set/Get/Del/Exists/Hash helpers apply the global prefix. Replace ClearByPattern KEYS with cursor SCAN; in cluster mode scan every master with ForEachMaster and delete one key at a time. Reject any scanned key outside the configured prefix.

Remove the unused TxPipeline raw bypass.

- [ ] **Step 4: Prefix direct Armada state stores**

Change the four stores to redis.UniversalClient and pass db.KeyPrefix() from start.go:

~~~go
contextStore := NewRedisContextStore(redisClient, redisPrefix, ttl)
guard := NewRedisPublishOnceGuard(redisClient, redisPrefix, ttl)
messageStore := NewRedisMessageCommandStateStore(redisClient, redisPrefix, ttl, timeout)
joinStore := NewRedisGroupJoinCommandStateStore(redisClient, redisPrefix, ttl, timeout)
~~~

Update all callers and tests; do not add compatibility overloads. Tests assert physical keys begin android-zhuan-perf: and a sibling armada-perf: key survives cleanup.

- [ ] **Step 5: Verify and commit Zhuan Redis support**

~~~bash
gofmt -w internal/configs/configs.go internal/configs/configs_test.go internal/db/redis_client.go internal/db/redis_client_test.go internal/db/redis.go internal/db/redis_test.go
gofmt -w internal/armada/context_store.go internal/armada/publish_once.go internal/armada/message_state.go internal/armada/join_state.go internal/armada/start.go internal/armada/*_test.go
go test ./internal/configs ./internal/db ./internal/armada -count=1
if rg -n '\.Keys\(' internal/db --glob '*.go'; then exit 1; fi
git add internal/configs internal/db internal/armada
git commit -m "feat: support shared redis cluster isolation"
~~~

### Task 2: Add the Simple Perf Deployment Files

**Files:**
- Modify deploy/configs/prod_configs.example.toml
- Create deploy/docker-compose.perf.yml
- Create deploy/deployment_files_test.go
- Modify deploy/README.md
- Modify Armada armada-deploy/docker-compose.rds.yml
- Modify Armada armada-deploy/.env.example
- Modify Armada armada-deploy/verify-config.mjs
- Modify Armada armada-deploy/prod/app/docker-compose.yml
- Modify Armada armada-deploy/prod/app/.env.example
- Modify Armada armada-deploy/package-prod.test.sh

- [ ] **Step 1: Add failing deployment contract tests**

Zhuan test requires the perf Compose to contain callback plus app, bind 0.0.0.0:8001, mount configs read-only, and contain no redis-zhuan service or Redis volume.

Armada existing verifiers must require PROTOCOL_ANDROID_BASE_URL in both test and production templates.

Run and observe RED:

~~~bash
go test ./deploy -count=1
node armada-deploy/verify-config.mjs
bash armada-deploy/package-prod.test.sh
~~~

- [ ] **Step 2: Add a separate two-service perf Compose**

deploy/docker-compose.perf.yml contains only callback-zhuan and whatsapp-android-zhuan. The app builds the existing Dockerfile, mounts deploy/configs, exposes 0.0.0.0:8001:8001, and depends only on healthy callback. Redis is external.

Update the config example with mode, username, tls, and keyprefix while leaving its defaults standalone-compatible.

- [ ] **Step 3: Pass Android URL into Armada backend**

Test/RDS Compose passes:

~~~yaml
PROTOCOL_ANDROID_BASE_URL: ${PROTOCOL_ANDROID_BASE_URL:-http://localhost:8000}
~~~

Production Compose passes the required variable without a fallback. Both env examples document an Android private-IP URL. This keeps the first environment compatible while allowing the second .env to set http://172.31.40.84:8001.

- [ ] **Step 4: Verify and commit each repository exactly**

Zhuan:

~~~bash
go test ./deploy -count=1
docker compose -f deploy/docker-compose.perf.yml config --quiet
git add deploy/configs/prod_configs.example.toml deploy/docker-compose.perf.yml deploy/deployment_files_test.go deploy/README.md
git commit -m "feat: add zhuan perf compose"
~~~

Armada:

~~~bash
node armada-deploy/verify-config.mjs
bash armada-deploy/package-prod.test.sh
git add armada-deploy/verify-config.mjs armada-deploy/docker-compose.rds.yml armada-deploy/.env.example armada-deploy/package-prod.test.sh armada-deploy/prod/app/docker-compose.yml armada-deploy/prod/app/.env.example
git diff --cached --name-only
git commit -m "feat: pass android protocol url to backend"
~~~

The Armada staged list must contain exactly those six files; preserve unrelated dirty files.

### Task 3: Run Local Gates Once

**Files:**
- No new files.

- [ ] **Step 1: Zhuan full verification**

~~~bash
go test ./... -count=1
go vet ./...
go build -o /tmp/zhuan-server-check ./cmd/server
go build -o /tmp/zhuan-migrate-check ./cmd/migrate
docker build -f deploy/Dockerfile -t whatsapp-server-feature-android-zhuan:perf-local .
git diff --check
~~~

- [ ] **Step 2: Armada focused verification**

~~~bash
node armada-deploy/verify-config.mjs
bash armada-deploy/package-prod.test.sh
git diff --check
~~~

Record the two exact reviewed commits. Do not deploy different source.

### Task 4: Prepare the Three Isolated Middleware Namespaces

**Files:**
- No repository files; second-environment external state only.

- [ ] **Step 1: Recheck connectivity**

From Android host verify MSK 9094, Redis TLS 6379, and RDS 3306 are reachable. From Armada host verify Android 8001 after the app starts. Stop if any dependency points to the first environment.

- [ ] **Step 2: Create the three Kafka topics**

First list topics and require all three exact perf names to be absent. Do not use --if-not-exists. Create each with:

~~~text
partitions=12
replication-factor=3
min.insync.replicas=2
unclean.leader.election.enable=false
cleanup.policy=delete
retention.ms=604800000
max.message.bytes=1048576
~~~

Describe each topic and verify name, partitions, replicas, ISR, and configs. Never create or modify protocol.android.* first-environment topics.

- [ ] **Step 3: Create the independent MySQL schema**

Create whatsapp_android_zhuan_perf and a dedicated application user with rights only on that schema. Copy only wa_devices structure/data from whatsapp_android_zhuan_test and compare row counts. Do not copy account, identity, session, prekey, sender-key, login, or Redis state.

Zhuan's built-in migration runs once during Task 5. It creates Signal identity, prekey/session shards, sender-key and contact tables without replacing wa_devices.

- [ ] **Step 4: Prepare the protected Zhuan TOML**

The mode-0600 file uses actual second-environment endpoints and credentials plus these non-secret values:

~~~toml
[redis]
mode = "cluster"
db = 0
tls = true
keyprefix = "android-zhuan-perf:"

[mysql]
name = "whatsapp_android_zhuan_perf"

[server]
host = "http://callback-zhuan:8080"

[kafka]
enabled = true
lifecyclecommandtopic = "armada.perf.protocol.android.lifecycle.commands.v1"
lifecycleconsumergroup = "armada-perf-android-zhuan-lifecycle-v1"
lifecycleconcurrency = 4
messagecommandtopic = "armada.perf.protocol.android.message.commands.v1"
messageconsumergroup = "armada-perf-android-zhuan-message-v1"
messageconcurrency = 4
groupjoincommandtopic = "armada.perf.protocol.android.group-join.commands.v1"
groupjoinconsumergroup = "armada-perf-android-zhuan-group-join-v1"
groupjoinconcurrency = 4
accounteventtopic = "armada.perf.protocol.account.events.v1"
messageeventtopic = "armada.perf.protocol.message.events.v1"
groupeventtopic = "armada.perf.protocol.group.events.v1"
messageprocessingtimeoutseconds = 120
joinprocessingtimeoutseconds = 60
contextttlseconds = 604800
securityprotocol = "SSL"
~~~

Reject a blank/different prefix, old Android topic, non-perf event topic, or wrong schema.

### Task 5: Deploy Zhuan, Then Cut Armada Once

**Files:**
- Android remote /home/ec2-user/whatsapp-android-zhuan-deploy/src
- Armada remote /home/ec2-user/armada-deploy/.env

- [ ] **Step 1: Deploy Zhuan**

Sync the reviewed Zhuan commit while excluding secrets/logs. Then:

~~~bash
sudo docker compose -f deploy/docker-compose.perf.yml config --quiet
sudo docker compose -f deploy/docker-compose.perf.yml build whatsapp-android-zhuan
sudo docker compose -f deploy/docker-compose.perf.yml up -d callback-zhuan
sudo docker compose -f deploy/docker-compose.perf.yml run --rm --no-deps --interactive=false whatsapp-android-zhuan /app/whatsapp-migrate -env prod
sudo docker compose -f deploy/docker-compose.perf.yml up -d whatsapp-android-zhuan
sudo docker compose -f deploy/docker-compose.perf.yml ps
~~~

Verify healthy app/callback, no local Redis, successful Redis/MySQL/Kafka connections, and the three consumer groups subscribe only to their matching perf topic.

- [ ] **Step 2: Guard the Armada cut**

Before changing Armada, this query must return zero rows:

~~~sql
SELECT kafka_topic, status, COUNT(*)
FROM protocol_command_outbox
WHERE protocol_backend = 'ANDROID'
  AND status IN (0, 1)
  AND deleted_at IS NULL
  AND kafka_topic IN (
    'protocol.android.commands.v1',
    'protocol.android.lifecycle.commands.v1',
    'protocol.android.message.commands.v1',
    'protocol.android.group-join.commands.v1'
  )
GROUP BY kafka_topic, status;
~~~

Do not rewrite old outbox rows.

- [ ] **Step 3: Set four Armada values and recreate backend only**

Back up the second Armada .env, then set:

~~~text
PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC=armada.perf.protocol.android.lifecycle.commands.v1
PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC=armada.perf.protocol.android.message.commands.v1
PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC=armada.perf.protocol.android.group-join.commands.v1
PROTOCOL_ANDROID_BASE_URL=http://172.31.40.84:8001
~~~

Dry-run and then deploy only backend using the second Armada host variables. Do not restart Web protocol or change ARMADA_PROTOCOL_BASE_URL/master/event topics.

Verify the running armada-backend environment, not only .env.

### Task 6: Smoke and Roll Back Simply

**Files:**
- Update .harness/changes/2026-07-17-android-zhuan-perf-deployment.md with redacted evidence.

- [ ] **Step 1: Android smoke**

Use one second-environment Android account:

~~~text
上线 -> 状态查询 -> 发测试消息 -> 进测试群 -> 下线
~~~

Confirm every command uses a perf Android topic and every result returns through the existing perf event topic. Kafka key is protocolAccountId.

- [ ] **Step 2: Isolation checks**

Verify:

- new Zhuan Redis keys all start android-zhuan-perf:;
- no new unprefixed whatsapp:*, armada:zhuan:* or raw *_fcm keys;
- old first-environment Android topic offsets do not move because of this smoke;
- new Android outbox rows use only the three perf topics;
- MySQL writes stay in whatsapp_android_zhuan_perf.

- [ ] **Step 3: Web regression**

Use one existing second-environment Web account for a lifecycle/message/group check. Web config and containers remain unchanged.

- [ ] **Step 4: Rollback if needed**

Stop only the new Zhuan app:

~~~bash
sudo docker compose -f deploy/docker-compose.perf.yml stop whatsapp-android-zhuan
~~~

Keep perf topics, schema, Redis namespace, Armada perf topic values, and queued perf messages. Never switch back to first-environment topics. Fix and restart with the same consumer groups.

- [ ] **Step 5: Final evidence**

Run superpowers:verification-before-completion, record commits/tests/topic descriptions/table counts/container health/smoke results, and update the change record without secrets.
