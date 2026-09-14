# 代理失败隔离与换 IP 补偿修复

- 日期：2026-09-12。
- 需求：IP 有问题时，协议请求 Armada 换 IP；原代理必须置为不可用，检测成功后才可重新分配。
- 状态：实现、测试、独立发布构建已完成；尚未提交、推送、部署或执行真实账号重试。
- 基线：后端 `1.0.3-snapshot / 34296a0c32a158844ccc3b3c3b0607c0ccf3435d`；Android `1.0.3-snapshot / b1759daf5559b14e3833876ea6ca85f1760be62d`。

## 已确认原因

test1 的失败事件缺少 proxyId，后端跳过隔离后仍重新分配，旧代理被释放回空闲池并再次选中。日志抽样中，账号 1554 的 480 次分配都是代理 9244，账号 846 的 360 次都是 9225，账号 1719 的 197 次都是 7171。不能把个别账号随后自行 ONLINE 当作换代理成功。

上线 outbox 原本保存 proxyId，但 ProtocolCommandPublisher 的实际 Kafka wire payload 丢掉了这个字段。Android 的 CommandPayload、CommandContext 和 AccountEventData 同样缺少该字段；Web 已有传递逻辑，但上游未提供。

## 修复

1. 后端实际上线消息补齐 proxyId，Android 在命令解析、Redis 上下文、状态事件中完整透传。旧事件有 attempt 时按租户、账号、原 attempt 精确查 outbox，不猜当前绑定。
2. 失败代理隔离返回明确结果，未完成隔离就停止重新分配。提前释放为空闲的失败代理也会隔离；后续新绑定、配对占用和较新的成功检测受保护。
3. PROXY_FAILED 上下文与状态在同一事务保存到既有 account_online_attempt_log，不依赖 Android 额外发送 offline_diagnosed。查重使用显式租户的 FOR UPDATE 当前读，避免 MySQL RR 旧快照重复插入。
4. 后台补偿读取当前失败时间水位，并以同一水位条件抢占；分配新代理时强制排除失败代理。中断、隔离异常或无空闲代理后仍可继续补偿。
5. 沿用既有不可用 IP 检测任务，默认每 15 分钟最多 200 条；检测失败保持不可用，真实检测成功后恢复空闲。

没有数据库 schema、前端或 Web 协议实现变更。

## 验证

- TDD：实际 Kafka wire 测试先复现 proxyId 缺失；Android 命令→Redis→失败事件契约测试先复现 proxyId=0。
- 后端 18 个相关测试类共 239 项通过，0 失败、0 错误、0 跳过；覆盖实际 MyBatis XML、租户插件、H2 事务、幂等、状态和上下文共同回滚、进程内存丢失后的补偿、旧事件与后续绑定保护。
- 独立后端工作树执行对应回归及 Maven package 成功，已生成可发布 JAR。
- Android gofmt、go vet ./...、go build ./... 成功；相关包 go test -race ./internal/armada 在原工作区与独立工作树均通过。
- Android go test ./... 的 pkg/noise 有 8 项失败；从未修改的 HEAD 导出该包和 go.mod/go.sum 后复现相同 8 项失败，其中包括缺失 vectors.txt。未改动 Noise 实现。
- Web 两组现有契约测试 46 项通过。
- deploy-test.sh 脚本测试、语法检查、test1 --be/--zhuan dry-run 通过。
- 附带生产打包脚本测试因 HEAD 本已缺失 inspect-production-host.sh 失败；本次不涉及生产打包。
- 两轮独立只读评审的问题已处理，最终评审无阻断发现。

## 发布准备与待验收

独立目录：`/private/tmp/pending-online-diag/release/`；`manifest.json` 保存组件基线、补丁文件 SHA-256 和后端 JAR SHA-256。两个独立工作树仅包含本次补丁；原工作区其他在途改动保留。没有复制环境凭据。

发布范围为 test1 后端与 Android coordinator + 3 个节点，来自上述两个基线加本次补丁；等待用户确认该分支/范围后使用当前发布脚本执行。

部署后需要逐层验证：实际 Kafka 消息与失败事件携带同一 proxyId；原代理变为 UNAVAILABLE 且不可再分配；新 outbox 使用不同 proxyId；账号后续实际状态；定时检测成功后才恢复原代理。部署前没有同水位失败上下文的存量记录，需要新失败事件或核实原 attempt 后单独处理，不能直接猜测代理或宣称已恢复。
