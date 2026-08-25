# 变更记录：测试环境持久验收 Runner P0

- 日期 / 分支 / worktree: 2026-08-25 / `1.0.3-snapshot` / 主工作区
- 需求来源: 用户要求开始编写 Runner，并明确“不要过度设计”
- 状态: 已完成并部署到 `test1`

## 目标（一句话）

提供一个可由 systemd 常驻、在 stage 边界恢复、始终留下验收证据的最小串行 Runner，先承载本机可信只读命令。

## 缺口拆解 / 任务清单

- [x] Go CLI：`run / serve / status / report / cancel / resume`
- [x] SQLite 持久状态、单 daemon 文件锁、stage attempt 历史
- [x] 超时/取消时终止子进程组，日志限额与尽力脱敏
- [x] 终态摘要、报告、SHA-256 证据清单
- [x] 崩溃后保留 PASS stage，中断 stage 只允许显式恢复
- [x] 非 root systemd unit 与本地 smoke plan
- [x] 普通测试、race、vet 和本机构建门禁

## 关键设计决策

- P0 是“可信本机命令的串行托管器”，不是工作流平台；不做 DAG、并行、插件、Web UI 或自动重试。
- Runner 不内置 SSH、Git、Kafka、Redis、Playwright 或 WhatsApp 逻辑；后续复用现有脚本作为外部 stage。
- `safety=read-only` 只是 plan 声明，不是沙箱；依靠专用非 root 用户和最小系统权限限制影响面。
- 崩溃时正在运行的命令可能已有外部副作用，因此不承诺 exactly-once；恢复必须由操作者显式执行。
- plan 中四仓 full SHA 是候选版本声明，必须由后续版本核对 stage 才能成为部署观测证据。

## 验证（evidence-before-done）

```text
go test -count=1 ./...
ok  github.com/itdxzss/armada/armada-deploy/staging-accept  4.410s

go test -race -count=1 ./...
ok  github.com/itdxzss/armada/armada-deploy/staging-accept  7.411s

go vet ./...
PASS（exit 0）

go build -o /private/tmp/staging-accept-p0 .
PASS（exit 0）

关键状态机与证据回归 -count=10
PASS

全量 go test -race -count=3 ./...
PASS
```

独立审查最终通过，无阻断或重要发现。空/漏项 manifest、未登记证据、attempt SHA、取消竞态、
双崩溃恢复和 retry 尚未启动再次崩溃均有回归测试。

目标 Linux（Ubuntu 26.04 / amd64 / Go 1.26.0 / GCC 15.2.0）原生验证：

```text
go test -count=1 ./...       PASS
go test -race -count=1 ./... PASS
go vet ./...                 PASS
go build -trimpath .         PASS

linux-pass
20260825T044059Z-f3b197b1  PASS，2/2 stages，通过 manifest 校验

linux-timeout
20260825T044215Z-6aeb5763  FAIL / STAGE_TIMEOUT（预期），后续 stage 未运行，进程组已清理

linux-cancel
20260825T044259Z-6616db6f  CANCELLED / CANCEL_REQUESTED（预期），进程组已清理

linux-crash-resume
20260825T044423Z-f3715af7  kill -9 后先恢复为 FAIL / RUNNER_INTERRUPTED；
                           systemd 自动拉起，旧 cgroup 已清理；显式 resume 后 PASS；
                           已 PASS checkpoint attempts=1，中断 stage attempts=2
```

四个 run 的 `checksums.sha256` 均再次验证通过；状态目录为 `0700`，数据库和证据文件为 `0600`。

## 影响与变更

- 影响模块: `armada-deploy/staging-accept/`
- 数据库变更: 无业务数据库变更；Runner 自有 SQLite 状态库
- API 变更: 无
- Redis 变更: 无
- 关键约束: P0 只运行受信任 plan；不用于真实 WhatsApp 写操作
- 回滚方案: 停止并禁用独立 systemd unit，移除 Runner 二进制；不会影响 Armada 业务进程和业务库

## 部署

- commit: `3c156feddcd076f8a7c20afb1136a7a320478ee4`
- 环境: `test1`（`ip-172-31-13-65`）
- 安装: `/usr/local/bin/staging-accept` + `staging-acceptd.service`，专用 `staging-accept` 非 root 用户
- 二进制 SHA-256: `bb00193f5f605c2c4390e82224b87e978f696590784bc12040f0a0b4b1ec07a3`
- systemd: `enabled / active`，`Restart=on-failure`，`KillMode=control-group`
- 证据: `/var/lib/staging-accept/runs/<run-id>/`，本次验证占用约 `632K`
- 部署后验证: PASS、超时、运行中取消、kill -9 自动拉起、显式 resume 和 checksum 均符合预期

## 遗留 / 跟进

- P1 再把现有 deep-check、版本核对和 UI smoke 作为外部 stage 接入。
- 总存储保留/磁盘告警暂由主机运维负责，后续依据真实运行量再增加清理策略。
