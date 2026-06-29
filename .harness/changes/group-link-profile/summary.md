# 变更记录：群组本地资料更新

- 日期 / 分支 / worktree: 2026-06-30 / main / armada
- 需求来源: 群组列表需要编辑群名称、群头像;本轮先做后端
- 状态: 进行中

## 目标（一句话）

在群组列表提供本地群名称、备注、头像 URL 更新接口,供前端明细/列表编辑后刷新展示。

## 缺口拆解 / 任务清单
- [x] 新增 `PATCH /api/group-links/{id}` 后端接口。
- [x] 新增 `GroupLinkProfileDTO` 请求体,支持 `groupName`、`remark`、`avatarUrl`。
- [x] 更新 `group_link.group_name`、`group_link.remark`。
- [x] 通过 `group_link_preview.avatar_url` 保存本地头像,不覆盖协议预览快照字段。
- [x] 补 controller/service 单测与 preview mapper 真库测试。

## 关键设计决策

- 本接口只更新 Armada 本地展示资料,不调用协议层修改 WhatsApp 真实群名称/头像。真实改群资料后续需要协议能力和权限控制再单独设计。
- PATCH 语义为 `null=不改`,空字符串清空对应字段。这样前端可以局部提交,也能显式清空本地值。
- 通用协议预览 upsert 不再用空头像覆盖现有 `avatar_url`;本地头像更新使用专用 `upsertAvatarUrl`,避免预览刷新清掉运营侧头像。

## 验证（evidence-before-done）

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn test -Dtest=GroupLinkServiceImplTest,GroupLinkControllerTest
```

关键输出:

```text
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
xmllint --noout armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml armada-api/src/main/resources/mapper/group/GroupLinkPreviewMapper.xml
```

关键输出: 退出码 0。

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
./armada-api/dbtest.sh GroupLinkPreviewMapperDbTest
```

关键输出: 命令退出码 0。

## 部署
- commit / 环境 / 部署后验证结果: 未提交,未部署。

## 遗留 / 跟进
- 前端群组列表/明细页接入 `PATCH /api/group-links/{id}`。
- 真实修改 WhatsApp 群名称/头像尚未实现,需后续补协议接口与 Armada 调用链。
