# 通讯录目标级失败与单条发送对照

> 本文记载 9 月 9 日的设计与验证。9 月 10 日用户明确要求单条无 ACK 后继续其他联系人，原文“UNKNOWN 停号”策略已被后续修复替代；最新代码、验证与部署状态见 [单条 ACK 未知后继续其他联系人](2026-09-10-contact-unknown-continue.md)。历史 UNKNOWN/SKIPPED 不自动重放。

## 已确认范围

用户确认：修复名单和目标失败处理；用当前账号 1558 的同一目标、同一内容分别通过直接协议路径和正式任务各发送一条新测试消息；依据返回继续定位。目标环境为第一套 test1，当前主仓库 `1.0.3-snapshot`。不重发历史 UNKNOWN，不使用旧账号 1714，不扩大为全量好友测试。

## 问题与设计

- 1557 云名单只有自身；既有协议过滤已生效，本次一起发布后台的新任务名单刷新、空中间页处理及空名单提示。
- 1559 的首个目标没有设备，可信关联 PN 查询为 contact type out。当前联系人 API 丢失此状态；协议需透传注册状态，并把明确目标级且未发送的失败与根 IQ/嵌套错误/超时区分。
- 原 Sink 无差别停止本任务账号并跳过剩余收件人。仅对白名单 `LID_SELF_RECIPIENT`、`LID_TARGET_DEVICES_UNAVAILABLE` 保留账号继续，且必须不是 UNKNOWN、没有消息编号。泛化 keys/query 错误、账号异常及入队拒绝保留原停号策略。单号仍最多一条在途，下一轮沿用现有 SQL 选择下一人。
- 1558 的自身密钥与 WhatsApp 一致，旧消息无 ACK 根因仍未确定。直接对照复现旧探针的调用顺序和 USync 原设备 JID，正式对照使用正常 ContactTask API、仅圈定 1558、maxSendsPerAccount=1、retryMax=0、正文 hello。对照消息是两条新消息。
- 直接入口默认关闭，仅 loopback、固定账号和目标、空请求；提交前持久化唯一发送槽，进程重启或 UNKNOWN 不允许重发。

## 数据及契约

无数据库 schema、Flyway、Redis 结构变化。后台复用既有协议 reasonCode 精确白名单，不解析中文错误文案。联系人 API 在原字段上透传注册状态；部署验证使用正常鉴权 API，凭据和完整身份不回显。

## 验证及发布

- Java 69 个聚焦测试通过，含 17 个真实 H2 回执/幂等/下一条可调度测试；日志 `/private/tmp/contact-recipient-failure-green.log`。
- root 复核 Sink 及 H2 测试：保留事件关联、终态幂等、UNKNOWN 优先、带 messageId 不放行；未发现阻断项。
- 后端部署脚本语法及测试通过；生产离线包测试因仓库既有 `prod/protocol/.env.example` 缺失失败，本次不涉及该离线包。
- 目标 backend 已部署的 c77803a2 与当前 HEAD 229e26f2 在 armada-api/armada-deploy 下无源码差异；本次只增加本任务未提交补丁，不回退已部署的批量删除功能。
- Go `gofmt`、`go vet ./...`、`go build ./...` 及本次相关包测试通过；一次性发送槽并发、重启后 UNKNOWN 不重发、取消及提交边界的定向 race 测试通过。全仓仍有部署环境依赖和 Noise 向量缺失等已复现基线失败，未宣称全仓全绿。
- 北京时间 23:06–23:08，`deploy-test.sh --env test1 --be -y` 完成后端发布，运行中 jar 摘要及 API 健康通过。23:10 后仅 node-02 更新为 `whatsapp-protocol:contact-target-20260909-1510`；节点、回调、诊断看板均 healthy。更新前通过正规 Account API 下线三号，更新后正常恢复，23:16 已逐号确认 ONLINE。

## 第一套实测结果（北京时间）

- **1557**：此前 22:45 的协议实测已确认原始 1 条仅为自身，过滤后 0 条。本次后端名单刷新和空名单提示已发布，真实 H2 验证通过；尚未完成发布后新建正式任务的页面端验证，不能把部署成功等同于该项 E2E 完成。
- **1558 直接路径**：仅发送一条新的 `hello`，目标 LID hash `2c29e72053bb`，消息 ID `D97A84ED239745FA942D19D03E361B84`。23:17:01.537 的设备 IQ 14 返回目标主设备 0；23:17:02.690 的 keys IQ 15 返回 key/skey；23:17:02.717 构包为单个 root `pkmsg`（235 字节），主设备密文存在。23:17:02.720 的真实 socket 上行 message 为 303 字节；不是仅提交队列。23:17:10.721 ACK 超时，截至 23:20:48 无 accepted/rejected ACK 或 receipt，期间心跳正常，无解码或连接错误。结果保留 UNKNOWN，不自动重发。
- **1558 正式任务路径**：本轮尚未创建或发送。正常测试用户可以管理账号，但缺少通讯录任务权限；`account-preview` 被方法级鉴权拒绝，异常处理错误地显示 Code 50000。现有浏览器停在登录页，配置中的旧开发登录值无法完成正常登录。调用一次 60 秒 browser-skill 登录协助后仅得到 RPC timeout，没有登录完成信号，已停止会话；没有绕过鉴权、修改角色、提取 token 或把超时当作登录成功。等待正常网页登录后再执行仅 1558、同目标、最大 1 条、零重试的正式对照。
- **1559**：23:21:54.378 对原目标可信 PN 的唯一一次只读查询 IQ 33 返回 result，`contactType=out`、无 LID mapping、无 devices、无 WhatsApp error code。实际 HTTP 响应已包含 `contactType=out`、`registered=false`、`registrationKnown=true`，旧字段保留。云端仍包含该旧目标，过滤自身后有 10 个。修复已解决 API 丢失状态的问题；不能据此解释注销、封禁、换号或旧映射等具体原因，也没有测试其余 9 个目标。

直接路径使用当前公共协议实现，复现已恢复旧探针的调用顺序和原 USync 设备 JID；没有恢复当年成功消息的完整原始帧和确切成功版源码，因此不能称为历史成功二进制的完整重放。这次绕过 ContactTask 调度也出现无 ACK，说明现象可以在直接路径重现；正式同条件新消息仍待执行，尚未完成两条新消息的受控对照。

流量证据：`/private/tmp/task4-direct-probe-1558-wire-trace.md`。发布日志：`/private/tmp/contact-target-backend-deploy.log`。当前补丁未 commit、push。

23:24:51 最终只读收尾：直接消息仍仅一次 303 字节写出，无晚到 ACK/receipt；1558 最后心跳响应为 23:24:40.825。三个账号的真实 auth/status 均 Code 0、ONLINE，owner 均 02；node-02 running/healthy、restartCount=0。该收尾没有发送、重登或再次查询 PN。

## 回滚

部署前备份当前镜像、jar 及协议变更源文件；仅回滚本次 backend/node-02 制品。保留测试任务和一次性发送槽，不重置 UNKNOWN、不删除历史消息或重放命令。
