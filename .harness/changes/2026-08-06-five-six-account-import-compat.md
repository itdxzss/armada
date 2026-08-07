# 变更记录：五段号与六段号兼容导入

- 日期 / 分支 / worktree：2026-08-06 / `1.0.2-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源：用户确认控台采用兼容入口，五段号可直接导入并走 Android 自动上线
- 状态：后端与前端实现完成，本地非数据库验证通过；数据库集成测试待确认可用测试库后补跑

## 目标（一句话）

保留现有六段号格式编码和 Android 上线链路，让控台与后端同时接受五段号和六段号，并由后端为五段号生成唯一 `phone_id`。

## 缺口拆解 / 任务清单

- [x] 对账当前前端入口、后端六列解析和 Android 六字段协议边界。
- [x] 确认采用同一入口兼容五列和六列。
- [x] 完成设计文档并自检。
- [x] 用户审阅书面设计。
- [x] 编写实施计划。
- [x] 按 TDD 实现后端五/六列解析兼容。
- [x] 调整前端入口、映射和说明文案。
- [x] 完成后端 parser / publisher 单测、前端测试、类型检查和构建。
- [ ] 完成后端数据库导入、Controller 和自动派发集成测试（待确认可用测试库）。
- [ ] 部署第一套测试环境并完成五段/六段联合验收。

## 关键设计决策

- 保留 `importFormat=1` 和 `credFormat=1`，不新增五段号枚举或数据库迁移。
- 五段号的 `phone_id` 只在后端生成，避免前端与其他 API 调用方行为不一致。
- 五段号保存为完整六字段运行时凭据，但 `raw_payload` 保留原始五列，继续满足原格式导出。
- 六段号的 `phone_id` 原样保留，不改变现有行为。
- 自动上线继续复用 `online_phase=QUEUED`、现有代理分配和 Android outbox。
- outbox 继续不保存账号凭据；发布器在发送 Kafka 命令前从凭据表补齐生成的 `phone_id`。
- 前端入口改为“五/六段号”，映射层兼容历史“六段号”文案。

否决方案：

- 新增独立五段号格式：需要扩展枚举、筛选、展示和运行时映射，且原始来源已有 `raw_payload` 记录。
- 仅前端补 `phone_id`：其他 API 调用方仍无法使用，规范化逻辑也会分散。

## 验证（evidence-before-done）

- 设计阶段已核对：后端当前只接受六列；前端当前仅展示“六段号”；Android 协议边界要求 `phone_id`。
- 设计文档：`docs/superpowers/specs/2026-08-06-five-six-account-import-compat-design.md`。
- 实施计划：`docs/superpowers/plans/2026-08-06-five-six-account-import-compat.md`。
- 后端 `mvn -Dtest='AccountImportParserTest' test`：BUILD SUCCESS，33 tests，0 failures，0 errors。
- 后端 `mvn -Dtest='ProtocolCommandPublisherTest#publishBatch_onlineAndroidRowBuildsZhuanLifecyclePayload' test`：BUILD SUCCESS，1 test，0 failures，0 errors。
- 后端 `mvn -DskipTests test`：BUILD SUCCESS，生产代码与全部测试源码编译通过，未执行测试。
- 前端定向 Node 测试：13 tests passed，0 failed。
- 前端 `pnpm run typecheck`：退出 0。
- 前端 `pnpm run build`：退出 0，Vite production build 完成。
- 后端数据库集成测试已通过仓库 `dbtest.sh` 尝试，但 Spring/Flyway 在建立 MySQL 连接前失败（当前运行环境禁止连接，未进入测试方法）；未将其计为通过，也未执行真实环境写入。
- 两个仓库均执行 `git diff --check` 通过；未修改 `armada-protocol`。

## 部署

- commit / 环境 / 部署后验证结果：仅设计，尚未部署。

## 遗留 / 跟进

- 在明确可用的本地测试库目标后补跑 `AccountImportServiceImplDbTest`、`AccountImportControllerDbTest` 和 `AccountImportOnlineDispatcherDbTest` 的新增用例及相关回归集。
- 部署第一套环境前再次确认远程目标；本次实现阶段不执行部署。
