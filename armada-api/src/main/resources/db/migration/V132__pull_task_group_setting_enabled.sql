-- 普通群链接任务新增「群信息设置」总开关。
--
-- 背景（2026-08-18 审计）：本表 12 个群资料与权限字段目前只写不读。全仓只有 Mapper、
-- 读服务回显、头像占用查询三类地方碰它，没有任何调度器或执行器消费。操作员在表单里
-- 填了群名、传了头像、点保存成功，执行时一个字都不会下发给 WhatsApp —— 这是一个静默
-- 的假功能。本列先把整块收起来，由开关同时决定三件事：前端是否展示、保存时是否落库、
-- 以及后续接入执行链路后是否下发群设置命令。
--
-- 存量任务一律视为关闭（业务确认 2026-08-18）。因字段本就无人消费，存量置关对正在
-- 执行的任务是零行为影响，不需要按任务状态区分回填。NOT NULL DEFAULT 0 覆盖存量行。
--
-- 列追加到表末尾而不使用 AFTER：MySQL 8.0.12+ 仅在追加末尾时可走 INSTANT ADD COLUMN，
-- 使用 AFTER 会退化为 INPLACE/COPY 重建整表。
ALTER TABLE pull_task_standard_group_setting
    ADD COLUMN is_group_setting_enabled TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否启用群信息设置:0=否 1=是';
