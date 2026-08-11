# 变更记录：Android 群资料协议路由修复

- 日期 / 分支 / worktree: 2026-08-10 / `fix/android-group-profile-routing` / `/Users/wanghh/IdeaProjects/baofu/armada`
- 需求来源: 第一套环境 Android 群修改群名称、群头像失败
- 状态: 已完成

## 目标（一句话）

群资料写操作携带完整账号协议引用，Android 账号调用 Zhuan 原生群名称、群头像接口，Web 账号继续调用 Baileys 接口。

## 缺口拆解 / 任务清单

- [x] 定位固定走 Web 群资料端口导致的协议错路由
- [x] 为群资料端口补齐 Web/Android 后端路由
- [x] 接入 Android 群名称和群头像原生 HTTP 契约
- [x] 更新业务调用方，禁止丢失账号协议类型
- [x] 补充适配器、路由和业务层测试
- [x] 更新故障诊断文档与验证证据
- [x] 修复权限写成功后详情立即读取旧快照导致的开关回弹
- [x] 补齐 Android 群成员响应中的 `MemberAddMode`，恢复“添加其他成员”写后回读确认

## 关键设计决策

- 群资料端口统一接收 `ProtocolAccountRef`，不再只传 `protocolAccountId`，避免协议类型和 Android `wsPhone` 在业务层到适配层之间丢失。
- Android 原生头像接口只接收 base64；URL 形态明确返回能力不支持，不做隐式下载或跨协议回退。
- Android 当前没有头像 URL 回读契约，写成功返回 `applied=true, avatarUrl=null`，由现有 metadata 刷新流程异步更新镜像。
- 群备注仍是 Armada 本地字段，不进入协议路由修复范围。
- 群权限写操作继续以同账号实时回读作为成功标准；确认成功后只同步写入本次权限字段的本地快照，避免前端立即重载时显示旧值。
- Android `members` 响应原先只暴露 `Announce`，遗漏 `MemberAddMode`；Zhuan 响应补齐该字段，Armada 已有 mapper 可直接识别。

## 验证（evidence-before-done）

- `mvn -DskipTests compile`：`BUILD SUCCESS`，1391 个主源码文件编译通过。
- `mvn -Dtest=HttpAndroidNativeClientTest,AndroidNativeGroupProfileAdapterTest,RoutingGroupProfilePortTest,HttpGroupProfileAdapterTest,GroupDetailServiceImplTest,GroupLinkServiceImplTest,ProtocolConfigurationTest test`：`Tests run: 75, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- `git diff --check`：无输出。
- 完整定向回归：群资料路由、Android metadata、权限业务与真实 Mapper 共 85 个测试通过，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。
- Android Zhuan 本机未安装 Go 1.25 工具链，无法执行 `gofmt/go vet/go build/go test`；已补充响应契约单测，须在 CI 或具备 Go 1.25 的环境完成验证。

## 部署

- commit / 环境 / 部署后验证结果: 未部署

## 遗留 / 跟进

- Android 群描述、公告文本及头像 URL 回读尚无本次需求确认，保持能力不支持。
- Android Zhuan 改动位于同名分支 `fix/android-group-profile-routing`，部署时必须与 Armada 修复配套发布。
