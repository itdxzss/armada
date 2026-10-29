# 新群拉人范围调整：perf2 发布记录

- 日期：2026-10-07；后端容器于北京时间 11:21:42 启动，随后完成制品验证及深度检查。
- 用户授权：前后端主仓库 commit/push，部署至第二套环境 perf2。
- 范围：两个新群模式的人数范围放开到 1–50 人，间隔改为可配置非负整数范围；1–3 人与 10–15 秒仅保留默认值。

## 提交与源码

- 后端主仓库 `/Users/daishuaishuai/IdeaProjects/armada`：`d15683e6aae0edc3361a1af7cceef76e40431913`。
- 前端主仓库 `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`：`5eed5ef994f64dbdc0d12f6116cdc465e423bd37`。
- 两仓均已推送 `origin/1.0.3-snapshot`，推送后本地与远端差异为 0/0。仅分别提交本次 7 / 8 个文件。
- perf2 已部署了其他会话尚未提交的账号批量隔离/提示补丁。本次从上述提交创建本地隔离构建副本，保留已在运行制品中核实的 4 个后端和 2 个前端源文件；未将这些文件混入本次提交，主仓库原修改保留。
- 比对发布前后 3245 个应用 class/资源：仅 `PullTaskNewGroupModeValidator.class` 变化，其余 3244 个相同，无增删。详见 `backend-diff.json`、`preserved-live-source.json`。

## 部署

- 在隔离构建副本运行仓库原脚本 `deploy-test.sh --env perf2 --all --yes`，使用 perf2 档案及前端副本路径，退出码 0。
- Backend / Frontend SUCCESS；Protocol / Zhuan SKIPPED。仅更新 `armada-perf` 的 backend / nginx。
- 部署编排、Dockerfile、Nginx 文件发布前与远端内容哈希相同；数据库、Redis、Kafka 及挂载目录仍使用 perf2 原配置。
- 后端本地、远端部署目录、运行容器 JAR SHA256：`aa2e01c1a2beb2e9ec2b3bf630c05e31d2548d66035c45f0d8c5b99af9bd8f3e`。
- 前端 618 个文件与远端部署目录一致；运行 Nginx 617 个静态文件一致，唯一差异为按“第二套环境”渲染的 `platform-config.json`。
- 首页本地、Nginx、公网 `http://3.110.124.52/` SHA256：`534557fb5c0e1b9a0193c97b8c64dff0e87462fd525f5332e1d848bd30de2918`。
- 两个容器 running，RestartCount=0；本次启动 ERROR=0。Flyway 校验 216 个迁移，schema V213，日志确认不需要迁移。
- 首页/平台配置 HTTP 200；未登录接口 HTTP 401 / code 40104。新人数与间隔文案已存在于实际发布资源中，旧硬限制文案已消失。
- `deploy-test.sh --env perf2 --check` 退出码 0：Armada、Baileys、Kafka、Zhuan、Cross-component 全部通过；各消费组 Stable，输出“只读深度检查通过”。协议服务仅只读检查，未部署或重启。

## 验证边界

- 前端相关 189 条、后端本次相关 28 条通过；前端类型检查、lint、构建通过。
- 扩大后端回归两项既有错误在独立未修改基线复现；本次未修改。生产离线包测试仍因缺失 `prod/scripts/inspect-production-host.sh` 失败；本次为测试环境发布，不使用生产打包路径。
- 未创建真实群或执行真实拉人，部署和制品验证不等同 WhatsApp 实际拉人验收。

## 回滚与证据

- 远端回滚目录：`/home/app/armada-deploy/release-backups/pull-limits-20261007-d15683e6`，含旧 JAR、前端压缩包、编排文件及镜像身份。
- 旧镜像标签：`armada-backend:before-pull-limits-20261007-d15683e6`、`armada-nginx:before-pull-limits-20261007-d15683e6`。
- 构建及部署日志：`/tmp/new-group-pull-limits-release/`；本目录 `runtime.json` 为脱敏验证结果。
