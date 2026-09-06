# 手机直传控端入口：主线集成交付

已基于当前 `1.0.3-snapshot` 的 `b68890ed` 完成移植；集成工作树为 `.worktrees/device-import-ingest-main`，分支 `codex/device-import-ingest-integration`。本地验证完成；test1 已暂存候选镜像并放行专用 443 安全组，但运行服务未切换，未验证真实手机上线。

## 结果与主线兼容

- `POST /api/device-imports` 只认环境变量中的静态令牌；成功裸 JSON 为 `200 {"batchId":123,"onlinePhase":"QUEUED"}`，失败为实际 4xx/5xx 和 `{message}`。
- 手机全参原文入明细；纯数字 `jid` 作为号码来源，与外层 `phone` 比较；六字段运行凭据写 `cred_format=1`，批次来源仍是 `import_format=3`，协议为 ANDROID。
- 主线现有管理端 iOS 原生全参路径仍保存 `cred_format=4`。`parseDeviceParams` 明确产生六段运行格式，`importDeviceAccount` 与管理端共用 `importEntries` 和原 RowWriter，没有另造入库或调度流程。
- 令牌中的机型是新入口的展示配置，不能将其误用于选择 iOS 原生解析。申报账号类型由主线现有 `declared_account_type` 保存；有效类型仍遵循当前主线协议校验规则。
- 外层 Spring 事务覆盖批次、账号、状态、凭据和明细；任意写入失败或同号冲突回滚。原有 QUEUED/10 秒调度不变。
- 独立 nginx 仅发布 443，只有精确设备路径可达；环境变量/证书不进入仓库，请求内容与令牌不写日志。

## 依赖来源

最初实现完整承接指定 `full-params-android-import` 工作树的 14 个未提交文件，SHA-256 见 [dependency-manifest.json](dependency-manifest.json)。该源工作树收尾再次核对，全部未被本任务修改。初始实现的 112 项定向测试和旧基线验证留在 [initial-worktree-verification.json](initial-worktree-verification.json)。

最新主线已经有全参转换、iOS 原生导入、五段输入和账号类型校验。此次按行为移植设备入口，复用主线现有转换器，并明确区分手机纯数字 jid 和管理端 phone 口径；没有用旧文件覆盖主线新增功能。原 14 文件清单是依赖来源证据，不表示当前主线仍需再次复制它们。不得部署七月旧树的 JAR。

## 当前验证

| 检查 | 结果 |
| --- | --- |
| JDK 17 定向测试 | 130 项通过，0 failures/errors/skipped；含新接口、真实安全链、H2 Mapper/事务/并发、全参和原生 iOS、上线命令及配对导入回归 |
| 新接口测试 | 31 项通过，其中真实 H2 11 项；明确验证五表回滚、唯一键等待、租户隔离、原文和六段凭据、现有 iOS 原生落库 |
| nginx / Compose / preflight | 9 项通过；真实本地 TLS、完整 body、精确路径、方法、身份头、端口、日志及当前 Compose 挂载适配 |
| API 文档 / Mapper XML | 文档测试 1 项通过（54 controllers / 291 endpoints）；xmllint 退出码 0 |
| JDK 17 打包 | 退出码 0；新 Controller 在包内，无 H2/testsupport/.env；哈希见 verification.json |
| 当前全库回归 | NOT_RUN；旧基点 332 项回归及既有失败仅作历史记录，不冒充当前主线结果 |
| 现有发布脚本测试 | BLOCKED：嵌套工作树跳板私钥路径未解析；生产协议 .env.example 不在 checkout；未复制私钥或制造配置掩盖失败 |
| 真库 / 公网 / 实机 | NOT_RUN，待目标环境及配置确认 |

测试命令（`armada-api`）：

```bash
env JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
  DB_URL=jdbc:invalid:local-unit-tests \
  mvn -q -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  '-Dtest=Device*Test,FullParamsToSixConverterTest,AccountImportParserTest,AccountImportRowWriterTest,AccountOnlineCommandServiceImplTest,AccountImportExportFilenameTest,AccountImportLoginResultSettlerTest,TokenAuthenticationFilterTest,AccountPairing*Test' test
```

## 评审与发布边界

按 expert-reviewer 自查完整改动：保留主线原生导入与类型校验；无 schema 迁移；设备请求不能指定租户或运行格式；固定错误消息不输出原始异常。H2 验证使用真实 Mapper/XML/事务，协议命令边界仍为测试替身，不能视为 Kafka 或协议握手成功。

发布需要：确认 test1/perf2、专用域名及可信证书、私网管理绑定地址、令牌对应租户/分组/机型/账号类型/IP 策略。秘密通过目标环境安全配置；现有部署脚本必须实际启用 `docker-compose.device-ingest.yml`，不能只使用原 base Compose，否则必填令牌未注入会导致启动失败。专用入口只开放 TCP 443。部署流程与回滚见 [README](../../../armada-deploy/device-ingest/README.md)。

## test1 准备进展

详见 [test1-staging.json](test1-staging.json)。`d96ea1e0` 修复了普通重部署可能丢失令牌注入/管理端绑定的问题，并已通过 9 项本地网关与 Compose 回归。主仓库的测试环境部署脚本测试已通过。AWS 专用安全组已放行 443，仅附着 test1；镜像已暂存且 JAR 哈希一致。域名/可信证书和实际默认配置尚未确认，所以未启动新入口、未切换后端。历史表格中的远程 NOT_RUN 指此前状态，当前只有只读预检、网络规则与制品暂存的证据。
