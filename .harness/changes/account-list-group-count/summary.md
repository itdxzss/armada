# 变更记录：账号列表群组数量统计

- 日期 / 分支 / worktree: 2026-07-09 / 当前分支 / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户会话确认的账号列表优化需求
- 状态: 代码完成,DbTest 待目标数据库环境确认

## 目标（一句话）

账号列表展示账号上控后当前有效群组数量,并记录上控后首次探测到进入群的时间。

## 缺口拆解 / 任务清单

- [x] 为 `account_group_membership` 增加 `joined_at` 并锁定 upsert 语义。
- [x] 账号列表后端返回 active membership 聚合数 `groupsNum`。
- [x] 营销账号树不再懒加载捕获 baseline。
- [x] 前端账号列表确认继续展示 `friends_num / groups_num`。
- [x] 跑 focused unit test / compile / XML / 前端 API 映射测试。
- [ ] 跑 focused DbTest: 当前数据库连接失败,需确认目标环境后执行。

## 关键设计决策

- 好友数本期不做,继续返回 0。
- 首次上线成功算账号上控;`group_baseline_state=PENDING` 的第一份群列表只拍 baseline。
- `joined_at` 表示 Armada 首次探测到账号在上控后进入该群的时间,不是 WA 原始加群时间。
- 账号列表群组数只统计 `account_group_membership.deleted_at IS NULL`。

## 验证（evidence-before-done）

- `git diff --check` (`armada`): exit 0,无输出。
- `git diff --check` (`wheel-saas-pure-web`): exit 0,无输出。
- `xmllint --noout armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml armada-api/src/main/resources/mapper/account/AccountMapper.xml`: exit 0。
- `cd armada-api && mvn -Dtest=AccountGroupMembershipReportServiceImplTest,AccountConverterTest,MarketingAccountTreeRealtimeServiceTest test`: exit 0,Tests run: 7,Failures: 0,Errors: 0,Skipped: 0,BUILD SUCCESS;同时完成 testCompile,包含新增 schema DbTest 编译。
- `cd armada-api && mvn -DskipTests compile`: exit 0,BUILD SUCCESS。
- `cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web && node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account.test.ts`: exit 0,tests 7,pass 7,fail 0。
- `armada-api/dbtest.sh AccountGroupMembershipReportServiceDbTest`: exit 1,Spring/Flyway 获取数据库连接失败,根因 `Communications link failure`;Tests run: 4,Failures: 0,Errors: 4。当前未继续申请真实数据库访问,需确认目标环境后补跑 `AccountGroupMembershipReportServiceDbTest`、`AccountListMapperDbTest`、`MarketingTaskAccountTreeDbTest`、`MarketingTaskDataModelMigrationDbTest`。
- `.harness/wiki/gen_datamodel.py`: 未执行生成,因为脚本输入 `/tmp/wheel_columns.tsv`、`/tmp/wheel_indexes.tsv`、`/tmp/wheel_tables.tsv` 不存在。该脚本要求基于真库 information_schema TSV 生成,本次不伪造 `.harness/wiki/数据模型.md`。

## 部署

- commit / 环境 / 部署后验证结果: 本次不部署。

## 遗留 / 跟进

- 历史数据清洗不在本次范围内;如需清洗需单独确认目标环境。
- 确认 DbTest 目标库后补跑 4 个 focused DbTest,并用真库 TSV 重新生成 `.harness/wiki/数据模型.md`。
