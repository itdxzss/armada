# 手机直传凭据控端入口：设计与实施计划

> 后续变更：用户已要求手机查询并选择分组。当前契约见 [选组联调提示词](../../2026-09-07-control-side-ingest-agent-prompt.md)；本文的固定分组、两字段请求和单路径约束仅保留为原始实施记录，不再作为当前接口要求。

- 日期：2026-09-07。
- 状态：用户已确认开始编码；已在 b68890ed 当前发布基线完成集成和本地定向验证，部署验收待办。初始旧基线结果另行归档。
- 分支：`codex/device-import-ingest-integration`。
- 工作树：`/Users/daishuaishuai/IdeaProjects/armada/.worktrees/device-import-ingest-main`。
- 需求依据：`wa-biz-compat-v8-extension/docs/2026-09-07-control-side-ingest-agent-prompt.md` 及同目录 `2026-09-07-phone-to-control-import-design.md`。
- 范围：Armada 接收接口、服务端令牌配置、专用 HTTPS 网关及验证。不包含手机代码、协议改造或管理员页面。

## 1. 依赖来源与主线集成

当前实现基于主线 `b68890ed`。管理端已支持 iOS 原生全参（运行格式 4），手机新入口按冻结契约保存六段（运行格式 1）：专用解析方法使用现有转换器，已校验条目复用同一个批次/明细落库流程。主线现有声明类型与有效类型校验逻辑保留。以下记录最初开发时的依赖核查，不表示当前仍停留在旧基点。

本工作树基于 `39e22427e7c68906c7bdf7aa6e3ee5051e6d109c`，完整承接 `codex/full-params-android-import-local` 工作树的 **14 个未提交文件**：12 个已跟踪文件改动和 2 个未跟踪的转换器/测试文件。文件清单与 SHA-256 见本次 change 目录的 `dependency-manifest.json`。源工作树和共享主 checkout 未被修改。

准备时该基点是主线 `6b763f1f` 的祖先，主线多出 **594 个提交**；交付时主线已推进到 `b68890ed`（595 个提交）。新分支的 Git 父提交本身不包含未提交全参实现，必须同时交付该依赖快照。本分支不能作为覆盖当前运行环境的旧版整包直接部署。进入部署阶段前，必须将“全参依赖 + 本任务增量”移植到确认过的当前发布基线，在独立集成工作树解决冲突并重跑门禁。

| 当前事实 | 本任务处理 |
| --- | --- |
| `AccountImportParser` 已支持 PARAMS 单行对象，经 `FullParamsToSixConverter` 转六段 | 直接复用；入口额外约束一条记录和手机号一致性 |
| `AccountImportRowWriter` 已将 PARAMS 写为 `protocol_id=ANDROID`、`cred_format=1` | 不新增凭据格式或协议分支 |
| 原文保存到 `account_import_detail.raw_payload`；成功明细写 `online_phase=1` | 原文原样保留，沿用 QUEUED |
| `AccountImportServiceImpl` 的批次、明细与行写入目前不在一个整体事务中 | 新单条入口增加外层事务，包住现有导入服务 |
| 批量导入将重复/格式错误计入明细，Controller 仍返回 HTTP 200 信封 | 手机入口将失败转换为实际 4xx/5xx，事务回滚；不把失败当上传成功 |
| `SecurityConfig` 保护 `/api/**`；Bearer 过滤器会建立租户上下文 | 为精确路径建立专用鉴权链，避免管理员身份影响令牌租户 |
| `GlobalExceptionHandler` 的业务和意外异常返回管理员响应信封，部分仍为 HTTP 200 | 新路径采用专用异常输出，覆盖过滤器、绑定、业务、提交异常 |
| `AccountImportOnlineDispatchScheduler` 仅在 `kafka` profile 启动，默认 fixed delay 10000 ms | 沿用现有调度，不新增 scheduler、不在请求内调用上线 |
| 当前 nginx 监听 80，并代理整个 `/api/`、提供管理页面 | 新建独立 443 网关，只代理设备导入精确路径 |

代码证据均相对当前工作树：

- `armada-api/src/main/java/com/armada/account/{converter/FullParamsToSixConverter.java,service/AccountImportParser.java,service/impl/AccountImportServiceImpl.java,service/impl/AccountImportRowWriter.java}`。
- `armada-api/src/main/java/com/armada/account/{job/AccountImportOnlineDispatchScheduler.java,dispatch/AccountImportOnlineDispatcher.java,dispatch/AccountImportOnlineDispatchWorker.java}`。
- `armada-api/src/main/java/com/armada/boot/{config/SecurityConfig.java,security/TokenAuthenticationFilter.java,web/GlobalExceptionHandler.java}`。
- `.harness/wiki/数据模型.md`、`armada-deploy/nginx.conf`、`armada-deploy/docker-compose.rds.yml`。

## 2. 冻结接口

```text
POST /api/device-imports
Header: X-Ingest-Token: <静态令牌>
Content-Type: application/json
Body: {"phone": "<7至15位纯数字手机号>", "payload": "<手机复制全参产生的完整JSON单行>"}
成功: 200 {"batchId": 123, "onlinePhase": "QUEUED"}
失败: 4xx/5xx {"message": "可读原因"}
```

`batchId` 是 JSON 数字。成功对象仅包含 `batchId`、`onlinePhase`，失败仅包含 `message`，不套 `{code,message,data}`。不增加请求字段、查询接口或回调。响应统一 JSON UTF-8，并设置 `Cache-Control: no-store`。

200 的含义是单条导入事务已提交，明细以 QUEUED 入队；不代表已上线、密钥已通过握手，也不保证恰好 10 秒内上线。调度可能在响应到达手机前推进明细，因此响应表示本次受理结果，不是状态查询快照。

### 输入校验

1. 先校验令牌，再读取/解析请求体；拒绝多值令牌头、空值和过长值。不接受 query、Cookie、Authorization 中的令牌。
2. 外层必须是单个 JSON 对象，`phone` 和 `payload` 都必须是非空字符串；拒绝类型强转、未知外层字段、重复 JSON key 和尾随 JSON/垃圾文本。
3. `phone` 用 ASCII `[0-9]{7,15}`，不自动剥离 `+`、空格、JID 域后缀。
4. `payload` 解码为字符串后不得含物理换行或 Unicode 行分隔符；必须是唯一 JSON 对象，拒绝数组、标量、重复 key、尾随对象。不得 trim 或重新序列化后替代原文入库。
5. 复用现有 parser/converter 校验 `jid`、`clientStaticPublicKey`、`clientStaticPrivateKey`、`identityPublicKey`、`identityPrivateKey`、`phoneUUID`；外层 `phone` 必须等于转换结果的 `phone`（来源是全参 `jid`）。全参内另一个 `phone` 字段不作为账号归属依据。
6. 手机当前导出 28 字段，接收端保留全部字段；校验沿用转换器必需字段，不为可空的非运行字段增加“必须非空”门槛，也不将字段总数写死。格式校验不宣称密钥密码学有效或账号已可登录。
7. 请求总大小上限 128 KiB，在网关和应用读取流两层执行，覆盖 chunked/缺失 Content-Length；不把凭据写到临时文件。

### 状态码和重复提交语义

| 场景 | HTTP | message/结果 | 新增业务记录 |
| --- | --- | --- | --- |
| 令牌缺失、错误、重复头或无效头形状 | 401 | 导入令牌无效 | 无 |
| phone/payload/JSON 结构非法、必需字段缺失、号码不一致 | 400 | 固定可读原因或白名单字段名；无字段值 | 无 |
| 同租户已有未删除账号，任意在线/离线/失败状态 | 409 | 账号已导入，请在控端查看并处理 | 无 |
| 同租户同号历史成功明细仍为 QUEUED/DISPATCHED，即使账号已删除 | 409 | 账号仍在导入上线处理中，请在控端查看 | 无 |
| 令牌配置对应租户已停用/不存在或分组失效 | 503 | 导入配置不可用，请联系管理员 | 无 |
| 请求过大 | 413 | 请求内容过大 | 无 |
| Content-Type 非 JSON | 415 | 请求必须使用 application/json | 无 |
| 精确路径上的 GET/HEAD/PUT/DELETE 等其他方法 | 405 | 请求方法不支持 | 无 |
| 网关其他路径 | 404 | 接口不存在 | 无 |
| 数据库不可用、事务失败等服务故障 | 503 或 500 | 导入服务暂不可用，请稍后重试 | 未成功提交则回滚 |
| 网关上游不可达/超时 | 502/504 | 导入服务暂不可用 / 请求超时，请在控端核对 | 可能已提交，不能以超时推断回滚 |
| 成功 | 200 | `{"batchId":123,"onlinePhase":"QUEUED"}` | 一批次、一账号、一状态、一凭据、一明细 |

不覆盖已有账号凭据，不以重传触发重复上线。账号软删除且不存在未终态导入后，才允许沿用原唯一键规则新建。跨租户保持当前导入的租户隔离与唯一键语义，本入口不迁移账号租户归属，也不宣称解决跨租户协议句柄去重。

网络断开可能发生在数据库提交之后。手机手动重传此时返回 409，不返回新 200；手机按冻结契约不会因此登出，需要在控端核对。若要让重传安全恢复 200 并继续登出，需要另行确认幂等契约，本次不暗改。

仅对相同精确路径支持 OPTIONS 204，返回 `Allow: POST, OPTIONS`，不返回任何 `Access-Control-Allow-*` 头，不启用 CORS，不开放其他 OPTIONS 路径。OPTIONS 不导入、不接收业务凭据。

## 3. 令牌与服务端默认值

运行环境通过 **`ARMADA_DEVICE_INGEST_CLIENTS_JSON`** 注入令牌映射数组；仓库只记录字段结构，不保存真实或固定可用令牌、真实租户映射或凭据样本。

| 配置字段 | 校验 / 用途 |
| --- | --- |
| token | 高熵静态令牌，至少 32 个随机字节生成的可打印编码；不允许空白、重复；与手机包配置对应 |
| tenantId | 正整数；请求时核实租户仍启用，建立服务端 TenantContext |
| accountGroupId | 正整数；必须属于该租户且未删除，不自动创建默认组 |
| deviceOs | 1=安卓、2=苹果；只决定展示/筛选，不改变 PARAMS 固定 ANDROID 路由 |
| accountType | 1=个人、2=商业；导入时冻结，WA Business 应配置为 2 |
| ipAllocationMode | `smart`、`mixed`，或空值表示沿用指定 ipRegion 的现有语义 |
| ipRegion | 使用现有代理池地区口径；指定地区模式必填；不接收手机提供的 IP |

“IP 默认值”复用现有导入的地区/分配策略，由既有上线服务分配实际代理，不新增指定原始 IP 或代理凭据字段。

变量缺失、空映射、JSON 非法、令牌重复、必填项缺失或枚举非法时启动失败；日志仅包含变量名与固定错误码。配置读取避免让绑定异常携带原始环境变量或嵌套 Jackson 原文异常；不将令牌放进可自动输出内容的 record/toString。移除环境变量不是功能关闭方式：这会按契约令启动失败。

令牌按固定长度摘要进行恒定时间比较，不记录令牌摘要；配置仅保存在进程内。请求完成或异常后清理租户和安全上下文。令牌轮换通过安全更新运行环境及重启生效；同一套默认值可临时配置多个不同令牌，删除旧令牌后旧手机包不能再上传。

## 4. 分层与事务

实际实现边界：

- `boot/config/DeviceIngestConfig`：读取环境配置、安装独立请求转换器；专用安全链装配在既有 `SecurityConfig`。
- `boot/security/DeviceIngestTokens`：严格校验令牌映射，只保留摘要和服务端默认值。
- `boot/web/DeviceImportRequestConverter`：严格外层 JSON 形状、重复 key 和流式大小限制。
- `boot/security/DeviceIngestAuthenticationFilter`：只匹配精确路径；校验令牌、核实启用租户、恢复和清理上下文。通过已有 `platform/tenant/mapper/TenantMapper.selectActiveById` 查询租户注册表。
- `account/model/dto/DeviceImportDTO`、`account/model/vo/DeviceImportVO`：冻结入参/出参；DTO 的字符串表示不暴露 body。
- `account/controller/DeviceImportController`：接收请求、调用服务、输出裸 JSON。
- `account/service/DeviceImportService` 与 `impl/DeviceImportServiceImpl`：入口校验、服务端默认参数、未终态查重、外层事务、批次结果判定。
- `boot/web/DeviceImportExceptionHandler`：限定设备路径的真实 HTTP 错误和脱敏输出；过滤器错误使用相同响应结构。

静态令牌过滤器只注册到专用链，避免作为 Servlet filter 二次运行；既有 `TokenAuthenticationFilter` 对该精确路径明确跳过，即使请求同时携带有效管理员 Bearer 也不能改写租户。其他路径维持原鉴权。新接口没有管理员 `@PreAuthorize`，也不授予令牌身份任何管理员权限。

新服务公开入口使用 Spring 代理生效的 `@Transactional(rollbackFor = Exception.class)`。执行顺序：

1. 校验外层字段和单行结构，调用既有 parser 验证转换结果与 phone 一致；格式不合格即抛安全业务异常。
2. 在当前令牌租户内检查同号未终态明细；复用 `AccountGroupService.requireExisting` 检查分组。
3. 构造 `AccountImportDTO`：固定 PARAMS=3，分组/机型/类型/IP 来自令牌，来源文件名用固定非敏感标识。
4. 调用既有 `AccountImportService.importAccounts(meta, null, 原始payload)`，仍由它解析、建立批次、调用 RowWriter、写原文和 QUEUED 明细；预校验与正式导入两次纯内存解析，不复制字段转换实现。
5. 仅当 totalRows=1、importedRows=1、duplicateRows=0、formatErrorRows=0 时生成成功 VO；重复转换为 409，其他不合格结果转换为 400 并回滚。
6. 外层事务提交成功后 Controller 才输出 200；提交失败走安全异常响应。既有 RowWriter 的 REQUIRED 事务加入外层事务，不改为 REQUIRES_NEW。

特别验证：RowWriter 并发撞唯一键后会将参与事务标记 rollback-only；既有批量服务捕获重复异常后，新入口必须继续抛 409 业务异常并结束事务，不能正常返回到提交阶段造成 `UnexpectedRollbackException` 误报 500。明细/批次写入任何一步失败都不得留下孤儿账号。

同租户并发导入最终由现有 `uq_tenant_phone(tenant_id,ws_phone,is_active)` 仲裁，前置查询只是可读提示，不以“先查后插”或 JVM 锁替代数据库唯一键。不新增 Redis 锁、表、列、索引或 Flyway。

仅在 `AccountImportDetailMapper` 和 XML 增加按当前租户、phone、成功结果和 QUEUED/DISPATCHED 判断存在性的有界 SQL 查询，筛选下推，复用租户插件，不新增跨租户豁免。既有调度的 `@InterceptorIgnore` 与显式 tenant_id 锁行 SQL 保持原语义，并纳入测试。

## 5. 凭据保护

- 真实全参只进请求内存、现有 `raw_payload` 和六段凭据列；不读取或复制设备导出样本到本工作树。
- 新入口只记录 batchId 和固定错误码。沿用导入路径的日志移除账号片段、凭据长度、解析源片段及异常原文；不打印 DTO、JSON、令牌、SQL 参数或异常 cause 栈中的 payload。
- 新路径的 JSON/参数绑定/事务失败必须进入专用安全处理，不能落入当前会输出异常栈的全局处理器。
- 该部署关闭请求体/请求头调试、MyBatis 参数日志和凭据配置的诊断输出；检查代理访问日志、异常日志与临时缓冲，不仅检查业务 log 调用。
- 测试使用运行时构造的无效占位凭据/随机哨兵，不使用真实设备导出、固定可用令牌或真实账号密钥。内容一致性断言比较布尔结果/散列，避免失败报告打印整个凭据。
- 内网管理员导出继续沿用现有权限；公网不存在导出、批次查询、Actuator、Swagger、登录、推广或后台页面路径。

## 6. 公网部署设计

在 `armada-deploy/` 增加独立设备导入 nginx 配置及 Compose 叠加配置。网关与 backend 在私网/容器网络互通，backend:8080 不发布到公网。

1. 专用 nginx 只监听公网 443，必须有受手机信任、域名匹配的完整 TLS 证书链；不开放 80，不做 HTTP 跳转或 HTTP-01 验证入口。
2. `location = /api/device-imports` 是唯一业务代理；额外核对原始 URI，拒绝尾斜杠、子路径、编码/归一化绕路及 query。其他路径和未知 Host 均不代理；不包含管理站点静态资源及通配 `/api/`。
3. POST 转发到 backend 的相同路径；OPTIONS 仅原路径本地返回；其他方法 JSON 405。代理只保留所需令牌头，清除 Authorization、Cookie 和客户端伪造的身份/租户头，覆盖转发来源头。
4. 网关配置 128 KiB 请求上限，关闭请求/响应缓存，上传流式转发；可能承载请求体的临时目录使用 tmpfs，禁止凭据落磁盘。
5. 网关错误页统一为 `{message}` JSON，覆盖 400/404/405/413/415/500/502/503/504；上游已返回的安全业务 JSON 原样透传。不能让失败返回 HTML 或 HTTP 200。
6. 新网关不记录 header、body、query、原始 request line；设备入口访问日志关闭，错误日志配置不得采集敏感请求片段。
7. 令牌仅注入 backend 环境，不生成带令牌的 nginx 文件，不写进镜像；证书/私钥以服务器外部只读挂载提供。安全配置文件权限由运行环境控制，不打包上传到 git。
8. 原管理员 nginx 的 18080/80 绑定私网接口或回环，安全组/防火墙不允许公网直达。必须核查 Docker 发布端口、IPv4/IPv6、安全组、已有网关和后端端口，新增 443 并不自动证明其他路由已封闭。

部署时先确认**测试环境主机/入口域名、当前发布基线、backend 私网地址、证书位置、令牌对应租户/分组/账号类型/IP策略**。令牌实际值只通过安全的运行环境注入，不要求粘贴到对话。生产须另行明确确认；当前未确定目标，未 SSH、未修改安全组、未连接真库。

## 7. TDD 与验收计划

| 层 | 必测用例与证据 |
| --- | --- |
| 配置 | 缺失/空/非法映射启动失败；错误报告不包含配置值；重复 token、无效默认项失败 |
| 安全链/MVC | 无效 token=401；正确 token 无 Bearer 可访问；同时携带另租户 Bearer 不改变归属；单 token 不可访问管理员 API；成功与全部错误响应结构准确；无 CORS |
| 输入 | 非 JSON、数组/标量、多行、重复 key、尾随对象、缺字段、非字符串、号码不一致；大 body 和流式 body=413；错误不回显哨兵 |
| H2 真实 Mapper/事务 | 单条成功五表归属正确，原文保留，PARAMS→SIX/ANDROID/QUEUED；无效分组/不同租户分组拒绝；任意写入失败五表回滚 |
| 并发/重复 | 同租户同号两连接同时请求只有一次成功，其余 409，无孤儿批次/账号；已存在离线/在线账号不覆盖；旧未终态明细阻止重导；租户上下文清理 |
| 调度复用 | QUEUED 被既有 worker 选中，Android/SIX outbox 受理后 DISPATCHED；拒绝/异常仍保留 QUEUED；锁行 SQL 的租户条件有效 |
| 日志 | 使用随机哨兵捕获应用、解析、异常及网关日志，确认无 payload/token/私钥值；断言输出不包含敏感对象 |
| nginx/Compose | bash -n、配置结构测试、nginx -t、Compose 校验、TLS 实际请求；仅精确路径 POST/OPTIONS 可达，其他路径/方法/端口不能触达后台 |
| 测试环境/真机 | 公网 HTTPS 有效证书；真实 phone 上传 200，控端按默认配置落库，观测既有调度、协议握手和在线回调；手机登出行为由手机端联调确认 |

H2 使用 test scope、真实 XML、MyBatis-Plus 租户插件和 Spring 事务，不启动生产配置或连接真实库。H2 无法覆盖的 MySQL 方言/锁语义增加 SQL 结构测试，授权后的指定测试 MySQL DbTest 再补证据。提示词要求的真库与 E2E 验收保留为待办；当前仓库规则允许先完成 H2 本地门禁，不能将其写成已完成真库验证。

普通 `mvn test` 在该旧基线会包含连接真库的 DbTest，因此完整本地门禁须显式排除 `*DbTest`，还要排除实际上继承 DbTestBase 的 `GroupLinkRegistryServiceImplTest`、`GroupCreationMarketingTaskServiceImplTest`；新增 H2 测试命名为 `*Test`。JDK 必须 17；本机 Mockito 如无法动态挂载，测试 JVM 显式加载匹配版本 Byte Buddy agent。定向新测试先记录红灯，再实施至绿灯，之后运行本地回归、测试编译、打包和相关部署脚本测试。

2026-09-07 基线实测：JDK 17.0.19 加载匹配版本 Byte Buddy agent，继承实现 81 项单测通过。实施后新入口与继承链路合计 112 项定向测试通过（新增 30 项），独立 nginx/Compose 9 项验证通过，打包通过。全库回归存在已在原基线副本复现的既有失败，详见 [.harness 交付记录](../../../.harness/changes/2026-09-07-device-import-ingest/delivery.md)。真实协议上线与手机交接未执行。

## 8. 实施顺序（设计复核后执行）

| 次序 | 单项工作（每项不超过 4 小时） | 交付/门禁 |
| --- | --- | --- |
| 1 | 配置、安全链、请求/响应契约测试先红；实现令牌加载与隔离 | 配置及 MVC 测试绿，无真实凭据 |
| 2 | 输入、原子导入、重复语义测试先红；实现服务层与必要查询 | 真实 Mapper/事务 H2 测试绿 |
| 3 | 日志哨兵、调度及管理员鉴权回归 | 无凭据泄漏；原入口和调度回归绿 |
| 4 | 专用 TLS nginx、Compose 叠加文件、部署/回滚文档 | 配置验证与本地 TLS 冒烟 |
| 5 | 定向及本地全面门禁，安全/后端评审，补交付证据 | 可复核变更、基线依赖与测试报告 |
| 6 | 确认测试目标并在当前发布基线集成；部署和真机联调 | 单列部署、真库、调度、协议上线、手机交接结论 |

设计阶段不提交代码、不推送、不部署。后续 commit/push、基线集成、部署和实机验收分别记录，不将“构建成功”写成入口已交付。

## 9. 回滚与未解决的边界

- 紧急关闭：先撤销专用 443 网关或其公网放行，阻断新上传；保留控端内网管理能力。运行中仅删除环境变量不会撤销已加载的令牌，轮换须重启。
- 应用回滚：恢复部署前的当前发布镜像及其配置，不能恢复成本工作树的七月基点；本任务无 schema 迁移，不删用户数据。
- 已受理批次/凭据保留审计，既有调度可能继续处理 QUEUED。若需中止这些任务，应经控端现有管理能力另行明确处理，不能把“关入口”当作“取消上线”。
- 10 秒调度可能先于手机用户确认登出触发，本次契约没有“手机已退出”回执，无法证明零抢登；联调需观察真实交接。如果要严格等待手机退出，须两侧另行修改契约。
- 全参转换单测只能证明字段映射，iOS 来源全参转 Android 六段的真实账号可登录性仍依赖测试环境握手和当前运行回调。
- 当前等待项：部署目标及非敏感默认配置参数。用户已确认设计并授权编码；远程和真库操作前仍须确认目标环境。

## 10. 实施对账

- 同号已存在和同号未终态统一使用安全的 409 message，不回显账号状态或原异常；固定 HTTP 和字段契约不变。
- 增加 406 的安全 JSON 输出，防止不支持的 Accept 头使请求绕回管理员错误信封；公网网关转发固定 `Accept: application/json`。
- 网关将上游错误统一为可读固定 message，保留实际失败状态；不透传错误 HTML 或可能含敏感信息的异常内容。
- 新增部署前私网绑定检查，拒绝 wildcard/public 管理绑定值；实际网络闭合仍需目标环境实测。
