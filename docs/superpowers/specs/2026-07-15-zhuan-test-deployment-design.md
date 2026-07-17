# Zhuan 测试环境部署脚本设计

## 背景

Armada 的 `armada-deploy/deploy-test.sh` 已支持后端、前端和 Baileys 协议层，但尚不能部署 Android Zhuan 协议。Zhuan 源码位于同一工作区的 `whatsapp-server-feature-android-zhuan`，测试实例运行在 Armada 测试机 `ubuntu@65.2.123.53`，部署根目录为 `/home/app/whatsapp-android-zhuan-deploy/src`。

远端当前使用 `deploy/docker-compose.yml` 管理以下服务：

- `redis-zhuan`
- `callback-zhuan`
- `whatsapp-android-zhuan`

三个服务当前均为 running/healthy。Zhuan API 只监听远端 `127.0.0.1:8001`。

## 目标

在现有测试部署脚本中加入 Zhuan 部署能力，同时保持已有命令兼容：

- 新增 `--zhuan`，只部署 Zhuan。
- `--full` 部署后端、前端、Baileys 协议层和 Zhuan。
- `--all` 继续只部署后端和前端。
- 支持 `--dry-run`、确认提示以及仅 Zhuan 场景的 `--logs`。
- 保留并保护远端运行时配置、日志和持久化数据。

## 命令与配置

脚本新增 Zhuan 构建标志，并通过以下环境变量允许覆盖默认值：

- `ARMADA_ZHUAN_DIR`：本地 Zhuan 仓库，默认 `${WORKSPACE_ROOT}/whatsapp-server-feature-android-zhuan`。
- `ARMADA_ZHUAN_DEPLOY_HOST`：默认继承 Armada 测试机地址。
- `ARMADA_ZHUAN_DEPLOY_USER`：默认继承 Armada SSH 用户。
- `ARMADA_ZHUAN_DEPLOY_KEY`：默认继承 Armada SSH 私钥。
- `ARMADA_ZHUAN_DEPLOY_REMOTE_DIR`：默认 `/home/app/whatsapp-android-zhuan-deploy/src`。

`--branch` 仍只决定 Armada 后端和部署编排文件的来源，不切换或拉取 Zhuan 仓库。Zhuan 与前端、Baileys 协议层一致，使用环境变量所指向的本地当前工作区，因此未提交修改也会被纳入部署。

## 部署流程

当范围包含 Zhuan 时，脚本执行以下流程：

1. 校验本地 Zhuan 目录及 `go.mod`、`go.sum`、`deploy/Dockerfile`、`deploy/docker-compose.yml` 等构建文件。
2. 使用 Zhuan SSH 配置检查目标服务器连通性。
3. 检查远端 `deploy/.env` 和 `deploy/configs/prod_configs.toml` 存在，但不输出文件内容。
4. 创建远端源码目录，并使用 rsync 同步本地工作区。
5. 在远端执行 `sudo docker compose config --quiet`。
6. 构建 `whatsapp-android-zhuan` 镜像并启动 `redis-zhuan`、`callback-zhuan`。
7. 运行一次性 `whatsapp-migrate -env prod` 数据库迁移。
8. 启动或重建 `whatsapp-android-zhuan`。
9. 检查三个容器均为 running/healthy，并通过远端 `127.0.0.1:8001/swagger/index.html` 验活。

`--dry-run` 只打印上述动作，不连接 SSH、不构建、不同步、不迁移、不重启。交互确认继续覆盖整个部署请求，避免 `--full` 中途多次确认。

## 同步安全边界

rsync 以本地 Zhuan 仓库根目录为来源，并使用删除同步保持代码一致，但必须排除并保护以下内容：

- `.git/`、`.idea/`、本地缓存和构建产物。
- `deploy/.env`。
- `deploy/configs/prod_configs.toml`。
- `deploy/logs/`、`deploy/callback-logs/`。
- 根目录 `.env`、`configs/*.toml`、`*.pem`、`*.key`、`*.log` 和压缩包。

同步排除规则应与 Zhuan 仓库现有 `.dockerignore` 的安全边界一致；脚本仍显式列出关键远端运行时路径，避免未来 `.dockerignore` 调整时误覆盖凭据。

排除项不得配合 `--delete-excluded`，确保远端凭据与运行数据不会被删除。Redis 使用命名 Docker volume `whatsapp-android-zhuan-redis-data`，源码同步和 Compose 重建均不得删除该 volume。

## 失败处理

脚本延续 `set -euo pipefail` 和现有 `die` 行为，任一步失败即停止后续操作：

- 缺少本地构建文件时，在 SSH 前失败。
- 缺少远端 `.env` 或生产配置时，在同步和重启前失败。
- Compose 配置、镜像构建或迁移失败时，不启动新主服务。
- 容器未 running/healthy 或 HTTP 验活失败时，部署返回非零状态。

本次不自动回滚镜像或数据库迁移。失败时保留 Compose 状态与日志，供人工诊断；这与现有测试环境部署脚本的处理方式一致。

## 日志语义

`--logs --zhuan` 在部署成功后执行 `sudo docker compose logs -f --tail 120 whatsapp-android-zhuan`。`--full --logs` 保持当前优先跟随后端日志的行为，避免同时启动多个无限日志流。

## 测试策略

扩展 `armada-deploy/deploy-test.test.sh`，至少覆盖：

- 帮助和部署指引包含 `--zhuan` 及 Zhuan 环境变量。
- `--zhuan --dry-run` 只显示 Zhuan，不触发后端、前端或 Baileys 协议层计划。
- `--full --dry-run` 包含 Zhuan，`--all --dry-run` 不包含 Zhuan。
- 默认本地目录、远端目录和 SSH 继承关系正确。
- 脚本包含远端配置存在性检查。
- rsync 排除远端 `.env`、生产配置和日志，且不使用 `--delete-excluded`。
- 远端流程包含 Compose 校验、依赖启动、迁移、主服务启动和健康检查。

验证阶段运行 shell 测试、`bash -n`，并执行 `--zhuan --dry-run`。本任务只修改本地脚本和测试，不实际部署或重启测试环境。
