# 变更记录：超链旧版未知结果最小兼容修复

> 后续用户已确认恢复五项修改。本文件仅记录中间的最小修复阶段，最终范围和验证见 [超链系统故障恢复](2026-09-18-hyperlink-system-recovery.md)。

- 日期 / 分支：2026-09-18 / 主仓 1.0.3-snapshot。
- 需求来源：用户明确历史不处理、只修代码问题、不做大改。本轮将此前五项方案收缩为单个明确缺陷修复，不代表完整自动补发改造。
- 状态：本地修复完成，聚焦验证通过；未提交、push、部署。

## 目标

Android 旧结果事件缺少 outcome，仅返回 success=false、reasonCode=SEND_RESULT_UNKNOWN 时，复用超链已有 UNKNOWN 分支，不再将尚在发送中的目标记成最终失败。

## 修改范围

- 生产代码仅 HyperlinkProtocolResultService：增加一个常量，扩展一个条件分支。
- 新增 HyperlinkLegacyUnknownResultTest 回归测试。
- 不处理历史任务或数据，不修改 SQL/数据库结构、前端、协议代码、其他营销 sink、自动换号、计费或回执状态机。
- 有明确 outcome 的事件继续沿用原判定；其他失败码与成功事件行为不变。

## 验证

- JDK 17；Mockito 使用本地 Byte Buddy javaagent，避免沙箱动态 attach 等待。
- 修改前新回归测试：11 个用例，7 个失败、0 error；复现未知结果落成失败及阻止后续 delivery 更新。
- 修改后执行新回归测试、HyperlinkProtocolUnknownResultTest、HyperlinkRecipientStateMachineTest、HyperlinkCompletionAndUnknownRecoveryTest：分别 11/9/3/4 个，共 27 个通过，0 failures/errors/skipped。命令为 JDK 17 下 `mvn -o -q -DargLine=-javaagent:<本地 byte-buddy-agent-1.14.19.jar> -Dtest=HyperlinkLegacyUnknownResultTest,HyperlinkProtocolUnknownResultTest,HyperlinkRecipientStateMachineTest,HyperlinkCompletionAndUnknownRecoveryTest test`，退出码 0；Surefire XML 已逐类核对。
- 本次生产文件 `git diff --check` 通过。没有运行全量测试或真实环境业务验收。
- 未修改 Mapper，无新增 SQL；本次为 Service 纯业务分支单测，不以 Mockito 替代数据库 SQL 验证。

## 边界与剩余风险

- UNKNOWN 保留 SENDING，沿用现有原 command 对账和占用管理；若长期无确定结果，任务仍可能等待，不承诺一定收敛。
- 只修错误归类，不新增消息发送，不扩展失败自动换号，也没有解决 ACK 超时被包装 SEND_FAILED、LID/会话恢复等其他问题。
- 既有最终成功/失败状态遇到迟到旧 UNKNOWN，不重开历史任务。
- 后续部署需独立执行；回滚只回退本次源文件改动，不能批量重置未知数据。
