# 第二套环境主仓库发布：2026-10-05

## 结果

- 用户授权：把当前主仓库代码部署到第二套环境。
- 执行 `bash armada-deploy/deploy-test.sh --env perf2 --all -y`，退出码 0。后端、前端 SUCCESS；Web/Android 协议未重新部署。
- 新容器于北京时间 17:41:15 启动。后端和 nginx 均 running、restart count 为 0，启动日志未发现迁移或应用启动失败。
- 部署前后 `--env perf2 --check` 均退出码 0，Armada、Baileys、Kafka、Zhuan、跨组件检查全部通过。

## 来源与制品

- 按用户要求直接构建主目录实际文件，包含当前未提交和未跟踪的生产源码，不 fetch/pull、不创建 worktree、不 commit/push。
- 后端：`armada`，分支 `1.0.3-snapshot`，基线 `01fc6225`，dirty。
- 前端：`wheel-saas-pure-web`，分支 `1.0.3-snapshot`，基线 `1240a2bc`，dirty。
- 已对后端及发布文件 2641 个、前端文件 640 个记录 SHA-256；构建期间及发布后核对均无变化。
- JAR SHA-256：`bcff3b612af6eee1ab6cc852d0391be527e08ad6f2b28f90c3128801f8966667`，本地、服务器、运行容器一致。JAR 内已确认包含首次延迟 SQL。
- 前端 index SHA-256：`c030c8a0a0904167b26e310840d07b313248aebf8113017efca9de3a2177892b`，本地、nginx、公开 HTTP 一致。
- 延迟配置 chunk：`static/js/MarketingNewGroupDelayConfig-BLIhMqHO.js`，SHA-256 `61f63d0e67e99f382b3975cdbf55dd6ccb27d6618905ca2d62e3362cb3e75428`，本地、nginx、公开 HTTP 一致；包含新的已有群/新群起算说明。

## 验证与限制

- 公开首页与营销 chunk HTTP 200；环境标题为“第二套环境”。API 代理返回 HTTP 401 / 业务码 40104，符合未登录请求预期。
- Flyway 验证 213 个迁移成功，`armada_perf` 当前 V210，无待执行迁移；本次无新 schema。
- 前端全量 TypeScript/Vue 类型检查、构建通过；后端构建通过，独立审查未发现本次生产差异的确定阻断项。
- 营销修复此前 235 项相关回归通过。部署前补充当前主仓修改测试 229 项，其中 226 项通过、3 项 ERROR：两项新增测试期待尚未实现的 NEW_GROUP 可不选管理分组，另一项测试复用同一草稿后重复追加 seq 导致唯一键冲突。对应生产校验与 HEAD 一致，不是本次营销/重连生产差异造成；未改这些在途测试，也不声明该独立需求已实现。
- 测试环境部署脚本测试通过；额外生产离线打包脚本测试因仓库缺失 `prod/scripts/inspect-production-host.sh` 失败，未用于本次测试环境发布。
- 未创建验证营销任务或发送真实 WhatsApp 消息；制品/服务验证不替代完整延迟首发业务验收。

## 回滚与证据

- 服务器保留旧镜像标签：`armada-backend:rollback-marketing-first-delay-20261005`、`armada-nginx:rollback-marketing-first-delay-20261005`。
- 旧 JAR、前端 dist 与编排文件：`/home/app/armada-deploy/release-backups/marketing-first-delay-20261005/artifacts.tar.gz`；未复制凭据。
- 本机完整日志与源码哈希：`/private/tmp/marketing-delay-perf2-release-20261005/`。
- 脱敏运行验证：[runtime.json](./runtime.json)。
