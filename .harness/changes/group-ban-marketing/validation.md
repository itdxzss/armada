# 验证记录

日期：2026-09-17。状态：本地实现与针对性验证完成；未 commit、未 push、未部署 perf2，未修改线上历史数据。

## Java

JDK 17，Maven 针对 9 个测试类执行，127 项测试通过，0 failure、0 error、0 skipped。

覆盖详情投影、成功 ACK 与封禁事实独立、营销统一发送过滤、普通轮次/新群/即时重试封禁跳过、建群营销兼容、Mapper SQL 形状、真实 H2 SQL 与租户隔离。

测试类：MarketingGroupBanMapperH2Test (3)、MarketingMessageSendServiceTest (4)、MarketingTaskServiceImplLifecycleTest (22)、MarketingGroupExecutionNormalizerTest (13)、MarketingRoundWorkerTest (19)、MarketingImmediateRetryServiceTest (5)、MarketingNewGroupImmediateSendServiceImplTest (18)、GroupCreationMarketingWorkerTest (18)、MarketingTaskMapperSqlShapeTest (25)。

详情复杂 CTE 的 H2 PreparedStatement 参数绑定限制及固定测试 ID 的替代验证范围见 summary.md，不等同于完整 MySQL 集成验收。没有对线上数据库做写入测试。

## Android

- gofmt、go vet ./...、go build ./... 通过。
- 新增封禁回归原实现 3 场景失败，修复后通过；相关 tracker、发送结果与新回归 race 检查通过。
- go test ./... 在 Go 1.26.5 和 Go 1.25.1 下均仅 pkg/noise 失败；其余包通过。
- 将 HEAD 的 pkg/noise、go.mod、go.sum 导出独立目录，用 Go 1.25.1 复测，仍为相同 8 个握手用例失败，确认是未修改基线问题。全量测试不能标记全绿。

## 前端

- GroupMarketingDetailDrawer.test.ts：6 项通过。
- 修改的 Vue / 测试文件 ESLint 通过。
- pnpm typecheck、pnpm build 通过，退出码均为 0。
- 三仓 git diff --check 通过。

## 日志与线上边界

本机验证日志：/private/tmp/group-ban-perf2-diag/ 下 java-final.log、go-red.log、go-vet.log、go-build.log、go-all.log、go125-all.log、go-noise-baseline.log、go-race.log、front-test.log、front-lint.log、front-typecheck.log、front-build.log。

线上问题证据：docs/operations/evidence/group-ban-perf2-20260916/diagnosis.md。

上线验收尚未执行：需部署对应后端、Android 协议与前端制品后，核验第二套现存封禁群显示 GROUP_BANNED，确认新发送被拦截且历史成功数保持原义。本次未为验证而发送真实 WhatsApp 消息。
