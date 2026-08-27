# 变更记录：Staging Acceptance 六层验收

- 日期 / 分支 / worktree: 2026-08-25 / `1.0.3-snapshot` / 四仓主工作区
- 需求来源: 用户要求在 Runner 部署验证完成后，接入深检与四仓版本、Playwright、基础观测、双协议流量、受控 WhatsApp canary 和 1h/6h/24h soak
- 状态: 本地能力已实现，test1 分层验收进行中（Kafka FAIL；UI/Android/Canary BLOCKED）

## 目标（一句话）

让 test1 Runner 按只读检查、受控 canary、分级 soak 的顺序自动执行六层验收，并始终留下可恢复、可核验的证据。

## 缺口拆解 / 任务清单

- [x] 复用现有部署深检，并实现四仓 expected full SHA / artifact identity fail-closed 门禁
- [x] 修复登录验证码旧等待和菜单请求失败悬挂，增加 test1 Playwright 页面 smoke
- [x] 实现 Kafka lag、Redis、CPU、内存和容器/PM2 重启 start/peak/end 采集与 evaluator
- [x] 实现 Web/Android 分离流量、采集器健康、连续窗口和未归因门禁
- [ ] 为 Web/Android 各配置独立资源租约和受控 WhatsApp canary
- [x] 实现可恢复 Runner、阶段上下文和 1h/6h/24h 证据语义
- [ ] 接通所有远程 observer、逐级执行 1h/6h/24h，并生成最终汇总
- [x] 四仓变更均已提交并推送（Android 代码尚未部署到 3 个节点）

## 关键设计决策

- 六层按风险递增执行；版本或采集证据不完整时 fail closed，不继续真实 WhatsApp 动作。
- Playwright 使用确定性脚本和有限超时；AI 只分析 trace/截图，不承担持续手点。
- Web/Android 流量分开统计；协议 payload 计量不冒充云网卡或代理商账单。
- 真实 WhatsApp canary 只允许专用测试账号、测试群、固定动作预算；账号异常、限流或归属漂移立即停止。
- 1h、6h、24h soak 使用持久 Runner 分段执行；前一级未通过不启动后一级。

## 验证（evidence-before-done）

初始基线：

```text
./armada-deploy/deploy-test.sh --env test1 --check
Armada: PASS
Baileys: SSH connect timeout from local workstation
overall: FAIL (exit 255)

Runner host -> Web protocol public port 22: reachable
Runner host -> Web protocol port 8080: timeout
Runner host -> Android coordinator /healthz: HTTP 200
```

2026-08-25 当前验证：

```text
bash armada-deploy/deploy-test.sh --env test1 --check
Armada: PASS
Baileys: PASS
Zhuan coordinator/fleet connectivity: PASS
Cross-component: PASS
Kafka exact metadata: SKIPPED（由新 collector 补足，不能据此放行）

Runner systemd: active
Runner Linux smoke / timeout / cancel / kill-9 + explicit resume: PASS
Runner evidence checksum/tamper and credential redaction: PASS

Kafka（真实 test1 offset，只读）：
armada.protocol.account.commands.v1 / armada-protocol-master-commands: lag=0
protocol.web.normal-group.commands.v1 / protocol-web-normal-group-commands: lag=0
protocol.account.state.events.v1 / armada-api-account-state-events: lag=0
protocol.account.group-sync.events.v1 / armada-api-account-group-sync-events: lag=42394（FAIL）
armada.protocol.group.events.v1 / armada-api-group-events-staging: lag=0
protocol.normal-group.events.v1 / armada-api-normal-group-results: lag=0
protocol.account.group-sync.events.v1.DLT: retainedRecords=687

后端/Runner 宿主机：7.55 GiB，available≈4.02 GiB，无 swap；load≈0.53/0.68/0.79
armada-backend: CPU≈27.69%，memory≈1.254 GiB
zhuan-native-probe-mysql: 703.5/768 MiB（91.60%，风险）

Web 宿主机：7.55 GiB，available≈2.40 GiB，无 swap；load≈0.55/0.54/0.45
PM2 7 个进程均 online；当前 restart counter 作为 soak 起点，不把历史累计值冒充本轮重启
Web traffic /api/health: ok=true, workers=5, expectedWorkers=5

Android 三节点新 GET /ws/v1/traffic/snapshot: HTTP 404（c66ca97 尚未部署，BLOCKED）
UI secret alias /etc/staging-accept/ui-smoke.env: 不存在（BLOCKED）
```

2026-08-26 本地提交门禁：

```text
Go Runner: go test ./... -> PASS
Python collector: 29 tests -> PASS
Python evaluator: 22 tests -> PASS
Backend Docker snapshot: 5 tests -> PASS
Backend observer: 9 tests -> PASS
test1 quick: 20 tests -> PASS
test1 soak: 9 tests -> PASS
CloudWatch observer: 4 tests -> PASS
Runtime manifest: 5 tests -> PASS
Runner deep-check client: 5 tests -> PASS
Runtime observer client: 7 tests -> PASS
Web observer bridge: 10 tests -> PASS
Redis collector: 3 tests -> PASS
Kafka collector: 10 tests -> PASS
preflight shell contracts: PASS
ui-smoke shell contracts: PASS
```

群快照 Kafka 结论：`max.poll.records=1` 已消除批量 poll/rebalance 风险，但完整 listener 仍在一个长事务中执行
旧兼容逐群 N+1 与共享群行锁；400 群约 1,600 条以上 SQL，4 个 consumer 对共享群串行并触发锁等待。当前
约 10 条/分钟的排空速度与 42k 积压不满足 soak 放行条件；详见本轮只读诊断，后续应另开集合化修复，不在
验收控制面中掩盖或调大 timeout。

## 部署

- Runner: `3c156feddcd076f8a7c20afb1136a7a320478ee4` / `test1` / systemd 与 Linux crash-resume 已验证。
- 后端: `95562d64d7cd7cbb8bb0235dded692c781a39bdc` / `test1` / 深检通过；后续控制面 commit 不改变应用逻辑。
- 前端: `0bec41d0dcc2c89dad25ae5167eb6a22f7f107fa` / `test1` / 容器、首页、配置与构建验证通过；真实登录待 secret alias。
- Web 协议: `14017901177527757d8e56922475e9c461e4c547` / `test1` / 5 worker + dashboard online，traffic health PASS。
- Android 协议: `c66ca97aad18a49ce59a0aab762c70c58d13fee1` / `test1` / 已 push；3 节点 SSH/SSM 通道不可用，未部署。

## 遗留 / 跟进

- 真实 canary 的账号、群、代理和允许动作需要从 test1 现有资源中解析为 alias；不能在代码、plan 或报告中保存凭据与原始 JID。
- UI smoke 只接受 root:`staging-accept` 0640 的 secret alias 文件；聊天、plan、argv、trace、截图和报告都不得保存密码。
- Android 节点部署需要恢复 AWS session 后使用 SSM/EC2 管理通道，或恢复 test1 到三节点的受限 SSH；不复制个人 PEM 到 Runner。
- 四仓 runtime manifest 只有 coordinator + node-01/node-02/node-03 全部匹配 expected full SHA 才可 PASS；当前必须 BLOCKED。
- Kafka 群快照 lag 与 DLT 属产品数据面 FAIL。修复并排空前，不启动真实 WhatsApp canary 或正式 1h/6h/24h soak。
