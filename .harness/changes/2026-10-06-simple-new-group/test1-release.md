# 第一套环境发布记录

日期：2026-10-06（Asia/Shanghai）。用户明确授权：迁回主目录、commit/push、删除本次 worktree，并先部署第一套环境。

## 主目录与提交

全部提交在各自主目录的 `1.0.3-snapshot` 分支，均已普通 push 并核对与远端无差异。

| 仓库 | 功能提交 |
| --- | --- |
| armada | 3cfc3823 |
| wheel-saas-pure-web | c3df6e2c |
| armada-protocol | 5f8a4e5 |

逐文件核对 worktree 基线及主目录内容后迁移，保留主目录其它会话、运行 PID 和历史证据。源码、测试、文档、截图均已入主目录提交；本次 `new-group-simple-20261006/{armada,web,protocol}` 三个 worktree 和各仓库对应临时分支已删除。其它 worktree 未清理。

## 发布范围与步骤

- 环境：test1，页面环境标识为“第一套环境”；数据库 schema 为 armada。
- 只发布后端、前端和 Web/Baileys 协议层；Android/Zhuan 未部署，perf2 未部署。
- 使用主目录当前提交，未使用 `--branch` 创建任何临时 worktree。
- `deploy-test.sh --env test1 --check`：发布前、发布后均退出 0。
- `deploy-test.sh --env test1 --protocol -y`：退出 0，Protocol SUCCESS。
- `deploy-test.sh --env test1 --all -y`：退出 0，Backend / Frontend SUCCESS。
- 已先备份当前制品、配置和镜像；发布前确认远端编排资产与主目录相同。

## 已验证事实

- 运行中后端 JAR 与本地主目录构建 SHA-256 一致：`0a034752e4cefeb59c1704439cf99fd09830d0941f2d33b235c8efcef76955c0`。
- 后端、Nginx 为本次新容器，均 running，RestartCount=0。
- Flyway 日志显示成功校验 215 份迁移，本次成功执行 V213；只读查库确认 V213 success=1、失败迁移数为 0。
- `pull_task.creation_mode` 仍为 VARCHAR(32) / NOT NULL / 默认 PASTED_LINK，注释包含 SIMPLE_NEW_GROUP。
- 公网首页及含 SIMPLE_NEW_GROUP 的 5 份 JS 均 HTTP 200，逐份 SHA-256 与本地构建一致；详见 `test1-public-artifacts.json`。
- 公网环境标识为“第一套环境”，未登录 API 返回 HTTP 401 / 业务码 40104。
- Web 协议 PM2 与 readiness 检查通过；修改的源码及生成 JS 与本地哈希一致。
- 后端 `.env`、协议 `.env`、原有 Compose/Dockerfile/Nginx/配置脚本哈希均保持一致。
- 部署后深度检查通过：Armada、Baileys、Kafka、Zhuan 和跨组件配置。

## 验证与限制

- 开发阶段后端聚焦 376 项、前端相关 187 项及独立评审记录见 verification.md。
- 迁回主目录后前端 typecheck、提交钩子 Prettier/ESLint/Stylelint 和发布构建通过。
- Web 协议在 Node 24.18.0 下重跑 5 套件 / 107 项通过，发布构建与远端 Node 24 PM2 检查通过。
- 部署脚本测试通过；生产离线包测试因仓库既有缺少 `inspect-production-host.sh` 未通过，本次不做生产离线包发布。
- 深度检查中 Kafka 工具仍有 TimeoutNegativeWarning，但各检查成功；未将其记录为无警告。
- 没有创建真实群或执行账号注销，部署成功不等同于业务验收。真实资料、加人权限、管理接管和可选注销仍需受控业务验证。

## 回滚材料

- 应用服务器：`/home/app/armada-deploy/release-backups/simple-new-group-3cfc3823/`，包含制品与配置备份，后端和 Nginx 镜像保留对应 rollback 标签。
- 协议服务器：`/home/ec2-user/armada-protocol/release-backups/simple-new-group-5f8a4e5/`，包含源代码、dist 和配置备份。
- 回滚前停止新模式创建并处理在途任务，保留所有群事实及注销账本。V213 只改注释，不执行反向 DDL；已发生的账号注销不能通过代码回滚恢复。

访问入口：http://armada.65.2.123.53.nip.io/
