# 安卓个人号 405 修复：perf2 验收记录

本次仅发布 perf2；两个仓库均使用 `fix/android-personal-wa-version`，所有提交留在本地，未推送。未发布 test1 或生产，未删除账号、批次或数据库数据。

## 版本及实现

使用 `2.26.32.84`。2026-10-26 读取 [WhatsApp 官方 Android 下载页](https://www.whatsapp.com/download/android)，页面标注 `(Version 2.26.32.84)`。日期是核对日期，不是推断的版本发布日期。13 个目标号的实号登录日志均确认 `platform=android / business=false / appVersion=2.26.32.84`。

| 仓库 / commit | 文件及改动 |
|---|---|
| Zhuan `d8370089fbe14c176f5863bf5f10e3aa6177193f` | `waver/person.go` 升级个人版并注明来源；`api/service/login.go` 忽略个人号旧版本覆盖；`api/service/login_platform_test.go` 回归默认、旧覆盖、平台归一化、商业版；`api/dto/dto.go` 更新字段说明 |
| Zhuan `4af1849` | `internal/armada/event.go` 将登录 405 映射为 `OFFLINE / CLIENT_OUTDATED / rawCode=405`；`internal/armada/event_test.go` 覆盖 405 与 401 保持原语义 |
| Armada `c72ddf661070b23a10b8647db5675ad2f8827298` | `AccountStateEventServiceImpl.java` 兼容旧 `NEED_REAUTH + 405`；`AccountImportLoginResultSettler.java` 结算为 `FAILED / CLIENT_OUTDATED`；`AccountStateEventServiceConcurrencyH2Test.java`、`AccountImportLoginResultSettlerTest.java`、`AccountImportLoginResultSettlerH2Test.java` 验证生命周期、真实 Mapper、首次结算、幂等、租户隔离和时间边界；本目录 `summary.md` 记录方案 |

§3.2 为单独提交，跨服务语义方案已经用户明确批准。未新增未知状态或修改 Kafka 字段结构。401/403 保持原行为。无数据库结构迁移或 Redis 格式变化。

## 旧版本是否持久化

- 六段登录路径每次从 `ToSixLoginDTO` 构造登录入参，再由 `api/service/login.go` 构造新的 `ClientPayload`。六段凭据不携带 WhatsApp 版本。
- `internal/service/app/accinfo.go` 的 `clientPayload` 为进程内字段；`SetCliPayload` 设置内存，`buildClientPayload` 仅序列化给握手，不写数据库。`CreateWSApp` 创建新实例时不恢复旧 payload。
- 同进程自动重连会复用该实例已有 payload。因此必须以新进程/正常重新上线建立新版本实例，本次已完成；出现自动重连的目标号也记录了新版本。
- Redis 的 `CommandContext` 只保存关联元数据；`CredentialVersion` 是业务凭据修订号，不是 WhatsApp 版本；无 appVersion/clientPayload 字段。未声称扫描了所有 Redis 键。
- perf2 的 `whatsapp_android_zhuan_perf` 实库只读核对为 70 表、432 列；按 version/payload/profile 名称查询仅命中 `wa_devices.os_version`，它是操作系统版本。此 schema 证据结合写入路径审查支持六段路径不持久化旧 appVersion 的结论。
- 原接口允许显式 `whatsapp_version`，现安卓个人号即使收到旧覆盖值也统一采用服务端版本。iOS 与安卓商业版逻辑及版本未修改。

## 自检

每轮 Go 修改后均按顺序执行 `gofmt -w <changed files>`、`go vet ./...`、`go build ./...`、`go test ./...`。

本机实际工具链为 Go 1.26.5；`go.mod` 声明 1.25.1，仓库现有发布 Dockerfile 使用 `golang:1.26-bookworm`，本次未改工具链。Java 聚焦测试与打包使用 JDK 17，远端后端 JRE 21 保持原配置。

| 检查 | 结果 |
|---|---|
| gofmt / go vet / go build | 通过；最终 gofmt -l 无输出，git diff --check 通过 |
| Go 全量测试 | 未全绿：仅 `pkg/noise` 8 项既有失败，在未修改的 `cd3c0bb` archive 基线复现 |
| Go 本次相关包 | `api/service`、`internal/armada` 通过；405 新增用例先复现错误映射，修复后通过 |
| Java JDK 17 聚焦回归 | 59 tests，0 failures，0 errors，0 skipped；H2 使用真实 Mapper XML、生产租户插件及 Spring 事务 |
| 部署脚本 | `bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh` 与 `bash armada-deploy/deploy-test.test.sh` 通过 |
| 生产打包脚本测试 | 既有缺失：`armada-deploy/prod/scripts/inspect-production-host.sh` 不存在；本次未使用生产打包/发布 |
| perf2 部署前后 `--check` | 通过，Kafka 消费组 Stable，组件及跨组件连接检查通过 |

8 项基线失败：`TestHandshakeRollback`、`TestNN`、`TestVectors`（缺少 vectors.txt）、`TestXX`、`Test_NNpsk0`、`Test_Npsk0`、`Test_XXpsk0`、`Test_Xpsk0`。

Java 命令：

```bash
cd armada-api
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -q \
  -Dtest=AccountImportLoginResultSettlerTest,AccountImportLoginResultSettlerH2Test,AccountStateEventServiceConcurrencyH2Test,AccountStateEventServiceImplTest,AccountStateChangedSinkAdapterTest,AccountTakeoverStateEventH2Test test
```

执行记录为 `/tmp/android-personal-405/java-focused.log`。其他原始自检输出保存在同一临时证据目录。

## 发布证据

使用仓库标准脚本，先 `--env perf2 --check`，再按授权分别执行 `--zhuan --yes`、`--be --yes`；最终再次 `--check`。SSH 经本机 SOCKS 代理访问两台 perf2 主机，未修改安全组。

| 制品 | 启动时间（北京时间，2026-10-26） | 镜像 |
|---|---|---|
| 个人版本升级 `d837008` | 18:00:33 | `sha256:2d3645338c3b45b1edad74c59c6bf0edd7452884e347ecc5e7bc887e45d4e00c` |
| 后端 `c72ddf66` | 18:17:19 | `sha256:c18bb0febb9d58d57947a65dc9831b02e7d5bb9f696604d8f2add4ad3ef95f36` |
| 最终 Zhuan `4af1849` | 18:21:01 | `sha256:62c2624cb0c63e9584337ec7fd4c8aeaeb3d52a68b2adc7f23d9d3810e3c9b6b` |

脚本均退出 0。Zhuan 远端改动文件 SHA-256 与本地一致；后端运行 JAR SHA-256 已由脚本核对。19:01 最终核验：Zhuan 三个容器 healthy、restart=0，后端 running、restart=0，镜像均保持上表版本。后端原有 JRE 21 保持不变：发布时临时采用远端原 Dockerfile（与本地仅 FROM 版本一行不同），发布结束恢复本地文件，无该文件差异。两侧迁移检查均显示无需执行。

回滚材料留在 perf2：

- Zhuan：`/home/ec2-user/whatsapp-android-zhuan/deploy/rollback/android-personal-20261026T095805Z/`，发布前镜像 `sha256:e0eac195f409cb384c68059a68d3e9be2171030dc0b4b15f9233f55ca04d1c6b`。
- 后端：`/home/app/armada-deploy/release-backups/android-personal-20261026T101352Z/`，发布前镜像 `sha256:fcda95203252ee8e9090348fc4286974b38b9c19217fd4c4b1d068e802104b47`。
- 仅回滚 §3.2 时应保留 `d837008` 的新个人版本，避免重新引入 405。

## 经批准的历史状态修复及操作范围

13 个原 405 账号已被旧逻辑标成 UNBOUND，批量上线跳过，单号上线按钮禁用。因此首次批量操作只产生预览，未产生登录命令。用户随后明确批准备份并仅将这 13 个号 `account_state=5` 改为 `1`。

修复于 18:33:17 提交：事务锁定 tenant=1、13 个精确 ID/号码、旧 UNBOUND/OFFLINE 状态和原 updated_at；提交前写 0600 备份；任一保护条件或影响行数不符即回滚。仅 SET `account_state=1`。只读逐字段比较确认其余字段全部一致。

- 远端状态备份：`/tmp/android-personal-405-state-backup-20261026T103317Z.json`
- 备份 SHA-256：`ccb9cf4b24d2a5bbf57b7dc3a6fc9b7a6f9fbee74d2ca84fa10a9d1a890b3579`
- 凭据仅在远端进程内用于连接，没有回显或落入交付文件。
- 随后经已登录 perf2 后台对 13 个号码逐个上线，网络记录和协议日志核对全部实际发出。原 7 个 401 未操作。
- 批次 113 的 20 条历史 `login_result/login_reason/online_phase` 与发布前一致；没有回写批次 113/114。
- 原在线 4 个号在进程重启后失去内存实例，数据库仍显示旧在线值。已通过正常下线/上线恢复，以下结果来自协议实时查询，不使用旧数据库计数代替。

## 实号结果（最终协议读回 19:01:05）

全部 13 个目标的握手版本均为 `2.26.32.84`，405 为 0。7 个曾成功上线；最终 6 个在线、3 个 401、3 个 403、1 个成功后被另一端替换而离线。原始脱敏证据见 [evidence.json](evidence.json)。

| 号码 | account ID | 结果 |
|---|---:|---|
| 201003523949 | 2813 | 当前在线；窗口内 5 次连接替换，自动重连 |
| 201008712230 | 2828 | 403，封禁 |
| 201068754906 | 2827 | 在线 |
| 2347013625547 | 2823 | 401，凭据失效 |
| 2348032130956 | 2815 | 401，凭据失效 |
| 27659407745 | 2821 | 403，封禁 |
| 6283861061793 | 2825 | 在线 |
| 6285380652987 | 2817 | 18:37:59 上线成功；18:55:22 被另一端替换，最终离线（LOGIN_REPLACED，生命周期 6） |
| 6285705600170 | 2820 | 当前在线；窗口内 4 次连接替换，自动重连 |
| 6285717313411 | 2816 | 在线 |
| 923004843124 | 2812 | 401，凭据失效 |
| 923042478541 | 2831 | 403，封禁 |
| 923153809009 | 2818 | 在线 |

恢复的 4 个原在线账号：`19107208629`（iOS 商业，`2.26.33.73`）、`5491157803145`、`593989174171`、`573224083969`（安卓商业，均 `2.26.25.74`）。四个协议状态均 Code=0/在线，均无 login rejected。

## 一小时拒绝码对比

以首次实际个人版版本升级容器启动时间 18:00:33 为比较边界；完整发布又于 18:21:01 补齐 405 分类。统计的是 `WhatsApp login rejected`，不把 stream error 混进登录拒绝码。连接替换原始事件是 `code="" / conflict="replaced"`，Zhuan 对 Armada 映射为 `LOGIN_REPLACED / rawCode=303`，没有观察到原始数值码 440。

| 时间段（北京时间） | 405 | 401 | 403 | 说明 |
|---|---:|---:|---:|---|
| 17:00:33–18:00:33 | 13 | 7 | 0 | 包含任务书 17:04 的 20 次失败 |
| 18:00:33–19:00:33 | 0 | 3 | 3 | 完整 3600 秒；拒绝均来自本次 13 个重登号 |

原 7 个 401 未发起重登，因此不出现在发布后拒绝计数中。4 个恢复的 iOS/安卓商业号在此窗口内无 login rejected。另记录到 10 次连接替换事件（201003523949：5，6285705600170：4，6285380652987：1），其原始 conflict=replaced，与登录拒绝分开统计。

## 限制与遗留

- Go 全量测试因 8 项已复现的基线失败仍未全绿，不能宣称全量测试通过。
- 401/403 是本次真实 WhatsApp 响应，不再是版本 405；需要业务方处理失效凭据/封禁账号。
- `201003523949` 与 `6285705600170` 虽最终在线，但发生过反复连接替换；`6285380652987` 成功后被替换，最终离线。需要业务方检查同号是否在另一客户端使用，当前不能宣称 13 个号全部恢复或持续稳定。
- 新 405 分类的真实 Mapper/状态服务和协议映射回归已通过；实号升级后未再产生 405，未人为注入生产式假事件或回写冻结批次制造验收结果。
- 代码、授权的历史状态修复、perf2 发布、13 号实测与完整一小时比较均完成。未处理上述账号自身失效/封禁/连接竞争，也未修复无关的基线测试及生产打包脚本问题。
