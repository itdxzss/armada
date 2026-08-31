# S1 — Kafka / Redis Stream topic 治理设计

- 任务编号：S1
- 日期：2026-08-30（Asia/Shanghai）
- 文档类型：治理设计；docs-generator flavor = null
- 输入：K1-backend-kafka.md、K2-web-kafka-stream.md、K3-android-kafka.md、K4-kafka-ops-runner.md
- 当前源码基线：Armada 6c2c749d27cd；Web protocol 3f28e8c50667；Android protocol 415e6ff16bd3
- 分析边界：只读静态分析；未运行测试、构建、Compose、Runner 或服务；未连接任何环境

## 1. 结论标签

- **Observed**：当前源码、配置、测试源码或迁移直接证明；也包括本轮实际执行的只读检查结果。
- **Inferred**：由多项 Observed 事实推导，或本报告提出的治理设计；尚未运行验证。
- **Unknown**：现有证据无法确认，必须由用户决定、离线测试或经授权的环境只读检查确认。
- K1–K4 仅作为矛盾清单与当前文件行号的二级索引，不是替代源码的事实源；本报告不以其中的历史说明或结论状态覆盖当前代码。
- 本报告中的“代码已存在”只表示找到了当前实现；“本地已验证”只表示本轮实际运行过验证；“环境已验证”只表示本轮检查过真实环境。

## 2. 执行摘要

1. **Observed**：当前三套运行代码与部署工具没有共同的 canonical topic manifest。topic、group、环境变量、分区期望、Runner 对、告警与文档散落维护；Web 与 Android producer 都禁止自动建 topic，Android 仅对 node 派生 topic 做局部运行时创建，且使用 broker 默认分区与副本。现有 checker 只校验给定 subset 的存在性、分区数和 group 状态。证据：E-S1-01、E-S1-06。
2. **Observed**：四份报告存在四个需要治理层显式阻断的关键矛盾：Armada 把 Web 上线命令发到 protocol.account.commands.v1，但 Web 当前只消费 master 与 Web normal-group 两个 command topic；后端存在 contact-sync consumer，而 Web 当前只记录联系人聚合事件、Android 也没有该 event topic producer；Android group-action 是真实第五命令族，但 backend Compose、perf 清单和 quick Runner 漏项；Spring .DLT、Web 预留 protocol.dlq.v1、Web/Android 本地 JSONL DLQ 是不同机制，不能混称一个 DLQ。证据：E-S1-02 至 E-S1-05。
3. **Observed**：Web 与 Android 的 Kafka command/event key 均以账号级稳定标识为基础，Android coordinator 原样保留 source key；但 Kafka 只保证同一物理 topic、同一 key 所在 partition 内的顺序。跨 topic、跨 Web/Android pipeline、跨 Kafka 与 Redis Stream 不存在统一顺序证明。证据：E-S1-07。
4. **Inferred（阶段一原则）**：阶段一必须冻结当前消息语义：不改 topic 名、不改 payload/schema、不改 key、不改 consumer group、不改变 retry/ACK/commit 行为、不合并 topic、不自动 replay。只建立 manifest、生成器、只读 drift check、Runner inventory、dashboard/alert 合同和审批规则。
5. **Inferred（canonical 位置）**：在没有独立 contracts 仓的当前结构下，canonical source 建议放在 armada/armada-deploy/contracts/messaging/topic-manifest.v1.yaml，由 Armada deploy / messaging governance 角色维护；协议 producer owner、后端 consumer owner和运维 owner共同审批各自条目。实际个人或团队名称为 **Unknown**，必须由用户指定。
6. **Inferred（合并结论）**：阶段一没有任何 topic 满足“同 SLA、同 key、同保序范围、同消费者模型”四项证明，因此不建议立即合并。protocol.account.commands.v1 与 protocol.master.commands.v1、protocol.account.events.v1 与拆分事件 topic 仅列为调查候选；当前都缺至少一项证明。
7. **Inferred（恢复结论）**：replay 默认拒绝。只有保留原 commandId/eventId、原 key、原 topic major、确认 schema 兼容、幂等状态仍存在、未超过 tombstone/replay window、下游去重合同已验证、人工审批与 canary 均通过时才可放行。新建 commandId 的外部副作用命令不得称为 replay。
8. **Unknown**：当前环境实际 topic、partition、replication、retention、consumer lag、DLT、文件 DLQ、Redis XLEN/XPENDING、outbox age、告警加载与业务可用性均未验证；本设计不能被写成“治理已经落地”。

## 3. 覆盖范围、未覆盖范围与验证层级

### 3.1 已覆盖

- **Observed**：完整审阅 K1–K4，并只把它们作为当前代码证据索引；关键矛盾由本轮直接回查源码裁决。
- **Observed**：只针对 account-command、contact-sync、Android group-action、DLT/文件 DLQ、group.metadata_updated、key/ordering、provisioning、Runner 和 replay 幂等门禁回查当前源码。
- **Observed**：读取工作区 AGENTS.md、armada/AGENTS.md、armada owner 规则与 Android AGENTS.md；armada-protocol 当前没有项目级 AGENTS.md。
- **Observed**：源码复核时三个业务仓 HEAD 与 K1–K3 报告基线一致；未使用历史 change/README 覆盖当前代码。

### 3.2 未覆盖

- **Unknown**：没有检查 broker、Redis、数据库、Prometheus、Grafana、K8s、PM2、systemd 或真实 WhatsApp。
- **Unknown**：没有运行 Maven、Jest、TypeScript、Go、Compose 或 Runner；测试源码存在不等于本轮通过。
- **Unknown**：仓库外是否存在额外 producer、consumer、provisioner、DLQ consumer 或运维平台。
- **Unknown**：实际 owner、环境命名、分区/副本/保留期和业务 SLO 尚未由用户确认。

### 3.3 验证状态总表

| 能力 | 代码/设计已存在 | 本地已验证 | 环境已验证 | 当前判定 |
|---|---:|---:|---:|---|
| 当前 producer、consumer、outbox、Redis Stream 路径 | **Observed：存在** | **Observed：仅静态复核** | **Unknown：否** | 不能推出业务可用 |
| canonical manifest | **Observed：不存在；本报告给出设计** | **Unknown：未实现** | **Unknown：未应用** | 尚未验证 |
| manifest schema / linter / generator | **Inferred：设计完成** | **Unknown：未实现** | **Unknown：未应用** | 尚未验证 |
| deterministic provisioning | **Observed：当前不完整** | **Unknown：未实现新方案** | **Unknown：实际 topic 未查** | P0 缺口 |
| 全量 Runner / SLO | **Observed：当前仅部分覆盖** | **Unknown：未实现新方案** | **Unknown：未运行** | P1 缺口 |
| 人工审批 replay | **Observed：当前无通用闭环** | **Unknown：未实现** | **Unknown：未运行** | 默认拒绝 |

## 4. 四份报告矛盾与当前源码裁决

| ID | 报告表象 | 当前源码裁决 | 影响 | 状态 |
|---|---|---|---|---|
| C1 | K1 有 Web account command；K2 只有两个 Web command consumer | Armada 的 onlineCommandTopic 明确让 Web 上线走 accountCommandProperties；Web config/server 只装配 master 与 normal-group consumer | protocol.account.commands.v1 在当前三仓形成“有 producer、无已知 consumer” | **Observed**；仓库外 consumer 为 **Unknown** |
| C2 | K1 有 contact-sync listener；K2/K3 没有对应 producer | 后端确有 account.contacts_reported 专用 listener；Web config 不读取 contact topic，contacts.upsert/update 只记聚合日志；Android topic 列表没有 contact | contact-sync 是 consumer-only 合同，不能判定可用 | **Observed**；是否应启用为 **Unknown** |
| C3 | K1/K4 说 group-action 部署漏项；K3 说 Android 有五族 | Android coordinator 当前确实启动 lifecycle、message、group-join、group-action、normal-group 五个 source loop；Armada Compose 与 perf/Runner 清单未覆盖 group-action | 可能产生环境名称漂移和未观测积压 | **Observed**；环境是否已外部补齐为 **Unknown** |
| C4 | K1 是 source + .DLT；K2 有 protocol.dlq.v1 与文件 DLQ；K3 有部分文件 DLQ | Spring recoverer按原 topic + .DLT、原 partition；Web protocol.dlq.v1 只在 config 出现，实际失败写本地 JSONL；Android仅部分 account event 使用本地 JSONL | 三种失败资产的 owner、retention、replay 与 SLO 不同 | **Observed**；不可合并为一个逻辑 DLT |
| C5 | Web group.metadata_updated 可路由；Android也有同名 builder | Web topicKindFor 把 group.* 路由到 group topic；Android AccountEventPublisher 的 group case 未包含 group.metadata_updated，调用处忽略 publish error | 同一 schema 名在不同 producer 上路由能力不一致 | **Observed**；目标 topic 应由消费者合同确认 |
| C6 | K4 文档有建 topic；K2/K3 producer 禁 auto-create | Web local broker与 producer禁 auto-create；Android forward writer禁 auto-create，仅 node topic启动时用 broker defaults创建；现有 checker不检查 retention/replication/min ISR | 无法确定性重建或证明环境合同 | **Observed** |
| C7 | 各报告都说“重试若干次” | Armada outbox、Spring listener、KafkaJS、Android coordinator 对“max attempts/retries”的计数口径不同 | 人工配置容易出现 off-by-one 和错误 SLO | **Observed**；manifest 必须写明 totalAttempts 与 retriesAfterFirst |

## 5. 当前事实矩阵

### 5.1 Kafka topic 与动态 topic

| Topic / 模板 | 当前 producer | 当前 consumer / group | Key 与顺序 | 当前失败出口 | 治理判定 |
|---|---|---|---|---|---|
| protocol.account.commands.v1 | Armada Web online outbox | 当前 Web 代码未装配；group **Unknown** | 账号 key；仅本 topic partition 内 | Armada outbox DEAD | **Observed：单边；阶段一登记并阻断“已闭环”声明** |
| protocol.master.commands.v1 | Armada Web master commands | Web protocol / protocol-master-commands | 账号 key；topic 内保序基础 | outbox DEAD；Web reject/route failure 无 command DLT | **Observed：保留独立** |
| protocol.web.normal-group.commands.v1 | Armada normal-group | Web protocol / protocol-web-normal-group-commands | actor 账号 key；独立 consumer | outbox DEAD；Web command rejection缺口 | **Observed：保留独立** |
| protocol.android.lifecycle.commands.v1 | Armada | Android coordinator / coordinator-router-lifecycle | 账号 key；family 内 | coordinator permanent exhaustion / Reject 无 terminal | **Observed：保留独立** |
| protocol.android.message.commands.v1 | Armada | Android coordinator / coordinator-router-message | 账号 key；family 内 | 同上；node message state 条件幂等 | **Observed：保留独立** |
| protocol.android.group-join.commands.v1 | Armada | Android coordinator / coordinator-router-group-join | 账号 key；family 内 | 同上 | **Observed：保留独立** |
| protocol.android.group-action.commands.v1 | Armada | Android coordinator / coordinator-router-group-action | 账号 key；family 内 | 同上；deploy/Runner 漏项 | **Observed：保留独立并列 P0 契约缺口** |
| protocol.android.normal-group.commands.v1 | Armada | Android coordinator / coordinator-router-normal-group | 账号 key；family 内 | 无 owner语义与其他族不同 | **Observed：保留独立** |
| protocol.android.各 family.commands.node-{nodeId}.v1 | Android coordinator | 对应 node group，五族各一组 | 原样保留 source key；每 node/family 内 | node consumer + 部分 Redis inbox | **Observed：动态模板；保留独立** |
| protocol.account.state.events.v1 | Web/Android | Armada account-state group | 账号 key；producer均按账号写 | Spring .DLT | **Observed：共享事件 topic；保留独立** |
| protocol.account.group-sync.events.v1 | Web/Android | Armada group-sync group | 账号 key；快照语义 | Spring .DLT | **Observed：保留独立** |
| protocol.account.contact-sync.events.v1 | 当前三仓未找到 producer | Armada contact group | producer key **Unknown** | Spring .DLT | **Observed：consumer-only；启用/退役待决** |
| protocol.message.events.v1 | Web/Android | Armada message group | 账号 key；send result 与 ACK 共 topic | Spring .DLT；producer侧失败机制不同 | **Observed：现有共享消费者模型，保留独立** |
| protocol.group.events.v1 | Web/Android | Armada group group | 账号 key；多类 group fact/result | Spring .DLT | **Observed：保留独立** |
| protocol.pairing.events.v1 | Web | Armada pairing group | 账号 key | Spring .DLT | **Observed：保留独立** |
| protocol.normal-group.events.v1 | Web/Android | Armada normal-group result group | actor 账号 key | Spring .DLT | **Observed：专用 consumer，保留独立** |
| protocol.account.events.v1 | Web | 当前 Armada 无 consumer | 账号 key | Web file DLQ | **Observed：遗留/外部 consumer候选；不可直接退役** |
| protocol.owner.events.v1 | Web | 当前 Armada 无 consumer | 账号 key | Web file DLQ | **Observed：遗留/外部 consumer候选；不可直接退役** |
| source topic + .DLT（7 类） | Spring DeadLetterPublishingRecoverer | 当前无 review consumer/group | 保留 source key 与 partition | DLT 自身无恢复闭环 | **Observed：必须逐 source 建 manifest entry** |
| protocol.dlq.v1 | 无运行时 producer | 无 consumer | **Unknown** | 无 | **Observed：预留配置孤岛；不能当作现有 DLT** |

### 5.2 Redis Stream 与 outbox

| 资产 | 当前 producer / consumer | 分片与顺序 | Pending / retry | Retention | 判定 |
|---|---|---|---|---|---|
| protocol:worker:{workerId}:commands:p0 | Web master / Web worker | per-worker p0；同账号 lane 串行 | 仅同 consumer PEL读取；无跨 consumer claim | 无 MAXLEN/TTL；成功 XACK+XDEL | **Observed：保留独立 lane；需 XLEN/XPENDING SLO** |
| protocol:worker:{workerId}:commands:p1 | Web master / Web worker | per-worker p1 | 同上；部分 pending 永久跳过 | 同上 | **Observed：保留独立 lane** |
| protocol:worker:{workerId}:commands | Web master / Web worker | per-worker normal | safe allowlist replay、ack-only、poison 永久 pending | 同上 | **Observed：保留独立 lane** |
| armada:zhuan:{lifecycle}:node:{nodeId}:commands | Android lifecycle handler / scheduler | per-node；group zhuan-lifecycle | 同 node Stream支持 XAUTOCLAIM；跨 node不迁移 | dedupe 有 TTL；Stream无已证明全局 retention合同 | **Observed：动态模板；保留独立** |
| armada:zhuan:{message-ack}:events | Android receipt / 任一 ACK worker | 全 fleet共享；group zhuan-message-ack | idle 后可跨 node XAUTOCLAIM | dedupe TTL与环境配置相关 | **Observed：共享 recovery domain；保留独立** |
| protocol_command_outbox | Armada业务事务 / dispatcher | 账号 key写入 Kafka；DB行状态机 | 默认总发送尝试 3；LOCKED恢复、DISPATCHING转 DEAD | SENT默认7天、超链至少30天；DEAD保留 | **Observed：不是 Kafka DLT；需 age/status SLO** |

## 6. Canonical manifest 的位置与 owner

### 6.1 建议位置

- **Inferred（设计）**：canonical source 放在 armada/armada-deploy/contracts/messaging/topic-manifest.v1.yaml。
- **Inferred（设计）**：schema 放在 armada/armada-deploy/contracts/messaging/topic-manifest.schema.json。
- **Inferred（设计）**：跨仓 release/Runner 使用 manifest SHA256 与 manifestVersion，不读取未固定的工作树文件。
- **Inferred（设计）**：生成物放在 armada/armada-deploy/contracts/messaging/generated/；所有生成物带“generated、do not edit、source hash”头，禁止成为第二事实源。
- **Observed（选址依据）**：当前环境 profile、Compose、deep-check、Kafka checker、staging Runner 都位于 armada-deploy；它是现有代码中最接近跨仓运维控制面的目录。
- **Unknown**：是否新建独立 contracts 仓。若用户选择独立仓，schema与治理规则不变，只迁移 canonical source 与审批 owner。

### 6.2 Owner 模型

| 角色 | 职责 | 审批条件 | 状态 |
|---|---|---|---|
| Manifest governance owner | schema、命名、版本、生成器、冲突裁决 | 每次 manifest schema或治理规则变更 | **Inferred：建议角色；实际团队 Unknown** |
| Producer owner | producer、key、schema、retry、PII | 新 topic、schema、key、replay变更 | **Inferred：按条目必填** |
| Consumer owner | group、兼容、ordering、DLT、幂等 | schema/partition/group/replay变更 | **Inferred：按条目必填** |
| Operations owner | provisioning、retention、SLO、dashboard、告警 | 环境配置与生产 apply | **Inferred：按条目必填** |
| Data/security approver | PII等级、保留、导出与人工 replay | restricted PII 或外部副作用 replay | **Inferred：条件必填；实际角色 Unknown** |

- **Inferred（设计）**：canonical YAML 单写；Compose/K8s/Runner/dashboard/docs 只允许生成或校验。
- **Inferred（设计）**：同一条目至少需要 producer owner 与 consumer owner 双方审批；仅 producer 或仅 consumer 的条目必须带 exception、到期日与关闭条件。
- **Inferred（设计）**：分区增加、retention缩短、cleanup policy改变、DLT replay和 topic退役需要 operations owner 额外审批。

## 7. Manifest 字段合同

| 字段 | 必填结构 | 规则 | 当前迁移注意 |
|---|---|---|---|
| name | canonical logical name；动态资产可另带 nameTemplate | Kafka major版本保留在 .vN；环境物理名在 environment 中解析 | **Observed**：当前环境前缀不统一，阶段一必须 exact-map |
| version | topicMajor、schemaMajor、manifestRevision | 三种版本分开；禁止用 manifest revision代替 wire版本 | **Inferred** |
| domain | command/event/dlt/stream/outbox + 业务域 | 用于 owner、SLO和dashboard聚合 | **Inferred** |
| owner | governance、producer、consumer、operations、security | 角色ID，不放手机号或个人凭据 | **Inferred** |
| environment | enabled、physicalName、prefixStrategy、revisionConstraints | 环境差异显式映射；不能靠多层 fallback猜测 | **Observed：当前有双命名与前缀漂移** |
| producer | repo、component、configKey、delivery语义 | 记录 acks/idempotence与外层outbox；不能只写“Kafka producer” | **Observed：三仓语义不同** |
| consumer | repo、component、handler、enabled条件 | producer-only必须显式 exception | **Observed** |
| group | static groups、dynamic group templates、concurrency | group不是 owner；dynamic node group需 materializer | **Observed** |
| key | expression、source field、nullable、validation | 只记录字段语义；dashboard/log禁止暴露实际值 | **Observed：当前多为账号 key** |
| ordering | scope、crossTopic、perKeySerialization、barriers | 只允许声明被代码/测试证明的范围 | **Inferred** |
| partition | count per env、replication、minISR、changePolicy | DLT partition必须与source兼容；禁止自动缩分区 | **Observed：当前合同缺失** |
| retention | retentionMs、cleanupPolicy、businessMaxAge、segment/trim | Kafka、file DLQ、Redis Stream分别声明 | **Observed：多处 Unknown/无边界** |
| schema | format、envelopeVersion、schemaPath、compatibility、fixtures | 同名event跨producer必须共用contract test | **Observed：当前无canonical schema artifact** |
| retry | 每一层的 totalAttempts、retriesAfterFirst、backoff、classification | 禁止模糊的 maxRetries 单字段 | **Observed：计数口径不同** |
| DLT | mode、name、owner、group、partitionMapping、retention、terminalPolicy | Kafka .DLT、generic DLQ、file DLQ、Redis poison分开 | **Observed** |
| replay | default、allowedTypes、approval、maxAge、rateLimit、identity、audit | 默认deny；外部副作用必须保留原ID并过幂等门禁 | **Inferred** |
| PII | class、payloadClasses、encryption、allowedLabels、redaction、access | 禁止 key/payload进入指标标签和报告 | **Inferred；当前消息/contact/lifecycle应按restricted评审** |
| SLO | tier、lag、DLT、outbox、XLEN、XPENDING、oldestPending、drainTime | 阈值与metric query一起版本化 | **Inferred** |

### 7.1 Kafka discovery 条目示例

以下是 **Inferred（设计）** 的 discovery-phase 示例；partition/retention 的 Unknown 会阻止其进入 enforce 状态，不是假定环境值。

    manifestVersion: 1
    entries:
      - name: protocol.android.group-action.commands.v1
        version:
          topicMajor: 1
          schemaMajor: 1
          manifestRevision: 1.0.0
        kind: kafka-topic
        domain: command.android.group-action
        owner:
          governance: role:messaging-governance
          producer: role:armada-backend
          consumer: role:android-protocol
          operations: role:messaging-operations
        environment:
          strategy: exact-map
          physicalNames:
            default: protocol.android.group-action.commands.v1
          enforcement: discovery
        producer:
          - component: armada-api
            configKey: PROTOCOL_ANDROID_GROUP_ACTION_COMMANDS_TOPIC
            delivery: transactional-outbox-at-least-once
        consumer:
          - component: zhuan-coordinator
            handler: FamilyGroupAction
        group:
          - name: coordinator-router-group-action
          - nameTemplate: whatsapp-server-feature-android-armada-group-action-node-{nodeId}
        key:
          expression: protocolAccountId
          nullable: false
          exposeValue: false
        ordering:
          scope: same-physical-topic-same-key-partition
          crossTopic: none
        partition:
          observedNodeTopicMode: broker-default
          desiredCount: unknown
          replicationFactor: unknown
          minISR: unknown
          changePolicy: increase-only-with-order-review
        retention:
          retentionMs: unknown
          cleanupPolicy: unknown
          businessMaxAgeMs: unknown
        schema:
          format: json
          envelopeVersion: v1
          schemaPath: contracts/messaging/schemas/protocol-command-envelope.v1.schema.json
          compatibility: backward
        retry:
          layers:
            - name: armada-outbox
              totalAttempts: 3
            - name: android-coordinator-permanent-write
              totalAttempts: 30
        DLT:
          mode: none-current
          desiredPolicy: decision-required
        replay:
          default: deny
          preserveCommandId: true
          approval: producer-consumer-operations
        PII:
          class: restricted
          allowedMetricLabels: [environment, logicalName, group, outcome]
          forbiddenMetricLabels: [key, commandId, payload]
        SLO:
          tier: T0
          maxOldestLagSeconds: 60
          maxDrainSeconds: 120

### 7.2 Redis Stream discovery 条目示例

    - name: protocol:worker:{workerId}:commands:{lane}
      version:
        schemaMajor: 1
        manifestRevision: 1.0.0
      kind: redis-stream
      domain: command.web.worker-inbox
      owner:
        producer: role:web-protocol-master
        consumer: role:web-protocol-worker
        operations: role:messaging-operations
      environment:
        strategy: exact-template
        enforcement: discovery
      producer:
        - component: web-protocol-master
      consumer:
        - component: web-protocol-worker
      group:
        - name: protocol-workers
      key:
        expression: workerId-and-lane
        exposeValue: false
      ordering:
        scope: same-worker-lane-and-account-lane
        crossStream: none
      partition:
        mode: per-worker-lane
        lanes: [p0, p1, normal]
      retention:
        current: unbounded-no-maxlen-no-ttl
        desired: decision-required
      schema:
        format: json-field-command
        compatibility: backward
      retry:
        current: same-consumer-pending-read-with-command-allowlist
      DLT:
        mode: none-current
      replay:
        default: deny
        currentSafeAllowlistMustBeRecorded: true
      PII:
        class: restricted
        payloadInspection: forbidden
      SLO:
        tier: T0
        maxOldestPendingSeconds: 60
        maxDrainSeconds: 120

## 8. 从 manifest 生成或校验的产物

下表的目标行为、失败规则和分阶段切换均为 **Inferred（设计）**；其中提到的现有缺口另由 Observed 证据支持。

| 目标 | Phase 1 行为 | Enforce 后行为 | 失败规则 |
|---|---|---|---|
| 环境变量 | **Inferred**：生成变量名、component、默认logical name清单；不生成凭据值 | 生成非敏感 topic/group fragment 与 schema validation | 未登记变量、双入口fallback或同环境冲突即失败 |
| Compose | **Inferred**：只读解析并与resolved manifest diff | 生成 topic/group environment fragment；secret仍由现有机制提供 | 缺失、孤儿、物理名不一致失败 |
| K8s | **Inferred**：校验 ConfigMap/env/volume/rule query | 生成非secret ConfigMap片段、annotations与监控inventory | image/revision/manifest hash不匹配失败 |
| Topic provisioning | **Inferred**：只生成 dry-run plan | 审批后 create missing、增加partition、设置非破坏配置 | 禁止删除、缩partition、静默缩retention |
| Topic checker | **Inferred**：生成完整topic/group/config清单 | 只读检查partition、replication、ISR、retention、cleanup与group | 未采集不能当0；Unknown阻断发布 |
| Android dynamic topic | **Inferred**：从runtime manifest的node列表展开5族模板 | node接收任务前先provision/verify | broker-default不得进入enforce |
| Runner | **Inferred**：生成Kafka pairs、DLT pairs、Redis stream patterns、outbox query contract | 采start/peak/end与drain-time | subset PASS不得提升为全系统PASS |
| Dashboard | **Inferred**：生成metric inventory和query lint | 生成/校验Grafana panels | query无metric、label高基数或无数据失败 |
| 告警 | **Inferred**：从SLO生成recording/alert rules并做表达式测试 | 部署前验证rule load与sample query | 名称/语义不匹配失败 |
| 文档 | **Inferred**：生成topic catalog、owner表、replay runbook、retirement ledger | 每个release带manifest hash | 手写topic表不得作为事实源 |

- **Inferred（设计）**：generator 输出必须 deterministic；同一 manifest 与 environment 输入产生相同hash。
- **Inferred（设计）**：所有输出包含 source manifest hash、generator version、environment和生成时间；runtime plan固定四仓SHA与manifest hash。
- **Inferred（设计）**：Phase 1 先 warning 两个迭代，再把 unmanifested active topic、缺owner、缺key/order、缺SLO提升为 CI error。
- **Inferred（安全）**：generator 与报告只处理变量名、topic/group与聚合指标；禁止读取、输出或缓存凭据、消息正文、联系人、JID或代理信息。

## 9. 命名、版本、兼容期与退役规则

### 9.1 命名

1. **Inferred（规则）**：Kafka logical name采用 protocol.[platform].[domain].[commands|events].vN；已存在名称在阶段一原样登记，不做批量改名。
2. **Inferred（规则）**：环境差异放 environment.physicalNames exact-map；不再允许应用、Compose和Runner各自拼前缀。
3. **Inferred（规则）**：Android node topic只允许 manifest模板从source name派生；nodeId必须来自runtime manifest，不从自由文本拼接。
4. **Observed**：Spring 当前 DLT 规则是 source physical name + .DLT。阶段一保留该精确规则，不能改成 protocol.dlq.v1。
5. **Inferred（规则）**：group name独立版本化；group变更会创建新消费进度，必须按offset迁移方案处理，不能当普通字符串修改。

### 9.2 版本升级

- **Inferred（规则）**：topic .vN 只表示wire major。向后兼容字段新增留在同major；删除、改类型、改含义、改key、改保序范围或改consumer模型必须新建.vN+1。
- **Inferred（规则）**：schema minor/patch由schema artifact版本管理；manifestRevision仅表示治理元数据变更。
- **Inferred（规则）**：major升级顺序为：创建新topic与DLT → 部署兼容consumer → 验证双读幂等 → producer dual-write或切换 → drain旧topic → 停旧producer → 兼容期 → 退役审批。
- **Inferred（规则）**：任何dual-write必须使用稳定eventId/commandId并有consumer去重证据；否则不允许。

### 9.3 兼容期

- **Inferred（规则）**：兼容期下限 = max(topic retention、DLT retention、最大允许replay age、部署rollback window)。
- **Observed**：Android message state默认30天，Armada Android超链replay安全窗为29天；相关message command major的兼容期不得短于30天，除非先改变并验证幂等合同。
- **Unknown**：其他topic具体兼容期。用户需按业务tier决定；在Unknown状态不得自动退役。

### 9.4 退役

退役必须同时满足以下 **Inferred（规则）**：

1. producer引用、环境变量、Compose/K8s与Runner生成物均为0；
2. 所有已知consumer group已切换，旧topic lag为0且持续一个完整rollback window；
3. DLT、file DLQ和待审批replay为0；
4. 旧schema兼容期结束，manifest记录lastProducerAt、lastConsumerAt和retireAfter；
5. producer、consumer、operations三方审批；
6. 先disable provisioning，再保留只读观察，最后单独审批删除；
7. 不以“当前代码没有consumer”单独证明可删除，因为仓库外consumer仍可能存在。

## 10. Partition、key 与 ordering 判断规则

1. **Observed**：当前主要Kafka key为账号级稳定标识；Android coordinator转发时原样保留source key。阶段一只校验，不改变。
2. **Inferred（规则）**：先写 ordering entity，再选key；没有明确ordering entity的topic不得进入enforce。
3. **Inferred（规则）**：严格同账号顺序只在“同一physical topic + 相同key序列化 + 相同partition + consumer按key串行”成立。任何跨topic顺序声明必须有显式barrier/sequence与测试，否则写none。
4. **Inferred（规则）**：partition count由峰值吞吐、单partition安全吞吐、consumer并发和key倾斜共同决定；公式与压测证据进入manifest evidence，不使用broker default。
5. **Inferred（规则）**：增加partition会改变hash到partition的映射，可能打断同key历史顺序；严格保序topic优先新major迁移，或先drain并设置明确cutover barrier。
6. **Inferred（规则）**：partition只允许增加，不允许自动缩减；replication与minISR变更走独立reassignment审批。
7. **Observed**：Spring recoverer保留source partition编号，因此每个.DLT的partition count必须至少覆盖source，治理目标应为相等。
8. **Inferred（规则）**：Redis Stream的partition等价物是stream template/lane。Web p0/p1/normal、Android per-node lifecycle与fleet-wide ACK recovery domain不同，不能因都叫Stream而合并。
9. **Inferred（合并硬门）**：只有同SLA、同key表达式、同ordering scope、同consumer模型四项均为Observed并通过测试，才能提出合并。任一Unknown即拒绝。

## 11. Retry、DLT、人工 replay 与幂等门禁

### 11.1 当前机制必须分层登记

| 层 | 当前事实 | Manifest写法 | 状态 |
|---|---|---|---|
| Armada outbox | 默认总发送尝试3；PENDING/LOCKED/DISPATCHING/SENT/DEAD等状态 | retry.layers.armadaOutbox + terminalState | **Observed** |
| Spring consumer | 首次后最多3次retry；BusinessException直接recover；source+.DLT | retry.layers.springConsumer + DLT.kafkaDerived | **Observed** |
| Web KafkaJS event producer | retriesAfterFirst=2，总计最多3次；required与non-required分叉 | retry.layers.kafkaJs + DLT.file | **Observed** |
| Web Redis pending | safe allowlist、ack-only、unsafe/poison永久pending；无跨consumer claim | retry.layers.redisPel + DLT.none | **Observed** |
| Android coordinator | transient无限retry；permanent最多30次后commit；Reject直接commit | retry.layers.coordinator + terminalPolicy.missing | **Observed** |
| Android lifecycle/ACK Stream | XAUTOCLAIM；recovery domain分别为per-node与fleet-wide | retry.layers.redisAutoClaim | **Observed** |
| Android file DLQ | 仅部分account event；无retention/replay | DLT.file-partial | **Observed** |

### 11.2 目标 DLT 合同

- **Inferred（规则）**：每个 source topic 独立声明 DLT，不能让一个 generic DLT 掩盖不同 schema、owner、PII 与 replay 规则。
- **Inferred（规则）**：DLT entry必须有owner、review group、partition mapping、retention、oldest-age SLO、schema、PII和terminal reason taxonomy。
- **Inferred（规则）**：malformed/permanent消息只有在DLT或标准terminal result成功落地后才能推进source offset；阶段一只记录现状，不改变commit语义。
- **Inferred（规则）**：file DLQ必须单独治理volume、权限、count/bytes/oldest age、rotation、checkpoint与replay；不得把文件append成功等同下游业务成功。
- **Inferred（规则）**：Redis poison entry需要独立dead stream或审计表；在实现前，manifest状态为gap，不允许Runner宣称drained。

### 11.3 人工审批 replay

Replay默认 **deny**。放行必须同时通过以下 **Inferred（门禁）**：

1. incident/change ID、环境、source、目标topic major、数量与reason分类齐全；
2. producer owner、consumer owner、operations owner审批；restricted PII或外部副作用再加security/data审批；
3. 保留原commandId/eventId、原Kafka key、原schema major和原trace关联；禁止生成新业务ID冒充replay；
4. 目标consumer schema兼容且dedupe合同已由离线测试证明；
5. Web/Android command state或下游eventId幂等状态存在、fingerprint匹配、未超replay/tombstone window；
6. 已确认没有terminal success或已发生不可逆副作用；无法确认时保持UNKNOWN/BLOCK；
7. dry-run只输出聚合count、reason、age、eligibility，不输出payload/key；
8. 先1条canary，再限速小批；每批有checkpoint和审计hash；
9. 失败时停止，不自动换topic、不重置consumer offset、不删除source/DLT；
10. replay完成后同时验证source/DLT lag、业务terminal状态与重复副作用计数。

- **Observed**：当前Armada通用replay只允许原message command从SENT/DEAD回PENDING；Web message state无TTL，Android message state默认30天，Armada Android安全窗29天。它们提供条件性防重基础，不是通用运维授权。
- **Inferred**：Redis state缺失、错误prefix、提前过期/淘汰或新commandId都会使重复外部触达成为可能；因此Runner必须把idempotency state eligibility纳入replay前置检查。

## 12. SLO 与指标合同

### 12.1 建议的初始 tier

以下阈值均为 **Inferred（初始设计值）**，不是当前达标结论。上线采集7天基线后由owner调整。

| Tier | 典型通道 | Kafka oldest lag | Drain time | Redis oldest pending | DLT/file DLQ |
|---|---|---:|---:|---:|---|
| T0 外部副作用/terminal | message command/result/ACK、group-join/action、normal-group result | ≤60s | 停止生产后≤120s | ≤60s | steady state=0；新增立即告警；15min内完成分类 |
| T1 生命周期/状态 | online/offline、account state、pairing、group fact | ≤300s | ≤600s | ≤300s | 新增15min内确认owner；4h内有处置决策 |
| T2 大快照/批处理 | group-sync、contact-sync、snapshot | ≤900s | ≤1800s | ≤900s | oldest≤4h；超时阻断“已drain” |

### 12.2 必须生成的指标

| 指标 | 定义 | 告警/验收 | 标签限制 |
|---|---|---|---|
| Kafka lag | topic/group/partition的latest-committed；汇总total/max | oldest-age或predicted drain超SLO；未初始化/截断直接失败 | env、logical topic、group、partition |
| Kafka lag age | 最早未消费record时间或等价安全估算 | 按tier阈值 | 不含key/payload |
| DLT backlog | count、ingress rate、oldest timestamp、drain rate | T0任何新增；其他按oldest SLO | source、reasonClass、owner |
| File DLQ | file count、bytes、oldest mtime、append failures | volume缺失或oldest超SLO失败 | component、env；不读行内容 |
| Outbox status | PENDING/LOCKED/DISPATCHING/SENT/DEAD count | DEAD新增告警；非终态oldest超SLO | backend、commandFamily、status |
| Outbox age | 每状态MIN(created_at/updated_at)聚合 | PENDING建议≤60s；LOCKED/DISPATCHING≤timeout+1 scan；DEAD 15min内分类 | 禁止payload/account标签 |
| XLEN | allowlist Stream长度 | count只作容量信号；与ingress rate共同算drain | stream logical template、lane、env |
| XPENDING | group pending count | pending count上升且oldest不降告警 | stream template、group |
| Oldest pending | XPENDING最老idle age | 按tier阈值 | 不读取entry字段 |
| Claim/retry | XAUTOCLAIM/XCLAIM、safe retry、poison、skipped计数 | poison新增、claim持续失败告警 | component、reasonClass |
| Drain time | 从peak到backlog达到目标的实际时间；并计算backlog/net drain rate预测值 | Runner必须同时输出start/peak/end与elapsed | env、logical queue、tier |

- **Inferred（规则）**：XLEN和XPENDING不设跨所有Stream的固定绝对阈值；count阈值取 max(100, 最近5分钟入口量的2倍)，但oldest pending与drain time仍是硬SLO。
- **Inferred（规则）**：Runner的“未采集”必须是UNKNOWN/FAIL，绝不能序列化为0。
- **Observed**：当前quick Runner只有6对Kafka、Redis INFO与三点采样；没有DLT、Stream、outbox age。当前Web Prometheus规则还有metric名称/语义不匹配，local Prometheus不加载rules。

## 13. 保持独立与疑似可合并

### 13.1 阶段一必须保持独立

| 集合 | 独立理由 | 状态 |
|---|---|---|
| Web master vs Web normal-group command | 代码强制dedicated topic；consumer filter、业务隔离与结果模型不同 | **Observed：保持独立** |
| Android lifecycle/message/group-join/group-action/normal-group | 五个source group、五个node group、handler与failure semantics不同 | **Observed：保持独立** |
| Android source vs node topic | source由coordinator消费，node topic由指定node消费，属于两级路由 | **Observed：保持独立** |
| account-state、group-sync、message、group、pairing、normal-group event | schema、consumer group、并发、SLO与业务域不同 | **Observed：保持独立** |
| normal-group event vs group event | 后端有专用normal-group consumer，producer也有显式topic override | **Observed：保持独立** |
| source .DLT、protocol.dlq.v1、Web file DLQ、Android file DLQ、Redis poison | 存储、partition、owner、consumer、PII、replay均不同 | **Observed：保持独立** |
| Web p0/p1/normal Stream | priority、block/count与pending allowlist不同 | **Observed：保持独立** |
| Android lifecycle Stream vs fleet ACK Stream | recovery domain分别为per-node和fleet-wide | **Observed：保持独立** |

### 13.2 仅调查、不建议合并的候选

| 候选 | 同SLA | 同key | 同保序范围 | 同消费者模型 | 当前判定 |
|---|---|---|---|---|---|
| protocol.account.commands.v1 → protocol.master.commands.v1 | **Unknown**：online与master命令SLO未统一 | **Observed：账号key** | **Unknown/不满足**：当前分属不同topic | **不满足**：account无当前consumer，master有 | **Inferred：疑似路由整合候选；当前禁止合并** |
| protocol.account.events.v1 → state/group-sync等拆分topic | **Unknown/可能不同** | **Observed：账号key** | **Unknown** | **不满足**：catch-all无Armada consumer，拆分topic有专用consumer | **Inferred：优先调查退役，不是合并建议** |
| protocol.owner.events.v1 → account事件 | **Unknown** | **Observed：账号key** | **Unknown** | **不满足**：owner consumer未知 | **Inferred：调查外部consumer后决定退役或保留** |
| 五个Android source topic合一 | **不满足/Unknown**：family语义不同 | **Observed：账号key** | **不满足**：当前family级隔离 | **不满足**：五个source/node group | **Observed：不合并** |
| 五个Android node topic合一 | **不满足/Unknown** | **Observed：账号key** | **不满足**：family和node双隔离 | **不满足**：五组consumer | **Observed：不合并** |
| 各source .DLT → protocol.dlq.v1 | **不满足** | **部分相同** | **不满足**：Spring需原partition | **不满足**：无generic consumer | **Observed：不合并** |

- **Observed**：没有一行同时满足四个合并硬门。
- **Inferred**：未来若要提出合并，必须附带四项测试证据、容量模型、dual-read/write计划与回滚；仅“名字相似”或“都没有consumer”不构成证据。

## 14. 分阶段实施任务

以下为 **Inferred（实施计划）**。每项估时均不超过4小时；估时只覆盖单个可审查切片。

### 14.1 Phase 0：canonical 合同骨架，不改消息语义

| ID | 任务 | 估时 | 交付/验收 | 语义影响 |
|---|---|---:|---|---|
| P0-01 | 用户确认canonical仓、governance owner与审批角色 | 1h | ADR决策表 | 无 |
| P0-02 | 建manifest JSON Schema，包含全部必填字段和Unknown/waiver状态 | 4h | schema fixture通过/失败样例 | 无 |
| P0-03 | 导入Armada 8个outbound与7个inbound topic | 4h | 与application/current listeners静态diff为0 | 无 |
| P0-04 | 导入Web topics、2个command group、3类Stream模板与file DLQ | 4h | 与config/server/stream代码diff为0 | 无 |
| P0-05 | 导入Android 5 source、5 node模板、5 event与2类Stream | 4h | 与commandFamilyOrder/options diff为0 | 无 |
| P0-06 | 为C1–C5建立exception/owner/到期条件 | 3h | 单边topic不能进入enforce | 无 |
| P0-07 | 实现manifest lint：唯一名、owner、key/order、DLT引用、PII、SLO | 4h | 离线fixture测试 | 无 |

### 14.2 Phase 1：shadow 生成与只读校验，不改消息语义

| ID | 任务 | 估时 | 交付/验收 | 语义影响 |
|---|---|---:|---|---|
| P1-01 | 生成各component环境变量名清单 | 3h | 只含非敏感变量名与topic/group值 | 无 |
| P1-02 | Compose resolved配置只读diff器 | 4h | 缺失/孤儿/冲突报告 | 无 |
| P1-03 | K8s ConfigMap/env/rule/dashboard只读diff器 | 4h | 不读取Secret值 | 无 |
| P1-04 | provisioning dry-run plan生成器 | 4h | 只输出create/increase/config diff，不apply | 无 |
| P1-05 | 生成完整Kafka topic/group/DLT inventory | 4h | 当前18 base、7 derived DLT与dynamic模板可追溯 | 无 |
| P1-06 | 从runtime manifest展开Android active-node 5族topic/group | 4h | fixture覆盖0/1/3 node | 无 |
| P1-07 | Runner改为读取生成的Kafka pairs | 4h | 旧6对仍存在且新增项不被写成0 | 无 |
| P1-08 | 增加Redis XLEN/XPENDING/oldest collector fixture | 4h | 只读聚合，不读payload | 无 |
| P1-09 | 增加outbox status/oldest聚合query contract与fixture | 4h | 不选择payload/account字段 | 无 |
| P1-10 | 增加DLT/file DLQ count/bytes/oldest collector | 4h | file只stat不读取内容 | 无 |
| P1-11 | 从SLO生成recording/alert rules并做metric名称测试 | 4h | 修复当前三类query mismatch | 无 |
| P1-12 | 生成Grafana panel query与provisioning校验 | 4h | 所有目标metric有panel/query | 无 |
| P1-13 | 生成topic catalog、owner与replay runbook | 2h | 文档source hash一致 | 无 |
| P1-14 | quick plan加入manifest hash与generator version | 4h | 四仓SHA + manifest SHA共同固定 | 无 |
| P1-15 | CI先warning后enforce缺owner/key/SLO/unmanifested topic | 2h | 两阶段开关和回滚开关 | 无 |

### 14.3 Phase 2：批准后的运维闭环，仍不合并 topic

| ID | 任务 | 估时 | 交付/验收 | 语义影响 |
|---|---|---:|---|---|
| P2-01 | 在隔离broker fixture验证provision plan幂等 | 4h | 第二次plan无变化 | 无生产影响 |
| P2-02 | 为每source生成DLT partition兼容检查 | 3h | source/DLT partition mismatch失败 | 无 |
| P2-03 | 实现replay dry-run eligibility报告 | 4h | 默认deny；不输出payload/key | 无 |
| P2-04 | 实现审批记录与批次checkpoint模型 | 4h | 中断可续且不重复整批 | 无 |
| P2-05 | 用fake sender验证同ID replay一次物理send | 4h | state完整/缺失/超窗三用例 | 无真实WhatsApp |
| P2-06 | 按环境逐个执行只读manifest check | 2h/环境 | 经授权后形成脱敏快照 | 只读 |
| P2-07 | 按环境逐个审批provision create-only | 2h/环境 | 不删topic、不缩partition/retention | 新建资源，需授权 |

### 14.4 Phase 3：语义修复，必须单独设计与审批

| ID | 任务 | 估时 | 单切片边界 | 状态 |
|---|---|---:|---|---|
| P3-01 | 决定Web account command是补consumer还是迁到master | 2h | 只做ADR与契约测试设计 | **Unknown：用户决定** |
| P3-02 | 决定contact-sync是补producer还是退役consumer | 2h | 只做ADR与schema确认 | **Unknown：用户决定** |
| P3-03 | 补Android group-action环境映射与Runner生成 | 3h | 不改payload/key/group | **Inferred：低风险配置修复** |
| P3-04 | 补Android group.metadata_updated topicFor与routing测试 | 3h | 先由consumer contract定目标topic | **Unknown：目标topic待决** |
| P3-05 | Web command rejection成功落DLT后才resolve | 4h/命令类 | 每次只覆盖一类failure | **会改commit语义，需审批** |
| P3-06 | Android coordinator permanent failure terminal/DLT | 4h/family | 每次只覆盖一族 | **会改commit语义，需审批** |
| P3-07 | Web PEL claim/poison DLQ | 4h/切片 | 先metrics，再claim，再terminal | **会改recovery语义，需审批** |

## 15. 验收标准

### 15.1 Phase 0 / 1

1. **Inferred（验收）**：当前代码/config/Compose/K8s/Runner中出现的每个Kafka topic、group、DLT、Redis Stream模板和outbox均被manifest覆盖；无未解释差异。
2. **Inferred（验收）**：每个active entry具备name、version、domain、owner、environment、producer、consumer、group、key、ordering、partition、retention、schema、retry、DLT、replay、PII、SLO；Unknown只能存在于discovery状态并带owner与到期条件。
3. **Inferred（验收）**：同一输入连续两次生成hash一致；生成物不可手改，CI diff为0。
4. **Inferred（验收）**：阶段一运行前后topic name、payload、key、group、retry、ACK、offset与Redis Stream行为完全不变。
5. **Inferred（验收）**：Runner inventory来自manifest；所有required通道都有明确采集结果，未采集为UNKNOWN/FAIL。
6. **Inferred（验收）**：dashboard/rule query在fixture registry中返回预期series；不存在当前RSS/Capping/WorkerLost语义错误。
7. **Inferred（验收）**：所有report、metric、log、generated artifact不含凭据、手机号、JID、消息正文、联系人、代理信息或原始payload。

### 15.2 Provisioning / replay

1. **Inferred（验收）**：dry-run能重建完整base、derived DLT与active-node topic集合，且第二次执行无diff。
2. **Inferred（验收）**：existing topic只允许安全增加partition或非破坏配置；删除、缩partition、缩retention必须被拒绝。
3. **Inferred（验收）**：source/DLT partition不兼容时release失败。
4. **Inferred（验收）**：replay默认deny；缺任一审批、原ID、schema、state、age或dedupe证据均BLOCK。
5. **Inferred（验收）**：隔离Kafka/Redis + sender stub中，同ID/state完整只执行一次物理send；state缺失/超窗返回BLOCK/UNKNOWN。
6. **Unknown**：真实环境验收必须在用户另行授权后进行；本报告没有完成。

## 16. 回滚方案

- **Inferred（Phase 1）**：manifest先shadow，不作为运行时配置源。发现误报时关闭CI enforce，保留warning与当前手写配置；消息路径不受影响。
- **Inferred（生成物）**：每个release保留上一版manifest hash与generated bundle；回滚只切回上一版生成物，不回滚业务数据。
- **Inferred（provisioning）**：Phase 1仅dry-run。批准apply后，topic创建和partition增加视为不可逆；回滚方式是停止producer/consumer切换并恢复上一配置，不删除topic、不缩partition。
- **Inferred（retention）**：任何缩短retention或改变cleanup policy不进入自动apply；若误配，立即恢复旧配置并冻结replay/退役，不能恢复已删除record。
- **Inferred（Runner/alert）**：新collector/rule/dashboard可独立feature flag；回滚到上一manifest hash，但不得把缺失metric当0。
- **Inferred（replay）**：canary失败立即停止批次，保留checkpoint和审计记录；不自动offset reset、不删除DLT、不换topic、不创建新commandId。
- **Inferred（topic migration）**：本阶段不合并topic，因此没有数据面切换回滚。未来major迁移必须保留旧consumer与旧topic到兼容期结束。

## 17. P0 / P1 / P2 问题

### 17.1 P0

| ID | 问题 | 证据与影响 | 状态 |
|---|---|---|---|
| S1-P0-01 | 无canonical manifest与完整provisioner | auto-create关闭，base/DLT/dynamic合同无法确定性重建 | **Observed** |
| S1-P0-02 | Web account-command、contact-sync单边合同；Android group-action部署/Runner漏项 | producer/consumer/environment无法闭环 | **Observed**；环境实际补齐为 **Unknown** |
| S1-P0-03 | 失败出口分裂且无统一owner/replay门禁 | Spring .DLT、orphan generic DLQ、两套file DLQ、Redis poison语义不同 | **Observed** |
| S1-P0-04 | 现有Web/Android有commit/delete但无terminal/DLT路径 | 可能形成不可自动恢复命令或结果 | **Observed/Inferred**；本设计阶段不改语义 |
| S1-P0-05 | partition/replication/retention/minISR未知，DLT需保留source partition | provisioning与recover可能失败 | **Observed**；实际配置 **Unknown** |

### 17.2 P1

| ID | 问题 | 状态 |
|---|---|---|
| S1-P1-01 | Runner无全量Kafka lag、DLT、outbox age、XLEN、XPENDING、oldest pending与drain SLO | **Observed** |
| S1-P1-02 | key/order只存在代码惯例，没有manifest与跨仓contract test | **Observed** |
| S1-P1-03 | environment变量、Compose/K8s、perf/quick pairs多处重复与漂移 | **Observed** |
| S1-P1-04 | Web PEL无跨consumer claim；Android lifecycle无跨node迁移 | **Observed/Inferred** |
| S1-P1-05 | group.metadata_updated在Web/Android producer间路由能力不一致 | **Observed** |
| S1-P1-06 | replay安全依赖Redis state、原ID与窗口，但Runner不检查 | **Observed/Inferred** |

### 17.3 P2

| ID | 问题 | 状态 |
|---|---|---|
| S1-P2-01 | Prometheus规则与metric名称/语义不一致，local rules未加载 | **Observed** |
| S1-P2-02 | Android每node增加5 topic/group，当前无容量与退役ledger | **Observed/Inferred** |
| S1-P2-03 | retry次数口径、topic/group默认值与文档术语不统一 | **Observed** |
| S1-P2-04 | protocol.dlq.v1、account/owner events等孤儿/遗留候选没有状态机 | **Observed**；外部consumer **Unknown** |

## 18. Unknown 与最便宜的下一步验证

| ID | Unknown | 最便宜的下一步验证 | 通过标准 |
|---|---|---|---|
| U1 | canonical repo与实际owner | 用户确认现有armada-deploy方案或独立contracts仓，并指定角色ID | owner和审批边界无空项 |
| U2 | account.commands是否有仓库外consumer | 先只读检查其余本地部署/IaC引用；仍不明再申请broker group只读检查 | producer、consumer、group闭环或明确退役ADR |
| U3 | contact-sync是否应启用 | 对照当前业务需求与consumer schema；决定补producer或disable/retire | 单向合同有owner与截止日期 |
| U4 | group.metadata_updated目标topic | 读取Armada consumer对该event的实际handler与schema契约，补离线routing test | Web/Android route一致 |
| U5 | 环境physical names | 只读渲染脱敏Compose/K8s env名和值hash，与exact-map diff | 无fallback猜测、无漏项 |
| U6 | topic partition/replication/retention/minISR | 经授权只读describe topics/configs，只输出聚合 | 与enforced manifest一致 |
| U7 | 完整group lag/DLT | 扩展只读collector后采start/peak/end | required项都有数据且drain达SLO |
| U8 | Redis XLEN/XPENDING/oldest | allowlist只读XINFO/XPENDING/XLEN，不读取entry | oldest与drain达SLO |
| U9 | outbox age/DEAD | 经数据库只读授权执行status count + MIN时间聚合，不读payload | 非终态age达SLO，DEAD有owner |
| U10 | replay是否单次物理send | 隔离Kafka/Redis + sender stub测试原ID、state缺失、超窗 | 一次send或BLOCK/UNKNOWN |
| U11 | SLO阈值是否适合真实吞吐 | 先上线只读metrics，收集7天baseline | owner基于p95/p99与业务窗口确认 |
| U12 | 当前测试是否通过 | 在允许临时构建产物的独立任务运行定向offline tests | 实际命令输出通过 |

## 19. 需要用户决定的事项

1. **Unknown / 决策 D1**：canonical source保留在armada-deploy，还是新建独立contracts仓。
2. **Unknown / 决策 D2**：manifest governance、producer、consumer、operations与security的实际owner ID。
3. **Unknown / 决策 D3**：test1/perf/prod physical topic的统一prefix策略；阶段一可先exact-map保留现状。
4. **Unknown / 决策 D4**：protocol.account.commands.v1是补Web consumer、迁到master，还是确认外部consumer；未决定前禁止合并。
5. **Unknown / 决策 D5**：contact-sync是补Web/Android producer，还是退役后端consumer与部署变量。
6. **Unknown / 决策 D6**：protocol.account.events.v1、protocol.owner.events.v1、protocol.dlq.v1是否有仓库外消费者/运维用途。
7. **Unknown / 决策 D7**：group.metadata_updated进入group topic还是normal-group topic，以Armada consumer合同为准。
8. **Unknown / 决策 D8**：各tier最终partition、replication、minISR、retention与SLO。
9. **Unknown / 决策 D9**：DLT review owner、审批SLA、file DLQ保留期与持久卷标准。
10. **Unknown / 决策 D10**：replay审批人数、canary批量、速率上限与restricted PII访问角色。
11. **Unknown / 决策 D11**：Android node topic保留/退役周期与最大active/historical node容量。

## 20. Evidence → Finding → Path

### 20.1 Evidence

下表各项均为 **Observed** 的当前文件证据；K1–K4 只承担定位索引作用。

| Evidence | source_type | 不可变观察 | source_ref | repro_command |
|---|---|---|---|---|
| E-S1-01 | file | 当前topic/config/Runner定义散落，未见共同canonical manifest | A:application.yml:176-233；A:docker-compose.rds.yml:36-63；A:test1-quick.py:60-67；W:config.ts:59-104；Z:coordinator/main.go:49-109 | rg -n -e 'TOPIC' -e 'topic' -e 'KAFKA_PAIRS' 指定文件 |
| E-S1-02 | file | Armada Web online走account topic，Web只消费master/normal-group | A:ProtocolCommandOutboxServiceImpl.java:2015-2037；W:config.ts:62-74,323-338；W:server.ts:383-415 | rg -n -e 'onlineCommandTopic' -e 'topicCommands' -e 'topicNormalGroupCommands' 指定文件 |
| E-S1-03 | file | 后端有contact listener；Web没有contact topic映射且联系人事件只记日志 | A:ProtocolAccountContactEventConsumer.java:22-60；W:config.ts:311-338；W:account-manager.ts:1777-1869 | rg -n -e 'account-contact' -e 'contacts.upsert' -e 'contacts.update' 指定文件 |
| E-S1-04 | file | Android有五族；node topic用broker defaults；deploy/Runner漏group-action | Z:route_key.go:14-49；Z:coordinator/main.go:49-90；Z:topics.go:15-74；A:docker-compose.rds.yml:36-57；A:test1-quick.py:60-67 | rg -n -e 'FamilyGroupAction' -e 'GROUP_ACTION' -e 'KAFKA_PAIRS' 指定文件 |
| E-S1-05 | file | Spring .DLT、Web orphan topicDlq与file DLQ、Android file DLQ不等价 | A:ProtocolKafkaConfiguration.java:93-135；W:config.ts:74,335；W:publisher.ts:125-143,289-359；Z:account_event_dlq.go:17-119 | rg -n -e 'DeadLetter' -e 'topicDlq' -e 'JSONLAccountEventDLQ' 指定文件 |
| E-S1-06 | file | auto-create关闭；checker只核partition/group；Runner只6对 | W:docker-compose.yml:12-29；W:publisher.ts:77-97；Z:kafka_adapter.go:140-176；A:kafka-check.mjs:14-112；A:test1-quick.py:60-67 | rg -n -e 'AUTO_CREATE' -e 'allowAuto' -e 'EXPECTED' -e 'KAFKA_PAIRS' 指定文件 |
| E-S1-07 | file | 三仓producer/forwarder使用账号key，顺序仅限topic/partition | A:ProtocolCommandPublisher.java:246-255；W:publisher.ts:221-249；Z:client.go:59-84；Z:command_forwarder.go:217-239 | rg -n -e 'ProducerRecord' -e 'key: envelope.accountId' -e 'Key:' 指定文件 |
| E-S1-08 | file | replay安全依赖原ID与Redis state/window | A:ProtocolCommandOutboxServiceImpl.java:200-210；A:HyperlinkUnknownResultRecoveryService.java:13-63；W:message-send-state.ts:100-194；Z:message_state.go:121-205 | rg -n -e 'replay' -e 'ANDROID_SAFE_REPLAY_WINDOW' -e 'Claim' 指定文件 |
| E-S1-09 | file | group.metadata_updated在Android builder存在但topicFor未覆盖 | Z:event.go:35-39,439-486；Z:client.go:89-108；Z:normal_group_creation_sender.go:119-159；W:subjects.ts:116-129 | rg -n -e 'group.metadata_updated' -e 'topicFor' 指定文件 |
| E-S1-10 | file | 当前指标/告警/Runner缺口 | A:test1-quick.py:60-67；A:kafka.mjs:34-54,205-273；A:redis.mjs:8-25,212-245；W:metrics.ts:169-190,242-247,277-319,439-442；W:prometheus-rules.yaml:18-30,76-103 | rg -n -e 'kafkaDlq' -e 'process_resident' -e 'WorkerLost' -e 'XPENDING' -e 'XLEN' 指定文件 |

说明：A = /Users/daishuaishuai/IdeaProjects/armada/armada-api 或 armada/armada-deploy；W = /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src 或 deploy；Z = /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan。

### 20.2 Findings

| Finding | 结论标签 | severity | category | status | evidence_ids | impact | confidence | remediation |
|---|---|---|---|---|---|---|---|---|
| F-S1-01 无canonical contract | **Observed** | high | design | validated | E-S1-01,E-S1-06 | 无法确定性provision、验收与drain | high | Phase 0/1 manifest + generator |
| F-S1-02 单边topic与部署漏项 | **Observed** | critical | design | validated | E-S1-02,E-S1-03,E-S1-04 | 代码存在但链路可能无consumer或未观测 | high | manifest exception + owner决策 |
| F-S1-03 DLT/replay语义分裂 | **Observed** | critical | design | validated | E-S1-05,E-S1-08 | 失败资产可能无owner、误replay或永久积压 | high | 分层DLT合同 + default-deny replay |
| F-S1-04 合并条件不成立 | **Inferred** | high | design | validated | E-S1-02,E-S1-04,E-S1-07 | 立即合并会改变SLA/order/consumer模型 | high | 阶段一全部独立 |
| F-S1-05 SLO不可观测 | **Observed** | high | design | validated | E-S1-10 | subset PASS可能掩盖真实积压 | high | manifest驱动全量metrics/Runner |
| F-S1-06 跨producer schema路由不一致 | **Observed** | high | design | validated | E-S1-09 | 同名事件可能只落本地DLQ | high | canonical schema + routing contract test |
| F-S1-07 replay仅条件性安全 | **Inferred** | high | design | candidate | E-S1-08 | state缺失/新ID可能重复外部副作用 | medium | 人工门禁 + fake sender验证 |

### 20.3 Paths

| Path | 结论标签 | path_type | start | steps | goal / residual risk |
|---|---|---|---|---|---|
| P-S1-01 配置漂移路径 | **Inferred** | callflow | 手写topic变量 | manifest缺失 → Compose/Runner漏项 → producer写入错误/无人消费topic → outbox或producer ACK仍不能证明业务完成 | 由F-S1-01/F-S1-02阻断；环境事实仍Unknown |
| P-S1-02 失败恢复路径 | **Inferred** | callflow | source处理失败 | retry口径分裂 → .DLT/file/PEL任一出口 → 无owner/oldest/replay → 永久积压或错误终结 | 由F-S1-03阻断；现有语义Phase 1不改 |
| P-S1-03 假drain路径 | **Inferred** | callflow | quick Runner 6对PASS | 未采Android/DLT/Stream/outbox → 缺失被忽略 → 发布“已排空”结论 | 由F-S1-05阻断；必须把未采集写UNKNOWN/FAIL |
| P-S1-04 重复副作用路径 | **Inferred** | callflow | 人工replay | Redis state缺失或新commandId → 首次claim → 再次物理send | 由F-S1-07门禁；真实重复率Unknown |

## 21. 文件与行号证据索引

- **Observed（输入索引，不替代源码）**：/private/tmp/armada-audit-2026-08-30/K1-backend-kafka.md:15-25,68-108,112-176,233-290,306-333。
- **Observed（输入索引，不替代源码）**：/private/tmp/armada-audit-2026-08-30/K2-web-kafka-stream.md:8-26,62-108,132-176,178-250,288-311,340-380。
- **Observed（输入索引，不替代源码）**：/private/tmp/armada-audit-2026-08-30/K3-android-kafka.md:18-32,56-125,147-174,196-257,291-320。
- **Observed（输入索引，不替代源码）**：/private/tmp/armada-audit-2026-08-30/K4-kafka-ops-runner.md:17-27,63-150,152-236,297-399,401-456。
- **Observed**：armada/armada-api/src/main/resources/application.yml:176-233。
- **Observed**：armada/armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java:200-210,840-880,938-999,1245-1278,2015-2037。
- **Observed**：armada/armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java:246-275。
- **Observed**：armada/armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolKafkaConfiguration.java:93-135。
- **Observed**：armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/contact/ProtocolAccountContactEventConsumer.java:22-85。
- **Observed**：armada/armada-api/src/main/java/com/armada/hyperlink/task/service/HyperlinkUnknownResultRecoveryService.java:13-63。
- **Observed**：armada/armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml:5-15。
- **Observed**：armada/armada-deploy/docker-compose.rds.yml:36-63；armada/armada-deploy/prod/app/docker-compose.yml:19-46。
- **Observed**：armada/armada-deploy/envs/perf2.conf:39-47。
- **Observed**：armada/armada-deploy/lib/kafka-check.mjs:14-112。
- **Observed**：armada/armada-deploy/staging-accept/wrappers/test1-quick.py:60-67。
- **Observed**：armada/armada-deploy/staging-accept/scripts/observability/kafka.mjs:34-54,205-273。
- **Observed**：armada/armada-deploy/staging-accept/scripts/observability/redis.mjs:8-25,212-245。
- **Observed**：armada-protocol/protocol-layer/src/config.ts:59-104,320-338。
- **Observed**：armada-protocol/protocol-layer/src/server.ts:383-415。
- **Observed**：armada-protocol/protocol-layer/src/commands/master-consumer.ts:102-109,157-219。
- **Observed**：armada-protocol/protocol-layer/src/commands/worker-inbox.ts:20-56。
- **Observed**：armada-protocol/protocol-layer/src/commands/worker-stream-consumer.ts:53-170,191-218,321-328,330-350,383-416。
- **Observed**：armada-protocol/protocol-layer/src/events/subjects.ts:7-50,107-130。
- **Observed**：armada-protocol/protocol-layer/src/events/publisher.ts:77-97,125-164,221-249,289-359。
- **Observed**：armada-protocol/protocol-layer/src/commands/message-send-state.ts:92-194。
- **Observed**：armada-protocol/protocol-layer/src/observability/metrics.ts:169-190,242-247,277-319,439-442。
- **Observed**：armada-protocol/protocol-layer/deploy/docker-compose.yml:12-29。
- **Observed**：armada-protocol/protocol-layer/deploy/k8s/prometheus-rules.yaml:18-30,76-103。
- **Observed**：armada-protocol/protocol-layer/deploy/prometheus.yml:1-11。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/coordinator/route_key.go:14-49。
- **Observed**：whatsapp-server-feature-android-zhuan/cmd/coordinator/main.go:49-109。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/coordinator/topics.go:15-74。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/coordinator/kafka_adapter.go:122-176。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/coordinator/command_forwarder.go:101-127,181-239,257-283。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/armada/client.go:59-108,125-134。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/armada/event.go:35-39,439-486。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/armada/normal_group_creation_sender.go:119-159。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/armada/lifecycle_inbox.go:17-48,70-133。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/armada/message_ack_inbox.go:22-74。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/armada/message_state.go:121-205,247-301。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/armada/account_event_dlq.go:17-119。

## 22. 最终判定

- **Observed**：当前代码已经存在多级Kafka/Redis/outbox路径，但topic、group、failure sink、replay与SLO没有共同治理源；至少五个静态合同冲突不能由“类和配置存在”自动闭环。
- **Inferred**：第一阶段最佳方案是canonical manifest + shadow generator/checker + 全量inventory/SLO，不改变当前消息语义，也不合并任何topic。
- **Inferred**：manifest必须把Unknown当作阻断状态，而不是填默认值；provisioner与checker分权，replay默认deny。
- **Unknown**：任何环境是否健康、topic是否齐全、SLO是否达标、replay是否单次触达；必须在后续授权范围内验证。
