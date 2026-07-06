# 营销任务明细账号群组聚合 - 规格

## 背景

群组营销任务明细页当前主要展示目标行。固定选群任务里目标行接近账号×群组,账号动态任务里目标行只有账号,发送后只回写最近一次真实群快照。业务现在需要在明细页按账号查看实际发送过的每个群组情况。

统一口径:固定选群和账号动态都从发送记录 `marketing_task_send_attempt` 查询真实群组明细,不再用创建任务时的 target 快照作为群组展示事实源。

## 范围

- 后端扩展 `GET /api/marketing-tasks/{id}` 返回账号维度明细。
- 前端明细抽屉改成一行一个账号,账号行里展示群组聚合信息。
- 汇总区文案调整:发送条数改为总发送条数,移除最后发送时间。

不调整任务创建、发送调度、发送结果回写、列表页任务分页和导出。

## 后端契约

`MarketingTaskDetailVO` 保留原有任务汇总字段和 `targets` 兼容字段,新增 `accountTargets`:

- `accountId`
- `accountPhone`
- `status`
- `sentMessageCount`:该账号成功发送总条数,从发送记录按账号聚合。
- `failedMessageCount`:该账号失败总条数,从发送记录按账号聚合。
- `lastAttemptAt`
- `lastSentAt`
- `lastReason`
- `groups`:该账号下实际发送过的群组聚合列表。

群组聚合行字段:

- `groupLinkId`
- `groupJid`
- `groupLinkUrl`
- `groupName`
- `sentMessageCount`:单群成功发送条数。
- `failedMessageCount`:单群失败条数。
- `lastAttemptAt`
- `lastSentAt`
- `lastReason`

聚合 SQL 以 `marketing_task_target` 连接 `marketing_task_send_attempt`。账号行基于 target 保留,这样未产生发送记录的账号仍显示。群组行只来自 attempt,按账号和群唯一键合并。成功数使用 `attempt.status = 1`,失败数使用 `attempt.status = 2`。`lastSentAt` 只取最近成功结果时间,`lastAttemptAt` 取最近结果时间或尝试时间。`lastReason` 取该群最近失败记录的原因;若最近记录是成功,但历史失败仍存在,仍展示最近失败原因作为最近原因。

## 前端展示

汇总区:

- `发送条数` 改为 `总发送条数`。
- 删除 `最后发送时间`。

明细表:

- 表格数据源改为 `detail.accountTargets`。
- `发送条数` 列改为 `号发送总条数`。
- 其后新增组合列 `群组情况`,默认展示该账号第一条群组记录。
- 多个群组时使用 Element Plus 表格展开行展示全部群组,展开内容包含单群发送条数、群组链接、群组名称、最近原因、最后发送时间。
- 账号暂无发送记录时显示 `暂无发送记录`。

## 测试

后端:

- DB 测试覆盖一个账号对两个群的成功/失败发送记录,断言账号总发送条数和群组聚合条数。
- DB 测试覆盖未产生发送记录的账号仍在 `accountTargets` 中出现且 `groups` 为空。

前端:

- 类型和展示辅助函数测试覆盖默认群展示、多群展开数据和空发送记录文案。
- 明细抽屉组件测试覆盖汇总文案和移除最后发送时间。

## 自检

- 无待定项。
- 固定选群与账号动态统一走 attempt 聚合,与用户确认口径一致。
- 兼容保留 `targets`,降低接口改动影响面。
