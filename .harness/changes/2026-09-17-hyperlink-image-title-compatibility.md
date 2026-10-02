# 带图按钮标题兼容候选

> 已被用户后续的[标题选填方案](2026-09-17-hyperlink-optional-title.md)替代。下文是历史实现记录；自动合并标题到正文的代码及合并长度门禁现已撤销。

- 日期 / 分支：2026-09-17 / armada 1.0.3-snapshot（共享本地工作区）
- 需求来源：用户确认第二套环境同一任务在不同手机漏显示标题，普通按钮与卡片按钮均出现；要求检查协议并解决。
- 状态：本地候选已实现并通过相关测试；未提交、未推送、未部署、未真实发送。
- 最新范围：用户随后要求优先调整「图片链接卡片」。本记录中的两种按钮改动暂缓发布，保留本地候选；当前工作见 [图片链接卡片完整文案](2026-09-17-image-link-full-copy.md)，不可将两者合并发布范围。

## 依据与边界

前序[协议复核](../../docs/operations/evidence/hyperlink-render-perf2-20260916/protocol-review.md)确认 Header.title 与 imageMessage 可同时编码；任务 4 六条出站记录的标题一致。没有接收端同一消息的解码证据，不能把操作系统、图片与标题互斥或某个版本字段定为原因。

本次实现的是改变字段布局的兼容方案，不是独立 Header.title 显示故障的根因修复。已向用户提出该布局选择，尚未收到明确选择；目前仅保留可审阅的本地候选。

## 候选行为

仅当超链消息类型为普通按钮（3）或卡片按钮（4），且配置主图时：

- Header 继续携带图片，清空 title，避免正常显示标题的手机重复显示。
- 原标题分行加粗，作为 Body 首段，空一行后接原 Body。已有星号的行保持原样，避免嵌套格式。
- 普通按钮原 Body 为 content；卡片按钮原 Body 为 cardText，原 footer 仍为 content。
- 编辑器、模板持久化字段、冻结内容、图片、按钮文字与 URL、短链规则均不改写。普通按钮的标题与原底部文字合并后，视觉层级可能与原 Header/Body 的原生样式不同，需要用户验收。
- 无图按钮及单图文（LINK_CARD）保持原发送布局。

共用 HyperlinkImageButtonText 在模板保存、任务 START 门禁和命令构造中校验合成文本。沿用现有 Body 上限 1024（Java UTF-16 长度，包含格式标记和换行），不截断。超长新内容拒绝保存，超长历史快照拒绝启动或构造消息。

## 验证

先增加命令构造回归测试：旧实现 10 个用例中 4 个失败，其余通过。实现后相关 7 个测试类共 62 个用例通过，0 failures / errors / skipped：

| 类 | 用例数 |
|---|---:|
| HyperlinkMessageCommandFactoryTest | 10 |
| HyperlinkMessageContentValidatorTest | 15 |
| HyperlinkTaskDraftLifecycleTest | 1 |
| AndroidMessageSendBackendTest | 17 |
| WebMessageSendBackendTest | 6 |
| HyperlinkDispatchServiceTest | 11 |
| HyperlinkDispatchConcurrencyTest | 2 |

在 armada-api 下执行 `mvn -q -Dtest=<上述类名，以逗号分隔> -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar test`。实际分两组执行：前五类一组、后两类一组，均 exit 0。第一组命令另外误写了一个不存在的 HyperlinkTaskRoundDispatchTest，并使用 failIfNoSpecifiedTests=false；上表只统计实际生成的 Surefire XML，不把该类算作已验证。

新增断言覆盖两种带图按钮完整保留文字、标题不重复、图片/CTA/冻结原文不变、可选正文为空、已有加粗和空段、1024/1025 边界以及超长先于素材加载/绑定拒绝。其余为既有流程回归，不代表已增加端到端手机号验收。

静态核对两种协议适配器均允许空 title；任务 START 的门禁发生在任务版本更新及资源准备之前。`git diff --check` 通过。

## 部署与实机验收尚未执行

1. 先确认用户接受改变发送字段布局；如必须保留独立 Header.title，本候选不满足要求，应继续取得接收端解码与客户端版本证据。
2. 发布前检查 perf2 现有待运行/运行中的带图按钮任务合成长度，避免此前合法但合并后超长的内容在发布后发送失败。不得静默修改冻结数据。
3. 只发布本候选及必要依赖的已审阅版本；工作区包含其他人的账号导入/注册改动，不能一起带入。
4. 实际发送需要明确测试范围与授权。建议沿用用户的测试手机，分别验收普通按钮和卡片按钮：文字齐全、无重复、图片显示、按钮目标正确。真实发送、计费/点击副作用和测试残留应在执行前说明。
5. 接收端显示通过前只能称兼容候选，不能宣称第二套环境问题已修好。回滚时恢复原命令布局及合成长度门禁，持久化模板没有改动。
