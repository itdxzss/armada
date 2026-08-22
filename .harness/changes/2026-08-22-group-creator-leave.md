# 变更记录：群主退群

- 日期 / 分支 / worktree: 2026-08-22 / 1.0.3-snapshot / 当前 worktree
- 需求来源: 当前会话业务需求；设计见 `docs/superpowers/specs/2026-08-22-group-creator-leave-design.md`
- 状态: 已完成（未部署）

## 目标（一句话）

为群详情和标准拉人任务增加共用的群主退群能力：有我方控端管理员时直接退群，否则先提升一个我方普通控端成员，再由建群者退出，且不影响拉人结果。

## 缺口拆解 / 任务清单

- [x] 冻结数据库快照、候选选择、任务收尾和失败隔离规则
- [x] 增加 Flyway 字段及数据模型映射
- [x] 实现并测试统一群主退群服务和手动接口
- [x] 通过 Outbox 接入标准拉人任务异步收尾并记录最小结果
- [x] 接入 Web 与 Android 协议命令消费和统一结果回调
- [x] 增加群详情抽屉按钮与标准任务双模式开关
- [x] 完成后端、前端定向验证

## 关键设计决策

- 点击和任务收尾只读数据库事件投影，不调用 `groupMetadata`。
- 离线但状态正常的控端管理员/普通成员都可参与判断；建群者本人执行协议请求时仍需在线且正常。
- 群内存在我方控端管理员时直接退群；只有不存在管理员时才固定选择普通控端成员并执行提升。
- 不重新读取 metadata，不新增业务操作历史、分组迁移或手动按钮并发控制。
- 自动退群挂接现有单群执行 `CLOSING`，失败只记录结果，不改变拉人成功和既有群组流转。
- 手动入口继续走 HTTP；任务入口走 Outbox/Kafka，并按建群者协议后端路由 Web master 或 Android group-action topic。

## 验证（evidence-before-done）

- 后端相关 19 个测试类、157 个测试通过，覆盖统一决策、手动接口、异步状态机、Outbox、hydrator、结果消费和任务集成链路；`test-compile` 通过。
- Web 协议定向回归：6 suites、129 tests 通过；`npm run build` 通过。
- Android 协议：`go vet ./...`、`go build ./...` 和 `internal/armada` 测试通过。全量测试仅遗留既有 `pkg/noise` 向量文件缺失/断言失败。
- 前端定向契约、状态和布局测试：45 个测试通过；`pnpm typecheck`、`pnpm build` 和改动文件 ESLint 通过。
- 四个项目 `git diff --check` 通过。
- 曾尝试后端全量 `mvn test`；既有 `PromotionCapiEventOutboxSchemaDbTest` 等待外部数据库连接，未能在本地完成，人工终止。该限制不在本次功能链路内。

## 部署

- commit / 环境 / 部署后验证结果: 未部署

## 遗留 / 跟进

- V137 部署到已确认的 MySQL 环境后，再基于真实 `information_schema` 运行 `.harness/wiki/gen_datamodel.py` 更新数据模型文档；本次没有手工改写生成文件。
