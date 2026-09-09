# 变更记录：test1 拉群板块问题池与验收

- 日期：2026-09-03
- 范围：普通拉群、速拉群、普通建群基线、专用建群营销、Web 页面、Android 协议
- 类型：只读测试与审计
- 状态：已完成问题池；验收结论 BLOCKED

## 目标

以真实 test1 任务和约五分钟时间窗建立拉群问题池，核对状态收敛、重试副作用、资源收口、全链追踪和
Web/Android/混合协议一致性。

## 已完成

- [x] 只读抽取普通拉群 #193/#194 的 execution、action、command、Outbox 和协议结果。
- [x] 对 execution 427/429/431 完成 61 次、约五分钟状态采样。
- [x] 只读核对普通建群 #127/#140 和速拉群 #54 的父子状态、command 与 Outbox。
- [x] 核对终态普通拉群 #195~#198 与速拉群 #51/#54 的资源释放样本。
- [x] 审计普通拉群、速拉群/建群营销、Web 和 Android 的状态机、幂等、回调与可观测性。
- [x] 运行普通拉群诊断脚本测试和 Web 相关定向测试。
- [x] 形成 `docs/operations/2026-09-03-group-task-test1-acceptance-pool.md`。

## 未执行与原因

- 未创建真群或发送营销消息：需要明确的账号、联系人、群和触达授权。
- 未执行专用建群营销 test1：当前 `group_creation_marketing_task` 为 0 行。
- 未运行 Java/Go 聚焦测试：本机缺 Java 17 和 Go。
- 未部署：dry-run/check 分别受 Java 17 和 Android fleet 配置缺失阻断。
- 未执行已登录 UI smoke：当前浏览器会话无 test1 登录态。

## 结果

当前验收不通过。已确认普通拉群状态循环、审批无法自动恢复、普通建群长期卡住和父子状态不一致；营销链路
仍有 UNKNOWN 换新 command、建群状态无 watchdog、Stop/outbox 栅栏和跨协议语义差异等阻断项。

本记录没有修改生产代码、test1 数据或部署状态。
