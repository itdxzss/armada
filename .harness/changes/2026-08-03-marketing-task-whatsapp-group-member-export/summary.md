# 变更记录：营销任务 WhatsApp 群成员导出优化

- 日期 / 分支 / worktree: 2026-08-03 / `codex/simple-whatsapp-group-member-export` / 主工作树
- 需求来源: `docs/business/marketing-task-whatsapp-group-member-export-design.md`
- 状态: 群成员缓存增强开发完成，待部署验证

## 目标（一句话）

营销任务全量导出和按国家导出统一使用任务实际涉及群的 WhatsApp 全量成员；首次查询成功后保存完整缓存，并由 Android 协议成员变更事件持续更新。

## 缺口拆解 / 任务清单

- [x] 按任务群 JID 查询全部目标群，不以 Armada 受控账号作为成员范围。
- [x] 每群选择最多两个实际发送 Android 账号，主账号失败时仅回退一次。
- [x] Android 群成员接口返回实时 `Announce`，Armada 映射为发言权限。
- [x] HistorySync `PastParticipants` 与 WGP2 `remove/leave` 事件复用群同步 Topic 上报。
- [x] MySQL 保存完整群成员缓存，并保留最近一次明确进群、退群事实。
- [x] 全量和按国家模式共享同一个 WhatsApp 成员数据集。
- [x] 增加协议事件、提供器、消费解析、SQL 查询和作业编排测试。
- [x] 首次实时群成员查询成功后，按租户和群 JID 保存完整成员缓存。
- [x] 后续导出优先复用完整缓存，不再要求观察账号仍在线或仍在群。
- [x] Android add/leave/remove 事件同步更新缓存成员的在群状态。
- [x] 两种导出均从 add 事实填充进群时间和累计成功进群号码数量。

## 关键设计决策

- 首次导出无缓存时实时查询；查询成功后以完整快照初始化缓存，后续导出读取缓存。
- 缓存只保存群元数据和成员最新状态；群人数现场按 `is_in_group` 计算，不保存重复统计值。
- 完整快照和增量事件按事实时间、来源优先级、事件 ID 顺序幂等更新；同一时刻事件优先于快照。
- 完整快照中消失的旧成员标记为不在群，但不伪造退群时间和退出方式；只有明确的 leave/remove 事实填充这两个字段。
- 退群成员无法从当前成员接口恢复，只保存 WhatsApp 主动下发的 `PastParticipants` 和实时退群通知；未被 WhatsApp 下发过的更早历史无法凭空补齐。
- 观察账号只用于通过协议鉴权查询对应群，不参与导出成员过滤。
- 每账号串行、最多四个账号并行；单群最多主账号加一个回退，防止循环重试。
- 每批最多四个群，当前成员和该批退群事实合并后直接推送到 SXSSF 两个工作表，不在 JVM 中累计完整成员结果集。
- `phone` 为空时只允许从 PN/device-PN JID 派生号码；LID 没有可信映射时保持未知，不参与国家归属。
- 已有完整缓存时允许观察账号离线或退群；仅在无缓存且所有候选观察账号均不可用时整单失败。
- 退群事实采用 MySQL 8.4 row alias 原子 upsert；H2 负责租户查询，MySQL 专有更新语义由 Testcontainers 真库测试覆盖。

## 验证（evidence-before-done）

- 最终相关回归套件共 15 个测试类、58 个测试：0 失败、0 错误、4 个跳过，Maven 退出码为 0；覆盖导出 Controller/Service/Provider/Writer、H2 Mapper、群成员缓存 Service，以及 Join/Departure 事件消费。
- MySQL 8.4 Testcontainers 测试覆盖 row alias upsert、来源优先级、同毫秒快照版本仲裁、手机号保留和重复重放；本机没有 Docker，因此真库用例自动跳过，须在具备 Docker 的 CI/机器补跑。
- 全量 `mvn test` 运行 5 分钟后被本地超时终止，终止前未输出测试失败；不将其声明为完整通过。
- Java 与数据库专项评审均未发现阻断问题；确认群 JID 规范化、事件事实与缓存原子事务、缓存头锁仲裁、租户隔离和索引设计成立。
- Android 相关包：`api/service`、`internal/armada`、`internal/service/app`、`internal/service/node/processor` 全部通过。
- Android 全仓 `go vet ./...`、`go build ./...` 通过；`go test ./...` 被仓库既有部署脚本 CRLF、promise 异步日志和 noise fixture/向量测试失败阻断，与本次改动包无关。

## 部署

- 未提交、未部署；按用户要求完成验证后仅放入当前仓库 Git 暂存区。

## 遗留 / 跟进

- 部署到目标环境后，用真实 Android 在线账号验证 WhatsApp 是否为目标群返回所需时间范围内的 `PastParticipants`。
- 缓存快照的新旧顺序目前以导出查询发起时刻为准；如果协议后续提供可信采集时间，应改用协议采集时间。
- 极端的同群快照与批量成员事件并发可能触发 InnoDB 死锁，但事务会完整回滚；部署后应观察 Kafka 重试和导出作业重试日志。
- 已核对相关 `1.0.2-snapshot*` 分支：`V090` 已被旧迁移占用，本次新增缓存使用 `V093`，部署前仍需由 Flyway 校验目标库历史。
