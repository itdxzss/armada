# 变更记录：WhatsApp 群封禁状态持久化

- 日期 / 分支 / worktree: 2026-08-07 / 1.0.2-snapshot / armada 主 worktree
- 需求来源: 用户确认 Web 与 Android 协议收到 `suspended/terminated` 后都应更新 Armada 数据库
- 状态: 实现完成，未部署

## 目标（一句话）

把 Web/Android 明确收到的群暂停或终止信号统一持久化为群封禁，并阻止可见群快照误清封禁。

## 缺口拆解 / 任务清单

- [x] 核对第一套测试环境协议日志和数据库现状
- [x] 确认复用 `group.health_reported` 契约
- [x] Web 发布 `suspended/terminated` 健康事件
- [x] Android 发布 WGP2/HistorySync 群健康事件
- [x] 后端支持按租户和群 JID 定位健康行
- [x] 账号群同步保留既有封禁事实
- [x] 三端定向测试通过

## 关键设计决策

- 复用 `group.health_reported`，不新增表、topic 或并行事件契约。
- 只依据协议明确的 `suspended/terminated`，不把普通 403 推断为封禁。
- 封禁是粘性事实；群仍可见只更新人数和观测时间，明确健康事件才可恢复。
- 实时事件允许缺少 `groupLinkId`，后端在租户上下文内按 `groupJid` 定位。

## 验证（evidence-before-done）

- Web：`npm run lint` 通过；`npm test -- --runInBand` 通过，61 个 suite、558 个 test 全部成功。
- Android：`go test -count=1 ./internal/armada ./internal/service/node/processor` 通过；`git diff --check` 通过。
- Android 全量：`go test ./...` 中本次涉及包全部通过；仅既有 `pkg/noise` 套件 8 项失败，包含缺失
  `vectors.txt` 和既有 Noise 向量不一致，本次未修改该包。
- 后端：Java 17 下
  `mvn -q -Dtest=ProtocolGroupEventConsumerTest,GroupLinkHealthReportServiceImplTest,MysqlModeMapperInMemoryTest test`
  通过，共 45 项；`mvn -q -DskipTests compile`、MyBatis XML 校验和 `git diff --check` 通过。
- 后端全量测试未作为本次完成门槛：需要本机 MySQL 的既有 schema 测试无法在当前环境独立完成。
- 已检查三个仓库状态；未覆盖或回退其他并行改动。

## 部署

- commit / 环境 / 部署后验证结果: 未部署；目标为第一套测试环境，部署前另行确认。

## 遗留 / 跟进

- Android 仓库既有 `pkg/noise` 全量测试失败需单独处理，与本次群封禁链路无关。
