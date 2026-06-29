# 导入链接(group import-links)实现总结

> 状态:**实现完成,未部署**(分支 `feat/group-import-links`,从 main `be40aad` 拉出)。armada 业务逐块重建第 3 块(接 营销模板 / IP 管理)。
> 设计:`docs/business/导入链接.md` · 计划:`.harness/changes/group-import-links/plan.md` · 迁移:`db-migrations.sql` + `rollback.sql`。

## 背景与范围
- **做(import core,纯 DB、零协议耦合)**:WS 链接分组 CRUD、导入群链接(TXT/CSV/Excel,同步,upsert 收编)、明细、导出失败、迁移分组、列表搜索。
- **拆出去(协议耦合,随群组列表/协议块 + 全局评审落地)**:链接群组检测(preview/健康巡检)、检测态列、群组列表的成员/设置。详见设计 §1/§9。
- 选这块的原因:wheel↔协议层改造在途时唯一零协议耦合、可独立验收的纵切,且是进群/拉群的群链接池根依赖。

## 交付
- **Flyway V003**:`group_link_label` / `group_link` / `group_link_import_batch` / `group_link_import_detail` 4 表(plain 唯一键、无虚拟列、无物理外键、TINYINT 枚举逐值 COMMENT)。
- **10 端点**(`com.armada.group`):分组 CRUD(A1-A4 `/api/group-link-labels`)、导入 multipart + 列表 + 迁移 + 删除(B1-B4 `/api/group-links`)、明细 + 导出失败 CSV(C1-C2 `/api/group-link-imports`)。
- **导入引擎**:`FileLinesExtractor`(FastExcel 解析 TXT/CSV/Excel,headRowNumber(0))+ `GroupLinkUrls`(归一化校验)+ `GroupLinkImportService`(同步 upsert,SUCCESS/ADOPTED/DUPLICATE/FORMAT_ERROR 四态 + batch/detail 回写)。
- **共享 util**:`LineImporter` 重构为结构化逐行产出(`List<LineOutcome<T,R>>`),IP 块调用方同步迁移。
- ~50 个 group 域文件 + 4 mapper XML + 17 测试类。

## 2026-06-30 增量:导入时识别公开邀请页元数据
- **范围**:导入成功(新增/复活/收编)后请求 WhatsApp 公开邀请页,解析 `og:title` / `og:image`,写入 `group_link_preview.invite_code/wa_subject/avatar_url/last_preview_at`;重复/格式错误不请求。
- **边界**:不调用协议层;`group_link.group_name` 仍保留运营自定义名,列表继续通过 `COALESCE(g.group_name, p.wa_subject)` 展示 WhatsApp 真实群名。
- **头像口径**:仅保存 `pps.whatsapp.net` 真实头像;`static.whatsapp.net` 默认 WhatsApp logo 不落库。
- **验证**:`mvn -q -Dtest=GroupLinkImportServiceImplTest,HttpGroupInvitePageFetcherTest,GroupLinkServiceImplTest test`;`xmllint --noout armada-api/src/main/resources/mapper/group/GroupLinkPreviewMapper.xml`;`./armada-api/dbtest.sh GroupLinkImportServiceDbTest,GroupLinkPreviewMapperDbTest`。

## 关键决策(全部已落实)
1. `group_link` 是跨业务共享群组表 → 按数模规范一.3 **目标拆 3 表**(group_link import 身份 + 延后的 group_link_preview/group_link_health);本期只建 import 身份段。后续块照此加表(全局评审已在设计 §5.1 定形态)。
2. `link_url` **租户内唯一**(plain `UNIQUE(tenant_id,link_url)`,无虚拟列);再导入 = **upsert 收编**(命中含软删行→复活+改 label_id);三入口收敛到一行,群组列表不重复。
3. **归属铁律**:`label_id` 只导入链接菜单写;拉群/进群只复用不改归属(新建 label_id=NULL)。origin/label_id/任务使用三正交。
4. **不建物理外键**(关联列 + JOIN,完整性走应用层级联软删);时间库 DATETIME(UTC)、VO 出参 Long epoch 毫秒。
5. 加依赖 `cn.idev.excel:fastexcel:1.3.0`(EasyExcel 维护版,一包吃 CSV+Excel)。

## 测试
- **全量 117 tests / 0 fail / 0 error**(17 类:6 真库 DbTest 类 + 11 单测)。
- 真库 DbTest 骨架:`com.armada.testsupport.DbTestBase`(@SpringBootTest + @Transactional 回滚 + TenantContext=1);跑法 `armada-api/dbtest.sh <Test>`(从 gitignored `armada-api/.env` 注 DB creds)。
- 覆盖:唯一键去重 / upsert-复活 / JOIN sourceFileName / 级联软删 / **跨租户隔离回归** / 导入四态 + 计数 / CSV 转义+BOM / FastExcel 真 xlsx。

## 终审结论
- **无 Critical**。租户隔离整体审计:**所有 JOIN mapper 安全**(MyBatis-Plus 拦截器对 JOIN 两侧均注 tenant_id + detail 侧显式 `d.tenant_id=b.tenant_id` 双保险)。
- 合并前 3 小项已修(新建分组返回时间、导入去重 join、注释)。

## 延后 seam(明确,非漏做)
- `group_link_preview` / `group_link_health` 两表 + 检测态列 → 群组列表/检测块 + 协议层(全局评审已定形态)。
- `group_link.origin` / `membership_state` → 群组列表(读 origin)/ 进群(推进 membership_state)块加 + 回填。
- `group_link_history`(归属变更审计)→ 二期审计视图需要时。
- 批量删分组 occupancy gate(防被未结束任务占用)→ 现 no-op(task 表未建),拉群/进群块接真校验。
- 「可用链接」二次确认语义 → 检测块落地后按 health 判。
- 拉群/进群入口写 group_link(label_id=NULL,paste)→ 各自块;本块 label_id 已 NULLABLE 容纳。

## 二期 Minor 清单(终审裁定可接受、留二期)
LineImporter.run @param 略简;label XML LIMIT 位置语法风格不一;GroupConverterTest 用 Mappers.getMapper 非 Spring;batchInsert 无 useGeneratedKeys;countByLabel/selectPageByLabel filter 轻微重复;DbTest 辅助全限定名;ImportServiceDbTest 未读回 batch 计数列;mock counts test 未 ArgumentCaptor;escapeCsvRow 可迁 shared/util;detail VO result 裸 int(前端映射);3 个 service 各有 BATCH_MAX=100 可提公共常量。

## 环境 / 部署注意
- **测试库**:本机 MySQL 9.3.0 `armada` schema(`armada-api/.env`,gitignored);RDS `ap-south-1` 的 `armada` schema 留最终验收(需要时手改 `.env`)。armada schema 与 wheel(wheel_tenant/wheel_admin)严格隔离。
- **遗留(非本块)**:marketing V001 的 `UNIQUE(...,deleted_at)` 是隐性 bug(MySQL 多 NULL 不挡重复活行),本块 group 表已改用 plain 唯一键 + upsert/复活规避;V001 待二期单独修。
- 部署走 Flyway(V003 幂等 CREATE TABLE IF NOT EXISTS);HTTP 层端到端验收需 armada 鉴权 token(全站基建),留接前端/鉴权时做。
