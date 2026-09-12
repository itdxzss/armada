# 养群 test1 验收记录

日期：2026-09-10（北京时间）。用户明确指定 test1，授权自行使用测试数据，从现有建群任务开始准备群，再验证剧本营销。

## 验收结论

前后端已部署到第一套环境。真实验证了建群、独立进群补齐、整任务资格拦截、按群绑定、提交间隔、暂停恢复、单群异常暂停及双群完整发送。

- 正常任务 **2**「SCR0910 双群完整成功验收」：执行完成，2 个群各 4 条，**成功 8、失败 0、未知 0**。
- 异常任务 **1**「SCR0910 全群资格与间隔验收」：执行完成，**成功 7、失败 1、未知 0**。失败来自主动关闭 WB 群成员发言；权限已恢复，原失败消息没有重发。
- API、数据库发送事实、Web 协议日志、Kafka `message.send_result_reported` 与真实任务列表一致。
- 本轮没有找到这些测试消息对应的成员接收或 `message.ack` 事件，**不宣称所有成员已收到或已读**；成功口径为协议发送结果成功。

任务与群保留供复查。没有直接改写业务数据库来伪造群成员或任务状态。

## 环境和运行制品

入口：http://armada.65.2.123.53.nip.io/ 。Armada 主机 65.2.123.53，数据库 armada；Web 协议主机 65.2.122.109。使用已登录用户代宣照（租户 1）的页面及业务 API，没有导出认证凭据。

| 项目 | 已部署本地候选提交 | 独立目录 |
|---|---|---|
| 后端 | d8e3a5448b72cad5155319fbce5cb92e81df01fa | /private/tmp/group-maintenance-accept-20260910/armada |
| 前端 | f9489933a452b2a2b4992eb046621692ddc9d047 | /private/tmp/group-maintenance-accept-20260910/wheel-saas-pure-web |

候选仅在本地提交，**未推送**。原工作目录及无关在途改动保留。最后只读检查两个容器 running=true、restart=0；运行制品与本地构建 SHA-256 一致：

| 制品 | SHA-256 |
|---|---|
| 后端 /app/app.jar | ec818e0d142aa1e33640f2289ba8125a52fea73b7a58783b455154b942c49fce |
| 前端 index.html | f32ffc64c486b73b0583f296999c0bde0f422e8aebd782fd3021f9cb6e59ec7c |
| 前端 index-Di3PXyCe.js | 08d9bfa619f8ab7744742841d754488c1a70167f5ad9efef88b442ad6ad59f66 |

V186/V187 执行成功，Flyway 共 189 个已应用迁移。部署前 --check 通过；环境档案跳过 Kafka 精确 metadata 检查，不能把该项列为通过。本轮另做真实消息 topic 的有界读取，不提交消费者偏移。

## 从建群开始准备数据

| 业务任务 | 真实结果 |
|---|---|
| 普群任务 146 | Android 管理员 685、成员 708/710；成员联系人准备返回 APP_STATE_NOT_READY，在 PREPARING_CONTACTS 失败，未建群。保留失败记录。 |
| 普群任务 147，项 183 | Web 管理员 71、推手 145/110；SCR0910-WA-1 创建成功，权限设置成功，管理员留群。 |
| 普群任务 148，项 184 | Web 管理员 71、初始推手 75；首次联系人准备超时，确认未发 GROUP_CREATE 后走已有单项重试，SCR0910-WB-1 创建成功。 |
| 链接刷新任务 223 | WB 群邀请链接刷新完成，1/1 成功。 |
| 独立进群任务 74 | 仅账号 145 通过 WB 邀请链接加入；5 秒间隔、无自动重试，DONE，1/1 成功。 |

使用推手分组 195「超链协议号测试9-2」，账号池 10 个；剧本需要 2 个不同推手角色，满足严格大于角色数的门槛。

| 群 | groupLinkId | JID | 管理员及可用推手 |
|---|---:|---|---|
| SCR0910-WA-1 | 32827 | 120363429158864145@g.us | 管理员 71；推手 110、145 |
| SCR0910-WB-1 | 32829 | 120363412887533798@g.us | 管理员 71；初始推手 75，独立进群补入 145 |

素材 39「SCR0910 验收素材」和可复用剧本定义 1「SCR0910 双推手间隔验收」保存成功。4 步为管理员 → 推手一 → 推手二 → 管理员；重复管理员角色不增加角色人数。内容均为带 SCR0910 标识的专用测试文本，本轮没有实际发送图片或按钮消息。

## 实测场景

### 人数缺口和整单拦截

任务 1 勾选 WA、WB。补齐前 WA required=2、available=2、ready=true；WB required=2、available=1、shortage=1、ready=false。报告包含群名、所需人数、可用人数和缺口，并提示前往进群任务。

启动返回业务码 40901 及完整报告，任务仍为草稿。API records.total=0，数据库两个群 `COUNT(r.id)=0`、bindings_json=NULL，证明已达标的 WA 也没有先发。

进群任务 74 成功后，两群重新检查均 ready=true、available=2；营销任务仍是草稿，直到显式启动，没有自动补人或自动启动。

### 按群绑定和暂停恢复

任务 1 固定绑定：WA（执行群 2）admin=71、promoter1=110、promoter2=145；WB（执行群 3）admin=71、promoter1=145、promoter2=75。

开始请求时间 1789054385057，首条提交为 1789054386635、1789054386815，约 1.6/1.8 秒，无配置等待。1789054387837 暂停时仅 2 条记录；暂停期间原命令的迟到回执写回原记录。

1789054442496 恢复，原记录 ID、commandId、submittedAt 和 bindingsJson 均未变化。两群下一条分别在恢复后 1569/1758 毫秒提交，没有延续原剩余等待。

### 提交间隔

任务 1 后续等待配置为 20、20、10 秒。排除主动暂停跨越的第一段，WA 第 2→3 条实际 20395 毫秒，第 3→4 条 10407 毫秒；WB 第 2→3 条 20393 毫秒。

任务 2 配置 3 秒间隔，WA 实际 3373/3370/3523 毫秒，WB 实际 3359/3380/3605 毫秒，包含调度误差。第 2 条提交早于第 1 条结果回写，证明等待不依赖发送结果耗时。

### 单群异常与恢复

任务 1 运行中通过群设置接口将 WB SEND_MESSAGES=false。WB 第 3 条已提交，协议返回 ANNOUNCE_ONLY_NON_ADMIN，对应原记录 6、commandId `cmd_c02288c0205a4b2cb311cc44e385fe91`。随后 WB 自动暂停在 nextStep=3，提示原绑定账号或群资格变化；WA 独立完成 nextStep=4。

通过同一业务接口恢复 WB SEND_MESSAGES=true，重新检查两群通过，显式恢复执行群 3，最后一条管理员消息成功。任务共 8 条记录，7 成功、1 预期失败；原记录 6 未重复提交，绑定未重抽。

### 双群完整正常运行

任务 2 在相同两群完整执行四步，没有注入故障。两群 nextStep=4、paused=false；任务 status=3，8 条记录全部 status=2，均有 messageId。

Kafka 独立只读消费者从本轮时间起点读取 protocol.message.events.v1，扫描 49 条事件，筛出两群相关 32 条。任务 2 的 8 个 messageId 均有 message.send_result_reported；没有对应成员入站或 ACK，因此接收/已读层不计通过。取证输出仅保存标识和状态。

## 页面验收

- 真实素材库可见新增素材，新建、编辑、复制按钮显示正常。
- 任务列表显示任务 2「执行完成，成功 8、失败 0、未知 0」和任务 1「执行完成，成功 7、失败 1、未知 0」。
- 新建表单选择分组 195 后，输入群名 SCR0910-WB 仅返回 SCR0910-WB-1，成功选中；已取消临时表单，没有新增草稿。
- 群资格弹窗显示双群明细和全群结果。素材→剧本→任务选用及缺口提示的完整页面交互另由本地 5 条 E2E 覆盖。
- 最后单独重开剧本定义路由时出现登录页，未把该次页面读取记为通过；剧本定义保存、任务快照和实际执行已由业务 API、任务结果验证。

## 部署中修复的问题

1. **迁移编号冲突**：test1 已有 V185 contact_task_delete_permission，本次最初占用同号使 Flyway 校验失败。临时恢复保留镜像，从旧制品取回原 V185（checksum -1575213737），新迁移改 V186/V187。部署前逐项核对 187 个历史迁移全部匹配，未执行 repair 或改写历史；重编号后 23 个聚焦测试通过。
2. **账号资料 upsert 歧义**：AccountProfileMapper 六处 GREATEST(updated_at, VALUES(updated_at)) 在真实 MySQL 存在歧义，已限定 account_profile.updated_at。22 个 H2 聚焦测试通过，6 个生产 SQL 在 MySQL 8.4 会话临时表首次/重复执行通过，未更改真实账号表。
3. **按钮权限未展示**：4 个页面/抽屉共 12 处由 v-auth 改为已有 v-perms。5 条 E2E 移除人工 meta.auths 后通过，ESLint/Prettier/Stylelint 通过，真实页面确认按钮出现。

其他单测、事务/SQL 验证、类型检查及构建见 [test-summary.md](test-summary.md)。发布脚本语法及测试通过。既有生产离线包测试缺 inspect-production-host.sh，本轮未走该路径，也未修改该无关问题。

从 test1 information_schema 导出四张剧本表结构，执行 gen_datamodel.py，自动生成对应段落追加至数据模型 wiki；没有手工编写表结构或替换其他业务段落。

## 证据文件与范围

- [任务与消息证据](test1-evidence-20260910.json)：两任务终态、按群绑定、原命令、提交/结果时间、协议结果事件。
- 本地原始文件：/private/tmp/script-test1-final-api.json、/private/tmp/script-test1-kafka-evidence.jsonl、/tmp/script-test1-flyway-preflight.json。
- 发布日志：/tmp/script-test1-deploy-final.log、/tmp/script-test1-frontend-permissions-deploy.log。
- SQL 和页面修复验证：/tmp/script-account-profile-sql-test.log、/tmp/script-account-profile-mysql-test.log、/tmp/script-button-permission-e2e.log。

本轮为两群、两个推手角色、文本消息和上述异常场景；不扩大为所有协议类型、媒体形式、并发规模或成员接收状态的完整验收。
