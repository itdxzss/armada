# 历史群创建者修复：本地验证

2026-09-18。按用户要求直接修改 armada 与 whatsapp-server-feature-android-zhuan 主仓库，避开其它 agent 的账号、拉群任务和登录相关在途修改。本轮没有 commit、push、部署、真实数据库更新或 WhatsApp 在线查询。

## Java

Java 17，执行 `mvn -q -f armada-api/pom.xml -DargLine=-javaagent:<本机 byte-buddy-agent-1.14.19.jar> -Dtest='<下列测试类>' test`。本机 Mockito 自附加受限，显式启动 agent 后测试正常；未改变 pom。

17 类共 218 项，0 failures / 0 errors / 0 skipped：

- GroupCreatorCompatibilityMapperH2Test：4
- GroupCreatorPhonesTest：3
- AndroidNativeFixedAccountGroupMetadataAdapterTest：15
- AndroidAccountParticipatingGroupMapperTest：7
- GroupMetadataSnapshotServiceImplTest：10
- GroupCreatorCompatibilityWriterTest：3
- AccountGroupMembershipSnapshotServiceImplTest：2
- GroupLinkServiceImplTest：39
- HttpGroupMetadataAdapterTest：2
- GroupDetailMemberRemovalIdentityTest：7
- GroupDetailServiceImplTest：37
- HistoricalGroupServiceImplTest：14
- WhatsappGroupMemberCacheServiceImplTest：5
- MarketingTaskWhatsAppMemberProviderTest：11
- RoutingFixedAccountGroupMetadataPortTest：2
- ProtocolGroupEventConsumerTest：35
- GroupProfileReportedSinkAdapterTest：22

先运行 217 项，再对最终 HTTP 角色隔离修改补跑相关 8 类，合并最终报告共 218 项。日志 `/private/tmp/group-creator-java-final.log`、`/private/tmp/group-creator-java-final2.log`；JUnit 报告位于 armada-api/target/surefire-reports，后续并行构建可能覆盖它们。

数据库回归直接加载生产 GroupLinkPreviewMapper.xml、MyBatis-Plus 租户插件和 Spring DataSourceTransactionManager，以 H2 MySQL 模式验证：空结果保留号码/国家/时间；不同号码不覆盖；同号码补国家；旧观察不压新观察；批量互不串行；租户隔离；事务回滚。原 XML 的 VALUES(...) 比较在 H2 重写 UPDATE 时不支持，现用匹配 group_link_id 分支中的绑定参数表达相同条件，最终生产 XML 可直接在 H2 执行，没有 mock Mapper 或测试专用替代 SQL。

H2 不证明 MySQL/InnoDB 行锁、并发和线上数据效果。真实 MySQL 测试未执行。

## Android Go

- 对本次改动文件执行 gofmt；git diff --check 无问题。
- `GOCACHE=/private/tmp/group-creator-go-cache go vet ./...`：通过。
- `GOCACHE=/private/tmp/group-creator-go-cache go build ./...`：通过。
- `GOCACHE=/private/tmp/group-creator-go-cache go test ./...`：已执行，唯一失败包为 pkg/noise。api/service、internal/armada、internal/service/groupidentity 等相关包通过。
- 沙箱初次执行不允许本机回环端口，miniredis/httptest 失败；获准后重跑本地全套，已消除该环境限制。

Noise 基线核对：将未修改 HEAD `6019251` 的 go.mod、go.sum、pkg/noise 归档到 `/private/tmp/group-creator-noise-baseline`，运行 `go test ./pkg/noise`，同样 6 passed / 8 FAILED。失败为 TestHandshakeRollback、TestNN、TestVectors、TestXX、Test_NNpsk0、Test_Npsk0、Test_XXpsk0、Test_Xpsk0。本次未修改 pkg/noise 或 Go 依赖。

日志：`/private/tmp/group-creator-go-vet.log`、`/private/tmp/group-creator-go-build.log`、`/private/tmp/group-creator-go-all-local.log`、`/private/tmp/group-creator-noise-baseline.log`。

## 业务边界

只有可确认身份才写创建者，国家和洲由已确认手机号解析。未提供创建者、无映射、映射故障或证据冲突仍保持未知，并通过 CreatorPhoneReason 区分；不能承诺现场缺失的 23 群全部补齐。第二套环境部署、刷新和页面验收按用户要求后置。
