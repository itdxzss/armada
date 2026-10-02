# 控台认证码关联结果反馈

## 范围与现状

- 用户要求查清并打通成功、失败反馈，确认目标为第二套 perf2。
- 当前分支 `1.0.3-snapshot`，工作区存在大量其他在途改动，未提交、未部署。
- 不修改推广落地页交互，不重写账号手机号，不改变协议账号 ID。

## 已核实证据

- 截图对应会话 8：后台在 2026-09-27 07:51:00 UTC 收到 `pairing.completed`；数据库随后为 EXPIRED，无 account_id。
- 该事件明确识别为 BUSINESS_STANDARD。回传号码含设备后缀与墨西哥旧版 521 前缀。
- 对回传号码去掉设备后缀，再将严格匹配 `521` 加十位数字的形式转换为 `52` 加十位数字，与本次输入精确一致。
- 原完成事件校验只去设备后缀，导致号码不一致的 BusinessException；原代码未将业务拒绝写入会话失败态，前端继续等待直到过期。
- 另确认查询接口在 30 秒事件投递缓冲期内提前返回 EXPIRED，导致前端停止查询；成功事件在父页面未绑定。
- 早前另一会话曾出现账号类型未知及死信 topic 不存在错误；并非本次号码差异事件的原因。当前已确认死信 topic 存在。

## 本次改动

- PromotionPairingEventSinkAdapter：仅在会话号码比对中兼容墨西哥两种形式；明确 BusinessException 落为 FAILED 并保留可展示原因；传输异常继续抛出供重试。
- ControlPairingServiceImpl：事件投递缓冲期隐藏过期认证码，但保留非终态，继续等待回传。
- 前端 AccountPairingDialog：显示正在自动查询；缓冲期显示正在等待后台结果。
- 前端导入页绑定成功事件刷新列表。
- 前端本地浏览器夹具限定 API 拦截到 `/api/`，避免错误拦截 Vite 的 `/src/api/` 模块。

## 验证

- Java 17：ControlPairingServiceImplTest、PromotionPairingEventSinkAdapterTest、PromotionPairingCompletionServiceTest、ProtocolPairingEventConsumerTest，共 30 项通过。
- 新增回归已先复现失败：墨西哥旧前缀、完成事件业务拒绝未更新失败、事件缓冲期误报过期。
- 前端本地 Playwright（已有 Chrome、API 夹具）8 项通过：成功、失败原因、较慢请求、断网恢复、旧响应隔离。
- 前端 API/组件契约 3 项通过；typecheck、定向 ESLint、生产 build、相关 diff check 通过。
- 未做线上真实手机重新关联验收；上述本地浏览器测试不代表真实 WhatsApp 验收。

## 发布阻塞

- 运行 JAR 与服务器部署 JAR SHA-256 已核对一致。
- 尝试将该基线 JAR 复制到本机临时目录以制作只含本次类改动的隔离补丁，被自动审批拒绝，未执行复制。
- 审批理由：当前授权未覆盖完整内部运行制品复制与发布准备。未绕过，未修改线上服务。
- 待用户明确授权复制该内部基线并仅向 perf2 发布本次修复后，重新准备隔离制品、审查并按部署规则验证；不得直接发布整个脏工作区。
- 已过期的会话未手工改回成功、未自动重放旧事件。

## 第二套发布完成

- 用户随后明确授权“速度发布吧”。2026-09-27 已发布到 perf2；没有提交或推送源码。
- 后端从已核验的运行 JAR 基线隔离构建，使用 Java 17、运行基线类和依赖编译；ZIP 内容对比确认只替换 ControlPairingServiceImpl 和 PromotionPairingEventSinkAdapter 两个 class。
- 运行 JAR SHA-256：`099a90f2e881ba41de7a0c731162d6a63ffd65740d07bbbd4d4714e3327943c5`。
- 后端镜像 `armada-perf-backend:pairing-feedback-20260927`，前端镜像 `armada-perf-nginx:pairing-feedback-20260927`。
- 自动审批拒绝完整前端目录复制；采用允许的小范围替代，只读取配对模块与导入页父模块。前端采用本地已测试弹窗编译模块，沿用线上依赖导出及 CSS scope；父模块只增加成功回调，线上其余模块业务代码不变。
- 服务器本地复制前端基线作为发布目录，统一给模块引用加 `pf=20260927` 查询版本以刷新 immutable 缓存；未下载整个 dist。
- 使用现有服务器 compose 配合仅指定镜像与原环境变量的覆盖文件部署；配置比较确认环境变量完全一致。原镜像、制品均保留用于回滚。
- 两容器 running，RestartCount=0；后端成功启动；运行 JAR 哈希匹配；真实配对状态接口未认证请求返回预期 40104。
- 针对第二套实际发布的前端资源，使用 API 夹具验证成功刷新与失败原因，两项浏览器测试通过（29.3 秒）。不等于真实手机重新配对验收。
- 发布记录及回滚文件位于服务器 `/home/app/armada-deploy/releases/pairing-feedback-20260927/`。不得输出其中环境覆盖文件内容。
- 待用户刷新页面重新发起手机关联，确认真实手机至账号落库的最终闭环。
