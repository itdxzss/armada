# staging-accept

Armada 测试环境的最小持久验收 Runner。它只负责串行托管本机可信命令，保存状态、日志和报告；
不会在进程内实现 Git、SSH、Kafka、Redis 或 Playwright 逻辑。

## P0 边界

- 一个 Go 二进制，一个 SQLite 数据库，一个 daemon。
- stage 严格串行，遇错即停；不做 DAG、并行、插件、Web UI 或自动重试。
- CLI 与 daemon 共享本地 SQLite，不开放 HTTP 端口。
- 每个 stage 使用 argv 直接启动，不由 Runner 拼接 shell 字符串。
- 超时或取消时对整个子进程组执行 `TERM`，宽限 2 秒后执行 `KILL`。
- 每次 attempt 使用独立日志，单个日志最多 10 MiB。
- daemon 会为终态生成 `summary.json`、`report.md` 和 `checksums.sha256`；若恰好在落证据时退出，重启后补完。
- daemon 异常退出后，原 `RUNNING` stage 记为 `INTERRUPTED`，run 记为
  `FAIL/RUNNER_INTERRUPTED`；显式 `resume` 才从首个未通过 stage 继续。

`safety: read-only` 是计划声明，不是操作系统沙箱。Plan 是受信任的本机输入，daemon 必须使用
专用非 root 用户运行；P0 不用于真实 WhatsApp 写操作。日志脱敏也是尽力而为，stage 不应主动
输出凭据，plan/argv 中不得放密码或 token。

## 本地运行

需要 Go 和 C 编译器。当前 SQLite driver 使用 CGO，因此 Linux 二进制应在 Linux/CI 上构建，
不能直接用简单的 `GOOS=linux` 从 Mac 交叉编译。

```bash
cd armada-deploy/staging-accept
go test ./...
go build -o staging-accept .

./staging-accept run \
  --state-dir /tmp/staging-accept-state \
  --plan examples/local-smoke.json

./staging-accept serve \
  --state-dir /tmp/staging-accept-state \
  --once

./staging-accept status --state-dir /tmp/staging-accept-state
./staging-accept report --state-dir /tmp/staging-accept-state <run-id>
```

Plan 中四个 revision 是声明的候选 full SHA，不是 Runner 自动观测到的部署版本。后续接入测试环境时，
应将现有 `deploy-test.sh --env <env> --check` 和版本核对脚本作为 stage 执行。

## Stage 运行上下文

Runner 启动 stage 时继承 daemon 的现有环境，并只替换以下三个 Runner 自有变量；不会改动或输出
其他环境变量，Plan schema 也不需要增加字段：

- `STAGING_ACCEPT_RUN_ID`：当前 run ID。
- `STAGING_ACCEPT_STAGE_ID`：当前 stage ID。
- `STAGING_ACCEPT_RUN_DIR`：当前 run 证据目录的绝对、规范化路径。

同一 stage 失败后显式 `resume`，新的 attempt 会继续获得相同的三个值。可信 wrapper 可以用
`STAGING_ACCEPT_RUN_DIR` 定位本次运行并写入已脱敏的 stage 产物；不得枚举或打印完整环境，凭据仍应
仅通过受控的 service 环境提供。

## test1 页面 smoke wrapper

`wrappers/ui-smoke.sh` 是 Runner 调用 Playwright 的固定、无参数入口。正式模式只使用
`/var/lib/staging-accept/workspace/wheel-saas-pure-web`、`/usr/local/bin/pnpm` 和已下载到
`/var/lib/staging-accept/.cache/ms-playwright` 的 Chromium；测试目标只允许
`http(s)://armada.65.2.123.53.nip.io/`，且 `ENVIRONMENT` 必须为 `test1`。

凭据保存在 `/etc/staging-accept/ui-smoke.env`，文件必须是 `root:staging-accept`、权限 `0640`，
并且只能包含以下四个键（值可用一对单引号或双引号包裹，内容不会做 shell 展开）：

```dotenv
ENVIRONMENT=test1
ARMADA_E2E_BASE_URL=http://armada.65.2.123.53.nip.io/
ARMADA_E2E_USERNAME=<dedicated-test-user>
ARMADA_E2E_PASSWORD=<dedicated-test-password>
```

```bash
sudo install -d -m 0755 /usr/local/libexec/staging-accept
sudo install -m 0644 wrappers/ui-smoke.lib.sh /usr/local/libexec/staging-accept/ui-smoke.lib.sh
sudo install -m 0755 wrappers/ui-smoke.sh /usr/local/libexec/staging-accept/ui-smoke
sudo install -d -m 0750 -o root -g staging-accept /etc/staging-accept
sudoedit /etc/staging-accept/ui-smoke.env
sudo chown root:staging-accept /etc/staging-accept/ui-smoke.env
sudo chmod 0640 /etc/staging-accept/ui-smoke.env
```

Plan 中只放 wrapper 的绝对路径，不放用户名、密码或 URL。Runner 提供的
`STAGING_ACCEPT_RUN_DIR` 经过规范路径、直属子目录和 symlink 检查后，wrapper 会新建
`ui-smoke.XXXXXXXX/`，将 Playwright 的 `test-results` 和 HTML 报告都限制在该目录中。执行命令固定为
`pnpm exec playwright test e2e/smoke.spec.ts --browser=chromium --reporter=line,html`，失败退出码原样返回。

```json
{"id":"ui-smoke","command":["/usr/local/libexec/staging-accept/ui-smoke"],"timeoutSeconds":300}
```

```bash
bash wrappers/ui-smoke.test.sh
```

## 控制命令

```text
staging-accept run --plan PLAN.json [--state-dir DIR]
staging-accept serve [--once] [--state-dir DIR]
staging-accept status [--json] [--state-dir DIR] [RUN_ID]
staging-accept report [--state-dir DIR] RUN_ID
staging-accept cancel [--state-dir DIR] RUN_ID
staging-accept resume [--state-dir DIR] RUN_ID
```

`cancel` 对排队任务立即落终态；证据由 daemon 空闲或当前 stage/run 结束后的下一轮补齐，因此
另一个最长 24 小时的 stage 正在运行时，报告可能延后，但 `status` 会立即显示 `CANCELLED`。
运行中任务最多在一秒内被 daemon 发现并终止。`resume` 只接受证据已经落完的 `FAIL`，已经
`PASS` 的 stage 不会重跑，失败或中断的 stage 会生成新的 attempt 日志。

`report` 只验证现有 checksum manifest 并重建人类可读报告，不会重新计算 manifest；日志或摘要被
修改后会直接失败。这里用于发现意外改动，不是数字签名，拥有状态目录写权限的人仍应视为可信运维者。

## Linux systemd

现有 `deploy-test.sh` 使用文件白名单，不会自动安装本目录。P0 先采用独立安装：

```bash
sudo install -m 0755 staging-accept /usr/local/bin/staging-accept
sudo useradd --system --user-group --home /var/lib/staging-accept --shell /usr/sbin/nologin staging-accept
sudo install -m 0644 systemd/staging-acceptd.service /etc/systemd/system/staging-acceptd.service
sudo systemctl daemon-reload
sudo systemctl enable --now staging-acceptd.service
```

systemd 使用 `StateDirectory=staging-accept` 创建 `/var/lib/staging-accept`。运行证据位于：

```text
/var/lib/staging-accept/
├── runner.db
├── runner.lock
└── runs/<run-id>/
    ├── plan.json
    ├── events.ndjson
    ├── summary.json
    ├── report.md
    ├── checksums.sha256
    └── stages/.../attempt-N.log
```

状态目录权限为 `0700`，数据库和证据文件为 `0600`。

P0 尚未实现自动保留期。安装到 Linux 后应对 `/var/lib/staging-accept` 配置磁盘告警，并按 run
目录人工归档或清理；不要在 Runner 运行期间直接修改某个 run 的证据文件。

当前门禁在 macOS 完成。部署到测试环境前仍必须在目标 Linux 上验证 CGO 构建、systemd 自动
拉起，以及 `KillMode=control-group` 在 daemon 被 `kill -9` 后能清理整个 stage 子进程组；
本地进程组测试不能替代这一步。
