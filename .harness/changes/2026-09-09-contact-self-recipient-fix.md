# 通讯录任务自身收件人修复与 task 4 排查

> 下文“尚未部署/未发送”为最初排查时点的历史记录。后续已按用户授权发布后端和第一套 node-02，并于 23:17 执行 1558 单条直接发送；最新状态、验证边界及尚待正常网页登录的正式任务对照，见 [通讯录目标级失败与单条发送对照](2026-09-09-contact-target-failure-comparison.md)。本轮补丁仍未 commit、push。

## 用户范围与交付状态

用户要求修复 1557，并详细排查 1558、1559 与 WhatsApp 的交互、提供官方接口参考。本轮在当前主仓库修改；尚未提交、推送或部署，没有新建任务、发消息、重登账号或修改历史结果。主仓库中已有的通讯录批量删除改动不属于本轮，保留不动。

## 问题与行为

1557 此次云端只返回自身 LID，旧流程误当好友发起设备准备，随后报 `LID_PRIMARY_DEVICE_MISSING`。协议改为基于登录可信 PN→LID 映射排除自身，发送前再次拦截旧固化收件人，确定自身目标不重试。Android 仍走云端 LID；没有重新引入通讯录同步快照前置条件。

本仓修改：

- `AccountMessagingAudienceServiceImpl.resolveContactAudience` 对新通讯录任务刷新其创建之前的 READY/EMPTY/FAILED 名单，复用本任务结果及正在采集的代次。
- `CloudStatusAudienceCollector` 允许过滤后的空中间页；仍保留游标去重、页数、大小与格式校验。
- `ContactTaskCloudPreparationService` 对空候选名单显示“没有可发送好友（已排除账号自身）”，结束账号准备并不生成收件人/发送命令。
- 补接口注释与真实 H2、分页、缓存刷新回归测试；无 DB schema、依赖或 Redis 结构变化。

## 验证

按照本仓 unit-test-write / unit-test-ci 的红绿流程，旧实现先出现 6 个预期失败，再修复：

- `ContactCloudAudienceResolutionTest` 9 个；
- `CloudStatusAudienceCollectorTest` 6 个；
- `ContactTaskCloudPreparationH2Test` 10 个；
- `AccountStatusAudienceH2Test` 3 个。

合计 28 个测试通过，失败/错误/跳过均为 0，其中 13 个使用真实 H2 Mapper/事务。日志 `/private/tmp/contact-self-java-red.log` 与 `/private/tmp/contact-self-java-green.log`。

协议仓 `gofmt`、`go vet ./...`、`go build ./...` 通过。全仓测试的本次相关 armada/app/cloudcontacts/node/nodes/processor 包均通过，ACK/构包定向 race 通过。全仓仍失败于修改前基线已复现的部署脚本环境测试和 Noise 向量测试（包含缺失 vectors.txt）；未声称全仓测试通过。日志 `/private/tmp/contact-self-go-check-3.log`，基线 `/private/tmp/protocol-diagnostics-baseline.log`。

独立复核未发现自身过滤和新增诊断的阻断问题；构包日志不改变节点字节，迟到 ACK 日志不改变投递和历史状态。自身映射缺失的云端失败码在 Armada HTTP 错误边界仍可能降级为 `CLOUD_FETCH_FAILED`，未承诺页面已区分该情况。

## 第一套环境证据及未确定项

task 4 于北京时间 21:10:46 创建、21:10:59 结束，23 条为 FAILED 2、UNKNOWN 1、SKIPPED 20。实际只尝试三个账号各自第一条；跳过不是服务端发送失败。

- 1557：确认目标就是自身，属于本地收件人选择问题。本轮已修代码，但尚无修复部署后的线上验证。
- 1558：实际写出 287 字节 message，没有观察到 ACK/receipt；已排除 ACK 注册晚与单纯 Kafka 漏回写。没有 WhatsApp 拒绝码，根因未确定，不能宣称已送达或服务端拒绝。新增构包结构与迟到 ACK 诊断待部署后取证。
- 1559：本次目标 LID 的三次设备查询均没有目标条目；新鲜云名单仍有目标；关联 PN 对照只读查询也未提供 devices。API Code 0 不等于已注册。新增 contact type / LID mapping 摘要待部署后同 PN 只读查询验证，不能据现有字段断言被封、注销或失效好友。

完整交互表、时间线、官方链接及证据边界见协议仓 `doc/whatsapp-interaction-diagnostics.md`。官方 Cloud API 的 Messages 与 Webhook 契约可以参考；本次未找到当前 Android 私有 LID/IQ 协议的官方公开调用规范，未替换现有发送协议。
