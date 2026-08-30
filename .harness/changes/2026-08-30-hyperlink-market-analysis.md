# 变更记录：超链市场分析

- 日期 / 分支 / worktree: 2026-08-30 / `codex/hyperlink-market-analysis-20260830` / `.codex-worktrees/hyperlink-remaining/armada-analysis`
- 需求来源: 竞品 analysis bundle 与 `docs/business/hyperlink-marketing-data-model.md` 冻结口径
- 状态: 集成分支口径校正完成，待环境数据验收

## 目标（一句话）

落地仅含主查询与国家选项的超链市场分析，并用 Asia/Shanghai 日/小时投影隔离在线查询与 recipient 大表。

## 缺口拆解 / 任务清单
- [x] 固定 V170 数据模型与发送时设备 OS 快照
- [x] 聚合回填、保留清理与账号终态失效入口
- [x] `marketing-stats` / 时间范围 `countries` API、RBAC 与真实 Mapper
- [x] 顶部 `overview` 全局账号去重与封号状态精确口径
- [x] 单元、Mapper、合同与迁移测试
- [x] 设计文档勘误和手写数据模型文档刷新

## 关键设计决策

- 页面只提供 `marketing-stats` 与 `countries`；账号统计/导出属于任务详情，不在市场页重复建设。
- `deviceOs=android|iphone` 读取发送时 `account.device_os` 快照，不把 `protocol_backend` 冒充设备。
- 国家对趋势保留时间桶行内去重；顶部 `overview` 单独全局去重，不从国家对行累加。
- 普通 OFFLINE/PROXY_FAILED 不记封号；只消费账号域已通过时间水位和映射校验的终态失效 side effect。
- `banned_account_count` 只统计 `usage_status=BANNED`，登录替换、需重登等失效不冒充封号。
- `countries` 跟随页面日/小时时间窗口，并排除 `ZZ`。

## 验证（evidence-before-done）

- 本次聚焦 Maven 回归 53/53 通过，覆盖 exact overview、168 小时边界、时间范围国家候选、封号状态、
  Controller 契约、V170、投影服务、真实 Mapper XML/H2、设备快照选号/派发、菜单白名单和生命周期门禁。
- `.harness/wiki/test_api_docs.py` 通过；全部 XML 可解析，`git diff --check` 通过。
- 数据模型生成器因未提供 `/tmp/wheel_tables.tsv` 无法刷新生成型 wiki；遵守“不连真实环境”边界，未伪造 information_schema 输入。
- `mvn test` 扩大回归在既有真库型 `PromotionCapiEventOutboxSchemaDbTest` 持续重连本地数据库时人工停止（exit 130）；
  尚未进入本次超链测试，聚焦 H2/单测无失败。

## 部署
- commit / 环境 / 部署后验证结果: 不部署真实环境。

## 遗留 / 跟进

- 真实钱包、审计提供方及协议私聊能力仍是超链任务上线前置，不由本市场分析切片补造。
- 生成型 `.harness/wiki/数据模型.md` 待有权环境导出 information_schema TSV 后按生成器刷新。
