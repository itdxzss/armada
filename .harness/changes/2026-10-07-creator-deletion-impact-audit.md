# 建群账号注销保护跨层影响审计（只读）

- 日期：2026-10-07；分支1.0.3-snapshot，现有四仓主checkout。
- 范围：用户要求全面分析2026-10-06新增注销保护，仅perf2现场只读；不修改业务代码、不部署、不写数据库或Redis、不执行真实协议操作。
- 状态：分析与方案完成；修复、治理、部署和真实业务验收均未执行。

## 结果

统一报告：[impact-analysis.md](/Users/daishuaishuai/IdeaProjects/armada/docs/operations/evidence/creator-deletion-impact-perf2-20261007/impact-analysis.md)。同目录含运行制品、脱敏现场快照、SQL及隔离复现证据，分开记录HEAD、未提交补丁、运行制品与现场数据。

本轮确认两条ENDED任务遗留RESERVED，一条仍命中未软删除账号。源码与真实Mapper/H2/Go隔离复现揭示：预留无释放、候选与占用漏防、非上下线混批回滚、直接端口漏门禁、回调身份隐去、确定未发任务永久隔离、未知结果与清理缺少独立收敛、页面和导出状态不一致、号码身份缺代次，以及锁范围和错误分类风险。

正常NEW_GROUP前置create/profile/invite/admin/contact未发现task/execution字段遗漏。执行305审批解析失败是独立缺陷，不归因注销。

## 验证和边界

- perf2数据库READ ONLY事务、Redis专用前缀GET/SCAN/PTTL、协议缓存状态GET、部署文件/运行制品哈希。
- 本地真实Mapper+生产租户插件+Spring事务/H2；Android Go overlay、真实Store/Lua与内存连接。
- 既有未提交业务补丁保留；无commit/push/deploy，无现场状态治理，无真实注销/发送/拉人/上下线。
- 后续按统一报告优先级修复；仅明确未发送且满足双端证据及旧worker/命令隔离条件的预留可安全释放，UNKNOWN/ACCEPTED永久保护不得清除。
