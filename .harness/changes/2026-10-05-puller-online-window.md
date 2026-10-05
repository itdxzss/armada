# 变更记录：拉手短暂离线保留进度，上线续跑

- 日期 / 分支 / worktree：2026-10-05，`codex/puller-online-window-20261005`，独立 worktree `puller-online-window-20261005`。
- 需求来源：用户要求使用反复抢登、反复上线的账号完成业务，并明确“调整代码吧”。
- 状态：代码与本地验证已完成，未部署。

## 目标

保留已分配拉手，谁当前在线就使用谁；暂离线等待，重新上线从已有业务进度继续。

## 改动范围

- 临时离线保留角色、账号占用和粘性分配，不写入 `PULLER_REPLACED`。
- 联系人和拉手进群按当前在线账号推进；明确未执行的离线失败复用动作，生成新的命令和尝试序号，拒绝旧尝试回调。
- ONLINE 复核账号实际拉群资格，恢复原角色，并提前因离线产生的等待；保留真实业务间隔。
- 临时离线不提前结算已提交的批量拉人请求；成功结果和原命令身份沿用。
- 已确认未执行的临时离线不消耗实际业务重试预算；结果不确定及真实业务失败仍受原重试限制。

## 关键决策

沿用原调度器、Outbox、账号自动上线和逐号码结果台账；不新增稳定在线时长、抢登等待期、配置或数据库列。封禁、解绑、业务受限和已移出角色继续按原规则处理。历史 `PULLER_REPLACED` 不自动复活，避免恢复已被其他账号接替的名额。

## 验证

- 测试使用 Java 17、现有 ByteBuddy agent，以及 H2 / 真实 MyBatis Mapper / 租户插件 / Spring 事务。
- 修改前已复现：短暂离线丢失名额和粘性分配、联系人检查点越过、原账号离线阻塞其他在线账号、无关在线候选错误唤醒，以及第二次请求尝试号不匹配。
- 最终 31 个相关测试类共 390 项通过，0 failures / 0 errors / 0 skipped；包含连续五次未执行离线后的第六次派发、混合真实失败仍保留退避、跨租户、暂停/终态、旧回调和成功事实保护。
- 实际命令：在 `armada-api` 使用 Java 17 执行 `mvn -Dtest="$(cat /tmp/puller-window-final-selection.txt)" -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar test`。最终日志 `/tmp/puller-window-verified-tests.log`：`Tests run: 390, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，耗时 11.795 秒。
- 新预算子查询曾放大深层条件的 SQL 解析耗时；通过等价展开条件、提前裁剪确定的结果类型分支修正，没有关闭租户拦截。真实 BoundSql 经生产租户拦截器的独立测量：料子 / 站台查询由 1612 / 1368 毫秒降至 74 / 31 毫秒。优化后完整回归由 8 分 23 秒降至约 12 秒。
- 四个变更 Mapper XML 的 `xmllint --nonet --noout` 和 `git diff --check` 通过。未做线上真实 WhatsApp 业务验收。

## 部署

用户后续明确授权合并到主仓库 `1.0.3-snapshot`、提交推送并删除本次独立 worktree。主仓库原有未提交修改保留，本次提交仅包含本修复。

尚未部署；未修改线上账号、任务或历史移出记录。
