# 安卓个人号版本过期与 405 分类修复

- 日期：2026-10-26；分支：`fix/android-personal-wa-version`。
- 来源：安卓协议仓库 `docs/operations/2026-10-26-android-personal-405-task.md`。
- 状态：代码与聚焦测试完成，perf2 发布及实号验收进行中。
- 用户已明确批准 §3.2 的跨服务事件语义方案。

## 目标与影响

- Zhuan 个人版升级至官方 Android 下载页当天标注的 `2.26.32.84`，登录忽略个人号凭据中的旧版本覆盖值；商业版和 iOS 保持原逻辑。
- Zhuan 的登录失败 405 发出 `OFFLINE / CLIENT_OUTDATED / rawCode=405`；401、403 保持原行为。
- Armada 在状态服务入口将旧 `NEED_REAUTH + 405` 归一为上述离线语义，使生命周期及后续副作用都不把它当作密钥失效。
- 导入结算器把新旧 405 记为 `FAILED / CLIENT_OUTDATED`。真实 Mapper 的首次结算约束保持不变，不回写已冻结历史批次。
- 无 HTTP API、数据库结构、Redis 格式变更；Kafka envelope v1 与字段结构保持不变，改变的是 405 的状态及语义值。

## 验证

- 修改前新增测试复现：旧 405 导入结果为 3（期望 2），生命周期为 5（期望保留 2）。H2 真实 Mapper 测试同样复现旧 405 分类错误。
- JDK 17：`mvn -q -Dtest=AccountImportLoginResultSettlerTest,AccountImportLoginResultSettlerH2Test,AccountStateEventServiceConcurrencyH2Test,AccountStateEventServiceImplTest,AccountStateChangedSinkAdapterTest,AccountTakeoverStateEventH2Test test`：59 tests，0 failures，0 errors，0 skipped。
- H2 使用 MySQL 模式、真实 Mapper XML、生产租户插件和 Spring 事务，验证首次结算、幂等、租户隔离、旧事件时间边界及历史结果不改写。
- Zhuan 每次修改后均执行 `gofmt / go vet ./... / go build ./... / go test ./...`。前三项通过；新增分类回归已转绿。全量测试仍有 `pkg/noise` 的 8 项既有失败，在未修改的 `cd3c0bb` 基线已复现，不能宣称全量测试通过。
- 评审：保留事件关联字段、发生时间和 rawCode；已知 `OFFLINE` 能完成导入失败结算；不新增未知状态；401/403 回归通过。未发现本次 diff 的阻断问题，实号与完整观察窗口仍待验收。

## 发布边界与回滚

- 仅 perf2，按部署脚本先 `--check`，分别使用 `--be` 和 `--zhuan`；不发布 test1 或生产。
- 后端先发布以兼容旧 405 事件，再发布新的 Zhuan 分类。
- 回滚先将 Zhuan 恢复到版本升级提交 `d837008`（保留新的个人版版本号），再恢复后端发布前镜像/JAR。历史批次不执行补写或清理。
- 发布前原始证据确认批次 113：13 个 405、7 个 401。7 个 401 不重试、不清理。

## 遗留验收

- 逐个重新上线任务书列出的 13 个号，核对协议实时状态、版本日志和拒绝码。
- 恢复并核验发布前数据库显示在线的 1 个 iOS 商业号和 3 个安卓商业号；首次重启后协议中无实例，数据库旧在线值不能充当成功证据。
- 完成最终发布前后一小时拒绝码对比，记录真实观察区间。
