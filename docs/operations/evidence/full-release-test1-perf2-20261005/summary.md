# 第一套、第二套四项目发布 — 2026-10-05

两套环境已完成发布：`deploy-test.sh --env test1|perf2 --full -y` 均退出码 0，后端、前端、Web 协议、Android 协议均 SUCCESS；发布后 `--check` 均退出码 0。

| 项目 | 第一套 test1 | 第二套 perf2 |
| --- | --- | --- |
| 后端/前端/Web/Android | 全部部署成功 | 全部部署成功 |
| 应用启动时间（北京时间） | 18:41:20 | 18:40:35 |
| 后端与 nginx | running，重启 0 次 | running，重启 0 次 |
| Android | coordinator + node1，健康，唯一活动节点 | 单节点健康，重启 0 次 |
| Flyway | V210，212 个迁移校验通过，无待迁移 | V210，213 个迁移校验通过，无待迁移 |
| 发布后深度检查 | Armada/Web/Kafka/Android/跨组件全部通过 | Armada/Web/Kafka/Android/跨组件全部通过 |

## 源码与发布内容

- 源分支为四仓库的 `1.0.3-snapshot`；基线 HEAD：后端 `fdaefef1`，前端 `1240a2bc`，Web 协议 `d8c35e1`，Android `a220f15`。
- 纳入主仓当前未提交和未跟踪的源码，使用固定快照与独立构建目录；未 fetch/pull、未切换主仓、未提交或推送主仓改动。
- 包含群名自动追加序号、建群人重连后资料核验/缺项补写、管理员已在群时阶段推进、拉手短暂离线后续跑、营销首次发送延迟等当前实现。
- 第一套仅操作 Armada coordinator/node1；未连接或部署 Athena/WB Pro 的 node2/node3。
- 源码清单及 SHA-256：[sources.json](./sources.json)、[source-sha256.json](./source-sha256.json)。

## 生效证据

- 第一套 JAR：`d2b0843d7e6534924e69a6746ae46081ea4ff13d4b10b61717098f230ac9aa55`。
- 第二套 JAR：`04d78152260fb38624efa5045dd1750a05a5be03eeb58aa8f87e2018204eb107`。
- 两套各自本地、上传与运行容器 JAR 哈希一致；两个 JAR 每个归档条目的内容哈希均相同，区别仅在归档本身元数据，见 [jar-content-comparison.json](./jar-content-comparison.json)。
- 两套本地、nginx、公网首页哈希均为 `c030c8a0a0904167b26e310840d07b313248aebf8113017efca9de3a2177892b`；环境标题分别为“第一套环境”和“第二套环境”。
- Android 源码哈希一致，运行容器健康、重启 0 次；运行二进制包含新群资料离线保护逻辑标识，见 [android-runtime.json](./android-runtime.json)。
- 运行记录：[test1-runtime.json](./test1-runtime.json)、[perf2-runtime.json](./perf2-runtime.json)。
- 深度检查：[test1-postcheck.txt](./test1-postcheck.txt)、[perf2-postcheck.txt](./perf2-postcheck.txt)。

## 已知限制与回滚

- 预检、测试和备份详情见 [preflight.md](./preflight.md)。后端 574 项回归中 571 通过、3 个已有错误；前端尚有 1 个已知标签断言问题，未宣称测试全绿。
- “新群管理分组可不选”前端已放开，后端仍要求必填；本次发布当前代码，没有修复或宣称该独立功能完成。
- 旧制品、协议代码与旧镜像标签已按 `full-20261005-1825` 保留。
- 新群编号任务回滚前应先暂停相关任务，保留已有群 JID 和冻结名称。
- 本次未创建真实 WhatsApp 配对、发送或拉群验收任务；部署生效不等于这些业务已验收。

## 日志与入口

完整本地日志：`/private/tmp/armada-20261005-{test1,perf2}-deploy.log`、对应 `-postcheck.log` 与 `-artifact-check.log`。

- [第一套环境](http://armada.65.2.123.53.nip.io/)
- [第二套环境](http://armada.3.110.124.52.nip.io/)
