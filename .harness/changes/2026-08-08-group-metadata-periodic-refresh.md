# 变更记录：新建普群成员快照周期刷新

- 日期 / 分支：2026-08-08 / `1.0.2-snapshot`
- 范围：Armada 后端群 metadata 耐久同步任务
- 状态：本地实现并验证，未部署

## 问题与证据

- Web 单群收到 `group-participants.update` 时可以触发实时同步。
- Android 没有同等的 metadata 变更事件；Web 批量建群也可能出现事件漏发或无法关联。
- 第一套测试环境只读核对显示：最近 24 小时 14 个自建群任务全部为
  `SUCCEEDED`，且 `next_run_at` 全部为空；首次快照成功后不会再执行。
- 快照执行器会完整替换 `group_link_preview.member_size` 和成员快照，故缺陷属于
  “同步触发缺失”，不是列表/详情展示或 Android metadata 映射错误。

## 实现

- 成功任务保留 `SUCCEEDED` 展示状态，并按默认 60 秒写入下一次对账时间；配置项为
  `armada.group-metadata-sync.periodic-refresh-ms`，最小值 1 秒。
- 调度优先读取 `PENDING/RETRY_WAIT`，只用剩余批次处理到期的 `SUCCEEDED`，避免周期任务
  阻塞协议事件、手动刷新和失败重试。
- 成功后把尝试次数归零，周期运行不会累积成失败重试次数。
- 兼容部署前 `SUCCEEDED + next_run_at IS NULL` 的任务：按成功状态允许领取一次，成功后
  自动进入新周期，不需要手工更新数据库。
- 无可用在群账号时仍进入 `DEFERRED`，待账号上线后沿用既有恢复路径。
- 不新增表、列或 Flyway 迁移；每租户每群仍只有一条同步任务。

## 验收口径

- Web 单群原有事件实时刷新不回归。
- Web 批量建群及 Android 单群/多群即使没有协议变更事件，也会由周期对账更新成员数和成员快照。
- 协议事件和手动刷新优先于周期任务。
- 旧成功任务在部署后能自动恢复一次对账。

## 验证

- 红灯：旧成功任务 `next_run_at=NULL` 时，真实 Mapper XML 返回空候选。
- 绿灯：群创建结果、事件触发、调度、协议路由、快照和 Mapper 共 7 个测试类、
  31 个测试通过；H2 使用 MySQL 模式并加载真实 Mapper XML，并覆盖未到期/恰好到期边界。
- `git diff --check` 通过；改动类和方法均未超过仓库规模限制。
- 第一套测试环境只读确认数据库为 MySQL 8.4.8；未执行任何远程写入。
- 全量 `mvn test` 实际执行 508 个测试类、2621 个测试，结果为 11 failures、
  443 errors、12 skipped，未通过。主要错误来自默认 `*DbTest` 尝试连接本机未配置的
  MySQL（`Communications link failure`），另有与本次群同步文件无关的既有测试失败；
  本次涉及的测试类在同轮报告中保持通过。
