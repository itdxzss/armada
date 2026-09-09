# Agent 02：超链任务报价与运行生命周期

## 目标

验证任务报价、异步准备、START/PAUSE/RESUME/STOP 状态机、并发、幂等、恢复和受控真实发送边界。

## 前置与依赖

- 硬前置：Agent 00 公共门禁通过；必须确认测试环境和可产生状态的资源范围。
- 软前置：优先消费 Agent 01 的草稿/等待任务；也可用 `HLQA-{RUN_ID}-A02-` 创建专属数据包、任务和测试账号夹具。
- 方法：状态机、并发、幂等以 API 为主；用户动作和状态展示必须浏览器复核。
- 浏览器操作必须使用腾讯开源的 `browser-skill`，通过 `bsk` 驱动已连接的 Chrome Agent Window，并保存动作前后截图和 Network 摘要；结束时必须停止本 Agent 的 `bsk` session。
- `CANARY` 用例必须由协调者再次明确授权，并给出白名单发信账号、收信号码、最大发送条数和钱包沙箱；否则记录 `BLOCKED`。
- 每条输出 `Case ID / PASS|FAIL|BLOCKED / Method / Expected / Actual / Evidence / Severity / Cleanup`；只报告问题，不修改业务代码。

## 报价与准备用例

- [ ] LIFE-01【API+浏览器】创建启用任务前获取报价，核对人数、数据包代次、国家明细、币种、余额、预计金额和有效并发。
- [ ] LIFE-02【API】篡改 quote token 绑定的租户、用户、purpose、任务、版本、数据包、代次、模式、人数或并发，必须失败关闭。
- [ ] LIFE-03【API+浏览器】报价过期返回稳定 stale 错误，原核对弹框刷新报价并重新倒计时，前端不得自行重算金额。
- [ ] LIFE-04【API】余额不足、账号不足、协议容量不足、计费不可用分别返回稳定业务错误，且不产生半成品发送事实。
- [ ] LIFE-05【API】创建 `enabled=false` 草稿，只生成任务/content/runtime，不产生 claim、recipient、billing、round、usage、stat。
- [ ] LIFE-06【API+浏览器】创建 `enabled=true` 返回 PROCESSING，短轮询最终到 READY 或 FAILED，并在终态停止。
- [ ] LIFE-07【API】重复创建/更新请求不得产生重复 recipient、claim、billing reservation 或任务快照。
- [ ] LIFE-08【API】数据包包含重复号码时，同任务同号码只能有一个 recipient；报价后新 generation 或 upperPhoneId 之外号码不得混入。

## 生命周期用例

- [ ] LIFE-09【API+浏览器】START 必须先报价并校验 version；成功后按钮变为暂停/停止，旧 version 启动失败。
- [ ] LIFE-10【API+浏览器】即时任务立即启动和延迟启动时间正确；零账号按设计失败，正常任务满足条件后自动完成。
- [ ] LIFE-11【API】预发布任务初始零账号时可等待；后加入匹配发信账号可参与，但新导入收信号码不得错误吸收。
- [ ] LIFE-12【API】周期任务不重叠、不补跑遗漏周期、同号码不重复、每轮最大账号生效，只处理剩余 recipient。
- [ ] LIFE-13【API】消息间隔、最大执行账号、最大使用账号、单号发送上限、delay 和 cycleInterval 必须真实约束运行，而非只落库。
- [ ] LIFE-14【API+浏览器】PAUSE 后不再领取或派发新命令，已进入 outbox 的消息自然收口，暂停时长不计执行时长。
- [ ] LIFE-15【API+浏览器】RESUME 从原 round/usage/recipient/游标继续，不重新领取、扣费或为既有 recipient 创建第二 command。
- [ ] LIFE-16【API+浏览器】STOP 立即进入终态；未提交 recipient 按停止语义失败，已有 command/ACK/点击保留，claim 和未消费余额释放。
- [ ] LIFE-17【API】重复 STOP 不重复清理、结算或释放；完成后的任务不能恢复。
- [ ] LIFE-18【API】对所有状态执行非法 START/PAUSE/RESUME/STOP，只有合法状态转换成功。
- [ ] LIFE-19【API】双击或两个会话并发动作，最多一个成功；409/version 冲突后页面以重新读取事实为准。
- [ ] LIFE-20【API】同 recipient 并发派发、outbox 重放、结果重放时复用稳定 commandId，不换账号、不产生第二次逻辑发送。
- [ ] LIFE-21【API】重复或乱序 SUCCESS/DELIVERED/READ/FAILED/UNREGISTERED 事件保持状态单调，终态失败不得被迟到 ACK 复活。
- [ ] LIFE-22【API】任务存在 PENDING、SENDING、活动 round 或未结算金额时不得自动完成；全部条件消失后只完成一次。
- [ ] LIFE-23【API】缺真实钱包、审计、Redis、PRIVATE 协议能力或公网短链配置时必须失败关闭，不得使用本地假余额或假发送。
- [ ] LIFE-24【API】worker 在 claim、billing、round、outbox 阶段恢复时从事实表续跑，不能重复领取、扣费或发送。

## 受控 Canary

- [ ] LIFE-25【CANARY】Web 白名单账号分别发送单图文、普通按钮、卡片按钮，每种最多 1 条，收信端内容与冻结配置一致。
- [ ] LIFE-26【CANARY】Android 白名单账号分别发送单图文、普通按钮、卡片按钮，每种最多 1 条，不产生群 typing/mention 行为。
- [ ] LIFE-27【CANARY】同一成功 commandId 重投两次，真实收信端只有一条消息，sendTotal 不增加第二次。
- [ ] LIFE-28【CANARY】短链唯一 CTA 可访问，第一次点击增加 UV/PV，重复点击只增加 PV，并 302 到冻结 HTTP(S) 目标。

## 交付与清理

- 输出可供 Agent 03/08 消费的任务别名：RUNNING、PAUSED、COMPLETED、STOPPED、含点击任务。
- 未经 canary 授权不得尝试扫码、重登、换代理、改账号资料或向非白名单号码发送。
- 结束时停止仍运行的测试任务，等待在途命令收口，并记录 claim、余额和账号租约清理结果。
