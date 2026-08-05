# 拉群任务分组邀请链接修复

## 变更概述

- 运营分组中的内部 `wa://group/{jid}` 入口改为使用 `group_link_preview.invite_code`
  输出真实 WhatsApp 邀请链接。
- 缺少邀请码的内部群入口不进入执行计划，并与分组可用数量保持一致。
- 原有邀请链接、健康、封禁、软删除、顺序和租户隔离条件保持不变。

## 影响模块

- 群组域运营分组 Mapper。
- 普通群链接拉群任务的分组来源取数。

## 数据库/API/Redis 变更

- 无表结构与数据迁移。
- 无 API 契约变更。
- 无 Redis 变更。

## 关键约束

- `wa://group/{jid}` 继续作为群组池内部稳定入口，不直接作为拉群邀请链接。
- 继续保留健康、未封禁、未软删除和租户隔离条件。

## 回滚方案

- 仅回退本次 Mapper XML、测试和文档改动。

## 验证结果

- TDD 红灯 1：内部入口用例实际返回 `wa://group/120363001@g.us`，准确复现取错字段。
- TDD 红灯 2：缺少邀请码时实际返回 `chat.whatsapp.com/`，准确复现空邀请码未过滤。
- `mvn -Dtest='GroupFolderMapperInMemoryTest' test`：5 个测试通过，0 失败。
- `mvn -Dtest='GroupLinkUrlsTest,GroupFolderMapperInMemoryTest,PullTaskStandardDraftServicePlanTest' test`：
  33 个测试通过，0 失败。
- `xmllint --noout armada-api/src/main/resources/mapper/group/GroupFolderMapper.xml`：通过。
- `git diff --check`：通过。
- 全量 `mvn test` 未形成可用门禁：仓库现有 `PromotionCapiEventOutboxSchemaDbTest` 持续尝试外部数据源，
  为避免连接未确认环境已中止。排除 `*DbTest` 的扩大回归执行到 337 个测试时，存在 1 个与本次文件无关的
  `AccountGroupMembershipMapperSqlTest` 既有失败、9 个跳过，随后
  `GroupLinkRegistryServiceImplTest` 再次尝试外部数据源，因此中止。
