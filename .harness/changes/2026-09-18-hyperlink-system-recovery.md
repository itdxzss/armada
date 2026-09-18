# 超链系统故障恢复

用户最终确认：按五项修改；历史任务不处理，只改代码，不部署。三个主仓均存在其他在途修改，逐文件小步修改。

## 实施与边界

1. 协议区分 NOT_SENT、FAILED（明确拒绝）、UNKNOWN、SUCCESS；只对超链启用新决策，避免改其他营销。
2. 会话/LID 的重试刷新目标设备和密钥，复用现有 LID 准备能力；不把会话失败当作目标无效。
3. 确定未发送的系统错误退避补发；原命令结果已固定后才创建新 attempt。使用已有 recipient / outbox / Redis fence，不加表。
4. Android 对账使用独立 query 命令，只读状态、不领取发送权；缺失结果保持 UNKNOWN。迟到 server ACK 复用可靠 ACK inbox 推进。
5. 每个目标连续三次系统失败后暂停任务；目标仍 PENDING、资金不自然结算。人工恢复后开启下一组有限重试。前端/API/导出统一业务文案，原始错误留在后台。

采用 30 秒、60 秒退避，第三次暂停。暂停需求复用现有任务 PAUSED；不新增任务状态。不保证未知最终能得到事实；查不到不重发。明确未注册或目标 JID 格式无效才能计为目标失败。其他明确拒绝/配置错误暂停待修复。

## 验证结果

- Go：gofmt、go vet ./...、go build ./... 通过。全量 go test ./... 的改动相关包通过；未改动的 pkg/noise 有 8 个测试用例失败（加密测试向量不符），未宣称全量通过。超链恢复、消息命令、对账路由、ACK 路径的定向 race 检查通过。
- Java：JDK 17 + 本地 Byte Buddy agent 执行 Hyperlink*Test 和 ProtocolCommandOutboxMapperInMemoryTest。371 个用例，365 通过、0 失败/错误、6 跳过（需要真实 MySQL 的两个测试类）。覆盖分类、重试幂等、旧回调、预算暂停/恢复、数据池不失败、真实 H2 Mapper、逻辑发送总数不重复投影。未连接远程 MySQL 或执行线上验收。
- 前端：4 个状态/文案用例通过；tsc、vue-tsc、定向 ESLint、Vite 构建通过，构建目录 /private/tmp/hyperlink-front-build。未进行真实浏览器业务验收。
- 发布需要后端与 Android 协议配套。分步发布宜先部署兼容新结果语义和只读 query 的后端，再部署协议；过渡期旧协议拒绝新 query，不可将查询退回发送。未做提交、push、部署或历史重放。

## 当前状态

本地五项实现与上述验证完成，保留其他在途修改。未新增表、列或数据库迁移。

补充：恢复动作也释放暂停期间后到失败产生的等待标记，避免恢复后立即再次因旧标记暂停。计划截止时间优先于自动暂停。结果未知仍可能长期待确认；没有健康账号时沿用等待资源，不能承诺一定成功。

日志：/private/tmp/hyperlink-java-verified.log、/private/tmp/hyperlink-go-verified.log、/private/tmp/hyperlink-go-race.log、/private/tmp/hyperlink-front-test.log、/private/tmp/hyperlink-front-typecheck.log、/private/tmp/hyperlink-front-lint.log。


## 本次独立提交验证

用户随后明确要求 commit、push；仅提交这次超链恢复相关改动，其他主仓在途修改保持原状，不部署、不处理历史任务。

为排除对其他未提交代码的依赖，从三个仓库当前 HEAD 导出源码，只覆盖本次文件清单构成独立副本：

- 后端：362 个用例，356 通过，0 失败/错误，6 个真实 MySQL 用例跳过。与主工作区 371 个用例的差异来自未纳入本次提交的其他在途测试。
- 协议：格式、vet、build、相关包测试、定向 race 通过；全量测试中的 pkg/noise 8 项失败与原工作区一致，该包未改动。独立导出副本起初缺少 Git 元数据造成的 deploy/fleet 单测失败，在副本 git init 后重跑通过。
- 前端：4 个用例、tsc、vue-tsc、定向 ESLint 和 Vite build 全部通过。

独立验证日志前缀：/private/tmp/hyperlink-commit-。本记录写于提交前，实际提交号和远端状态以本次 Git 结果为准。
