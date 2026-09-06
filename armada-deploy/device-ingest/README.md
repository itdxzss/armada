# 手机凭据导入：部署、验收与回滚

本目录提供独立 TLS 网关，唯一公网业务路径是 `POST /api/device-imports`。入口使用静态 `X-Ingest-Token`，成功返回 `200 {"batchId":123,"onlinePhase":"QUEUED"}`，失败返回真实 4xx/5xx 和 `{ "message": "可读原因" }`。仅同一路径 OPTIONS 返回 204，无 CORS。

**当前状态：已移植到当前主线 b68890ed，本地 Java 130 项、网关 9 项和打包通过；尚未部署或完成真实账号交接。** 初始七月工作树仅保留依赖与验证历史，不用于发布。当前验证及依赖关系见 [交付记录](../../.harness/changes/2026-09-07-device-import-ingest/delivery.md)。

## 1. 上线前输入

先确认测试环境主机、当前发布提交、入口域名、证书位置、私网管理员访问方式、令牌默认配置。生产环境需另行明确确认。

部署目录保留既有 `docker-compose.yml`（或 `docker-compose.rds.yml`），并增加：

```text
docker-compose.device-ingest.yml
device-ingest/nginx.conf.template
device-ingest/preflight.py
```

这三个文件没有真实令牌、手机号、租户映射或证书。实际秘密通过服务器受保护的环境配置注入，不能复制进项目、镜像、Git、工单或对话。不要使用 `set -x`，不要输出展开后的 `docker compose config`、`docker inspect` 环境变量或请求 body。

| 运行环境变量 | 说明 |
| --- | --- |
| `ARMADA_DEVICE_INGEST_CLIENTS_JSON` | 必填 JSON 数组，每项包含下面的令牌及默认配置；缺失或无效时后端启动失败 |
| `INGEST_HOSTNAME` | 单一小写 DNS 域名，不带协议、端口、路径或通配符；证书必须覆盖它 |
| `INGEST_TLS_DIR` | 服务器上的绝对目录，包含 `fullchain.pem` 和 `privkey.pem`，只读挂载 |
| `ARMADA_ADMIN_BIND_IP` | 服务器的 RFC1918 私网 IPv4 或回环地址；禁止 `0.0.0.0`、`::`、公网 IP |
| `ARMADA_HTTP_PORT` | 原管理员端口，默认 18080，仅绑定上面的私网/回环地址 |
| `SPRING_PROFILES_ACTIVE` | 必须包含 `kafka`，否则既有导入调度器不会启动 |
| `ACCOUNT_IMPORT_ONLINE_DISPATCH_SCHEDULER_ENABLED` | 必须为 true，沿用默认 10000 ms fixed delay |
| 既有数据库、Kafka、Android 协议、代理池变量 | 使用该目标环境现有配置，不从本工作树的旧默认值推导 |

令牌映射每项字段：

| 字段 | 类型 / 业务含义 |
| --- | --- |
| `token` | 至少 32 个随机字节生成的 base64url 无填充字符串；允许 43–256 个字母、数字、`-`、`_`，区分大小写，不 trim |
| `tenantId` | 启用租户的正整数 ID；请求时会查租户注册表确认仍启用 |
| `accountGroupId` | 该租户现有未删除分组的正整数 ID，失效返回 503 |
| `deviceOs` | 整数 1=安卓、2=苹果，只决定展示；全参运行协议固定 ANDROID |
| `accountType` | 整数 1=个人、2=商业，作为主线申报类型保存；WA Business 配置为 2，有效类型仍由现有协议校验 |
| `ipAllocationMode` | `smart` 或 `mixed`；省略/null 表示现有指定地区分配语义 |
| `ipRegion` | 现有代理池地区名；指定地区模式必填，smart/mixed 可省略 |

不得把手机发来的字段用作分组、租户、账号类型或 IP 默认值。IP 参数指分配策略/地区，实际代理仍由现有上线服务选择。不记录令牌值或摘要；轮换需更新环境并重启后端，可暂时配置两个不同令牌映射到同一默认值。

## 2. 部署次序

1. 在独立集成工作树基于当前发布提交完成集成、后端评审及构建。旧工作树的打包结果只用于本地验证。此次未验证通过的旧部署脚本不能直接当作一键发布入口。
2. 为确认的域名准备受手机信任的 TLS 完整证书链。证书通过 DNS 验证或既有证书流程提供；本入口不开 80 或 HTTP-01 验证路径。
3. 将上述无秘密配置文件部署到目标的现有 Compose 目录，并通过服务器配置系统注入环境。令牌必须同时安全配置到对应手机包。
4. 在该服务器的部署目录执行下列命令。所有变量已由受保护的运行环境提供；如使用 `--env-file`，需使用部署目录外的受保护文件，并让 preflight 读取同一组已注入的非输出环境。

```bash
python3 device-ingest/preflight.py
docker compose -f docker-compose.yml -f docker-compose.device-ingest.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.device-ingest.yml up -d backend nginx device-ingest-nginx
docker compose -f docker-compose.yml -f docker-compose.device-ingest.yml exec -T device-ingest-nginx nginx -t
```

Compose 必须支持 `!override`（至少 2.24.4）：它用于**替换**管理员端口列表，不能降级为数组追加，否则可能保留原先面向公网的绑定。底层文件名如为 `docker-compose.rds.yml`，两个 `-f` 命令均使用该实际文件名。后端不得存在面向公网的 `ports` 映射。

基础 `docker-compose.rds.yml` 同样读取必填令牌映射、关闭请求/SQL 参数日志，并读取 `ARMADA_ADMIN_BIND_IP`。首次部署后将这些值保留在服务器受保护的 `.env` 中，后续常规 `--be` / `--fe` 发布才能保持令牌注入和管理端绑定。基础文件的绑定默认值仍供未启用设备入口的旧环境使用；本入口部署必须运行 preflight 并显式配置私网或回环地址。EC2 公网 NAT 可以抵达私网地址绑定的端口，因此必须同时验证安全组；私网绑定本身不证明公网封闭。

5. 先确认服务健康、配置启动门禁和私网管理访问，再放行该网关的公网 TCP 443。检查 IPv4/IPv6、防火墙/安全组、Docker 发布端口、旧负载均衡器以及后端 8080：只有 443 可从公网访问；其他入口按现有私网范围收敛。`preflight.py` 只核对绑定值，不能代替安全组实测。

网关没有健康检查 API、后台站点、登录、批次查询、导出、Swagger 或其他 `/api/` 代理。nginx access log 关闭，处理请求的 error log 不记录请求原文；请求临时目录使用 tmpfs。后端叠加配置关闭 MyBatis 参数日志及 Web 请求详细日志，不得在运行环境开启请求/凭据调试。

## 3. 无凭据的外围验收

从指定的公网验收位置，仅记录 HTTP 状态和固定 message：

- TLS 链可信、域名匹配；明文 HTTP 不能导入，不开放 80。
- `POST /api/device-imports` 无令牌、错令牌均为 401；正确令牌和非法 body 为 400。
- 同路径 OPTIONS 为 204、`Allow: POST, OPTIONS`，没有任何 `Access-Control-Allow-*` 头。
- GET/PUT/DELETE 为 405；其他路径、尾斜杠、编码别名、子路径为 404；query 被拒绝。
- `/`、`/api/account-imports`、`/api/public/login`、`/actuator/health`、导出和后台页面均不能通过公网网关访问。
- 错误响应都是 JSON message，不能将 4xx/5xx 改写成 200 或 HTML。
- 18080/8080/数据库/Kafka/协议端口不能从公网直达。

## 4. 真实账号交接验收

使用指定测试租户和明确授权的测试手机账号，手机按冻结接口发送完整单行全参；禁止把 body/token 写到终端参数、日志、截图、测试断言或 Git。

1. 手机收到 200 且只包含 `batchId`、`onlinePhase=QUEUED`。200 只代表批次、账号、状态、六段凭据、原始明细的事务已提交。
2. 控端内网按 batchId 验证：来源 PARAMS=3、运行凭据 SIX=1、协议 ANDROID、令牌分组/机型/账号类型/IP 策略正确，原文保留。检查仅输出字段存在性、长度或哈希，不输出密钥。
3. 观察既有 10 秒调度从 QUEUED 派发，确认 outbox/协议受理和当前轮次 ONLINE 回调；没有代理资源、Kafka/协议故障时可能保持队列，不能将 HTTP 200 当作在线验收。
4. 手机成功提示后执行官方登出，确认当前账号交给控端。现有契约没有手机退出回执，因此调度可能先于用户确认登出；不得声称零抢登交接。
5. 同号再次提交返回 409，不覆盖已有凭据、不增加批次、不再次派发。在线、离线或失败但未删除的账号均属于已存在账号；删除账号后旧明细仍 QUEUED/DISPATCHED 也会拒绝。
6. 网络超时可能发生在提交之后；重传 409 时必须从控端核对结果。手机按现有契约不会因为 409 登出，不新增自动重试或幂等成功恢复语义。
7. 核查应用/网关日志没有请求内容或令牌；分别保存 HTTP、数据库、调度、协议状态、手机登出五层脱敏证据。

## 5. 本地验证入口与边界

```bash
python3 -B armada-deploy/device-ingest.test.py
xmllint --noout armada-api/src/main/resources/mapper/account/AccountImportDetailMapper.xml
```

Python 测试使用本机 Docker、官方 nginx 镜像、临时自签测试证书、回环随机端口和无效哨兵；不连接实际后端。测试验证 TLS、完整 body 转发、剥离管理员身份头、私网端口叠加、错误 JSON、路径/方法以及日志/临时目录；临时容器与证书在结束时清理。

Java 使用 JDK 17；定向测试包含 `Device*Test`、全参转换/解析/写入/上线命令/导出/结算和原 Bearer 过滤器。需要时通过测试 JVM `-javaagent` 指定本机匹配版本的 Byte Buddy agent，不修改生产 JVM。完整命令和结果见 [.harness 交付记录](../../.harness/changes/2026-09-07-device-import-ingest.md)。

本地数据库验证使用 H2、真实 XML、MyBatis-Plus 和 Spring 事务。并发测试确认第二个事务在真实 INSERT/锁上等待，第一笔提交后只保留一次导入，第二笔业务冲突整体回滚。调度测试的外部命令服务使用测试替身，只证明队列和事务流转，不证明真实 Kafka/协议发送。

H2 不等于 MySQL InnoDB；指定测试 MySQL、远程部署、公网封闭和真机交接均须后续执行。运行全库测试时，除 `*DbTest` 外还须排除 `GroupLinkRegistryServiceImplTest` 和 `GroupCreationMarketingTaskServiceImplTest`（实际继承 DbTestBase）；未经环境确认不运行这些测试。

## 6. 回滚

先关闭专用网关或撤销其 443 放行，阻止新上传；保留私网管理员访问。再回滚到部署前**当前发布基线**的镜像和配置，不使用本分支的七月基点替代线上版本。

本任务无 schema 迁移，不删除任何导入数据或凭据。已经受理的 QUEUED 仍可能继续由既有调度处理；关闭公网入口不等于取消队列，取消任务需要通过现有管理能力另行明确处理。移除运行环境变量不会即时撤销进程内令牌；轮换后必须重启。
