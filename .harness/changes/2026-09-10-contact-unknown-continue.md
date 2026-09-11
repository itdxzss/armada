# 通讯录单条 ACK 未知后继续其他联系人

## 用户要求与范围

用户明确纠正：一个目标没有服务器 ACK，不能让整个账号的其他联系人都不发。沿用当前主仓 `1.0.3-snapshot` 和第一套测试环境的已授权修复、部署范围；保留既有未提交改动，不重放历史 UNKNOWN 或自动恢复旧 SKIPPED。

## 设计

- 明确单条结果未知（`SEND_RESULT_UNKNOWN`、`UNKNOWN`、`EMPTY_MESSAGE_ID` 及既有无编号未知结果）仍落收件人 `UNKNOWN`，不计成功、不重试本条、不停止账号或跳过其他 PENDING。
- 明确账号故障及其余未在本次范围内的准备错误保留停止策略；矛盾事件的通用 `outcome=UNKNOWN` 不覆盖明确账号错误。
- 复用现有调度 SQL：只有 PENDING 可认领，有 SENDING 时同号不再认领；UNKNOWN 释放当前等待名额，下一轮重新检查账号真实可发送状态后处理下一人。固化收件人数继续约束每号上限，不按成功数补发。
- 迟到送达/已读按原命令、目标、消息 ID 幂等修正 UNKNOWN，不影响已经在发送的下一人。
- 排干但含 UNKNOWN 的任务账号按处理完成收尾，保留明细未知和已有账号状态快照，不仅因零成功就判失效；真正已因账号故障停止的 FAILED 行不翻转。迟到成功同步修正平均发量。
- 不增加表、列、Flyway、Redis 或 API 字段，不改协议加密和真实发信入口。

## 验证与发布

- 行为红测：Receipt H2 30 项中 9 个预期失败；旧停止账号文案另有 1 个预期失败。收尾 H2 10 项中 5 个预期失败，均在修复前复现。
- 最终真实 Maven 回归 `ContactTask*Test,ContactCloudAudienceResolutionTest,CloudStatusAudienceCollectorTest`：196 项通过，失败/错误/跳过均 0。其中 Receipt H2 31 项、Unknown Settlement H2 10 项，真实 Mapper 验证原条不再认领、下一条实际认领、第二条 SENDING 阻止第三条抢发、迟到回执幂等及跨租户隔离。日志 `/private/tmp/contact-ack-unknown-final-green.log`。
- XML 校验与 `git diff --check` 通过；独立 expert-reviewer 已审完整生产 diff、调度器与最终测试证据，无阻断项。
- 协议只读审查确认 contact UNKNOWN 不阻塞下一命令、锁正常释放、重试关闭；崩溃恢复 UNKNOWN 可以没有 messageId，后端同样终结本条。无需更新协议节点。
- 对允许继续的 UNKNOWN 使用后端业务提示“发送结果未知，本条不重试，继续处理其他联系人”，保留 reasonCode/messageId，避免沿用旧协议“停止该账号”的过期文字；真实账号错误提示不改。
- 09:47:29 部署前只读检查：后端稳定，通讯录 SENDING=0，三测试账号 login_state=1/account_state=2；最新仍为历史 task 4，没有新任务。部署计划明确 test1、仅后端、当前主仓 HEAD 229e26f2 加本任务未提交补丁。
- 2026-09-10 北京时间 09:51 第一套仅后端发布成功，日志 `/private/tmp/contact-unknown-backend-deploy-20260910.log`；运行中 jar SHA-256 与本地构建一致：`6b0fe879afe3ee2c93503bf4ae6496154cdbc2243f3c969035d954b9883cf33d`。运行镜像 `sha256:ee7ec238f9f6aedaafa96d6e84f95eb67b4d15e9ba72cd148346830e64656f5f`，容器 running、restartCount=0，部署 API 健康检查通过。
- 09:51:24 发布后只读检查及正常登录账号查询通过：1557、1558、1559 均 ONLINE（login_state=1/account_state=2），通讯录 SENDING=0；最新仍为历史 task 4，task 3/4 各 FAILED=2、UNKNOWN=1、SKIPPED=20，与部署前一致。没有新增任务、重新提交历史 UNKNOWN 或恢复 SKIPPED；本次未新增 WhatsApp 实发验证，不将 H2 验证等同于实发送达验证。本次未 commit/push。

## 回滚

部署前备份位于 test1 `/home/app/contact-unknown-backup-20260910-014456`，镜像标签 `armada-backend:before-contact-unknown-20260910-014456`，原运行 jar SHA-256 为 `a5ce4be7b1e3d94952f8fca8994201317a679d71604aac826bced261daf2b115`。仅回滚本次后端制品，不修改协议节点、账号会话或历史任务明细。
