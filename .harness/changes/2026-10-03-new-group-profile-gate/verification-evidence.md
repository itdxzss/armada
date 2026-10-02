# 必填群资料核验证据

本次 V209 的两个字段属于 `pull_task_group_execution` 执行检查点聚合：

- `profile_verified_at`：最近一次从协议实时回读群名、简介并确认符合本执行行要求的 epoch 毫秒时间。
- `profile_verified_command_id`：该次核验关联的资料命令身份。

资料动作已有 `SUCCESS/FAILED/UNKNOWN` 描述整条命令的协议结果，可能还包含头像、禁言或权限设置。
必填群名、简介的回读确认不能替代整条命令结果，因此不能把 UNKNOWN 或可选项失败篡改成 SUCCESS。
执行步骤游标也不能独立证明资料已生效，历史版本曾在命令提交时直接推进。
这两列记录一个新的执行前置核验事实，不复制资料内容、动作尝试号或协议结果，不新增表。

核验证据与步骤推进由同一条 `transitionGroupCreate` CAS 原子写入；版本、租约或步骤失效时均不写。
其他步骤不携带 `verifiedProfileCommandId`，不会清除已有证明。新执行行和历史行均默认 NULL，
禁止根据历史步骤、命令入队或 HTTP 成功回填证明。H2 测试验证 Mapper、租户边界、失效 CAS 和回滚；
它不证明真实 WhatsApp 群资料已生效。

V209 以 information_schema 守卫新增列；生产执行仍通过 Flyway。回滚应用时优先保留 nullable
证据列，避免丢失核验历史；如确需删除，先停用依赖字段的版本并另行确认目标环境后使用 rollback.sql。
当前仅修改本地文件，没有连接或迁移真实数据库。数据模型生成文档尚未刷新：本地缺少生成器所需的
`/tmp/wheel_tables.tsv` 导出输入，未连接数据库补齐，也未手改生成文档冒充同步完成。
