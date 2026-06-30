# 变更记录：真实群资料协议接口

- 日期 / 分支 / worktree: 2026-06-30 / main / armada
- 需求来源: 群组详情需要通过协议层修改 WhatsApp 真实群名称、描述、公告文本和头像
- 状态: 已完成

## 目标（一句话）

在 Armada 群组列表后端补齐真实群资料修改接口,由本地群链接 ID 和操作账号路由到协议层 group profile 接口。

## 缺口拆解 / 任务清单

- [x] 新增 `POST /api/group-links/{id}/subject`。
- [x] 新增 `POST /api/group-links/{id}/description`。
- [x] 新增 `POST /api/group-links/{id}/announcement-text`。
- [x] 新增 `POST /api/group-links/{id}/picture`。
- [x] 新增 `GroupProfilePort` 与 `HttpGroupProfileAdapter`,对接协议层 `/v1/groups/:groupJid/*`。
- [x] 校验群链接存在、已解析 `groupJid`、操作账号存在且在线、有 `protocolAccountId`。
- [x] 协议成功后同步本地展示镜像:`subject` 写 `group_link.group_name`,`picture(url)` 写 `group_link_preview.avatar_url`。
- [x] 补 controller、service、HTTP adapter、配置测试。
- [x] 复核修正:picture 请求只发送实际使用的 `image.url` 或 `image.base64`,避免向协议层 optional 字段传 null。
- [x] 复核修正:`subject` 本地镜像只更新 `group_name`,不再复用本地资料更新接口覆盖 `remark`。

## 关键设计决策

- 原 `PATCH /api/group-links/{id}` 继续只改 Armada 本地展示资料,不改变语义。
- 新增的四个 POST 端点才代表真实 WhatsApp 群资料修改,请求体都显式传 `accountId` 作为操作账号。
- `description` 允许 null 或空字符串,表示清空群描述;`subject` 和 `announcement-text` 必须非空。
- `picture` 支持 `url` 或 `base64` 二选一;只有 URL 形态会同步本地头像 URL。
- 协议层调用失败时不写本地镜像字段,避免出现假成功展示。
- 真实协议调用方法不包 DB 事务,避免数据库事务跨外部 HTTP 调用。

## 验证（evidence-before-done）

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q clean -DforkCount=0 -Dtest=GroupLinkServiceImplTest,GroupLinkControllerTest,HttpGroupProfileAdapterTest,ProtocolConfigurationTest test
```

关键输出:

```text
GroupLinkServiceImplTest: Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
GroupLinkControllerTest: Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
HttpGroupProfileAdapterTest: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
ProtocolConfigurationTest: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dmaven.test.skip=true compile
```

关键输出: 命令退出码 0。

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
xmllint --noout armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml
```

关键输出: 命令退出码 0。

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git diff --check -- armada-api/src/main/java/com/armada/group armada-api/src/main/java/com/armada/platform/protocol armada-api/src/main/resources/mapper/group armada-api/src/test/java/com/armada/group armada-api/src/test/java/com/armada/platform/protocol .harness/changes/group-profile-protocol
```

关键输出: 命令退出码 0。

普通 fork 模式下同一组 surefire 测试在本机 JDK 23 环境出现 forked VM 启动失败,未进入业务断言;改用 `-DforkCount=0` 后测试通过。

## 部署

- commit / 环境 / 部署后验证结果: 未提交,未部署。

## 遗留 / 跟进

- 前端群组详情页接入四个 POST 端点。
- 后续群设置、成员管理、邀请审批、发送消息仍需单独小口接入。
