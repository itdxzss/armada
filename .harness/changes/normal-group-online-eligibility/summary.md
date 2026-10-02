# 新建普群按当前在线状态准入

- 日期：2026-09-29
- 分支/worktree：1.0.3-snapshot，/Users/daishuaishuai/IdeaProjects/armada
- 需求：用户明确入口为“群组列表 → 新建普群”，被抢登账号只要当前在线即可建群。
- 状态：本地实现与相关回归通过，已发布 perf2 后端；未提交，未执行真实 WhatsApp 建群。

## 修改

- 普群专用严格协议查询改为 `findOnlineStrictByGroupId`，按 login_state=ONLINE 筛选，不再限定 account_state=NORMAL。管理员、成员、次管理员与重试补选使用同一查询。
- 复用既有账号 Mapper，空生命周期列表表示不加生命周期条件；其他调用方仍传原有状态集合。保留分组、租户、软删除、完整手机号/协议账号及明确 WEB/ANDROID 后端校验。
- executableOnlineCount 同步改为在线且协议身份完整，不限定生命周期标签。前端字段注释同步；前端交互逻辑未改。
- 普群 GROUP_CREATE 回执 UNKNOWN 且账号仍在线时，保留 RESULT_UNKNOWN，不再因生命周期标签直接落成失败；已有群 JID 的处理与离线归因保持原行为。
- 不涉及拉群营销、速拉群或普通拉群任务规则。

## 数据/API/回滚

无表结构、Flyway、Redis 或 HTTP 字段变更。仅调整既有 executableOnlineCount 口径和 Java 内部方法名称。
回滚时仅撤销本次涉及的精确 diff，保留工作区其他会话已有修改。

## 验证

- 新增/更新回归先红：28 项测试中 6 项失败，覆盖数量、候选和在线账号结果未知归因；修复后转绿。
- JDK 17，离线 Maven，显式加载现有 Byte Buddy agent 解决本机 Mockito 动态附加卡住：
  `JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar '-Dtest=AccountGroupMapperH2Test,AccountProtocolLookupServiceTest,NormalGroupCreation*Test,ProtocolNormalGroupCreationEventConsumerTest' test`
  109 tests, 0 failures, 0 errors, 0 skipped，BUILD SUCCESS。
- AccountGroupMapperH2Test 加载真实 AccountMapper/AccountGroupMapper XML 与生产 MyBatis-Plus 租户插件，覆盖在线被抢登/抢登中及其他生命周期状态、离线/待上线排除、软删、协议身份缺失、跨租户隔离、原有 NORMAL 查询不变。
- 两个 Mapper XML 经 `xmllint --noout` 校验通过。
- 前端使用仓库测试 loader：
  `node --import tsx --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account-group.test.ts src/views/task/pull-task/CommonGroupCreate.test.ts src/views/task/pull-task/composables/useCommonGroupCreate.test.ts`
  23 tests passed；未加载 loader 的首次调用因 CSS 导入失败，正确加载后通过。
- 前端仅同步一行接口注释，本次未提交，未执行全量 typecheck/build。
- 验证日志：/private/tmp/normal-group-online-red.log、/private/tmp/normal-group-online-green.log、/private/tmp/normal-group-online-front.log。

## perf2 发布（用户追加授权）

- 目标：perf2，仅后端；前端与协议层不发布。
- 为保留线上其他已发布修复，以线上 JAR 为基底构建补丁；变更前相关 Java 类重新编译后与线上字节完全一致，两个 XML 与 HEAD 原文一致。
- 用线上类及同版本依赖编译修改后的源码，补丁的全部 10 个类/SQL 条目与已通过 109 项测试的 target/classes 逐字节一致。
- 重打包逐条断言除这 10 个条目外内容全部保持不变，保留 ZIP 压缩方式；未加入本地其他脏修改、数据库迁移或配置变更。
- 原 JAR SHA-256：099a90f2e881ba41de7a0c731162d6a63ffd65740d07bbbd4d4714e3327943c5。
- 新 JAR SHA-256：4fc66ffd87277d2b6ee79e278351e3bcf92a223cd6e18e03660a9b03df4ae1cf。
- 回滚备份：perf2 `/home/app/armada-backups/normal-group-online-20260929-2235/armada-api-deploy.jar`。
- 发布工具复用仓库 `armada_start`、`armada_wait_backend_ready`、运行制品和运行配置验证函数；仅 `--no-deps backend`。
- 部署脚本回归：`bash armada-deploy/deploy-test.test.sh` 通过。
- 发布目录及日志：`/private/tmp/normal-group-perf2-20260929/`；发布脚本退出码 0，最终运行配置检查通过。

- 发布后独立检查：2026-09-29 22:37:28（北京时间）启动，running，RestartCount=0；已记录 Started Application；启动日志 ERROR=0，无 APPLICATION FAILED TO START、NoSuchMethodError、AbstractMethodError、BeanCreationException 或 FlywayException。
- 容器 /app/app.jar SHA-256 与本次发布制品一致；接口就绪检查已通过（未鉴权请求用于确认 API 接入，不冒充真实建群验收）。
