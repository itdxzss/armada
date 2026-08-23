# 群成员 PN/LID 并发分裂防护

## 变更概述

- 群成员事实同时携带 PN 与 LID 时，事务内锁定两种身份对应的成员行。
- 若命中一条 PN-only 与一条 LID-only，保留 LID 行、合并事实、迁移账号绑定、删除 PN 行，随后补齐完整身份。
- 成员批量写入遇到重复键时，重新检查并归并一次，再重试写入一次。
- V139 清理存量确定配对，并增加 `(tenant_id, group_id, phone)` 唯一键，阻止可信手机号已知时再次并发写出双行。

## 影响模块

- `group` 当前成员快照持久化 Service、Mapper 与 Mapper XML。
- `wa_group_participant`、`wa_account_group_binding`。

## 数据库变更

- Flyway: `V139__group_participant_phone_identity_guard.sql`。
- 只自动处理同租户、同群、同 phone 下恰好一条 PN-only 与一条 LID-only 的确定配对。
- 其他重复形态不会静默合并；唯一索引创建失败会阻止带歧义数据启动。
- 执行 Flyway 前必须先运行 `db-migrations.sql` 中的预检和备份。

## API 与 Redis 变更

- API：无。
- Redis：无。

## 关键约束

- LID 行为 canonical，PN 行为 duplicate。
- 账号绑定必须先迁移，随后才能删除 PN 行；PN 身份必须在删除后才能回填到 LID 行。
- 自动归并只接受 PN-only + LID-only；已有完整身份冲突时失败，不猜测身份归属。
- phone 为空时唯一键不生效，但后续同时携带 PN/LID 的事实仍可触发定点归并。
- 执行部署前备份、V139 和应用切换期间必须暂停群成员事实写入，避免旧版本在清理与建索引之间再次产生双行。

## 验证

- Service 单测覆盖主动归并、身份冲突拒绝、重复键后只重试一次。
- H2 MySQL 模式加载真实 Mapper XML，执行身份行查询、绑定迁移、删除和身份补齐 SQL。
- MySQL Testcontainers 用例覆盖事实合并；本机无 Docker 时该用例需在 CI 或具备 Docker 的环境补跑。
- Flyway MySQL 专有语法由 SQL 结构测试和 `xmllint` 覆盖静态形状。

## 回滚

- 先回滚应用或停止成员事实写入，再按 `rollback.sql` 删除唯一键并从部署前备份恢复双行与绑定。
- 若未执行部署前备份，已删除的 PN 行不能从业务表自行恢复，只能依赖数据库全量备份。

## 部署状态

- 2026-08-23 已部署到 `test1 / 第一套环境`，范围仅后端，来源为当前
  `1.0.3-snapshot` 工作区（基线 commit `3bda41b3`，dirty build）。
- 部署前暂停后端写入并备份 509 组、1018 条成员行和 499 条账号绑定。
- Flyway V139 用时 7.453 秒执行成功；迁移后重复组为 0、509 条 canonical 行均持有
  PN/LID、旧 PN 行为 0、499 条绑定迁移不匹配为 0。
- 唯一索引已确认为 `UNIQUE(tenant_id, group_id, phone)`；容器运行中且重启次数为 0。
- 4 条悬空绑定是部署前已存在基线，迁移后仍为 4，本次未扩大范围处理。
- 公网入口及 Armada 主机到协议健康端口的跨组件探测在部署前后均超时；组件各自主机健康
  检查及 Armada 本地 API 检查通过，记录为既有网络基线问题。
- 重启后的 Kafka 积压回补曾在旧的 `touchAccountObservedGroup` 路径出现锁等待超时和一次
  consumer rebalance；该栈不经过本次 PN/LID 归并代码，随后连续 60 秒 ERROR 为 0，容器
  保持 `running / restart=0`。
- 已基于迁移后的 test1 `information_schema` 运行 `gen_datamodel.py`，生成结果包含正确的新索引；
  因当前自动文档相对真库已有 1956 行无关漂移且包含临时备份表，未将整份生成结果覆盖进仓库。
