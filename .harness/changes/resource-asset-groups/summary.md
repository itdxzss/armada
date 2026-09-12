# 图片素材分组与业务隔离

日期：2026-09-12；分支：1.0.3-snapshot。状态：代码已提交并推送，已部署第一套 test1；详情见 deployment.md。

用户确认规则：历史图片超链和养群两边都能查到；此后新上传图片按业务分开。两边均支持新建分组、筛选、批量移组、上传指定分组及删除分组。

## 实现

- 继续使用 marketing_template_file 保存单份字节和既有 ID。asset_scope 为 NULL 的历史图片在两边可见；新上传写 1 超链 / 2 养群，旧普通营销上传写 3，不会误归入超链。
- 不按文件名、引用或日期猜测历史归属。迁移不更新任何历史图片的 asset_scope，不复制 BLOB。
- 分组名在 tenant_id + scope 内唯一。resource_asset_group_ref 按 tenant_id + file_id + scope 保存归属，同一历史图片可以在两边各放一个不同分组。
- 删除分组只解除该业务分组关系，图片、标签、ID 和已有引用保留。未分组为虚拟分组，不可删除。
- 新图片的列表、详情、内容、标签候选、编辑、删除、移组及新建引用按业务校验；API 按业务检查现有权限。已有执行链路的内容读取保留，历史任务不因这次分类改变图片 ID。
- 分组锁与素材行锁保护上传、移组及删组；整批校验后操作，跨业务/跨租户或无效素材使整批回滚。
- 历史图片本身仍是共享对象，其名称、标签和删图状态两边共享；独立的是分组关系。删除有引用的图片继续受引用保护。

## API 和页面

/api/resource-assets 相关接口携带 scope=HYPERLINK 或 SCRIPT；未提供时兼容默认 HYPERLINK，新上传不能选择历史共享。GET/POST /groups，DELETE /groups/{id}，PUT /group。列表 groupId 省略为全部、0 为未分组、正数为指定组。上传可传 groupId。

超链图片页固定 HYPERLINK；剧本素材库新增“管理养群图片”，及剧本编辑器选择器固定 SCRIPT。历史图片显示“历史共享”。

## 迁移与回滚

按顺序运行 V189 和 V190。V190 将分组归属由 file.group_id 迁移至按业务的关系表，并删除旧列。发布需停止旧版写入并协调应用切换，不支持在迁移期间让旧版继续写入。

上线前确认目标环境、Flyway 版本占用及 schema；2026-09-12 已在 test1 由 Flyway 执行 V189、V190，均成功。回退到使用 group_id 的中间版本前，必须先按 rollback.sql 恢复旧列及超链关系。回退旧版本会恢复未隔离行为，不能视为业务隔离仍有效。新表、归属字段和养群分组关系保留，不直接破坏性删表。数据模型 wiki 已通过既有 gen_datamodel.py 从 test1 三张素材表的实际 information_schema 生成，保留其余章节。

## 验证

87 项后端聚焦测试通过，包含 19 项 H2 Mapper / Spring 事务 / 租户插件测试，覆盖历史双边可见、新图隔离、同名分组独立、共享图独立移组、越界操作拒绝、标签隔离、删除保留引用和真实 PNG 绑定检查。内容 URL 范围转换测试及服务回归也通过。

前端 tsc + vue-tsc 通过，定向 ESLint / Stylelint 通过；素材领域测试 4 项通过；production build 和本地浏览器夹具测试 4 项通过，涵盖分组全流程、上传分组、权限及养群管理入口的 SCRIPT 查询/创建/上传。浏览器夹具不能替代测试环境真实 API 联调。

证据：/tmp/resource-asset-scope-tests.log、/tmp/resource-asset-scope-converter-tests.log、/tmp/resource-asset-scope-e2e.log、/tmp/resource-asset-scope-typecheck.log。

不包含普通营销与养群的消息模板集合拆分；本次隔离对象为图片素材。其他会话在途修改保留。
