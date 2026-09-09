# 通讯录 LID 超链实现

用户已授权本地代码实现。三个隔离工作区位于 `/Users/daishuaishuai/IdeaProjects/contact-lid-implementation`，分支均为 `feature/contact-lid-hyperlink`。基线：Armada `f27a7a1d`、ws-go `155fc10`、web `19694c9`。未修改原仓库的在途业务代码，未 commit/push/部署/真号发送。

## 当前实现

- [x] 复用五张现有表，联系人/收件人手机号可空，JID 唯一，新增 delivered_at/read_at 与账号 stop_reason。Flyway V181。
- [x] Kafka 命令接受 LID；协议查询完整 LID/自身 PN 设备，强制最新 pre-key 建会话；目标主设备必须成功。LINK_CARD 组装复用原链路。
- [x] 页面可配 0.1–60 秒，暂默认 5–10 秒；writer 实际写出时计时。Armada 立即写 outbox，不按发送间隔延后 Kafka 投递。
- [x] 接通 contact_task 的 ACK 索引/转发、后端幂等回写、现有页面发送明细。
- [x] 每个任务账号最多一条在途；失败/未知停止该任务账号并跳过待发。两端不自动重发不明结果。
- [ ] 找回交接云端探针，核对云端与 app-state 的采集来源。探针路径已失效，已询问用户。
- [ ] 经用户确认账号与环境后做真实端到端验收。

## 影响与约束

没有新业务表、通用任务控制框架、配额系统或模板按钮 UI。原 PN 路由保持；其他业务查询有名字联系人口径保持。快照消费锁定账号并校验租户归属，旧水位不覆盖新水位，任务只使用完整且新鲜快照。新字段仅属于已有联系人/任务聚合。

新增 GET `/api/contact-tasks/{id}/accounts/{taskAccountId}/recipients?page=1&pageSize=20`，沿用 `tenant:contact_task:view`。账号数据增加 taskAccountId/state/stopReason。Redis 复用已有 message ACK correlation 结构及 TTL，不新增 Redis 业务状态模型。

间隔是实际发送的下限；网络、准备、回执或队列阻塞可使间隔延长。每账号一条在途用于及时停发，未做新的吞吐调度平台。暂停/停止仍回收已在途消息的结果，不宣称可撤回已写出的消息。旧批量在途任务升级前应暂停并排干；不做旧任务无缝迁移。

现有 app-state 采集链路已支持 LID 与已验证的 F7 主设备编码。尚未证明它与交接文档那次云端查询的数据源一致，未编造云端 API 或新增未经核实的刷新入口。

## 验证

日志位于 `/private/tmp/contact-lid-*.log`。后端 220 个相关测试通过，含 5 个任务回执 H2 测试。Mapper XML 经 xmllint 校验通过。H2 使用真实 Mapper、租户插件和事务，覆盖 LID/PN 身份、旧快照、跨租户、分页、一次在途、未知不重试、回执乱序和计数幂等。Go 全量测试的 Noise 8 个向量失败与部署脚本 7 个环境相关失败已在未修改的 `155fc10` 工作区复现。前端相关 42 个测试、TypeScript/Vue 类型检查、定向 lint 和构建已通过；样式拆分后的构建也通过。Go vet/build 通过；受影响的 armada/node/app/appstate 包测试及 -race 全部通过。

数据模型生成器已尝试，但缺少 `/tmp/wheel_tables.tsv` 等数据库导出输入；本次按迁移补充结构文档，不声称已从真实数据库刷新。没有连接共享数据库。

## 回滚

优先停用本功能并前向修复。保留已增加的空手机号和回执字段，不能将手机号强改非空或删除 LID 行。回退应用前暂停通讯录任务并排干已有在途命令；旧版本不得重开 LID 任务。数据库回滚检查见 rollback.sql，本次不执行。
