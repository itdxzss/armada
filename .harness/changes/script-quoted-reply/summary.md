# 养群剧本「回复哪一句」实施与验证

日期：2026-09-11。用户授权在主仓库实施；四个仓库均在 `1.0.3-snapshot` 主工作目录完成本地改动。已配套部署 test1；未提交、推送或发送真实消息。保留各仓库原有在途改动。

## 实现行为

- 编辑器增加「回复哪一句」，只选择前文，支持搜索、取消、原句定位、时间线及对话预览。保存/重开保留步骤 ID，复制整份剧本同步重建内部引用；删除和排序不能破坏引用。
- 后端共用 `stepId` / `replyToStepId` 校验；旧 JSON 自动补局部稳定 ID，旧任务仍按无引用方式执行。任务持有独立快照。
- 引用由任务锁内的 `ScriptReplyResolver` 按当前租户、任务和群执行记录解析。原句未收敛时不创建回复发送意图或推进游标，既定间隔不重复抽取，按 1 秒复查。
- 原句失败、未知或缺快照时，回复保留原角色、正文、消息类型和间隔，去掉引用照常发送；原因独立保存为 `reply_fallback_reason`，本句成功不计入失败。迟到/重复回调不重发。
- 如果 A 失败，B 改为普通消息后成功，C 仍可正常引用 B。
- Web 文本/图片/链接通过 Baileys `quoted`，按钮在生成消息时合并引用；Android 在加密前合并 `ContextInfo`。保留 `@all`，使用原账号实际 PN/LID 作者而非当前回复账号。
- 两端用 `{version:1,senderJid,messageBase64}` 回传裁剪后的 WA Message protobuf 快照。快照限 131072 字符，不保留递归引用、消息上下文或媒体密钥；存入原命令结果，重复命令恢复原快照。
- 新增 V188 两个可空列，仅扩展单项发送事实聚合。没有新增回复关系表、账号表字段或素材字段。

## 验证结果

| 层次 | 结果 |
|---|---|
| 后端 | 18 个相关测试类，共 196 用例通过；真实 H2 MySQL 模式、Mapper、租户插件、任务锁和事务，覆盖等待、失败去引用、未知/缺快照、跨群/租户、暂停恢复、服务对象重建、链式回复、双端 wire 及事件转换 |
| 前端 | 回复与原表单 11 单测通过；页面套件原有 10 用例通过，新增引用用例修正组件标签定位后单独通过；typecheck、生产构建、改动范围 ESLint 通过 |
| Web | 全量 123 套件中的 1380 用例通过，本地端口受限的 dashboard 6 用例解除沙箱端口限制后通过；新增按钮引用用例后，聚焦 20 用例与 build 通过，合计当前用例覆盖 1387 |
| Android | gofmt、go vet ./...、go build ./... 通过；go test ./... 仅 pkg/noise 的 8 个既有向量用例失败，其余 46 包包括新增脚本命令/回传/重放及消息类型用例通过；internal/armada、internal/service/entity、internal/service/node 的 -race 测试也通过。用 git archive HEAD 提取未修改的 Noise 源码复现相同 8 失败 |
| SQL / 文档 | Mapper xmllint 通过；H2 从 V188 提取并执行两条 ALTER，实际 Mapper 验证新列读写；按既有 gen_datamodel.py 生成单表待部署结构，保留其他在途文档 |

前端全量 `npm test` 的部分无关用例报 `ERR_UNKNOWN_FILE_EXTENSION`（nprogress.css），不计为本次功能通过证据；stylelint 启动缺少 `stylelint-config-standard`，未改动依赖与 lockfile。这两项限制未用弱化断言或关闭规则绕过。

关键执行命令（从对应仓库执行）：

```sh
# armada/armada-api，Java 17；本机 Mockito 必须显式安装代理才能运行 H2 测试。
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -q -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar '-Dtest=Script*Test,WebMessageSendBackendTest,AndroidMessageSendBackendTest,ProtocolMessageEventConsumerTest,RoutingMessageSendPortTest,ProtocolCommandOutboxServiceImplTest,ContactTaskReceiptH2Test,FeedTaskSendResultSinkTest' test
# 最后新增的链式回复和事件快照测试另跑对应两个类，最新报告合计 196。

# wheel-saas-pure-web（沿用已安装依赖，不修改 lockfile）
node --import tsx --test src/views/task/script-marketing/form.test.ts src/views/task/script-marketing/reply.test.ts
node node_modules/@playwright/test/cli.js test -c playwright.script-marketing.config.ts
npm run typecheck
npm run build

# armada-protocol/protocol-layer
npm run build
npm run test:unit -- --runInBand

# whatsapp-server-feature-android-zhuan
GOCACHE=/private/tmp/script-reply-go-cache go vet ./...
GOCACHE=/private/tmp/script-reply-go-cache go build ./...
GOCACHE=/private/tmp/script-reply-go-cache go test ./...
```

## 发布与回退边界

用户明确确认后，已在 test1 完成双协议、后端与前端发布。V188 由 Flyway 实际应用，新增列经 MySQL 只读查询确认；运行制品、服务状态、跨组件与 Kafka 检查通过。已在真实页面验证下拉选句与引用预览（草稿未保存）。详见[发布核验记录](deployment.md)。手机上的引用渲染、点击定位及四种跨端账号组合仍需现场验收。

配套发布顺序：更新所有 Web/Android 发送节点和结果链路 → 后端 V188 / 执行器 → 前端。当前没有节点引用能力注册或新的开关，不允许旧节点接管含引用的任务。回退先暂停/收敛这类任务及 outbox，保留新增列和发送事实；详见 [rollback.sql](rollback.sql)。

[设计与执行契约](../../../docs/business/2026-09-11-script-quoted-reply-design.md) · [V188](../../../armada-api/src/main/resources/db/migration/V188__script_quoted_reply.sql) · [数据模型生成](refresh-schema.py)
