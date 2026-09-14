# 拉群三项修复验证

2026-09-13 完成本地代码与测试。基于分支 1.0.3-snapshot、HEAD a3f9c4c0。未提交、推送、部署或修改在线任务数据。

| 用户问题 | 实现后的行为 | 核心验证 |
| --- | --- | --- |
| AK03/AK08 计划人数与绑定不一致，未发命令却空转 | 持锁检查未提交批次，取消已成功成员的新计划，保留旧调用成功事实，13 人计划校正为 12；空批直接推进 | 真实 H2 13→12、12 人提交、空批无拉手推进、事务回滚、已有提交证据拒绝重规 |
| 收口反复异常拖住下一轮 | 同群规划/提交/回调/收口串行化本地状态；收口先刷新台账，成功聚合只释放匹配的活动指针；一个参与者异常不阻断其他号码，日志保留调用和尝试 ID 及异常堆栈 | 旧成功与新 SUBMITTED 并存、重复旧快照、跨租户输入、取消历史不妨碍批次关闭 |
| 未确认结果无限重试且页面隐藏 | UNKNOWN 保持可见；仅未开始、确认不在群、允许重试的明确 TIMEOUT 可再尝试，总上限四次，60/120/240 秒冷却；恢复旧状态并阻止 INITIAL 绕过上限 | 历史 UNKNOWN/FAILED 归一、最新尝试/活动绑定保护、重试预算、波次端到端 |

页面新增等待结果、待重试人数、已提交尝试次数、未确认次数、最近拉入成功时间；有未知结果的结束任务明确提示待核实。人数与尝试次数分开展示。

## 测试结果

- 后端聚焦回归：25 类，153 tests，0 failures，0 errors，0 skipped。
- 环境：Microsoft JDK 17.0.19，Maven；Mockito 使用显式 byte-buddy-agent 1.14.19。
- 清单：[repair-test-summary.json](repair-test-summary.json)。日志：[repair-backend-tests.log](repair-backend-tests.log)。
- 前端：20 项测试、tsc/vue-tsc、ESLint/Stylelint、Vite 生产构建全部通过；记录位于 wheel-saas-pure-web/.harness/changes/2026-09-12-pull-task-progress-visibility.md。
- 扩展 E2E 中两项既有邀请码恢复测试失败，已在独立 git archive HEAD a3f9c4c0 相同复现；没有改变预期或掩盖失败。见 [repair-existing-invite-failure.log](repair-existing-invite-failure.log)。

## 验收边界

以上证明代码和数据库状态机回归成立，尚未证明在线 task 208 已恢复或耗时已降低。部署后应核对实际任务状态，再确认 AK03/AK08 校正后的命令只含尚未成功号码，收口异常不再重复出现，历史高尝试号码停止自动提交且未知人数可见。暂停、结束及资源不足状态仍以线上当前状态为准。本次没有恢复/结束任务、补拉手、换群或发送验证消息。
