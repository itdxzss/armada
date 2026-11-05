# 账号档位检测后端

主记录：`../2026-11-05-account-tier-check.md`。设计和契约依任务书。

- 新增 V225 两表与 V226 菜单；`db-migrations.sql` 为迁移副本，`rollback.sql` 仅方案，均未运行于真实数据库。
- API：`/api/accounts/tier-check-tasks` 的 preview/create/list/detail/items/stop/retry-failed/recheck；`/api/accounts/{id}/tier-history`。
- Web 端口三件套 + adapter，仅能力检查供预览使用；Android 自查作为不支持排除，Web 探针可查询 Android 目标。
- 调度认领/HTTP/结果短事务分离，默认关闭，独立 bounded pool，跨任务账号锁、租户恢复、晚到结果令牌、重启超时回收。
- 无 Redis schema 变更；限流事实位于本功能明细，原账号表不加列，不改账号类型/状态。
- JDK 21 + 现有本机 Byte Buddy agent 离线验证：功能 29 项与相邻回归总计 67 项全部通过；XML、API 文档生成测试通过。
- 离线 H2 不代表 MySQL InnoDB 锁语义/线上网络验收，真实查询和最终冷却参数待单独授权。
- 用户已授权本功能提交和推送；未部署、未查真实账号。
