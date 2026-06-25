# 并舟(armada)后端重构设计

> 状态:进行中 · 2026-06-19 起草,2026-06-20 转模型 B
> 本文档 = armada **目标架构与编码规范基线**(每块重建的业务都落到这套架构)。
> 系统按**业务逐块全栈重建**(模型 B):一块业务 = 一轮「梳理 → 设计 → 实现 → 验收」,各有独立设计文档。
> 结构/规范决策见 §1–§10、§12;迁移方式见 §11。

## 1. 背景与目标

- **现状**:wheel 后端 = 8 个 Maven 模块(common / domain / infra / tenant / internal / admin / app / archtest),约 11.7 万行 Java,最终只部署一个 app。
- **痛点(用户)**:① 模块太多、代码该放哪想不清;② 这套按"层"切的模块边界没带来价值,只剩构建摩擦(`-pl/-am`、`.m2` stale jar、reactor 卡)。
- **目标**:重构成**一个 Maven 工程**、**按业务域分包**、域内 controller-service-mapper(放弃 DDD);并**按业务逐块全栈重建**(模型 B),把审计出的债务(上帝类、内存分页、`Vo/View/Response` 三套、`三镜像` 分组…)在重建中一并清掉,端态是真正干净的 armada。
- **非目标(YAGNI)**:不引入 DDD/聚合/CQRS;不为"将来可能用到"提前抽象;一次只重构一块业务,不跨业务大爆改。
- **注意**:这**不是**"只搬不改"的机械迁移 —— 是按业务真重构(模型 B,见 §11)。

## 2. 命名(已定)

| 项 | 值 |
|---|---|
| 后端工程 | `armada-api` |
| Java 包根 | `com.armada`(原 `com.wheel.*`) |
| 前端(后续) | `armada-saas-web` / `armada-admin-web` |
| 重构代号 | 并舟(接前次「焚舟」) |

## 3. 顶层结构:9 个域(已定)

```
com/armada/
  account/    账号:导入 / 列表 / 6态生命周期 / 凭据 / 分组 / 协议号导出
  group/      群组 / 群链接 / 导入链接
  task/       拉群+进群任务、调度引擎、补拉手、水军
  marketing/  群组营销任务 / 模板 / 发送引擎
  resource/   代理IP、料子/物料
  admin/      后台:租户 / 用户 / 角色 / 权限 / BI / 操作日志
  platform/   基础设施:协议层 / Kafka / 文件上传 / 持久化   (内部结构见 §4.1)
  shared/     纯基础:异常 / 分页 / 工具 / 枚举 / 基类         (内部结构见 §4.1)
  boot/       启动 + 全局配置                                (内部结构见 §4.1)
```

- 顶层 = 业务域,几乎不增(只有出现整块全新业务才加)。
- 30+ 菜单都长在域**内部**,顶层永远 ~9 个,保持"目录页"可一眼读完。

## 4. 业务域内部结构(已定)

适用于 6 个业务域(account / group / task / marketing / resource / admin):

```
<业务域>/
  controller/                 XxxController
  service/                    XxxService(接口)
    impl/                     XxxServiceImpl(实现)
  mapper/                     XxxMapper(MyBatis 接口)
  converter/                  XxxConverter(MapStruct @Mapper 转换接口,见 #3)
  model/
    entity/                   实体,裸名(如 Account),映射一张表的一行
    dto/                      入参:XxxQuery(读) / XxxDTO(写)
    vo/                       出参:XxxVO
resources/mapper/<业务域>/      XxxMapper.xml(namespace 指向接口全限定名)
```

- **无 Repository 层(决策)**:MyBatis Mapper 接口本身即数据访问缝;「焚舟」已删 27 个 InMemory 实现,Repository 沦为转发样板,故砍掉。Service 直接调 Mapper(详见 §12)。

## 4.1 基础设施域内部结构(已定 · #1)

不套 controller-service-mapper,按技术关注点分包。

**shared/**(纯基础,原 common;无 I/O、无 web)
```
shared/
  tenant/       TenantContext(当前租户 ThreadLocal)、@IgnoreTenant 注解
  exception/    BusinessException、ErrorCode 错误码
  response/     ApiResponse、PageResult 统一响应
  paging/       PageQuery 分页入参基类
  enums/        跨域共享枚举
  util/         工具类
```

**platform/**(基础设施适配,原 infra)
```
platform/
  protocol/     协议层(laqunxitong)HTTP 客户端 + 防腐层(内部结构见 §4.2)
  kafka/        Kafka 消费/生产(含状态回写 handler;整体平移 —— 决策 A)
  proxy/        运行时 IP 出口解析/分配适配器(决策 B:与 resource/proxy 业务CRUD 拆开)
  upload/       文件上传
  persistence/  MyBatis 配置 + 租户拦截器(TenantLineConfig/MybatisPlusConfig + IGNORED_TABLES,决策 C)
```

**boot/**(启动+全局装配,原 app)
```
boot/
  Application.java   启动类
  config/            所有 @Configuration(数据源/MyBatis/Kafka/WebMvc/JWT/CORS/Swagger/异步)
  web/               全局异常处理器、Web 拦截器(鉴权 + 解析并写入 TenantContext)
```

### 决策记录(#1)
- **A(已确认)** Kafka 事件 handler → `platform/kafka/`(先整体落此;按业务重建时可就近下沉到对应业务域)。
- **B(已确认)** proxy 拆分:业务 CRUD(ip_proxy 表增删改查)→ `resource/proxy/`;运行时 IP 出口解析适配器 → `platform/proxy/`。
- **C(已确认)** 租户拦截器 = MyBatis-Plus `TenantLineInnerInterceptor`:给每条 SQL 自动注入 `AND tenant_id=当前租户`,实现 SaaS 行级数据隔离;租户从 `TenantContext`(ThreadLocal)取,无上下文回退 `-1`(兜底查不到)。
  - 落点:配置类 `TenantLineConfig` / `MybatisPlusConfig`(含 `IGNORED_TABLES` 白名单)→ `platform/persistence/`;`TenantContext`(纯 ThreadLocal)→ `shared/tenant/`;
  - **34 处 `@InterceptorIgnore(tenantLine)` 随各自 mapper 进业务域,逐字保留 —— 迁移红线**(漏标 / 白名单漏表 → 启动 crash-loop 全站 502)。

## 4.2 platform/protocol 内部结构(防腐层细化 · 0624)

> 出站命令通道。入站 Kafka 事件在 `platform/kafka`(§4.1 决策 A);两者合为完整防腐层(命令出去 / 状态回来)。
> 依据:`armada/docs/business/platform-protocol-contract.md`(wheel↔lqxt 实证契约 §9-11)。

```
platform/protocol/
  port/          ← 防腐层唯一对外接口(业务域只能 import 这个包)
                   AccountLifecyclePort(online/offline/logout/status/probe/submitBatchOnline)
                   MessagePort · GroupPort · ContactPort · ProxyPort —— 按能力分,不要 god-port
  model/         ← 协议层专用出入参(record),翻译边界,不外泄业务域
                   command/(LoadConnectRequest…)  result/(OnlineResult/AccountStatus…)
  http/          ← port 的 HTTP 实现(adapter,可替换:将来 NATS/whatsmeow 换这里)
                   ProtocolHttpExecutor(连接池 Apache HC/超时/2xx 校验/错误码映射)
                   HttpAccountLifecycleClient / HttpMessageClient / HttpGroupClient / …
  resilience/    ← 横切一处收口(装饰器,实现同 port、包住 http impl)
                   PerAccountRateLimiter(②封号轴 per-发言号令牌桶,按动作分阈值)
                   ProtocolCircuitBreaker · 按吞吐类隔离的有界 executor(bulkhead)
  ProtocolErrorCode / ProtocolException
```

**三条规约(ArchUnit 守,见 §10 规则 4)**:
1. 业务域只依赖 `port/`,看不到 `http`/`model`/`resilience`——协议层换传输/换库(HTTP→NATS、Baileys→whatsmeow)业务零改。
2. 协议 wire DTO 关在 `model/` 不外泄;port 签名用业务可懂的参数,adapter 内翻译(杜绝 wheel 那种协议 DTO 漏进 service)。
3. 限流/熔断/bulkhead 全在 `resilience/` 一处收口——②封号轴 per-号令牌桶包在 port 外,所有调用方(营销/拉群/补拉手)自动被同一套限速管住(杜绝 wheel 各写各的 + 漏 per-号)。

**吞吐分类 pacing 归属**(见 contract §11):①CPU 握手 → 此处不设 cap,归协议层 OnlineGate + 横向扩 worker;②封号 → `resilience/PerAccountRateLimiter`;③IO → 业务侧有界宽松并发。批量 = port `submitBatchOnline` 调 lqxt batch 端点 + 终态走 `platform/kafka`。
配置(base-url/X-Api-Key/连接池):放 `boot/config` 或内聚于 `platform/protocol/http/`,择一。

## 5. 对象集(已定)

| 对象 | 角色 | 方向 | 命名 | 包 |
|---|---|---|---|---|
| entity | 映射一张表的一行 | — | 裸名 `Account` | `model/entity/` |
| Query | 列表/搜索入参(分页+筛选) | 入·读 | `AccountListQuery` | `model/dto/` |
| DTO | 创建/修改请求体 | 入·写 | `AccountCreateDTO` | `model/dto/` |
| VO | 返回前端的视图 | 出 | `AccountListVO` | `model/vo/` |

- **对象转换:MapStruct**(单用,不引入 Lombok)。转换接口 `@Mapper(componentModel="spring")` 命名 `XxxConverter`、放 `<域>/converter/`,注入 service 使用;MapStruct 支持 record 作 source/target。
  - ⚠ 与 MyBatis 的 `@Mapper` **同名不同物**:MyBatis mapper 在 `mapper/`、后缀 `Mapper`;MapStruct 转换器在 `converter/`、后缀 `Converter`,别混。
- **对象风格**:实体 = 普通类 + getter/setter(无 Lombok);**DTO / VO = record**;**Query = 可变 class `extends PageQuery`**(@ModelAttribute 走 setter,不认 record)。
- **禁止**:BO;DO/Entity/PO 多名混用(统一叫 entity,= PO);wheel 现存 `Vo`/`View`/`Response` 三套并行 → 收敛成一个 `VO`。

## 6. 包归属规则(已定)

1. 顶层目录 = 业务域,新增极少。
2. 一个菜单/功能的所有类,待在它所属域里(controller / service / mapper / model 各就各位)。
3. 跨域调用:只调对方域的 **Service**,不直接碰对方 Mapper / entity(ArchUnit 守,见 §10)。
4. 实体一域内多功能共用 → 放该域 `model/entity/`;暂不下沉到功能级。

## 7. 域内功能怎么区分(已定 · #2)

采用 **(a) 类名前缀、域内分层扁平**:同一域多个菜单不再分子包,靠类名前缀区分。

```
account/
  controller/   AccountListController  AccountImportController  AccountGroupController …
  service/      AccountListService …    impl/ AccountListServiceImpl …
  mapper/       AccountMapper  AccountCredentialMapper …      ← 按表命名
  model/entity/ Account  AccountCredential …                  ← 按表命名
  model/dto/    AccountListQuery  AccountImportDTO …
  model/vo/     AccountListVO …
```

- **命名口径**:controller / service 按**菜单**命名(`AccountList*`);mapper / entity 按**表**命名(`AccountMapper` / `Account`)——一个菜单可能读写多表,一张表也可能被多菜单用。
- **逃生通道**:某个域将来菜单特别多(>8–10),**只在该域**引入功能子包,不全局预先嵌。

## 8. 工程与构建(单 pom · #4)

- **坐标**:`com.armada:armada-api:1.0.0-SNAPSHOT`(新项目重起版本号)。
- **基线(保持 wheel,迁移不升级)**:Java 17、Spring Boot 3.3.5、MyBatis-Plus 3.5.7、jjwt 0.12.6。
- **parent**:`spring-boot-starter-parent:3.3.5`(单模块,白拿构建默认;不再用 wheel 的 BOM import —— 其多模块约束已消失)。
- **依赖(8 pom 的并集,合并后更简洁)**:
  - 核心:`spring-boot-starter-web`、`spring-boot-starter`
  - 数据:`mybatis-plus-spring-boot3-starter`、`mysql-connector-j`、`flyway-core`、`flyway-mysql`
  - 消息:`spring-kafka`
  - 安全:`jjwt-api/impl/jackson`、`spring-security-crypto`
  - JSON:`jackson-datatype-jsr310`
  - 转换:`mapstruct` + `mapstruct-processor`(~1.6.x,#3 新增)
  - 测试:`spring-boot-starter-test`、`junit-jupiter`
  - 原各 library 模块手写的细粒度依赖(`spring-context/web/jdbc`、`jakarta.servlet-api` 等)→ 用 starter 后变传递依赖,不再显式列。
- **插件**:`spring-boot-maven-plugin`(repackage,mainClass=`com.armada.boot.Application`)、jacoco、surefire(starter-parent 已带版本)。
- **MapStruct 处理器**:配进 `maven-compiler-plugin` 的 `<annotationProcessorPaths>`(无 Lombok,无顺序坑)。
- **协议层 HTTP**:沿用 spring-web 自带客户端,无第三方 HTTP 依赖。
- **效果**:8 个 pom(~420 行)→ 1 个(~80 行),消灭 `-pl/-am`、reactor、stale jar。

## 9. MyBatis 与租户隔离平移(#5)

- **Mapper 扫描**:每个 MyBatis mapper 接口加 `@Mapper`(`org.apache.ibatis.annotations.Mapper`);单条 `@MapperScan(basePackages="com.armada", annotationClass=Mapper.class)` 注册全部域的 mapper。
  - `annotationClass` 过滤是关键:只认 MyBatis 的 `@Mapper`,**不会误抓** service 接口和 MapStruct 的 `@Mapper`(`org.mapstruct.Mapper`,不同类);
  - 自动覆盖所有域,加新域无需改扫描配置;放 `boot/config/MapperScanConfig`;
  - 迁移动作:wheel 现仅 1 个 mapper 标了 `@Mapper` → 给其余 mapper 接口逐个补上。
- **mapper-locations**:`classpath*:mapper/**/*.xml`(不变);XML 在 `resources/mapper/<域>/`。
- **租户拦截器**:`TenantLineConfig` + `MybatisPlusConfig` → `platform/persistence/`;`IGNORED_TABLES` 白名单逐字搬;**34 处 `@InterceptorIgnore` 随各自 mapper 进业务域,逐字保留(迁移红线)**。
- **TenantContext** → `shared/tenant/`;写入它的 Web 鉴权拦截器 → `boot/web/`。

## 10. 架构守护:ArchUnit(#6)

引入轻量 ArchUnit(真字节码规则),替掉 wheel 那套手写 `String.contains()` 假守卫。**迁移收尾时引入**(当"结构锁",避免搬迁途中过渡态假红)。

- 依赖:`com.tngtech.archunit:archunit-junit5`(test scope)。
- 一个测试文件(`src/test/.../ArchRulesTest`),守三条:
  1. 跨域只调对方 **Service**,不碰对方 controller / mapper / entity;
  2. controller → service → mapper 单向;**controller 不直接调 mapper**;
  3. 依赖方向:业务域可依赖 `shared`/`platform`;`shared` 不依赖 `platform` 与业务域;`platform` 不依赖业务域。
  4. 协议层调用只依赖 `platform/protocol/port`:业务域(及其它包)**不得 import** `platform/protocol/http`、`…model`、`…resilience`——防腐层实现细节与 wire DTO 不外泄(见 §4.2)。
- 时机:迁移结构完成、编译与测试全绿后引入,一次性锁定结构。

## 11. 迁移方式:模型 B —— 全栈纵切、按业务逐块重建(#7 + #8)

**不是**"先搬后改"的机械迁移,而是**一块业务一块业务地全栈重建**到 armada 架构。

- **单位 = 业务纵切**:一次只做一块业务(账号 / 群组 / 拉群 / 营销 …),后端 + API + 前端**一起**重做。
- **API 可改**:梳理业务时顺带理顺接口(命名 / 出入参 / RESTful);该业务的前端随之改。**前后端按业务耦合推进**(不再是冻结 API 的两条解耦赛道)。
- **每块业务一轮流程**(对齐 `开发流程规范` 十阶段):
  1. **梳理需求** —— 对 `0617 终版需求` + `接口协议.md` / `数据模型.md`,厘清这块业务该长什么样;
  2. **审计现状** —— 列出 wheel 现状与债务(上帝类 / 内存分页 / 分叉…);
  3. **对齐设计** —— 出该业务设计文档(像本文档这样逐点对齐),落 `armada/docs/business/<业务>.md`;
  4. **TDD 实现** —— 新测试钉新行为,落到 armada 目标架构(§3–§10);
  5. **验收** —— 按"这块业务对不对"端到端验收,而非"老测试保持绿"。
- **安全网**:不是"老测试绿 + 端点 diff=0",而是 **TDD 新测试 + 真库 DbTest + 业务验收**。
  - 硬约束:真库测试**必须真跑**(审计:37 个 DbTest 缺 env 静默跳过 → 动手前先让全套测试真跑真绿,否则无网)。
- **顺序**:挑一块业务起步(优先依赖少、能端到端打通的);`shared` / `platform` / `boot` 基础设施先于第一块业务搭好。
- **新旧并存与切换**:armada 逐块建起,wheel 仍在跑;切换策略(按业务灰度 / 整体切)留到积累几块后再定。
- **仍要避开 wt1–4 在途**(前端冲突更重)。

## 12. 规范对齐(.harness · 严格执行)

`.harness` 已复制进 armada。本次重构**严格执行**其规则,但分两类处理:

- **A 类|代码质量规则 → 100% 遵守**:反屎山约束、红线 1-11(禁魔法值 / 重复 / 空catch / 返null / System.out…)、Java 风格、Javadoc、方法≤100行 / 类≤800行 / 参数≤5 / 圈复杂度≤10 / 嵌套≤3、SQL 下推禁内存分页、真库测试、`account_type` 冻结。
- **B 类|结构规则 → 为 armada 重写**(它们描述 wheel 旧架构,正是本次重构替换的对象):
  - `工程结构.md` 整篇按 armada(单工程 / `com.armada` / 按域分包 / mapper 分域 / XML 在 `resources/mapper/<域>`)**重写**;
  - 编码规范结构条款随之更新:**砍 Repository 层**(红线#3/#6 改);传输对象**保持本文档版**(`*DTO`/`*VO` + `model/{dto,vo}` + MapStruct,替原 `*Request`/`*Vo`/`*Dtos.java`);包根 `com.armada`(替红线#1)。
- **Repository 砍掉(决策)**:Mapper 接口即数据访问缝;「焚舟」已删 27 个 InMemory 实现,Repository 沦为转发样板。域内 = controller / service / mapper / converter / model。wheel 现有 Repository 的真实查询逻辑在重建时并入 Service(盯 ≤800 行)。
- **流程**:走 `开发流程规范` 十阶段;每块业务建 `.harness/changes/<业务>/summary.md`。

## 13. 待对齐清单(逐条对齐后回填上文)

- [x] **#1** platform / shared / boot 内部结构 → §4.1(A→platform/kafka、B→拆、C→拦截器 platform/persistence + TenantContext shared,均已确认)
- [x] **#2** 域内功能命名 → §7:(a) 类名前缀、域内分层扁平;controller/service 按菜单命名、mapper/entity 按表命名;逃生通道按需引入功能子包
- [x] **#3** 对象转换 → MapStruct(单用,无 Lombok);对象风格保持现状(实体普通类+getter/setter,VO/DTO record);转换接口 `XxxConverter` 放 `<域>/converter/`(见 §5)
- [x] **#4** 工程与构建 → §8:`com.armada:armada-api:1.0.0-SNAPSHOT`、parent=`spring-boot-starter-parent` 3.3.5、基线不升级、依赖并集 + MapStruct
- [x] **#5** MyBatis 扫描 + 租户拦截器平移 → §9:每个 mapper 加 `@Mapper` + `@MapperScan(annotationClass=Mapper.class)`;拦截器/白名单/34 处 ignore 逐字平移
- [x] **#6** ArchUnit 软护栏 → §10:加(轻量字节码规则,守跨域/分层/依赖方向),迁移收尾时引入当"结构锁"
- [x] **#7** 范围 → §11:前后端都重构;**模型 B**(全栈纵切、API 可改、按业务耦合推进)
- [x] **#8** 迁移方式 → §11:模型 B 全栈纵切、按业务逐块重建;每块走 梳理→设计→TDD→验收;安全网 = 新测试 + 真库 + 业务验收;基础设施先行
- [x] **附加** Repository 砍掉、规范对齐(.harness)→ §12

## 附:已落地的目录骨架(空,无 Java 类)

- 6 业务域已铺 `controller / service(+impl) / mapper / model{entity,dto,vo}` + `resources/mapper/<域>/`。
- platform / shared / boot 暂留空(待 #1)。
