# 账号导入原始格式导出

- 日期 / 分支 / worktree: 2026-06-30 / `codex/account-import-format-export` / `/private/tmp/codex-worktrees/armada-account-import-format-export`
- 需求来源: 账号导入导出从 CSV 调整为“导入 ZIP 导出 ZIP,导入 TXT/粘贴导出 TXT”;仅考虑新增批次
- 状态: 进行中

## 目标（一句话）

新增导入批次保存原始来源类型和逐条原始内容,导出时按导入容器恢复 ZIP/TXT,保留现有导出入口和范围。

## 缺口拆解 / 任务清单

- [x] 批次表增加 `source_file_type`,明细表增加 `raw_payload`、`source_entry_name`。
- [x] 导入解析阶段写入原始 payload 与条目名。
- [ ] 导出服务按 `source_file_type` 生成 ZIP/TXT 响应。
- [ ] 前端下载动态文件名、Blob 和 content-type。
- [ ] 真库 DbTest、parser test、前端类型检查和构建验证。

## 关键设计决策

- 不回填历史批次;历史批次缺少原始材料时由后端返回业务校验错误。
- 原始 payload 是敏感字段,只在导出专用 Mapper 投影读取,不进入列表 VO。
- `source_file_type` 只保留当前需要恢复的容器类型:ZIP/TXT。

## 验证（evidence-before-done）

- `armada-api/dbtest.sh AccountImportListMapperDbTest#mapper_persistsOriginalExportMetadata`
  - 结果: 通过;Flyway 从 v019 迁移到 v020,新增批次/明细原始导出字段可写可读。
- `mvn -q -Dtest=AccountImportParserTest test`
  - 结果: 通过;ZIP entry、JSON 数组、PARAMS 数组和非法 JSON 均保留导出所需原始 payload/条目名。

## 部署

- commit / 环境 / 部署后验证结果: 未提交;未部署。

## 遗留 / 跟进

- 无。
