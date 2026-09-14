# 群详情按已持久化资料展示

- 位置：Armada 与 wheel-saas-pure-web 主仓库；后端分支 `1.0.3-snapshot`。
- 状态：本地调整和聚焦验证完成，未提交、未部署。

## 问题与处理

第一套环境群 `120363428783102402@g.us`（groupLinkId=33855）已有 15 人完整成员快照、4 个管理员及部分群权限，详情却被空的 `metadata_observed_at` 整体拦截。

- 详情读取取消整行 metadata 时间戳门槛，展示已有群资料，缺失权限保留 null。
- 成员可用性独立读取既有 `wa_group_profile.member_snapshot_version`，沿用完整快照成员 SQL；区分尚无完整名单与完整名单为空。
- `GroupLinkPreview.memberSnapshotVersion` 仅为现有 SQL 投影字段，没有新增数据库列。
- 前端缺失权限及限时消息标为“未知”，头部按已读取成员展示提示，不再把后台 PENDING 任务当成全部资料缺失。
- 保留旧时间戳在后台的数据版本用途；不改同步开关、不补写数据、不触发真实 WhatsApp 操作。

## 验证

- 后端新回归先红：40 项中 7 项失败、0 errors，复现旧标记拦截和未投影成员版本；修复后 52 项全部通过、0 skipped。
- 后端集合：`GroupDetailServiceImplTest,GroupDetailCurrentMapperH2Test,GroupDetailSnapshotReaderImplTest,GroupDetailMemberRemovalIdentityTest,GroupListCurrentMapperSqlShapeTest`。
- H2 使用原 Mapper XML、生产 MyBatis-Plus 插件和 Spring 事务，覆盖 15 人/4 管理员、未知权限、旧快照及离群成员排除、租户和软删除隔离、完整空名单与缺失名单区分。
- 本机 Mockito 不能自行附加 agent，Maven 使用 `-DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar` 完成测试。
- 前端详情、限时消息及刷新轮询聚焦测试 8/8 通过；typecheck、变更文件 ESLint、生产 build 和两仓 diff check 通过。
- 扩展前端回归 21 项：17 通过、4 失败。将 HEAD 的相关文件导出到临时目录后，原样复现同样 4 项：头像上传 timeout 断言，以及 3 项权限请求漏断言已有 timeout config。未改动这些无关用例或请求行为。
- 详情原测试仍在抽屉源码中查找已迁到 `waitForGroupMetadataRefresh.ts` 的判断，已改为检查实际实现文件。
- 未部署，因此截图对应群的线上页面尚未验收。

## 边界与回退

- API 结构不变；`liveStateAvailable` 表达本地资料可读，各项权限与成员完整性独立判断。
- 没有数据库、Redis 或协议层变更。
- 保留其他会话的在途文件。回退仅撤销本次详情 Service、投影、前端展示和对应测试差异。
- 抽屉与详情 Service 原已超过仓库文件长度建议；本次仅作局部修复，未进行无关拆分，也未执行合入。
