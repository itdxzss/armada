# 本地验证记录

日期：2026-09-19；主仓当前工作区实现。没有连接共享数据库或远程协议服务。

## 先红后绿

先加入老JID补齐与真实PN替换推导值两个用例，得到2 tests / 2 failures / 0 errors；原实现分别不写号码及保留旧推导值。

最终聚焦回归：15个测试类，145 tests / 0 failures / 0 errors / 0 skipped，Maven退出码0。

```bash
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -q -Dtest='GroupCreatorCompatibilityWriterTest,GroupCreatorCompatibilityMapperH2Test,GroupCreatorSourceMigrationTest,GroupProfileReportedSinkAdapterTest,GroupMetadataSnapshotServiceImplTest,AccountGroupMembershipSnapshotServiceImplTest,GroupLinkRegistryServiceImplUnitTest,GroupLinkRegistryPullTaskTargetTest,GroupLinkServiceImplTest,GroupControlRelationMapperH2Test,PullTaskGroupMarketingCandidateMapperH2Test,GroupFolderMapperInMemoryTest,FlywayMigrationSqlContractTest,FlywayMigrationVersionContractTest,CountryServiceImplTest' -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar test -f armada-api/pom.xml
```

| 测试类 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| GroupCreatorCompatibilityWriterTest | 8 | 0 | 0 | 0 |
| GroupCreatorCompatibilityMapperH2Test | 9 | 0 | 0 | 0 |
| GroupCreatorSourceMigrationTest | 2 | 0 | 0 | 0 |
| GroupProfileReportedSinkAdapterTest | 23 | 0 | 0 | 0 |
| GroupMetadataSnapshotServiceImplTest | 11 | 0 | 0 | 0 |
| AccountGroupMembershipSnapshotServiceImplTest | 3 | 0 | 0 | 0 |
| GroupLinkRegistryServiceImplUnitTest | 4 | 0 | 0 | 0 |
| GroupLinkRegistryPullTaskTargetTest | 7 | 0 | 0 | 0 |
| GroupLinkServiceImplTest | 39 | 0 | 0 | 0 |
| GroupControlRelationMapperH2Test | 6 | 0 | 0 | 0 |
| PullTaskGroupMarketingCandidateMapperH2Test | 3 | 0 | 0 | 0 |
| GroupFolderMapperInMemoryTest | 11 | 0 | 0 | 0 |
| FlywayMigrationSqlContractTest | 1 | 0 | 0 | 0 |
| FlywayMigrationVersionContractTest | 2 | 0 | 0 | 0 |
| CountryServiceImplTest | 16 | 0 | 0 | 0 |

## 扩展回归与既有失败

扩展至18个类得到189 tests / 1 failure / 10 errors / 0 skipped，178项通过。11个未通过项均在本任务开始前的脏工作区副本复现，失败方法集合完全一致：

- GroupMembershipCountSemanticsMapperH2Test：1 error，账号测试表缺 declared_account_type。
- MysqlModeMapperInMemoryTest：2 errors，群分组测试表缺 system_builtin。
- PullTaskGroupMarketingGroupMapperInMemoryTest：1 failure / 7 errors，测试表缺 group_classification；其中1项因期望业务异常却先收到SQL异常而失败。

副本位于 /tmp/armada-jid-baseline，使用本轮开始前备份覆盖任务修改文件，未回退主仓或覆盖其他会话修改。与本次新增列相关的旧位置式INSERT已改为明确列名；上述其他缺字段不在本任务顺手修改。

## 覆盖与限制

- 首次历史群、真实事件处理器、HTTP快照均覆盖缺PN老JID；样例经真实CountryServiceImpl/libphonenumber解析得到916375552817、IN、ASIA。
- 正常PN优先、空白、现代JID/错误域名/多连字符、国家服务异常、真实来源覆盖较新推导值、反向禁止、地区同步清理、租户隔离、事务回滚、成员角色/营销执行资格均有断言。
- 真实GroupLinkPreviewMapper XML、生产MyBatis-Plus租户插件、H2 MySQL模式和Spring事务运行通过。
- V207实际ALTER与来源初始化在H2执行；动态PREPARE和information_schema守卫做结构检查，版本唯一性通过。
- 5份相关Mapper XML的xmllint检查及任务相关git diff --check通过。
- 未运行真实MySQL/InnoDB、Testcontainers MySQL、远程迁移或业务验收；MySQL顺序赋值以额外SQL顺序测试补充H2差异。

临时日志：/tmp/armada-jid-red.log、/tmp/armada-jid-focused-final.log、/tmp/armada-jid-final-tests2.log、/tmp/armada-jid-baseline-tests.log、/tmp/armada-jid-baseline-marketing.log。
