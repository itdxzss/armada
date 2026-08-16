# WhatsApp 群变更事件直投影 Implementation Plan

**Goal:** Web/Android 收到群成员、群名、描述和群设置变化后，直接把报文中的增量事实可靠写入 Kafka，
由 Armada 幂等投影到成员、账号群关系和群资料数据库；正常事件链不再触发完整 metadata 查询。

**Architecture:** 复用 `protocol.group.events.v1` 和现有 EventEnvelope。成员变化沿用
`group.participant_changed`，群资料使用已预留的 `group.metadata_updated`。Armada consumer 完成契约校验后，
分别进入成员 reducer 与逐字段 metadata reducer；首次建档、人工刷新和异常修复的完整快照也复用同一 reducer，
通过逐字段版本解决跨账号、跨协议和快照/增量乱序。

**Design:** `docs/superpowers/specs/2026-08-16-group-event-direct-projection-design.md`

**Related plan:** `docs/superpowers/plans/2026-08-16-group-snapshot-kafka-sync-implementation.md`

**Repositories:**

- Backend: `/Users/daishuaishuai/IdeaProjects/armada`
- Web protocol: `/Users/daishuaishuai/IdeaProjects/armada-protocol`
- Android protocol: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan`

**Constraints:**

- 不新增 Kafka topic，不修改现有页面 API；
- 正常 `add/remove/promote/demote/modify/groups.update/WGP2 metadata` 事件产生零次 metadata 查询；
- ACK 只表示协议收包，不能推进 Armada 业务状态或成功指标；
- 字段必须保留“未出现、明确 false/0、明确清空”三种语义；
- PN/LID 只能来自明确报文或可信映射，不猜手机号；
- `inviteCode` 继续只走 `group.invite_link_changed`，不进入 metadata patch；
- 头像未取得稳定事件和内容口径前不纳入本期；
- Flyway 必须使用实施时未占用的下一版本，不能覆盖 V120/V121/V122 和群快照计划的在途 migration；
- 每个任务不超过 4 小时，保持三个仓库的用户在途修改。

---

## 实施依赖与关键路径

```text
Task 1 契约、fixture、schema 门禁
  -> Task 2 逐字段版本迁移
  -> Task 3 Armada consumer/DTO
       -> Task 4 最小群建档能力
       -> Task 5 成员 reducer
       -> Task 6 metadata patch reducer
            -> Task 7 完整快照汇入同一 reducer
  -> Task 8 Web 成员直投影
  -> Task 9 Web 群资料直投影
  -> Task 10 Android WGP2 解析
  -> Task 11 Android 可靠发布与刷新收口
  -> Task 12 修复旁路、开关和监控
  -> Task 13 三仓回归与并发验证
  -> Task 14 test1 验收、灰度与回滚
```

Task 5/6 可在 Task 3 契约固定后并行；Task 8/9 与 Task 10/11 可在 Armada consumer 已具备安全消费能力后
并行。发布顺序固定为 Armada -> Web -> Android，最后关闭旧 metadata fallback。

---

## Task 1：冻结统一契约、真实 fixture 和数据库门禁（≤4h）

**Files:**

- Create: 三仓就近的 `group.participant_changed` / `group.metadata_updated` fixtures
- Verify: `armada-api/src/main/resources/db/migration/V120__group_data_model_foundation.sql`
- Verify: Web Baileys event typings 与 Android WGP2 脱敏报文
- Modify: 设计文档（仅在实证与当前映射不一致时）

- [ ] 固定 participant action：`add/remove/promote/demote/modify`，成员最多 500 个。
- [ ] 固定 metadata fieldMask 白名单：`subject/description/announceOnly/adminOnlyEditInfo/memberAddMode/`
  `joinApprovalMode/ephemeralDurationSeconds`。
- [ ] fixture 覆盖字段缺失、false、0、description=null、多字段 patch、非法类型、未知 action/tag。
- [ ] Android 使用真实脱敏 WGP2 样本确认 subject、description、正反权限节点、ephemeral/not_ephemeral 路径。
- [ ] 确认 V120 当前没有 `field_version_keys`，确定新增 JSON/文本版本水位的 MySQL 8.4 与 H2 测试口径。
- [ ] 给本计划和群快照计划分配不冲突的 Flyway 版本；禁止两边同时使用候选 V123。
- [ ] 确认 test1 的 Web/Android group event topic 都实际指向 Armada 订阅的现有 topic。

**Exit gate:** 三仓 fixture 字段等价；Android 节点映射有真实证据；迁移版本无冲突。

---

## Task 2：补齐群资料逐字段版本水位（≤4h）

**Files:**

- Create: `armada-api/src/main/resources/db/migration/V123__group_profile_field_versions.sql`
  （仅为候选名，实施时按 Task 1 分配版本）
- Modify: `GroupCurrentLocalMapper.java/xml`
- Modify: `GroupCurrentLocalProfileWrite.java` 或新增独立 patch write record
- Create: migration、H2 Mapper、MySQL 8.4 并发/JSON 测试

- [ ] 先写失败测试：不同字段可独立更新，同字段只接受更新版本，false/0/null 不丢失。
- [ ] 为 `wa_group_profile` 增加 `field_version_keys`，每个字段保存 occurredAt、事实精度、backend、
  observerAccountId、sourceEventId 的确定性版本键。
- [ ] Mapper 更新必须同时包含 tenantId + groupId，并在 SQL 内比较当前字段版本，禁止 Java 先查后写竞争。
- [ ] 明确空 profile 的创建时间和默认 NULL；不得把未观察布尔值写成 false。
- [ ] 迁移保持向后兼容，不回填伪版本，不让旧 `metadata_observed_at` 反向覆盖字段版本。

```bash
cd armada-api
mvn -Dtest='*GroupProfileFieldVersion*Test,*GroupCurrentLocal*Test' test
```

**Exit gate:** 两线程乱序写同字段稳定收敛，不同字段互不阻塞，租户隔离由真实 Mapper XML 验证。

---

## Task 3：Armada 接入成员增量与 metadata patch 契约（≤4h）

**Files:**

- Modify: `ProtocolGroupEventConsumer.java`
- Modify: `ProtocolGroupParticipantChangedEvent.java`
- Create: `ProtocolGroupMetadataUpdatedEvent.java`
- Create: `ProtocolGroupMetadataUpdatedSink.java`
- Modify: `ProtocolGroupEventConsumerTest.java`

- [ ] 先写 consumer 测试：Web/Android 合法事件、add/remove/modify、fieldMask、多字段 patch、null/false/0。
- [ ] `SUPPORTED_PARTICIPANT_ACTIONS` 增加 `modify`，移除 add/remove 的忽略分支，五种 action 全部进入 sink。
- [ ] 增加 `group.metadata_updated` 分支，严格校验 envelope/data 账号一致、tenant、backend、groupJid、时间。
- [ ] mask 必须非空、去重且只含白名单；mask 内字段类型非法时整条拒绝，mask 外同名字段忽略。
- [ ] `participants` 每项至少一个合法 PN/LID/id，不允许把 LID 规范化成 phone。
- [ ] consumer 只做契约和关联校验，不调用协议、不直接写 Mapper。

```bash
cd armada-api
mvn -Dtest=ProtocolGroupEventConsumerTest test
```

**Exit gate:** Armada 可先行部署并安全接受 Web/Android 新事件，未知事件仍按现有兼容策略处理。

---

## Task 4：提供事件目标群的最小建档能力（≤4h）

**Files:**

- Modify/Create: `GroupCurrentLocalPersistence.java`
- Modify: `GroupCurrentLocalPersistence.java` 对应实现及 Mapper/XML
- Reuse: `GroupLinkRegistryService` 与新群模型 `wa_group/wa_group_profile`
- Create: 最小建档 service/Mapper H2 tests

- [ ] 先测：群不存在、已存在、并发重复、跨租户同 JID、软删除记录和非法 JID。
- [ ] 按 tenantId + 规范化 groupJid 幂等取得/创建 `wa_group`，来源标记为账号同步/事件观察。
- [ ] 同事务确保空 `wa_group_profile` 存在，不创建邀请码、不伪造 subject/description/设置。
- [ ] 兼容旧 `group_link/group_link_preview` 时只做必要身份桥接，旧表不能成为新模型创建前置条件。

```bash
cd armada-api
mvn -Dtest='*GroupIdentity*Test,*GroupCurrentLocal*Test' test
```

**Exit gate:** 新群的第一条增量事件不会因“找不到 group_link”静默丢弃。

---

## Task 5：收敛统一成员事件 reducer（≤4h）

**Files:**

- Create: `GroupParticipantEventReducer.java` 及实现/测试
- Modify: `ProtocolGroupParticipantChangedSinkAdapter.java`
- Modify: `GroupParticipantObservation.java`
- Modify: `GroupParticipantObservationServiceImpl.java`
- Reuse: `WhatsappGroupMemberCacheService`、`AccountGroupMembershipStatusService`

- [ ] 先写五种 action、重复/迟到、PN/LID 合并、self/其他受控账号/外部成员测试。
- [ ] add -> presence=IN_GROUP；remove -> OUT_OF_GROUP；promote/demote 只改 role；modify 只合并身份。
- [ ] remove 根据 operator 与唯一目标可靠身份判定 LEFT/REMOVED/UNKNOWN，不影响 presence 退出结论。
- [ ] 同事务更新 `wa_group_participant`、旧成员缓存、受控账号群关系和兼容事实表。
- [ ] 只有事件前状态已知时才对 member_count 做 +1/-1；未知时保留人数并标记待校准。
- [ ] 事件源使用 `eventId + participant stable JID` 幂等；旧事件 ACK 消费但不回滚新事实。

```bash
cd armada-api
mvn -Dtest='ProtocolGroupParticipantChangedSinkAdapterTest,GroupParticipantEventReducerTest,GroupParticipantObservationServiceImplTest,WhatsappGroupMemberCacheServiceImplTest' test
```

**Exit gate:** Web 统一事件和 Android 兼容 joined/departed/role 事件均汇入同一 reducer。

---

## Task 6：实现群资料 fieldMask reducer 与兼容投影（≤4h）

**Files:**

- Create: `GroupMetadataPatch.java`
- Create: `GroupMetadataPatchService.java` 及实现/测试
- Create: `ProtocolGroupMetadataUpdatedSinkAdapter.java`
- Modify: `GroupCurrentLocalMapper.java/xml`
- Modify: 当前页面仍读取的 `group_link_preview/group_link` 兼容写入口

- [ ] 先写单字段、多字段、false、0、description=null、未进 mask 不覆盖和非法 patch 测试。
- [ ] 把 source 精度、occurredAt、backend、observerAccountId、eventId 组成字段版本键。
- [ ] 按字段比较并原子更新 `wa_group_profile`；不同字段的迟到顺序互不影响。
- [ ] subject/description/权限字段按当前页面读取路径做单向兼容双写；本地备注/displayName 不与 WhatsApp 字段混写。
- [ ] metadata patch 不更新 inviteCode、头像、完整成员、member_snapshot_at。
- [ ] 日志只记录字段名和关联 ID，不记录完整描述、成员或原始 payload。

```bash
cd armada-api
mvn -Dtest='GroupMetadataPatchServiceImplTest,ProtocolGroupMetadataUpdatedSinkAdapterTest,*GroupCurrentLocal*Test' test
```

**Exit gate:** 控端现有查询 API 可读到最新群资料，未出现字段零误覆盖。

---

## Task 7：完整 metadata 快照汇入同一个字段 reducer（≤4h）

**Files:**

- Modify: `GroupMetadataSnapshotServiceImpl.java`
- Modify: `GroupMetadataSnapshotPersistenceImpl.java`
- Coordinate: 群快照计划的 `GroupMetadataSnapshotReducer`
- Modify: snapshot/patch 乱序测试

- [ ] 把完整快照转换为包含实际已观察字段的 `GroupMetadataPatch`，不绕过字段版本比较。
- [ ] 完整快照保持较低事实精度；同一 occurredAt 下精确事件优先。
- [ ] 较新的完整快照可以修正旧增量，迟到快照不能覆盖新事件。
- [ ] 完整成员数组继续走快照 reducer，不伪装成 participant_changed 增量。
- [ ] 本任务与群快照 Kafka 计划复用一个 reducer 实现，禁止两套 profile 更新规则。

```bash
cd armada-api
mvn -Dtest='GroupMetadataSnapshotServiceImplTest,GroupMetadataPatchServiceImplTest,*Snapshot*Patch*Order*Test' test
```

**Exit gate:** 增量主链和低频快照只在后端 reducer 汇合，不互相触发协议查询。

---

## Task 8：Web 成员事件直投影并移除 add/remove metadata 请求（≤4h）

**Files:**

- Modify: `protocol-layer/src/worker/event-bridge.ts`
- Modify: `protocol-layer/src/worker/account-manager.ts`
- Modify: `protocol-layer/src/worker/event-bridge.test.ts`
- Modify/Create: account-manager group signal tests

- [ ] 先测 add/remove/promote/demote/modify 的 groupJid、participants、author/operator、occurredAt 和业务引用。
- [ ] `group.participant_changed` 保留属性存在性和 PN/LID；每项至少传一个明确身份。
- [ ] 删除 `groupParticipantsSignalHandler` 中 add/remove 的 `publishGroupMetadataSyncRequested`。
- [ ] self add/remove 可以维护低成本 participating 群列表基线，但不得查询完整 metadata/participants。
- [ ] socket generation 过期、terminating、缺 businessRef/非法 groupJid 时不发布。
- [ ] 发布失败必须走现有 Kafka 重试/DLQ，不能以 Baileys 事件回调已返回视为成功。

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand src/worker/event-bridge.test.ts
npm run lint
npm run build
```

**Exit gate:** 普通成员变化只发布增量事实，metadata 同步请求数量为 0。

---

## Task 9：Web groups.update 生成字段级 metadata patch（≤4h）

**Files:**

- Modify: `protocol-layer/src/worker/account-manager.ts`
- Modify: `protocol-layer/src/events/subjects.ts`
- Create: `protocol-layer/src/worker/group-metadata-patch.ts`
- Create/Modify: mapping、publisher、account-manager tests

- [ ] 用属性存在性映射 subject/desc/announce/restrict/memberAddMode/joinApprovalMode，禁止 truthy 判断。
- [ ] 描述明确清空产生 `fieldMask=[description], description=null`；false/0 必须保留。
- [ ] `EPHEMERAL_SETTING` 产生 `ephemeralDurationSeconds`，关闭明确为 0；非法值跳过并计异常。
- [ ] 一个 groups.update item 最多一条 metadata patch；无支持字段时不发布空 patch、不查 metadata。
- [ ] inviteCode 同时出现时继续单独发布 `group.invite_link_changed`，不写入 metadata patch。
- [ ] 将 `group.metadata_updated` 从 best-effort 调整为 critical，仍路由现有 group topic。
- [ ] 删除 `groupsUpdateHandler` 的 `METADATA_CHANGED` 请求；保留紧急 fallback 开关，默认 false。

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand src/worker/event-bridge.test.ts src/worker/group-metadata-patch.test.ts
npm run lint
npm run build
```

**Exit gate:** Web 群资料事件可靠发布完整 patch，正常路径不调用 `groupMetadata`。

---

## Task 10：Android WGP2 解析群资料 patch（≤4h）

**Files:**

- Modify: `internal/service/node/processor/group_notification.go`
- Modify: `internal/service/events/type.go`
- Modify: `internal/service/node/processor/group_notification_test.go`
- Create: 脱敏 fixture files（按现有测试资源目录）

- [ ] 先用 Task 1 fixture 写 subject、description、announcement/not_announcement、locked/unlocked 测试。
- [ ] 再测 member_add_mode、membership_approval_mode、ephemeral/not_ephemeral 的正反节点。
- [ ] 新增内部 `GroupMetadataUpdatedEvent`，只携带报文已有字段和 fieldMask。
- [ ] description 缺 body 明确映射 null；not_ephemeral 映射 0；未知枚举不猜 false。
- [ ] 解析 nil、非法群 JID、缺账号、非法 expiration 和未知 tag 分别计低基数分类，不记录原始 node。
- [ ] 保持 create/invite/成员/终止现有解析兼容，picture 继续不进入本期 patch。

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w internal/service/node/processor/group_notification.go internal/service/events/type.go
go test ./internal/service/node/processor ./internal/service/events
```

**Exit gate:** 当前会返回 nil 的已确认群资料节点均可生成结构化内部事件，正反设置成对通过。

---

## Task 11：Android 可靠发布 patch 并收口快照刷新触发（≤4h）

**Files:**

- Create: `internal/armada/group_metadata_event.go`
- Create: `internal/armada/group_metadata_publisher.go`
- Modify: `internal/armada/group_snapshot_coordinator.go`
- Modify: `internal/armada/client.go`、`internal/armada/start.go`
- Modify/Create: coordinator、publisher、routing、DLQ tests

- [ ] 构造与 Web 等价的 `group.metadata_updated` EventEnvelope，key 固定 protocolAccountId。
- [ ] 复用现有 group topic、Kafka 三次重试和本地 DLQ；Kafka/DLQ 均失败必须显式失败并告警。
- [ ] `GroupSnapshotCoordinator.ObserveEvent` 对 metadata patch 直接发布，不进入 GetAllGroup/防抖刷新。
- [ ] 普通 add/remove/promote/demote 已有精确事件时不再触发完整群 metadata；self 关系基线另行低成本维护。
- [ ] node_received、parsed、delivery_confirmed 分开计数；notification ACK 不推进 delivery 指标。
- [ ] direct projection 关闭时允许旧刷新 fallback，正常默认关闭 fallback。

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w <本任务改动的.go文件>
go vet ./...
go build ./...
go test ./...
go test -race ./internal/armada ./internal/service/node/processor
```

**Exit gate:** Android patch 可可靠到达现有 group topic，ACK 与业务发布成功完全分离。

---

## Task 12：异常修复旁路、开关、指标和安全日志（≤4h）

**Files:**

- Modify/Create: Armada group event metrics/config
- Modify/Create: Web/Android direct projection 与 metadata fallback 配置
- Modify: 异常修复任务 enqueue 入口
- Modify/Create: 配置、指标、日志 tests

- [ ] Armada 对无法解释、状态矛盾、member_count 不可安全推导的事件仅异步 enqueue REPAIR，不在 consumer 查协议。
- [ ] repair 按 tenant+group 去重、限频和退避；普通字段校验失败不能无限放大查询。
- [ ] 增加 received/parsed/delivery/applied/stale/unknown/query_avoided/repair_enqueued/latency 指标。
- [ ] Web、Android 分别支持 direct projection 开关和 metadata fallback 开关，默认 direct=true、fallback=false。
- [ ] 指标标签保持低基数；日志不输出完整成员、描述正文、邀请码或原始 WGP2 payload。
- [ ] 添加“metadata QPS 未下降”“delivery_confirmed 与 applied 持续偏差”“Kafka+DLQ 双失败”告警。

**Exit gate:** 任何失败阶段可定位，且紧急 fallback 不需要回滚数据库结构。

---

## Task 13：三仓回归、事务和乱序验证（≤4h）

**Files:**

- Modify/Create: 三仓 integration/contract tests
- Verify: 三仓所有本任务 diff 与用户在途改动

- [ ] Armada H2 加载真实 Mapper XML，验证 tenant、事务、最小建档、重复、迟到、跨账号收敛。
- [ ] MySQL 8.4 验证 field_version_keys 更新、JSON/字符集和并发锁行为。
- [ ] Web/Android 共用 fixture 做结构等价比较，覆盖 false/0/null/字段缺失。
- [ ] 断言正常群变更不会发布 `account.group_metadata_sync_requested` 或触发 GetAllGroup/groupMetadata。
- [ ] 断言 Kafka 失败 -> 重试 -> DLQ，且 ACK 不会让失败用例误通过。
- [ ] 运行格式化、编译和相关全量测试；逐仓检查 diff，不覆盖已有脏文件。

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn test

cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runInBand
npm run lint
npm run build

cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
go vet ./...
go build ./...
go test ./...
go test -race ./internal/armada ./internal/service/node/processor
```

**Exit gate:** 三仓契约一致，后端字段收敛正确，正常群变更 metadata 查询为零。

---

## Task 14：test1 端到端验收、灰度和回滚演练（≤4h）

**Prerequisite:** 明确确认 test1 环境后才能执行，不直接部署生产。

- [ ] 先部署 Armada consumer/reducer/Flyway，确认不开 producer 时无异常。
- [ ] Web 小流量开启 direct projection，关闭 fallback；逐项触发 add/remove/promote/demote/modify。
- [ ] 触发群名、描述设置/清空、四类权限开关、限时消息开启/修改/关闭、邀请码重置。
- [ ] Android 按同样动作验收真实 WGP2 解析和 Kafka/DLQ；未知节点无静默丢失。
- [ ] 每条动作串联 received -> parsed -> delivery_confirmed -> armada_applied，并通过现有页面 API 读取最新值。
- [ ] 验证事件到数据库 P95 < 3 秒，正常事件 metadata HTTP/IQ 查询为 0，迟到事件零回滚。
- [ ] 演练先开 metadata fallback、再关 direct producer；确认 Armada consumer 可保持向后兼容。
- [ ] 灰度稳定后再清理仅服务旧事件后查询的代码分支，完整快照能力继续保留。

**Exit gate:** test1 证据写入 change 记录；无持续重试/DLQ 同类积压；回滚演练通过后方可申请生产发布。

---

## 完成定义

- Web/Android 对已确认群变更都能完整解析并可靠上报，不以 ACK 代替成功；
- Armada 成员、账号群关系、群资料和页面兼容投影均可读取最新事实；
- false、0、null 清空和字段缺失语义全部有自动化测试；
- 重复、迟到、跨协议、跨观察账号和完整快照乱序稳定收敛；
- 正常群变更不会查询 metadata，完整查询只保留给首次建档、人工刷新、异常修复和低频对账；
- 未新增 Kafka topic，现有页面 API 不变；
- test1 指标、数据库、页面、metadata QPS 和回滚证据齐全。
