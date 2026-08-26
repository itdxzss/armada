# 一次性业务决策包

- `change_id`: `2026-08-26-group-canonical-first-classification`
- 状态: `CONFIRMED`
- 确认: 2026-08-26 15:01:51 CST，当前提出人回复“全部按 A”
- `scope_hash`: `316839dc898e558b494ff6835abf15cd55d942e8a730c927ba76a6c25be61825`
- 回复方式: 可直接回复“全部按 A”，或只写例外，例如 `D2-03=B，D2-04=C`。

## D2-01 分类作用域

- owner: 群业务 owner
- question: 同一个 WhatsApp 群出现在不同租户时，是各租户独立定类，还是全系统共享一次分类？
- why_blocking: 这决定唯一键、租户隔离、跨租户数据泄露边界和迁移范围。
- **A（推荐）**: 按租户内规范群 `(tenant_id, group_jid)` 独立定类。
- B: 全系统按 `group_jid` 共用分类。
- default_behavior: 未确认不实现跨租户共享；保持租户隔离。
- acceptance_example: 租户 T1 首次把 G 判为历史群，不影响 T2 后续把同一 G 判为上控后群。

## D2-02 哪些动作有资格首次定类

- owner: 群业务 owner
- question: 一个尚未定类的群，哪些事实可以决定它的唯一分类？
- why_blocking: 链接导入、预览或普通 metadata 也能提前创建群记录，若算“第一次”会绕过账号上线语义。
- **A（推荐）**: 只有账号的首次完整 baseline 可判 `HISTORICAL`；baseline 后可靠 self-add 或完整群快照首次新增可判 `POST_CONTROL`。链接导入、预览、手工资料查询只建群，不定类。
- B: 任何首次创建群记录的来源都参与定类。
- C: 只有显式 self-add 事件能判上控后群，完整快照发现不参与。
- default_behavior: 未确认时非账号控制事实保持 `UNCLASSIFIED`。
- acceptance_example: 先导入邀请链接但没有账号在群内时不显示分类；账号首次完整 baseline 报告该群后显示历史群。

## D2-03 “第一次”与并发冲突口径

- owner: 群业务 owner
- question: 两个账号几乎同时以相反身份观察到同一未定类群时，怎样定义第一次？
- why_blocking: 分布式消息可能乱序；不同口径会决定分类是否允许被晚到旧事件改写。
- **A（推荐）**: 第一个成功写入规范群分类的可靠事实获胜；数据库原子竞争后永久不变。精确同时冲突不承诺按手机端事件时间追溯重排。
- B: 按协议事实时间最早者获胜；为处理晚到旧事件，分类在收敛前允许被更早证据改写。
- C: 相反候选并发时固定“历史群优先”。
- default_behavior: 未确认不实现会改写已定分类的追溯逻辑。
- acceptance_example: A 的上控后候选先成功入库，B 随后的 baseline 候选不能把它改成历史群。

## D2-04 旧数据如何收敛为唯一分类

- owner: 群业务 owner
- question: 对当前双 true 或证据不足的群，迁移时如何决定唯一分类？
- why_blocking: 直接任选一类会改变列表筛选和可能依赖历史群的营销资格。
- **A（推荐）**: 先 dry-run；能从各账号 baseline 时间与 `first_post_control_observed_at` 证明先后的，取最早可靠事实；无法证明的双 true 保守归为 `HISTORICAL`，同时输出审计清单和数量。
- B: 证据不足的记录保持 `UNCLASSIFIED`，由人工复核后再定类。
- C: 所有双 true 一律归为 `POST_CONTROL`。
- default_behavior: 未确认只生成只读 dry-run，不执行回填。
- acceptance_example: 可证明 post-control 先于另一账号 baseline 的 G 迁为上控后群；无时间证据的双 true 按所选兜底规则处理并进入报告。

## D2-05 用户可见合同与过渡期

- owner: 群业务 owner
- question: 页面和 API 是否从本期起只呈现一个分类，并取消“同时属于两类”？
- why_blocking: 前端当前支持双标签与 `BOTH` 筛选，必须明确是立即收口还是长期兼容旧语义。
- **A（推荐）**: 页面立即只显示一枚分类并移除 `BOTH`；API 新增单值 `groupClassification`，旧双布尔在一个发布窗口内只读兼容且保证互斥，随后删除。
- B: API 与页面一次性破坏式切换为单枚举，不保留旧字段。
- C: 数据库唯一分类，但页面继续保留双标签/`BOTH` 兼容。
- default_behavior: 未确认不改变公开 API/页面筛选。
- acceptance_example: 每行最多一枚“历史群”或“上控后群”标签，筛选项只有两类；旧客户端在过渡期收到的两个布尔不可能同时为 true。

## D2-06 成功标准与业务签认

- owner: 当前提出人
- question: 是否以本包推荐纵切作为本次流程实验的业务范围，并由当前提出人承担业务验收？
- why_blocking: 没有业务 owner 与成功标准不能冻结 `scope_hash`，也无法判定测试环境结果是否被接受。
- **A（推荐）**: 是；以“零新增双分类、首次写后不变、历史迁移可审计、页面单标签”为成功标准。
- B: 只做技术原型和本地测试，不进入 test1/业务验收。
- default_behavior: 未确认停在 `NEEDS_BUSINESS_DECISION`。
- acceptance_example: owner 确认一次性决策后生成 `scope_hash`，代码、测试和 Runner 报告都引用同一范围。

## D3 提醒（现在不要求授权）

后续部署到 test1、运行 Flyway、用真实测试账号制造 baseline/self-add 事件、安装 Runner soak/CloudWatch wrapper 或修改实例 IAM，都需要在动作前单独确认目标环境和安全信封。本次 D2 回复不自动授权这些远程动作。

## 决策日志

| 决策 | 结论 | 状态 |
|---|---|---|
| D2-01 | A：租户内规范群独立定类 | `CONFIRMED` |
| D2-02 | A：仅完整 baseline 与 baseline 后可靠新增事实有资格定类 | `CONFIRMED` |
| D2-03 | A：首个成功原子写入获胜，已定类不可追溯改写 | `CONFIRMED` |
| D2-04 | A：最早可靠证据迁移，歧义双分类保守归历史群 | `CONFIRMED` |
| D2-05 | A：API 单枚举、旧布尔兼容一窗口、UI 单标签无 BOTH | `CONFIRMED` |
| D2-06 | A：当前提出人为业务 owner，采用推荐成功标准 | `CONFIRMED` |
