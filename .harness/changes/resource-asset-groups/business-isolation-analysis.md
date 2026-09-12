# 图片业务隔离确认（2026-09-12）

用户已明确：“历史的两边都能查到，新增、后来的要分开清楚”。按此实现，已在第一套 test1 部署并应用迁移；详见 deployment.md。

此前确认两端共用 marketing_template_file、ResourceAssetPicker 和 /api/resource-assets，查询与上传缺少业务范围。现增加 nullable asset_scope：历史 NULL 双边可见，新超链 1、新养群 2；普通营销旧上传路径显式写 3，避免新数据继续成为历史共享。

分组及图片分组归属按业务保存，历史图片不因一边移组而影响另一边。新图片管理和新引用校验业务归属。已有引用继续读取既有 ID；历史数据不根据用途猜测归类。

具体实现、验证、迁移和回滚见 summary.md。当前边界为图片库；消息模板共用 MarketingTemplateService 的集合未在本次拆分。旧运行时内容解析保持兼容，本次未声称所有历史原始文件端点均改为业务隔离。
