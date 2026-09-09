# 变更记录：LID Status 本地最小实现

- 日期：2026-09-09。
- 需求：用户在可行性分析后同意继续；先实现单个自有受众的协议实测候选。
- Android 基线：`155fc10a7e50bb93dbf57d5a9761cabdad708fda`。
- 实现分支：`codex/lid-status-20260909`。
- 工作区：`/Users/daishuaishuai/IdeaProjects/.codex-worktrees/lid-status-20260909/android`。
- 状态：本地实现和验证完成；真实发送等待环境和明确自有接收账号，NOT_RUN。

## 结果与证据

[完整实现说明及验证记录](/Users/daishuaishuai/IdeaProjects/.codex-worktrees/lid-status-20260909/android/docs/status-lid-local-validation.md)。

- [x] 独立 Status 适配，受众不复用 mentions。
- [x] 保留 LID 及当前发送设备号，按完整 JID 查询设备。
- [x] 隐私名单保留身份域；空/异常隐私不按有效 contacts 发布。
- [x] 密文只包含本次确认设备，目标设备缺密文时不提交。
- [x] 编译标签隔离的单受众、单次、回环实验入口。
- [x] 普通与实验构建 vet/build，相关包测试及 5 个包定向 race 通过。
- [x] 全量测试均仅剩 `pkg/noise` 的 8 项失败，已在未修改原版本复现。
- [ ] 真实环境核对原失败任务、部署版本、outbox 与结果。
- [ ] App-State key 为 0 的单条 LID Status、ACK 及接收端显示实测。
- [ ] 实测成立后，再接入云联系人及后端批量动态任务受众。

## 边界

Java、Web、数据库结构和线上数据未修改。普通 Android 候选修改只存在上述独立 worktree；未 commit、push 或部署。实验入口不进入普通构建，不会自动登录账号或自动执行发送。

回撤方式：不合并、不部署该候选分支即可；不得清理其他任务的 worktree。当前没有需回滚的线上动作。
