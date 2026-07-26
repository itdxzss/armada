# 变更记录：Flyway 历史迁移对齐

- 日期 / 分支 / worktree: 2026-07-26 / `fix/flyway-history-reconciliation-1.0.2` / 独立 worktree
- 需求来源: 用户要求补齐迁移后重新部署第一套测试环境，且“不要漏了”
- 状态: 进行中

## 目标（一句话）

恢复第一套测试库已执行的 V061-V079 迁移历史，账号期望登录状态恢复到真实版本 V077。

## 缺口拆解 / 任务清单

- [x] 恢复 V061-V079 原始迁移文件并核对校验和
- [x] 确认账号期望登录状态使用历史版本 V077
- [x] 增加版本唯一性、完整映射、校验和和打包内容测试
- [x] 修正部署健康检查对 `40104` 的识别
- [x] 完成 Maven、部署脚本和 JAR 内容验证
- [ ] 部署第一套测试环境并验证 Flyway、容器和接口

## 关键设计决策

- 采用代码侧恢复真实迁移历史；历史 SQL 来源为第一套环境已通过 Flyway 校验的可运行旧 JAR。
- 禁止 `flyway repair`、禁止关闭校验、禁止手工修改共享测试库 schema history。
- V061-V079 保持历史校验和；拉群营销使用 V070；账号期望登录状态使用 V077。

## 验证（evidence-before-done）

- RED：迁移契约测试准确报告 V061-V079 缺失/冲突，拉群测试准确报告 V070 缺失；部署脚本测试准确报告受保护接口仍使用 `curl -fsS`。
- GREEN：迁移契约、版本唯一性和拉群迁移测试共 4 项全部通过。
- 业务回归：拉群营销、重试策略、SQL 形状、Flyway 契约等 48 项测试全部通过（0 失败、0 错误）。
- 构建：使用 JDK 17 执行 `mvn -q -DskipTests clean package` 成功，产物为 `armada-api-1.0.2-SNAPSHOT.jar`。
- 打包检查：JAR 内 V061-V079 恰好 19 个文件，文件名与迁移契约完全一致。
- 脚本：`bash -n`、`deploy-test.test.sh`、`package-prod.test.sh` 均通过；`git diff --check` 通过。
- 来源一致性：19 个 SQL 与测试一当前可运行旧 JAR 的对应文件逐字节 `cmp` 全部一致。
- 全量 `mvn test` 已尝试：现有 `EpochMillisSchemaDbTest`、`HarnessSmokeDbTest` 默认连接本机 MySQL（`root@localhost`）且当前环境无密码配置，12 项执行后产生 2 个 Spring 上下文错误；与本次迁移代码无关，因此改用不依赖外部数据库的 48 项相关测试完成回归。

## 部署

- commit / 环境 / 部署后验证结果: 待补充。

## 遗留 / 跟进

- 无代码遗留；部署后补充真实运行证据。
