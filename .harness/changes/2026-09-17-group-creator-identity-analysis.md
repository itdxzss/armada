# 变更记录：历史群创建者身份解析分析

- 日期 / 分支 / worktree：2026-09-17 分析，2026-09-18 实现 / 1.0.3-snapshot / armada 与 Android 协议主工作区。
- 需求来源：先分析创建者解析；随后用户明确要求在主仓库完成代码，测试环境验证放后面。
- 状态：实现、本地验证及 test1/perf2 后端与 Android 协议部署完成；未提交、push 或主动数据回填。历史群补齐业务验收待做。

## 目标

解释 perf2 历史群创建者身份的解析缺口，给出不混淆原创建者和当前成员权限的修复方案。

## 缺口拆解

- [x] 追踪 Android IQ → GroupInfo → profile event → Java 兼容写。
- [x] 确认已有 LID 映射未被创建者路径使用。
- [x] 确认 superadmin 兜底缺同一人证据。
- [x] 确认 HTTP Creator/Owner 字段错配及 CreatorPN 未透传。
- [x] 审计未知值覆盖与按时间覆盖风险。
- [x] 产出 [分析设计](../../docs/business/2026-09-17-group-creator-identity-analysis.md)。
- [x] Android 共用创建者解析器、HTTP 字段透传和 Kafka 来源/原因。
- [x] 后端独立确认号码、未知保护、不同号码保护与本地回归。

## 关键设计建议

显式创建者 PN → 显式配对 PN → 相同创建者成员 → 已有双向可信 LID 映射；不得将未匹配的当前群主或管理员当原创建者。未知保留旧值，不同号码先报冲突。复用现有列和协议缓存，第一阶段不加库表、不做全通讯录/在线反查、不改变群角色或账号状态。

## 实现

- Android `internal/service/groupidentity/creator.go`：规范 PN/设备 JID；显式 creator_pn、精确匹配成员；仅最后读取已有 LID 映射并检查反向一致性。裸 Creator 必须有 addressing_mode；不按群 JID 或 superadmin 猜号。同次全量同步去重查缓存，缓存故障后停止该批后续查询，不进行在线反查。
- 全量、单群、建群结果和建群通知共用解析器；HTTP 同时返回 Creator、CreatorPN、CreatorPhone、CreatorPhoneSource/Reason；Kafka 增加来源/原因，后端日志读取这两个诊断字段。
- Java `GroupMetadataResult.creatorPhone` 与 raw ownerJid 分开；Android metadata 正确接 Creator，群列表接 creator_pn，Web metadata 同步接 ownerPn。
- HTTP 资料入库只用确认的 creatorPhone，不从任意 superadmin 回退。Creator 原始身份接通后可能触发现有“创建者即管理员”的推断，因此该 HTTP 快照角色归一只采用 participant 权限字段；Go 既有权限路径没有扩大修改。
- 未知手机号/国家不宣称观察到空值；SQL 在原子 upsert 中保留已确认的不同号码及其国家、观察时间，仅空号码填充/同号码更新。手机号最后赋值，避免 MySQL 顺序赋值影响冲突条件。
- 未新增表或列；不同号码 SQL 保留旧值，不自动修复历史错误号码；没有新增冲突队列。

## 验证

详见 [本地验证记录](../../docs/operations/evidence/perf2-group-creator-20260917/local-validation-20260918.md)。

- Java：17 个相关测试类，合计 218 tests / 0 failures / 0 errors / 0 skipped。包括真实 Mapper XML、生产租户插件、H2 MySQL 模式与 Spring 事务回滚。
- Go：gofmt、go vet ./...、go build ./... 通过。go test ./... 已执行；受影响包均通过，唯一失败包 pkg/noise（8 个向量/握手测试）。在未修改 HEAD 6019251 的隔离归档中复现相同 8 个失败，非本次修改引入。
- 首次 Go 全量测试被沙箱禁止本机监听阻断，经批准在沙箱外重跑本地测试后消除该限制；未连接 perf2。Java 通过 JVM 启动参数加载已安装 Byte Buddy agent 解决本机 Mockito attach 限制，未修改构建配置。
- H2 不是 InnoDB：真实 MySQL 行锁、并发与 perf2 业务刷新效果留待后续测试环境验证；本轮未执行真实数据库测试。

## 部署与回滚

用户后续明确授权将本地主仓库当前代码部署第一、二套环境。2026-09-18 已完成 test1/perf2 后端与 Android 协议发布，运行制品核对与两套标准深度检查均通过。部署包含主仓库未提交修改，使用冻结快照避免构建期间混入其他 agent 的新增写入；未提交或 push。详见 [部署证据](../../docs/operations/evidence/perf2-group-creator-20260917/deployment-20260918.md)。未主动刷新目标群或批量回填；二进制回滚与数据修复分开。

## 遗留

现场缺失群的原始 creator/creator_pn 与映射命中分布未取得，不能承诺 23 个缺失群全部补齐。已有不同来源旧值的归属需要直接证据，不能自动覆盖。
