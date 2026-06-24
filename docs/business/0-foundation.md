# 地基重写血泪清单（Foundation Scar List）

> 来源:多 agent 扫 wheel 基建代码 + 注释挖出的「用线上崩溃换来的正确性」(52 条 / 7 子系统,均带 file:line 证据)。
> 用法:armada **重写 shared/platform 时逐条钉**。判定标准 = 「重写时不知道这点,会重新引入一个已修过的线上故障」。
> 重写它的**形**(结构/命名/对象),**不重走它的坑** —— 旧代码当"血泪规格书",不是拿来 copy 的。
> 🔴critical(漏=全站崩/越权) · 🟠high · 🟡medium

---

## 🔴 最致命(漏一条 = 全站 crash-loop / 跨租户越权)

1. **租户 fail-closed**:无租户上下文必须注入永不匹配的哨兵 `-1`(绝不回退 null/放行/抛异常),否则任一漏 ignore 的路径变跨租户全表读=越权。`TenantLineConfig.java:52-58`
2. **IGNORED_TABLES 漏登记**任何无 `tenant_id` 列的表 → 注入 `AND tenant_id=?` → Unknown column 崩库,只在真库 runtime 暴露。`TenantLineConfig.java:18-43`
3. **Mapper XML 裸 `<` `>`** → 解析炸 → SqlSessionFactory 装不起 → 启动 crash-loop 全站 502,build/mock/跳过的 DbTest 全抓不到。`GroupMarketingTaskMapper.xml:55`
4. **FOR UPDATE+ORDER BY/LIMIT** 被 JSqlParser 重排成非法语法 → 上线分配代理 BadSqlGrammarException;必须 `@InterceptorIgnore`+SQL 内手写 `tenant_id`。`IpProxyMapper.java:82-94`
5. **异步/线程池/Kafka/后台 Job 不重建租户上下文** → 全 SQL 注 `-1` 恒查空 → 任务启动即 BATCH_NOT_FOUND/登录查不到用户/状态落不了库。`BatchDispatchWorker.java:124-129`
6. **线程复用前不 finally clear()** 租户上下文(含 ignore 标记)→ 残留 tenantId 让下个请求越权读别租户。`BatchDispatchWorker.java:130-137`
7. **`@Transactional` 体内直起依赖该事务写入的 worker** → worker 独立连接读不到未提交行 → 一行不执行即退出;必须走 AfterCommitExecutor。`AfterCommitExecutor.java:5-8`
8. **Flyway 破坏性 DDL 不做 information_schema 幂等** + 严格 validate 无逃生口 → 撞号重排/prebuilt 不 clean → validate 失败 crash-loop 全站 502。`V063:11-18`、`deploy-test.sh:236-238`
9. **impersonate `noopAllowAll()` 误用到生产** / 忘注入真实 jti 白名单 → 代登录 token 永不可吊销 = 半永久越权后门。`ImpersonateTokenRegistry.java:47-61`
10. **DevAuthBypassInterceptor** profile 判定写反/生产忘激活 db profile → 每个 admin 请求被注超管 = 完全无鉴权后台。`DevAuthBypassInterceptor.java`、`WheelApiWebMvcConfig.java:127-132`
11. **协议层失败不抛异常而吞掉返回成功值** → 上层把失败当成功推进状态机(没拉进群记成已拉);join 可空字段序列化成 null 被 zod 判 400 整批失败。`ProtocolHttpExecutor.java:112-127`、`HttpGroupClient.java:333-341`

---

## 1. 租户隔离(TenantLine 拦截器 + TenantContext + IGNORED_TABLES + @InterceptorIgnore)

- [ ] **🔴 无上下文回退 -1 fail-closed** —— 必做:无上下文→注入永不匹配哨兵值(-1),绝不 fail-open(放行/不加条件/null/抛异常放行);跨租户只走显式白名单/ignore 且只允许 admin/后台。`TenantLineConfig.java:52-58`
- [ ] **🔴 IGNORED_TABLES 白名单** —— 无 tenant_id 列的两类表(平台元数据表 + account_id 关联关系表如 `account_tag`)必须登记,否则注入 `AND tenant_id=?` 崩库。必做:把"漏加白名单"提前到测试期——启动期/archtest 用真库 information_schema 断言每张被 MyBatis 访问且不在白名单的表都真有 tenant_id 列。`TenantLineConfig.java:18-43`
- [ ] **🟠 表名归一化** —— ignoreTable 比对前必须剥 schema 前缀、去反引号(`` `package` `` 等 SQL 保留字)、trim、toLowerCase,白名单存裸小写名;配三种输入单测。`TenantLineConfig.java:67-85`
- [ ] **🔴 FOR UPDATE 行锁取号** —— 凡 FOR UPDATE(尤其叠 ORDER BY/LIMIT):①关自动改写(@InterceptorIgnore);②SQL 内手写 `AND tenant_id=#{tenantId}`。CI 须连真库+真拦截器跑通(mock 无效)。`IpProxyMapper.java:82-94`
- [ ] **🟠 复杂语句关拦截器** —— 含 JSON 函数/相关子查询/跨 JOIN/INSERT...SELECT 的 mapper:关自动改写 + 对**每个**引用业务表的子查询和主查询都显式写死 tenant_id(漏一个子查询=跨租户泄漏,比崩库更隐蔽)。双租户脏数据 DbTest 验不泄漏。`AccountMapper.java:90-99` 等 9 文件
- [ ] **🔴 跨线程必显式 set 上下文** —— @Async/线程池/定时任务/Kafka 消费者做租户查询前必须显式重建租户上下文。最好用 TaskDecorator/上下文传播统一带过线程边界。`BatchDispatchWorker.java:124-129`、`JoinTaskWorker.java:121`
- [ ] **🔴 跑完必 finally clear()** —— 跨线程任务 try/finally 清理(同清 tenantId+ignore 两个 ThreadLocal),异常路径也执行;HTTP 侧拦截器 afterCompletion 无条件清。做成框架级保证(装饰器统一 set+clear)。`BatchDispatchWorker.java:130-137`、`TenantContext.java:38-41`
- [ ] **🔴 @IgnoreTenant 无 AOP 切面!** —— wheel 的 @IgnoreTenant 没有切面,真正绕过靠显式 `callIgnoringTenant/runIgnoringTenant` 包裹。必做二选一:(A)真做读注解的 AOP 切面;(B)删注解只留显式包裹。callIgnoringTenant 须恢复原 ignore 状态(支持嵌套)。`IgnoreTenant.java:10-12`、`MyBatisTenantAuthRepository.java:44-49`
- [ ] **🔴 公开入口/后台/Kafka 必 runIgnoringTenant** —— 登录(公开)、后台 Job(ProxyReaper/GroupLinkImportJob/…)、Kafka 消费者(按 acc_ 句柄反查)即便 mapper SQL 自带 tenant_id 条件也会被拦截器 -1 覆盖恒查空;必须包在 ignore 内执行。`MyBatisTenantAuthRepository.java:22-28`、`AccountEventConsumer.java:84-87`

## 2. MyBatis / 持久化与分页

- [ ] **🔴 Mapper XML 裸尖括号炸全站** —— SQL 正文一律 `&gt;`/`&lt;`/`!=` 转义,非空判断用 `!list.isEmpty()` 不用 `size()>0`,排除用 `!=` 不用 `<>`;动态 SQL 用 CDATA。**CI 加 archtest 遍历所有 mapper XML 跑解析**(唯一能在部署前抓到的方式)。`GroupMarketingTaskMapper.xml:55`、`TaskRowMapper.xml:89`
- [ ] **🟠 XML/拦截器无自动门禁** —— mock 单测对 XML 与拦截器改写全盲。必做:①archtest 解析所有 XML;②关键 SQL(FOR UPDATE/JSON/隔离)配真库集成测;③"改 XML 必过校验"做成 CI 硬门禁而非注释。`AGENTS.md:88-89`
- [ ] **🟡 IN () 空列表炸** —— mapper 收 List 做 IN:调用方空列表时短路返回,或 XML `<if test="!list.isEmpty()">` 包裹。`AccountMapper.java:37`
- [ ] **🟠 分页统一 + 禁内存分页** —— 单一 `PageResult`(字段 `page/page_size/total/total_pages`,totalPages 由 total+pageSize 推导)禁自造分页 DTO;统一 `PageQuery` 基类(page≥1 钳制、pageSize 上限 500、snake_case 兼容、offset());**分页/count/筛选全 SQL 下推,严禁 load-all 内存分页**(几十万行 OOM);count 与 list 共用同一 `<sql>` 片段。`PageResult.java`、`PageQuery.java:21-43`

## 3. 协议层客户端(infra/protocol)

- [ ] **🔴 可空字段绝不序列化成 null** —— join 的 inviteCode、message 的 caption/description/mimetype 等 optional 字段,null 必须从 JSON 省略(协议层 zod 拒绝显式 null → 400 整批失败)。Jackson `@JsonInclude(NON_NULL)`;配序列化断言测试。`HttpGroupClient.java:333-341`、`HttpMessageClient.java:74`
- [ ] **🔴 participants 出口补 @s.whatsapp.net** —— 群成员操作客户端出口对每个 participant 归一:已含 @ 原样,纯数字裸号补 `@s.whatsapp.net`(裸号被 WA 静默丢弃→30s 超时+total:0)。approveJoinRequests 复用。`HttpGroupClient.java:303-322`
- [ ] **🔴 失败统一抛 ProtocolLayerException** —— 非 2xx/网络/超时/反序列化失败全转成带规范化 `errorCode`+httpStatus 的异常抛出,绝不返回 null/部分对象让上层误判成功。网络/超时/反序列化失败 httpStatus 置 0(区别真 HTTP 错误)。`ProtocolHttpExecutor.java:112-127`
- [ ] **🟠 200 OK ≠ 全成功** —— 保留 `partial` 标志 + 逐条 `status`/rawStatus + 单项 error;批量逐条判 status==OK、partial 决定补号;导入逐项判 imported();join `joined=false`=进审批 pending 非真入群。`ParticipantsResult.java:8-23`、`JoinResult.java:8-10`
- [ ] **🟠 accountId 是句柄 acc_<wsPhone>** —— 正向永远拼 `acc_<wsPhone>`(与导入落库的 protocol_account_id 一致);反查 wheel 主键按 `account.protocol_account_id` 查表,**禁 Long.parseLong**(对 acc_ 句柄恒抛异常→事件静默跳过)。封成单一工具。`MyBatisGroupLinkUpdater.java:271-273`
- [ ] **🟠 无 body 的 POST 发空 JSON {}** —— body 为 null 的 POST 降级发 `{}`(带 json 头的空 body 被 Fastify 判 FST_ERR_CTP_EMPTY_JSON_BODY 400)。`ProtocolHttpExecutor.java:49-55`
- [ ] **🟡 反序列化失败处理 + 脱敏** —— 2xx 但反序列化失败抛 SERVER_ERROR/httpStatus=0 留 bodyPreview;读 body 的 IOException 返空串不二次抛;关 FAIL_ON_UNKNOWN_PROPERTIES;日志全脱敏(credential 只打长度、code/链接留末 6 位)。`ProtocolHttpExecutor.java:134-147`

## 4. Kafka 消费基建(+ AfterCommitExecutor)

- [ ] **🔴 AfterCommitExecutor** —— 事务内写库 + 触发异步/worker/MQ 依赖该写入:必须包进"提交后执行"(注册 afterCommit;无活跃事务则立即跑兼容测试)。不在 @Transactional 体内直起 worker。`AfterCommitExecutor.java:5-8`、`TransactionAwareAfterCommitExecutor.java:21-30`
- [ ] **🟠 ErrorHandlingDeserializer** —— value 反序列化必须包 ErrorHandlingDeserializer(脏消息转 null 跳过,否则一条卡死整个 partition);消费者收 null value 时 ack 跳过。配 VALUE_DEFAULT_TYPE/TRUSTED_PACKAGES/USE_TYPE_INFO_HEADERS=false、FAIL_ON_UNKNOWN_PROPERTIES=false。`KafkaConsumerConfig.java:79-81`
- [ ] **🟠 at-least-once 四件套** —— 关自动提交 + 手动 immediate ack + 成功才 ack + 失败抛不 ack + 幂等兜底;未注册/null 事件 ack 放行(否则卡 partition)。落库本身也要幂等(upsert/状态收敛)。`KafkaConsumerConfig.java:77`、`AccountEventConsumer.java:71-100`
- [ ] **🟡 幂等检查器跨实例** —— 当前是单 JVM 内存 LRU(多节点失效);多实例必须换 Redis SETNX/DB unique 索引,或保证所有副作用幂等。null eventId 放行+告警。`InMemoryEventIdempotencyChecker.java:16-20`
- [ ] **🟠 state_source/block_reason 落库前按列宽截断** —— PREFIX:reason 拼接(reason 来自协议层不可控),account.state_source 仅 VARCHAR(64);不截断→DataIntegrityViolation→毒丸消息无限重投卡 partition。截断收口到一个工具,所有写入路径统一走。`MyBatisAccountStateUpdater.java:153-162`
- [ ] **🟡 MSK TLS(:9094)开关** —— `wheel.kafka.ssl` 布尔:本地 false 走明文 9092,生产 true 走 SSL 连 MSK 9094(无需自带证书/无 SASL)。yml 无默认须环境变量注入,重写别漏这隐式配置。`KafkaConsumerConfig.java:84-88`

## 5. 代理 / IP(分配 CAS + session 轮换 + 出口探测 + 孤儿回收)

- [ ] **🟠 session 是客户端自编串** —— 凭据建模成"base 线路 + 可轮换 session 段",不把含 session 的完整串当不可变身份存死。换 IP = 正则 `replaceFirst` **只替换 `_session-([A-Za-z0-9]+)` 一段**,其余(`_lifetime-30m`/`_country-in`)逐字保留(否则丢国家锁/粘性→跳 IP 触发封号)。抽到单一来源(wheel 在 internal+tenant 抄了两份)。`ProxyFailureRecoveryService.java:212-220`、`ProxyAllocator.java:127-151`
- [ ] **🟠 绑定/换绑 CAS 防双占** —— `markBound` 条件 UPDATE 检查 affected rows==1,!=1 视为被抢→放弃/抛冲突,绝不无条件 update(双占→两账号同出口 IP 互相牵连封号)。FOR UPDATE+markBound 同事务;rebind 的 HTTP 调用放事务**外**,失败只 warn 不回滚不上抛。`ProxyAllocator.java:67-71`、`ProxyFailureRecoveryService.java:35-38`
- [ ] **🟠 IP 自动恢复顺序 + 吞异常** —— proxy_failed 事件:①先落失败计数再读库判阈值(=3,顺序反了读旧计数永不触发);②recover 内部 resolve 不到一律 log 跳过不抛;③Kafka handler try/catch 吞掉 recover 的 RuntimeException 保证消息照常 ack(否则死循环重投反复换 IP)。`AccountProxyFailedHandler.java:45-62`
- [ ] **🟠 孤儿回收带离线宽限期** —— 两条路径:正常窗口(release_eligible_at<now)+ 孤儿(release_eligible_at IS NULL 且 账号已删立即/离线超 orphanGrace 才回收)。**宽限期不可省**(容忍 class-B 自动重连的短暂掉线,否则误回收在线号 IP 双占)。login_state 假在线幽灵号躲回收→需定时拉协议层对账翻离线。回收跑后台无上下文须 callIgnoringTenant。`IpProxyMapper.releaseOrphanBindings:149-166`
- [ ] **🟠 出口探测器吞所有失败返 empty** —— `Optional<String> resolveEgressIp`,任何失败返 `Optional.empty()` 绝不上抛(只为列表展示真实 IP,上线不依赖,抛了带崩上线)。配总开关/echo host:port/超时(8000ms);log 代理只打 host:port,凭据/session/url 一律脱敏。`Socks5EgressIpResolver.java:24-25`
- [ ] **🟡 手写 SOCKS5 用 readN 精确读** —— 保留 `readN` 循环读满 N 字节(提前 EOF 抛),按 ATYP 算变长绑定地址;用本会话凭证不用全局 Authenticator(竞态);纯函数单测。或直接用成熟库(须支持 per-connection 账密)。`Socks5EgressIpResolver.java:231-243`
- [ ] **🟡 protocol 入库归一 SOCKET→SOCKS5** —— 所有 IP 入库路径过同一归一函数:大写后 SOCKET→SOCKS5,白名单仅 {HTTP,SOCKS5} 否则拒,默认 SOCKS5(历史前端误写 SOCKET,协议层只认 HTTP/SOCKS5)。`TenantResourceService.java:304-321`

## 6. 鉴权 / JWT / 拦截器链

- [ ] **🔴 impersonate jti 白名单可吊销** —— 每个代登录 token 带唯一 jti + 维护可主动吊销的 jti 白名单;tenant 拦截器对 isImpersonating 每次请求查 isActive 失败拒绝;签发 activate、登出/改密/强制下线 revoke;**生产绝不注入 noopAllowAll**(测试专用全放行=关掉吊销)。`ImpersonateTokenRegistry.java:47-61`
- [ ] **🔴 dev 鉴权旁路 fail-closed** —— DevAuthBypassInterceptor 只在明确 local/dev profile 生效,生产/测试走真鉴权;判定 fail-closed(默认真鉴权,只显式 dev 标记才旁路)+测试断言生产 profile 挂真 AuthInterceptor。最稳:生产构建根本不含 bypass 类。`DevAuthBypassInterceptor.java`、`WheelApiWebMvcConfig.java:127-132`
- [ ] **🔴 ThreadLocal 身份 afterCompletion 必清** —— AuthContext/TenantContext/MDC 请求结束(含异常)无条件 clear 全部相关 ThreadLocal(TenantContext 同清 ignore),否则 Tomcat 线程复用身份串号=跨租户越权。`AuthInterceptor.java:74-81`、`AuthContext.java:96-102`
- [ ] **🟠 拦截器顺序硬约束** —— Auth(写 AuthContext)→DenyImpersonate→TenantContext(写 TenantContext)→Module→Permission;读 TenantContext 的必排在写它的之后(错序→后置拦截器读空→注 -1 查空/误放行)。加测试断言注册顺序。`WheelApiWebMvcConfig.java:135-148`、`ModuleInterceptor.java:17-18`
- [ ] **🟠 X-Tenant-Code 一致性 + 租户状态** —— tenant id 以 token 为权威;客户端传的租户标识只用于一致性校验,不等即 TENANT_MISMATCH(防改 header 跨租户);入口校验租户启用/过期/状态(DISABLED/SUSPENDED/EXPIRED/PENDING 拒绝)。`TenantContextInterceptor.java:52-101`
- [ ] **🟠 admin/tenant 双密钥双 issuer** —— 各持独立 HMAC 密钥 + 各自 issuer,解析 verifyWith 自己密钥 + requireIssuer 自己 issuer(合一→跨 scope 越权 + 一方泄露危及两方);tenant token 强制 tenant_id+tenant_code claim 否则 TOKEN_INVALID。`JwtUtil.java:171-179`、`:152-156`
- [ ] **🟠 JWT 密钥环境变量外置** —— 支持 env 覆盖,本地有内置默认便于零配置;类生产 profile 仍用默认密钥时 WARN(可伪造任意 token);**日志绝不打印密钥明文**,只打"是否默认串"布尔。`WheelApiWebMvcConfig.java:77-89`
- [ ] **🟠 DenyImpersonate 高危接口** —— 用 `@DenyImpersonate` 标记改密/删数据等敏感接口,代登录态访问拒绝(IMPERSONATE_FORBIDDEN)+审计。重写时逐一审查别漏标改密类。`DenyImpersonateInterceptor.java:31-40`
- [ ] **🟠 内部通道双因子 fail-closed** —— `/api/internal/**` 用 API key + CIDR 白名单两道闸;**空白名单=全拒**(fail-closed);来源 IP 取 X-Forwarded-For 首段(经反代)。不用 JWT 复用到内部通道。`InternalApiKeyInterceptor.java:40-68`
- [ ] **🟡 impersonate token 走 URL fragment** —— 代登录跳转 token 放 `#` fragment 不放 query(query 落 nginx access log);reason 强制≥10 字符 + 写 IMPERSONATE_START 审计(带 jti)。`AdminTenantService.java:236-277`
- [ ] **🟡 BCrypt(cost=10)统一封装 + 权限不进 JWT** —— 密码 BCrypt 统一 cost 封工具类;权限/角色运行时动态查(不烤进 token,改权限即时生效);JWT 只放稳定身份(userId/tenantId/scope/jti)。`BCryptUtil.java:11`、`PermissionInterceptor.java:14-18`

## 7. Flyway / 部署 / 运维

- [ ] **🔴 破坏性 DDL 必 information_schema 幂等** —— 每个 ALTER ADD/DROP COLUMN 包成 information_schema.COLUMNS 计数 + IF + PREPARE/EXECUTE/DEALLOCATE(ADD 用 count=0、DROP 用 count=1 才执行),不写裸 ALTER(以便重放到撞号重排/被手动 ALTER 过的库)。+ 单一线性版本号源消灭撞号。`V059:11-12`、`V063:8-18`
- [ ] **🔴 Flyway 严格 validate + 可观测 + runbook** —— 保留严格 validate(别开 out-of-order 掩盖撞号);配套:①迁移幂等;②部署脚本探测 `Started ... in` vs `FAILED TO START/Validate failed/checksum mismatch` 报警;③runbook 写明 crash-loop 时核实列存在+checksum 一致后修 `flyway_schema_history.success=1`。`application.yml:73-78`、`deploy-test.sh:388`
- [ ] **🔴 prebuilt 必先 mvn clean** —— prebuilt/本地打包先 `mvn -pl <app> clean package`(或对 db/migration 资源 comm 比对 src vs target),否则旧版本号迁移残留 target/ 打进 fat jar 撞号 crash-loop。把 clean 设为 prebuilt 默认。`deploy-test.sh:236-238`
- [ ] **🟠 rsync 大产物 --partial** —— 传 jar/dist 一律 `--partial`(jar 已压缩去 -z);SSH 配 ControlMaster + ServerAliveInterval + ControlPersist + 清死 master 逃生(VPN/跨境高 RTT 必断)。`deploy-test.sh:240-242`
- [ ] **🟠 bash 3.2 `$VAR` 后全角字符** —— `$VAR` 后紧跟中文/全角/多字节一律 `${VAR}`(macOS bash 3.2 会并进变量名→unbound variable 部署崩)。改 zsh/python 可绕。`deploy-test.sh:320-323`
- [ ] **🟡 --fe-only 不应用迁移 + scope last-wins** —— 前端-only 快速通道必须 warn"不应用 DB 迁移"且改了 migration 时拒绝;scope 避免 last-wins 静默覆盖;部署后等 backend 真 Started 再探 Flyway(前端 200 只证明 nginx 活)。`deploy-test.sh:13-14`、`:194`

---

## 重写时怎么用这份清单

1. 重写某块基建前,先看它对应子系统的 scar,把每条 `必做` 当**验收点**;
2. 凡标"必做加 archtest/真库测试/CI 门禁"的,**把人工纪律升级成机器挡**(熵管理,见 `skills/unit-test-ci`);
3. 这些坑大多 **mock 单测抓不到**,只有真库 DbTest + 真拦截器 + 真 XML 解析才暴露 —— 地基阶段就把这条测试线建起来跑真;
4. 多处"wheel 抄了两份"的(session 正则、proxy 归一)→ armada 收敛到单一来源(shared/平台),消除漂移。
