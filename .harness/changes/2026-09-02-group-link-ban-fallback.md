# 变更记录：群链接模式封群自动换群

- 日期 / 分支：2026-09-02 / `codex/group-link-ban-fallback`
- 需求来源：测试环境普通拉群任务出现明确群封禁后，原 TXT 未换群继续执行
- 状态：本地开发与聚焦回归完成，待测试环境验收

## 目标

群链接模式选择群组分组后，单个 TXT 遇到明确群封禁时串行领取下一可用群并从头执行；并行上限保持任务配置，成功群移入系统“已使用群组”。

## 范围

- In scope：`PASTED_LINK` + `source_group_folder_id` 的封群重试、运行时换群、成功移组、分组占用保护、重复群排除；创建页隐藏资源池入口。
- Out of scope：纯手工链接自动换群、历史失败执行行补跑、测试环境部署和数据修复。

## 影响模块

- 普通拉群执行生命周期与调度取群。
- 群组分组移动与删除保护。
- 拉群任务创建页模式入口。

## 关键设计决策

- `RESOURCE_POOL` 后端枚举和存量行为暂时保留兼容；新建入口只展示群链接模式和新群模式。
- 仅任务保存了来源分组时自动换群；纯手工链接封禁仍按原终态收口。
- 重试行保留原 `source_file_index`，`attempt_no + 1`，复制原 TXT 全量成员并重置执行状态。
- 候选群排除全局活动占用和本任务历史已绑定群，避免同一任务重复用群。
- 分组暂无可用群时沿用 `WAIT_GROUP_RESOURCE`，运营补群后可继续，不把其他运行中的 TXT 一并暂停。

## 数据库 / API / Redis

- 不新增表、列、迁移、API 或 Redis 结构。

## 验证

- 聚焦后端测试：`PullTaskStandardExecutionLifecycleServiceTest`、`PullTaskExecutionTransactionServiceTest`、`PullTaskClosingTransactionServiceTest`、`PullTaskGroupBanTerminationServiceTest`，共 24 条，全部通过。
- 覆盖关键路径：群链接分组封群生成同 TXT 下一次执行、复制全量成员并重置、排除本任务历史群、成功群移入已使用、纯手工链接不自动换群。
- `git diff --check` 通过。
- 后端全量 `mvn test` 未完成：运行到 `PromotionCapiEventOutboxSchemaDbTest` 时持续等待外部数据库连接（`HikariPool-1 - Starting...`），为避免无限等待已中止；本次相关 H2/Service 测试均已通过。

## 回滚

- 回退本分支代码即可；无数据结构回滚。

## 遗留 / 跟进

- 昨日测试环境 5 条历史失败行是否补跑，部署后单独确认并执行。
