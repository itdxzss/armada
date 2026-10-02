# 超链未知结果超时与账号补位

2026-09-18，用户确认实施。本地主仓 1.0.3-snapshot 有其他会话在途改动，保留已有差异；不自动部署、不处理真实任务数据。

## 已确认口径

- 所有待确认发送从当前 command 创建起等待上限为 2 分钟，到期由对账扫描收口为超时失败；封禁不会重新延长计时，封禁后宽限同样不超过 2 分钟。
- 超时失败只结束业务等待，不宣称 WhatsApp 确定未发送；保留 command/message ID，默认不重发。
- 迟到的成功/送达/已读可以修正超时失败；重复事件不能重复释放槽位或增加成功数。
- 即时/预发布任务在有效执行账号不足并发时补位，不等待已失效账号的 UNKNOWN；仍遵守最大使用账号数。周期轮切换仍等旧轮在途结果收口。

## 实现与边界

- 复用协议 Outbox 当前 command 的 created_at 作为固定超时起点；历史无 Outbox 时回退首次 submitted_at，不用反复变化的 updated_at。
- recipient 复用 FAILED + 专用原因码；UNKNOWN 超时不推进号码池为 RETRYABLE_FAILED，保持原领取归属，避免失败重置把不确定消息重新发出；迟到回执再推进真实发送事实。
- 不新增表/列、不改请求参数、不新增队列或锁。继续使用已有 usage→recipient 行锁和状态 CAS。
- 并发计数保留“有效账号额度暂时被在途预占”的占位，避免回执失败后账号恢复可用造成超并发。封禁账号的历史对账不作为有效执行账号。
- 最大使用账号数采用严格累计选入数，受限账号仍计入已使用名额。

## 验证计划

- [x] 复现未知无限轮询和失效账号阻断补号的红测试
- [x] 超时边界、封禁宽限、重复/迟到回执、跨租户测试
- [x] 真实 H2 Mapper 与事务测试：CAS、计数、号码池防重发
- [x] 相关超链回归与 XML 校验

## 部署与回滚

2026-09-18 已按用户要求定向部署第二套 perf2 后端，详见 docs/operations/evidence/perf2-hyperlink-timeout-deploy-20260918.md；未 commit/push。远端保留旧 JAR 以便回滚。源码回滚仅撤销本任务差异，保留其他会话修改。发布后历史超时记录可能按规则收口；已停止任务不会恢复发送。迟到成功只修正统计，既有按 submitted_at 计费口径保持不变。

## 验证结果

- TDD 红测试：未知过期仍轮询、封禁账号 UNKNOWN 阻断即时补号两例均按预期失败（2 failures / 0 errors）。
- JDK 17 + 显式 Byte Buddy agent：`mvn -q -Dtest='Hyperlink*Test,DataPackage*Test' -DargLine=-javaagent:.../byte-buddy-agent-1.14.19.jar test`，退出 0；94 个测试类，共 437 例，431 通过、6 个可选真 MySQL 用例跳过，无失败/错误。
- 新增真实 H2 `HyperlinkUnknownTimeoutH2Test` 7 例，覆盖两分钟边界、封禁后两分钟、重复超时/迟到回执、当前 command 时间与跨租户隔离、回滚、占用计数、失败重置排除。Service 边界另有 8 例 completion/recovery 测试。
- `HyperlinkMetricsProjectionH2Test` 新增超时失败后迟到送达的实际 Mapper 投影：任务/轮次/账号失败数减一，成功与送达加一，发送总数不重复。
- `HyperlinkRoundAccountSelectionServiceTest` 模拟并发 2、使用上限 10：按每批 2 个推进 5 批，第 6 次不再选号；受限账号占用总名额，有在途预占的正常账号保留并发名额。
- XML 校验和 `git diff --check` 通过。最后对超时方法做私有方法整理后，针对 recovery + H2 的 15 例再次通过。
- 用户追加要求将普通未知等待上限缩短为 2 分钟：先以 120 秒边界复现旧实现仍为 SENDING 的红测试，再调整上限；recovery + H2 共 15 例全部通过，涵盖 119999/120000 毫秒边界及封禁不延长计时。
- 未执行真实 WhatsApp 发信验收，未连接真库执行写操作；H2 不替代 MySQL InnoDB 并发行为验收。

## 本次差异

后端 recipient 回执/超时、轮次选号与推进、相关 Mapper、状态原因文案及测试。前端沿用既有 FAILED 展示及后端业务说明，无前端变更；无数据库迁移。已有 463 重试、指标及其他会话改动均保留，未提交、未推送。

接口仍为既有 FAILED 状态，业务原因码增加 SEND_RESULT_TIMEOUT / BANNED_SEND_RESULT_TIMEOUT。超时号码池保留 CLAIMED 归属，因此数据包“失败导出/重置”不会纳入；任务明细仍可查看/导出超时原因。保留的是发送不确定性，不是无限占用执行槽位或无限主动轮询。
