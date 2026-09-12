# 养群纯图片修复与批量发送诊断

日期：2026-09-11。续接 [首轮实测报告](2026-09-11-group-maintenance-test-report.md)。本次用户要求：修复纯图片；排查批量发送不稳定的原因。

**结论：纯图片已修复并部署到 test1，两轮真实发送累计 8/8 成功（任务 11、12）；批量问题已定位到账号断线重连与协议发送准入缺口，协议层本次未修改。**

## 1. 纯图片修复与复测

根因：共享 `MarketingMessageComposer.composeText()` 在判断实际消息类型前就拒绝空文字。素材层允许纯图片，但剧本定义、任务检查和创建调用真实组装器时失败。

最小修复：先拼接并校验说明长度；当图文模式有真实图片字节时允许空说明并生成 IMAGE。其余消息仍要求非空文字，无图片/空文件不能通过纯图片分支。图文、普通文本、按钮及 4096 字长度限制保留。

修改文件：

- [MarketingMessageComposer.java](../../armada-api/src/main/java/com/armada/marketing/service/MarketingMessageComposer.java)
- [MarketingMessageComposerTest.java](../../armada-api/src/test/java/com/armada/marketing/service/MarketingMessageComposerTest.java)：增加纯图片、无有效图片、非图片空文字、超长说明回归。
- [ScriptMarketingImageContentTest.java](../../armada-api/src/test/java/com/armada/marketing/script/ScriptMarketingImageContentTest.java)：通过真实 converter/composer 验定义角色、任务角色、内容快照与协议 IMAGE payload。

测试先红后绿：新增纯图片断言首先复现“营销模板发送内容为空”；修复后，7 个聚焦测试类共 **45 项通过、0 失败、0 跳过**，包含定义/执行/图片资源 H2 测试。主工作区与独立发布候选均执行通过。最初 Mockito 在沙箱中无法附加 JVM 代理；改为本机执行相同测试通过，未连接真库跑单测。

### test1 部署

- 基线为已在 test1 运行的 `d8e3a5448b72cad5155319fbce5cb92e81df01fa`，独立目录 `/private/tmp/gm-image-fix-20260911`，只带本次 3 个文件差异。未混入主工作区其他在途修改，未提交或推送。
- 通过现有 `deploy-test.sh --env test1 --be -y` 更新后端，退出码 0。前端和 Android/Web 协议层未部署，未新增迁移。
- 新运行 JAR SHA-256：`c7c4f28422db34e86ec544f5ebcf0bd1a513a5767c3ab43dccd5e89b83230f7e`，与本地候选构建一致；容器 running=true、restart=0，V180/V186/V187 仍成功。
- 发布脚本语法和主仓 `deploy-test.test.sh` 通过。隔离目录运行脚本测试最初缺少兄弟仓库路径/私钥路径；生产离线打包测试在隔离目录缺基线 `.env.example`，未记为通过，本次未做生产打包/部署。

### 真实业务结果

使用原测试图片 **34**，`linkMode=3, content="", bodyText=""`：

| 检查 | 结果 |
|---|---|
| 创建纯图片剧本定义 | 成功，定义 **5** |
| 任务资格检查、保存、启动 | 成功，任务 **11** |
| 两个既有测试群，管理员和推手各发一张 | **4 个原命令，4 成功、0 失败、0 未知** |
| API 中标题和正文 | 均为空字符串 |
| outbox 实际命令 | 4 条均为 `messageType=IMAGE`，caption 长度 0，携带图片 assetRef，已投递 |
| 独立协议结果 | 4 条匹配原命令的成功事件 |
| 去掉图片后仍提交空内容 | 40001 拒绝 |

任务已完成，测试浏览器会话已关闭。仍未取得独立成员设备的图片显示证据；本次证明的是原接口缺陷修复与真实 IMAGE 发送链路成功。

### 第一套环境追加复测（任务 12）

按用户要求再次登录页面标题为“第一套环境”的 test1，使用业务接口新建剧本 **6**、任务 **12**，没有复用任务 11 的结果。本轮两个账号：管理员 **685**、推手 **690**；两个现有测试群：**7506、7507**。原图片 **34** 的标题、正文均为空。

- 检查、创建、启动成功；任务最终为执行完成，**4 成功、0 失败、0 未知、0 发送中**。
- 两群各完成两步，均未暂停。四条数据库原记录为 **255–258**，实际命令均为 `IMAGE`，说明长度 **0**，携带 `assetRef`，全部已投递。
- 独立读取协议事件，四个原 commandId 均匹配到 `message.send_result_reported success=true`，读取完成且未提交消费偏移量。
- 反例仍通过：移除图片并保持空标题、空正文时，检查接口返回 **40001**。
- 当前容器内部 `/app/app.jar` SHA-256 与上述修复制品一致，running=true、restart=0；本轮专用浏览器会话已关闭。

本轮证据：[GM-20260911-image-retest](../testing/evidence/group-maintenance/GM-20260911-image-retest)。本轮与上轮累计 **8/8 协议发送成功**；仍未观察接收设备上的图片显示，不据此认定断线重连或大批量稳定性问题已修复。

## 2. 批量任务 7 的原因时间线

以下时间均为 **UTC**，北京时间加 8 小时。对照范围为原任务 7、账号 685/687、发送记录 230/231；没有重新发送这两条失败记录。

| 时间 | 证据 | 解释 |
|---|---|---|
| 00:32:26.580 | Android node3：`read server data error eof`，generation 19 关闭，进入 Reconnecting | 管理员 **685** 连接断开 |
| 00:32:27.598 | 后端写回 685：`loginState=2, stateSource=RECONNECTING` | 后端资格检查不再认为管理员在线，与第一次 7 群暂停吻合 |
| 00:32:29.943 / 00:32:30.963 | 协议恢复 Online / 后端收敛 ONLINE | 约 3.4 秒恢复；后来重查已合格不代表此前没有断线 |
| 00:34:06.029 | node2 账号 **687**：EOF，generation 15 关闭，进入 Reconnecting，准备 generation 16 | 第二个断线窗口 |
| 00:34:06.970 / 00:34:07.169 | 原记录 230 / 231 创建提交意图 | 位于断线后、后端状态传播和调度并发窗口附近 |
| 00:34:07.039 | 后端 687 写回 RECONNECTING、loginState=2 | 后续群资格检查因此暂停 |
| 00:34:07.430 / 00:34:07.933 | 两个失败目标群原生 dispatch 日志仍显示成功；同刻 sender 报 `use of closed network connection` | dispatch 成功只代表异步入队，实际 socket 写入失败 |
| 00:34:10.147 / 00:34:11.163 | 协议 / 后端恢复 ONLINE | 快速重连成功，不是持续离线 |
| 00:34:17.017 / 00:34:17.034 | 后端收到原命令 `success=false`，统一 `SEND_FAILED` | 对应记录 230、231；不是缺群人数造成的启动拒绝 |

两个目标群是 `120363430638294217@g.us`（条目 7513）和 `120363427447992430@g.us`（条目 7515）。两条原命令为 `cmd_d203f81635164468bad15d2a843dfae5`、`cmd_b3fa292cb6264f7cbc7605489aee4012`。

00:34:49 还观察到账号 686 短暂重连，说明本轮不能只盯 687。当前只读查询时 685/686/687/690/692 均已 ONLINE，平台消息限制 active=0；这不否定过去的断线。协议节点图像也存在差异：node1/node3 为 `fleet-34903cb`，node2 为 `contact-target-20260909-1510`。**版本不一致是后续验证条件，尚不能证明它是 EOF 原因。**

## 3. 已定位的协议层缺口

当前源码与线上行为相符：

1. [app/cache.go](../../../whatsapp-server-feature-android-zhuan/internal/service/app/cache.go) 的 `GetValidWSApp()` 返回条件包含 `Online/Ready/Reconnecting/RetryLogin515`，所以“得到有效实例”不等于“连接可以发送”。
2. [message_sender.go](../../../whatsapp-server-feature-android-zhuan/internal/armada/message_sender.go) 的 `ensureCurrent()` 只比较查到的 WaApp 是否仍是同一实例；同实例内部快速重连时可以通过。
3. [prepared_group_send.go](../../../whatsapp-server-feature-android-zhuan/internal/service/node/prepared_group_send.go) 注册发送凭据后返回；[group_send_ticket.go](../../../whatsapp-server-feature-android-zhuan/internal/service/node/group_send_ticket.go) 通过 `SendBuilder()` 异步投递并等待 ACK。此时返回 ticket 不证明实际 socket 写成功。
4. [sender.go](../../../whatsapp-server-feature-android-zhuan/internal/service/node/sender.go) 的队列处理错误只记录日志后继续；`CompleteSend()` 再把 ACK 非成功统一归入 `SEND_FAILED`，丢失了“重连窗口／关闭连接”的直接原因。

因此可确认两个层次：

- **触发原因：** 真实 TCP/Noise 连接 EOF 后快速重连；没有证据认定是封号，也没有足够证据区分代理、中间链路或 WhatsApp 对端为何关连接。
- **放大失败的实现缺口：** 重连状态仍可进入发送路径，异步队列入队成功与真实写入成功混用，底层写失败未准确反馈原命令。后端资格保护有状态传播延迟，无法代替协议层最后一次发送前校验。

原暂停机制符合当前设计：原绑定失效时暂停，重新合格后需要显式继续，不自动换号。不能为了少暂停就把重连中的账号继续当作在线，也不能通过自动重发未知消息掩盖问题。

### 后续修复边界

协议层另行修复时，优先在消息发送入口和最终写入处校验可发送状态、当前连接代次；不要直接收紧被生命周期流程共用的 `GetValidWSApp()` 而影响重连流程。明确未写入的消息应反馈可恢复的未投递状态并保留原命令；写入结果不确定时保持 UNKNOWN，不盲目重发。同时保留底层失败原因及 commandId，并对“准备后断线、typing 中断线、入队后断线”补竞态测试。

**本次按用户要求完成原因排查，尚未实施或部署上述协议层变更，不能宣称批量稳定性已修复。**

## 4. 证据与回滚

证据目录：[GM-20260911-image-fix](../testing/evidence/group-maintenance/GM-20260911-image-fix)。包含纯图片请求/结果、DB 命令核对、独立协议事件、node2/node3 脱敏断线日志、后端状态事件、验证摘要。首轮的失败记录和用例结论保留，新的修复结果不覆盖旧现场。

如需回滚纯图片变更，只回退本次 `MarketingMessageComposer` 差异并构建原 test1 基线；无需数据库回滚。本轮未新增数据结构、权限、接口参数或协议配置。
