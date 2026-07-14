# 变更记录：群链接导入失效判定

- 日期 / 分支 / worktree: 2026-07-14 / `1.0.1-snapshot` / 当前工作目录
- 需求来源: 用户会话确认“公开邀请页获取不到群名即链接失效”
- 状态: 代码完成，未部署

## 目标（一句话）

群链接只有在 WhatsApp 公开邀请页取得真实群名后才允许新增、复活或收编，失败明细统一反馈“链接失效”。

## 缺口拆解 / 任务清单

- [x] 公开页检测前置到 `group_link` 主表变更之前。
- [x] 新增失败原因“链接失效”，并纳入批次失败统计和逐行错误描述。
- [x] 新增、软删复活、未分组收编三类主表不变测试。
- [x] 增加 Flyway V054 及回滚脚本，更新失败原因字段注释。
- [x] 真跑 focused DbTest 并刷新自动数据模型文档。

## 关键设计决策

- `waSubject` 是导入有效性的唯一公开页判据；只有头像没有群名仍视为失效。
- 已经属于导入分组的活跃链接优先判“重复”，不额外请求公开页。
- 失效链接不新增、不复活、不收编，避免失败明细与活跃主表数据并存。
- 不调用在线账号或协议层，不回溯清理历史链接。

## 验证（evidence-before-done）

- `mvn -q -Dtest=GroupLinkImportServiceImplTest test`: RED 时 27 tests / 4 failures，均命中新行为缺口。
- `mvn -q -Dtest=GroupLinkImportServiceImplTest test`: GREEN，27 tests / 0 failures / 0 errors / 0 skipped。
- `./dbtest.sh 'GroupLinkImportServiceDbTest,GroupListDataModelMigrationDbTest'`: 沙箱外连接本机 `armada` 测试库，exit 0；Flyway 校验 54 个迁移并应用至 V054，两类 DbTest 全绿。
- `./dbtest.sh 'GroupLinkUrlsTest,GroupLinkImportServiceImplTest,GroupLinkImportServiceDbTest,GroupLinkImportDetailMapperDbTest,GroupListDataModelMigrationDbTest,GroupLinkControllerTest,GroupLinkImportControllerTest,HttpGroupInvitePageFetcherTest'`: exit 0，导入链路单测、真库测试、Controller 测试及公开页解析测试全绿。
- `./dbtest.sh '*'`: 共运行 1037 个测试，11 failures / 28 errors / 0 skipped；失败集中在与本次无关的营销任务、建群营销、协议配置、模板删除及协议命令 outbox 测试，本次群链接导入相关测试未失败。全量基线当前不能作为绿色交付证据，保留原始结果供后续专项处理。
- `python3 .harness/wiki/gen_datamodel.py`: 基于本机真库 `information_schema` TSV 生成完成，`armada=31` 表；已用生成结果刷新 `.harness/wiki/数据模型.md`。
- `xmllint --noout armada-api/src/main/resources/mapper/group/GroupLinkImportDetailMapper.xml`: exit 0。
- `git diff --check`: exit 0。

## 部署

- commit / 环境 / 部署后验证结果: 按用户要求保留本地未提交，不部署。

## 遗留 / 跟进

- 不回溯检测或清理历史已经入池的群链接。
- 本次不部署；部署前需确认目标环境并执行正常 Flyway/冒烟流程。
