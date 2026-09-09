# WhatsApp 协议诊断与通讯录端到端验证

## 授权与目标
用户确认：梳理协议交互、补逐步诊断、修错误分类和结果回写、修 LID 构包；提交部署第一套环境；使用当前账号，由助手创建启动任务并监测。允许读取相关原始流量。目标是能说明具体请求阶段、WhatsApp 返回和最终业务状态。

## 已知证据
- task 3：Android 1557/1558/1559 云端名单为 1/11/11，三条命令均进入 node-02。
- 1557/1559：三次设备 IQ 后失败，没有进入密钥查询，底层错误未保留。
- 1558：10:44:55.605Z 写 57 字节，10:44:56.629Z 收 ACK 77 字节；失败 ACK 被包装 UNKNOWN。
- 本地 overlay 复现：LID 主设备 AD=true 与目标 AD=false 字符串相同但 struct map key 不同，单密文查找失败，空消息节点 38 字节 + Noise16 + 帧头3 = 线上57字节。
- 历史流量保存元数据，不保存 ACK error 或完整 IQ 内容；不能补造历史原始码。

## 执行清单
- [x] 交互清单及诊断规范（协议仓 doc/whatsapp-interaction-diagnostics.md；其他业务只有入口目录）
- [x] LID 密文构包修复与空密文防护
- [x] 设备/密钥/IQ/ACK 的分类、相关 ID 与安全结构日志
- [x] 1559 回写阻塞根因与恢复路径
- [x] 本地 Go/Java 相关检查及评审
- [ ] test1 部署，使用当前账号新建限量任务并监测
- [ ] 测试时间线与最终结果

## 边界
不重发旧 UNKNOWN 消息，不修改旧任务状态冒充回执。新测试任务每号先发送1条，沿用当前任务目标来源及消息配置。后续按证据扩展验证。密钥/正文不进入普通日志。无数据库结构变更计划。

## 回写恢复与验证证据

- 1559 的结果在 message topic partition 1 offset 156575；早先超链 recipient 不存在异常阻塞该分区，因 `.DLT` 不存在恢复器无法跳过。
- test1 补建 message/contact-sync 的 `.DLT`，均为 12 分区、复制数与源一致、保留 7 天。未重置 offset。task 3 于 2026-09-09 11:30:56 UTC 自动结束：FAILED 2、UNKNOWN 1、SKIPPED 20。
- Go `go vet ./...`、`go build ./...` 通过；通讯录相关 armada/app/cloudcontacts/node/nodes/processor 包通过。全量测试仍失败在原 HEAD 可复现的 deploy 配置脚本、Noise 向量和缺失 vectors.txt；不宣称全量通过。
- Java JDK17：`AccountContactSnapshotSinkH2Test,ContactTask*Test,ProtocolKafka*Test,ProtocolMessageEventConsumerTest` 通过。H2 使用真实 Mapper，MySQL 歧义的红色证据来自 test1 实际日志；H2 本身未复现旧 SQL 歧义，不把它写成红绿证明。
- `xmllint`、diff 空白检查、`deploy-test.test.sh` 通过；离线 `package-prod.test.sh` 停在既有缺失 `prod/protocol/.env.example`，本轮不发布生产包。

## 评审与回滚

本轮修改仅为诊断、LID 密文匹配、ACK 分类、SQL 表名限定与部署检查；无依赖、DB schema 或 Redis 结构变更。未发现本轮尚未修复的部署阻断项。仍待 test1 实测 WhatsApp 设备与 ACK 返回；未保存的历史码不可恢复，云端 GraphQL 内部错误字段尚未全部结构化。

回滚使用本次部署前保留的 node 镜像和后端制品；DLT 保留供排障，不删除原始事件。不要用回滚重发历史 UNKNOWN 收件人。
