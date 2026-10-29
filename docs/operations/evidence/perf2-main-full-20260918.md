# 第二套环境四组件主仓库部署 — 2026-09-18

用户明确要求将主仓库前后端、Web 与安卓协议部署至第二套环境。执行范围为四个主仓库当前工作区，均包含未提交修改；未 fetch/pull、未创建 worktree、未 commit/push。

## 来源与命令

四仓库分支均为 `1.0.3-snapshot`：后端 HEAD `6aa319cd`、前端 `23f8476c`、Web 协议 `ce1877c`、安卓协议 `2779836`。上述 HEAD 仅为基线，不代表包含本次脏工作区的完整制品。

从 `armada/` 执行：

```sh
CI=true bash armada-deploy/deploy-test.sh --env perf2 --full -y
CI=true bash armada-deploy/deploy-test.sh --env perf2 --check
```

两条命令退出码均为 0；Protocol、Zhuan、Backend、Frontend 全部 SUCCESS。只读深度检查 Armada、Baileys、Kafka、Zhuan、Cross-component 全部通过。

## 验证证据

- 发布前后端聚焦回归 173 项通过，Web 协议 116 项通过，安卓协议 `internal/armada`、`api/service`、`internal/service/node/nodes`、`internal/service/groupidentity` 测试通过。
- 部署脚本语法和 `deploy-test.test.sh` 通过。既有 `package-prod.test.sh` 因缺少 `prod/scripts/inspect-production-host.sh` 失败；本次没有使用生产离线包路径。
- 后端、前端、Web 协议本地构建成功；Web 远端构建和安卓远端镜像构建成功。
- 本地、远端磁盘、运行容器 `/app/app.jar` SHA-256 一致：`02537771bcbe98d67d9e6ef52f220a243a6656e6a82faf5ba0b0d3360136bea6`。
- Flyway 启动日志确认 `armada_perf` 从 V200 成功应用 V201、V202、V203、V204；07:24:36 UTC 启动完成。
- 后端和 nginx 均 running，RestartCount=0，启动于 07:24:15 UTC；前端 HTTP 200。导出 API 未登录请求返回预期业务码 40104。
- 实际 HTTP 提供的 `AccountExportDrawer-DJOl5clu.js` 与本地构建完全一致，SHA-256：`4c2beb3227c94f07681e1a550b5be382b363930e95c2005b5c81814756a5bbb7`。
- Web 主进程及四个 worker 在线，Node.js 24，readyz 通过。
- 安卓主服务、callback、traffic-dashboard 均 running/healthy、RestartCount=0，运行镜像 `sha256:fd54887164ad738fa71d5d85e9feacb429d346cc22c20b4dd5faebe1834cbd54`。两个改动命令处理源码文件的远端 SHA-256 与主仓库一致。
- 八个目标 Kafka 消费组检查均为 Stable，跨组件连通性通过。

部署日志仅保留在本机 `/tmp/perf2-full-deploy-20260918.log`，深度检查日志 `/tmp/perf2-full-deploy-check-20260918.log`。未执行真实账号导出删除、上线或消息发送验收；本记录证明制品生效、迁移及健康检查，不代表业务验收。
