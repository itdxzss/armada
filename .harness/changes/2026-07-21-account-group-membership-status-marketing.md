# 变更记录：账号群关系状态保留与营销跳过

- 日期 / 分支 / 工作区: 2026-07-21 / `1.0.1-snapshot` / 当前工作区
- 需求来源: 用户要求保留被踢/主动退出群状态，不发送但在创建任务和营销明细中展示；设计见 `docs/superpowers/specs/2026-07-21-account-group-membership-status-marketing-design.md`
- 状态: 三项目已部署 perf2，待真实群退出事件与营销任务端到端验收

## 目标（一句话）

把账号群关系从存在/软删改成显式当前状态，并让营销执行对退出群生成 SKIPPED 明细而不调用协议。

## 缺口拆解 / 任务清单

- [x] 完成现状代码、性能环境日志和 Android 群事件链路排查。
- [x] 与用户确认 IN_GROUP、UNCONFIRMED、KICKED_OUT、LEFT、NOT_IN_GROUP 状态口径。
- [x] 与用户确认所有状态均在创建任务时展示并允许勾选。
- [x] 与用户确认执行前读本地表，不可发送状态写 SKIPPED，跳过不计失败。
- [x] 完成跨 Android 协议、Armada 后端和 Vue 前端的设计文档。
- [x] 用户复核书面设计。
- [x] 编写 Android、Armada、Vue 三个分项目实施计划和总发布计划。
- [x] 按 TDD 完成 Android、Armada、Vue 业务实现和非数据库自动化验证。
- [x] 将 Android Zhuan、Armada 后端和 Vue 前端部署到第二套 perf2 环境并完成制品/健康冒烟。
- [ ] 确认测试环境后完成端到端联调。

## 关键设计决策

- 当前关系保留在 `account_group_membership`，状态代替退出时软删；全局 `group_link` 不变。
- UNCONFIRMED 允许发送；KICKED_OUT、LEFT、NOT_IN_GROUP 生成 SKIPPED 且不调用协议。
- 创建任务展示并允许选择所有状态，发送拦截只在运行时执行。
- 精确 remove/leave 事件独立发布，完整群快照负责未知原因退出和重新入群校准。
- Android 在观察通知时同步固定精确事件事实时间，异步发布不得重取时间。
- 快照缺失不覆盖已确认的被踢/主动退出原因；更新的快照重新出现才恢复在群。
- 不完整快照不更新缺失关系，查询失败不伪造空快照。
- 快照完整性字段缺失时按账号 `protocol_id` 兼容：Web/Baileys 沿用旧完整快照语义，Android
  按不完整处理；显式 false 或有跳过群始终按不完整处理。
- 营销明细以已解析目标群和 attempt 的并集保留群，分离当前关系状态和最后执行结果，SKIPPED
  单独计数且不计失败。
- Kafka 精确关系事件要求顶层路由账号与 data 协议账号严格一致；按 JID 重新发现软删除群时复活原群入口。
- 当前范围不包含 Baileys `armada-protocol`。

被否决方案：

- 继续只软删：不能区分退出原因，也无法在选择列表和明细保留当前状态。
- 软删加 exit_reason：重新进群后的当前行选择和乱序处理更复杂。
- 独立完整状态历史表：当前需求已有发送尝试历史，只需可靠当前关系，双写成本过高。

## 验证（evidence-before-done）

- 实施计划见 `docs/superpowers/plans/2026-07-22-account-group-membership-marketing-rollout.md`。
- Android：`go build ./...`、`internal/armada` 全包测试和定向 race 通过；全仓 `go test ./...` / `go vet ./...`
  仅命中实施前已存在的其他包问题，已保留原始结果。
- Armada：117 个聚焦普通单测通过；`mvn -DskipTests test-compile` 通过，260 个测试源码可编译；
  三份改动 Mapper XML 和 API 文档检查通过。
- Vue：25 个本次相关 Node 用例、类型检查、定向 ESLint/Prettier/Stylelint 和生产构建通过。
- 专家复审：首轮 7 个 Important 均已修复，复审无 Critical/Important；真库仍是发布门禁缺口。
- perf2：Flyway 已把 `armada_perf` 从 059 升至 v060；运行 JAR 与上传 JAR SHA-256 一致，
  新增消费事件、发送策略和 V060 均已进入运行包。
- perf2：前端 `group-membership-status` 实际 HTTP 资源与本地构建产物 SHA-256 一致；
  Armada API 返回预期未登录响应，Armada 到 Zhuan 健康页返回 HTTP 200。
- perf2：Zhuan 新镜像包含 `account.group_membership_changed`，callback/主服务均 `healthy`、重启 0。
- 真库 DbTest、数据模型生成、测试 Kafka 事件抽样与真实群退出/营销任务端到端联调尚未执行。

## 部署

- 2026-07-22 部署当前 `1.0.1-snapshot` 脏工作区到 perf2，未提交代码。
- Armada 后端镜像：`7020170f490e`；Nginx 镜像：`ca4cdf4b6324`；Compose 项目沿用现有
  `armada-deploy`；两个容器运行、重启 0。
- Zhuan 镜像：`a547beec1f71`；callback 和主服务运行且健康、重启 0。
- 已为部署前镜像保留 `rollback-before-membership-20260722` 回滚标签。

## 遗留 / 跟进

- 三项目改动均保留在用户当前工作区，待本地人工复核。
- [ ] 部署脚本：统一 perf2 Armada Compose 项目名；环境档案当前为 `armada-perf`，存量容器属于
  `armada-deploy`，本次通过显式覆盖接管存量容器。
- [ ] 部署脚本：前端 rsync 对 root 所有的远端 `dist` 目录使用 `--no-owner --no-group --no-perms
  --omit-dir-times`，避免内容已上传却因目录元数据失败退出 23。
- [ ] Zhuan 主机：升级 buildx（当前 0.12.1）以兼容 Compose 5.1.2，或在部署脚本提供传统 builder
  兼容路径；本次使用 `DOCKER_BUILDKIT=0 docker build` 完成部署。
- [ ] 在 perf2 触发一次真实 KICKED_OUT/LEFT 事件，验证状态落库、创建任务展示、执行跳过和营销明细。
