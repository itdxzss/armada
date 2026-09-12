# 养群剧本「回复哪一句」

2026-09-11：用户已授权在主仓库实施。前端、后端、Web 与 Android 协议本地实现和针对性验证完成；已配套部署 test1 并核验运行制品、V188 与真实页面；未提交或发送真实消息。

关键口径：原句失败时，回复句按原角色、内容、间隔继续普通发送，仅去掉引用；迟到结果不重发。

- [设计与契约](../../docs/business/2026-09-11-script-quoted-reply-design.md)
- [实施范围、测试与限制](script-quoted-reply/summary.md)
- [迁移入口](script-quoted-reply/db-migrations.sql) / [回退策略](script-quoted-reply/rollback.sql)

运行环境验收尚未开展，不能把本地通过视为成员手机收到并正确显示引用。
