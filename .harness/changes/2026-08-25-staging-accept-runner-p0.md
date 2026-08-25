# 变更记录：测试环境持久验收 Runner P0

- 日期 / 分支 / worktree: 2026-08-25 / `1.0.3-snapshot` / 主工作区
- 需求来源: 用户要求开始编写 Runner，并明确“不要过度设计”
- 状态: 已完成（仅本地，未部署）

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

## 影响与变更

- 影响模块: `armada-deploy/staging-accept/`
- 数据库变更: 无业务数据库变更；Runner 自有 SQLite 状态库
- API 变更: 无
- Redis 变更: 无
- 关键约束: P0 只运行受信任 plan；不用于真实 WhatsApp 写操作
- 回滚方案: 停止并禁用独立 systemd unit，移除 Runner 二进制；不会影响 Armada 业务进程和业务库

## 部署

- commit / 环境 / 部署后验证结果: 未提交、未部署；真实 Linux/systemd 验证待明确测试主机后执行

## 遗留 / 跟进

- 在已确认的测试 Linux 上验证 CGO 构建、systemd 自动拉起与 cgroup 子进程清场。
- P1 再把现有 deep-check、版本核对和 UI smoke 作为外部 stage 接入。
- 总存储保留/磁盘告警暂由主机运维负责，后续依据真实运行量再增加清理策略。
