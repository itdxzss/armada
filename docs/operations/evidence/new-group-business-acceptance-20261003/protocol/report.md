# NEW_GROUP 协议本地验证补充

日期：2026-10-03（Asia/Shanghai）。仅本地源码/现有测试，没有连接远程环境、WhatsApp或共享数据库，没有修改代码/安装依赖。

## 工作树

- Web HEAD `d8c35e1ceca0c041285779826e527a83541fa00f`，工作树干净；搜索仓库未发现根或嵌套 AGENTS.md。项目测试命令与 Jest 配置已读。
- Android HEAD `a220f15064f425594702fbad7473dd503e901d1e`；仅既存 `.codegraph/daemon.pid` 修改，测试前后一致。已读根 AGENTS.md，未发现嵌套 AGENTS。
- 哈希与测试后状态：source-snapshot.json。

## 本次测试

### Web

使用本机已有 Node v24.19.0（符合项目 engines >=24），命令：

```sh
/Users/daishuaishuai/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node --experimental-vm-modules ./node_modules/jest/bin/jest.js --runInBand --runTestsByPath src/commands/group-profile-executor.test.ts src/routes/groups-detail.test.ts src/worker/group-member-link-mode.test.ts
```

结果：3 suites、57 tests 全通过，0失败。日志 `web-tests.log`。

- group-profile-executor.test.ts:188-222 从真实 worker 分发入口解析序列化 outbox envelope，调用 mock socket 的群名/简介 setter，并验证两者完成后发布结果，再 worker ACK；简介多行正文完整传递，结果包含 commandId/actionId/attemptNo/source/operation。
- :252-285 验证发布失败重放结果，不重复 setter；简介 setter 异常保持 UNKNOWN/UNCERTAIN，failedItem=DESCRIPTION。
- 此文件其余现有测试覆盖逐项缺失/失败/超时、非法字段、无账号、busy等；不把 setter 异常当 SUCCESS。
- groups-detail.test.ts:6-35 验证 metadata 和 participants 路由均经账号管理器的主动读取边界；:39-102 验证 desc、subject、id、成员与角色字段透传。
- group-member-link-mode.test.ts:9-28 以构造 IQ 回包验证只发一次 query，再解析字段；不是外连。

Web覆盖缺口：当前已选测试没有将“长多行中文/emoji简介的实际 IQ 解析→HTTP JSON”串成单个端到端契约测试；群描述参数的多行传递和HTTP desc映射分开覆盖。源码未发现简介截断，但不以源码推断冒充真群验证。

### Android

使用本机已有 Go 1.26.5，模块 go 1.25.1；关闭依赖下载，命令：

```sh
GOPROXY=off GOSUMDB=off go test ./api/service ./internal/armada -run 'Test(GroupProfile|GroupMemberResponse|ParseGroupActionCommandAcceptsGroupProfileApply|ParseGroupActionCommandRejectsAllNullProfileFields|ParseGroupActionCommandStillAcceptsNormalGroupCreationAfterProfileApply)' -count=1 -v
```

结果：两个package成功，22个顶层测试和33个子测试通过，0失败。日志 `android-tests.log`。这些测试用fake resolver/native client/promise/state/event writer或纯IQ解析，HTTP使用httptest Recorder而不监听端口；不启动业务服务。

- api/service/group_profile_metadata_test.go:20-100 覆盖完整多行中文/emoji与外围空白简介、id/jid属性、明确空简介、缺省简介。构造原始IQ→真实IqNode.Process/GetResult→真实groupMemberResponse→Gin JSON→解码逐字相等。测试边界从收到IQ开始，不证明外网账号在线或真实资料已生效。
- internal/armada/group_profile_sender_test.go:45-106 覆盖生产命令族分发、字段完整进入原生接口、重放只发结果、非法载荷无原生调用、简介失败UNKNOWN且failedItem=DESCRIPTION。
- :145-165 覆盖原生简介setter必须等待GetResult：IQ成功、IQ拒绝、超时、仅accepted四类；仅accepted不算成功。
- group_profile_apply_test.go覆盖profile命令解析、全null拒绝、normal_group_creation兼容、逐项跳过/失败、仅首个失败原因、成功、原生头像/简介接口和非法头像。

## 回读是否是主动真实查询

### Web源码链

1. `protocol-layer/src/routes/groups.ts:524-539` GET `/v1/groups/:groupJid/metadata` 调用 `ctx.accounts.readGroupMetadata(accountId, groupJid)`。
2. `protocol-layer/src/worker/account-manager.ts:878-900` 每次取当前在线socket并调用 `readGroupMetadataWithMemberLinkMode`。groupMetadataReadStates仅跟踪inFlight和suppressUntil以抑制主动读取引起的事件回灌，不存metadata结果。
3. `protocol-layer/src/worker/group-member-link-mode.ts:75-93` 直接 `await sock.query`，IQ为type=get、xmlns=w:g2、to=当前groupJid，子节点query request=interactive；然后 `extractGroupMetadata(result)`。
4. 当socket没有query方法，回退 `sock.groupMetadata(groupJid)`；本机Baileys实现 `node_modules/baileys/lib/Socket/groups.js:70-81` 同样发送上述实时get IQ，不取缓存。
5. Baileys同文件:359-367取description/body为完整字符串；路由 `groups.ts:229-238` 将 `meta.desc ?? null` 原样返回，没有截断。成员权限也来自本次metadata participants。

结论：该源码路径为主动IQ回读。此次本地测试只验证发起查询/解析/映射契约，真实WA响应仍须环境验收。

### Android源码链

1. `api/router/router.go:145` POST `/groups/members/:key` → `api/controller/group.go:61-66` → `api/service/group.go:79-108`。
2. `GetGroupMemberService`通过GetValidWSApp取在线实例，调用waapp.GetGroupMember(groupJid)，同步promise.GetResult，拒绝IQ错误或无GroupInfo。
3. `internal/service/app/group.go:60-62` → `internal/service/node/node_processor.go:815-819` 构造并 `SendBuilder`。
4. `internal/service/node/processor/iq.go:377-383` 注册10秒IQ超时；:2625-2640 构造type=get、xmlns=w:g2、to=当前群、query request=interactive。
5. `internal/service/node/nodes/iq.go:1067-1069,1121,1132-1139` 从description/body完整读取字节并转字符串写入GroupInfo.Description；`api/service/group.go:126-130`映射为Desc。

结论：此API路径直接发送群metadata IQ，不经过用于发送准备的Redis成员缓存。源码中其他路径存在缓存，不影响上述回读链。缺省简介与明确空简介在Android响应均为Desc空串；NEW_GROUP要求非空简介，均无法匹配非空期望值。

## 证据边界

两端设置命令成功都不是“回读一致”的替代物，UNKNOWN也不能被直接改写成成功。此次结果仅属于本地测试与源码链条，不包含test1/perf2的真实资料、群JID、成员或料子拉入证据。
