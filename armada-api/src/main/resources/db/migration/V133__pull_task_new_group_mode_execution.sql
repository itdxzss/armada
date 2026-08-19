-- 新群模式：执行行支持「建群成功之前还没有群链接」，并新增建群阶段所需字段。
--
-- 背景见 ADR-0010 / ADR-0011 与 docs/business/pull-task-new-group-mode-development-design.md。
--
-- 1) 链接三列改为可空
--    pull_task_group_execution 原本要求 normalized_link / invite_code / source_link_line_no
--    非空，因为群链接模式的链接是用户粘贴进来的。新群模式在建群成功之前拿不到链接，
--    必须允许为空，建群成功后再回填。
--
--    这里只改列的可空性，唯一键 uq_pull_task_execution_link 与生成列 link_occupancy_key
--    及其唯一键一律不动：MySQL 的唯一索引不对 NULL 做唯一性约束，多行 NULL 可以共存；
--    normalized_link 为 NULL 时生成列的 CASE 结果也是 NULL，跨任务占用键自然不生效。
--    建群成功回填链接后（此时 execution_status=2），生成列取到值，占用保护随即开始生效，
--    正是期望行为。存量群链接模式的行这三列一直有值，改为可空对它们零影响。
--
--    MODIFY 会整列重写，必须原样重复 CHARACTER SET ascii COLLATE ascii_bin，
--    否则会退回表默认排序规则，邀请码的大小写敏感性就丢了。
--
-- 2) 新增建群阶段字段
-- 3) 订正 stage 列注释：存量注释停留在 V093 时代的七阶段，缺 MANAGER_ADMIN 与 CLOSING，
--    按它理解状态机会得出错误结论。本次一并补全为九个阶段。
--
-- 全脚本幂等，不写任何业务数据。

-- 1) 链接三列改为可空 -----------------------------------------------------------

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'normalized_link' AND is_nullable = 'NO') = 1,
    'ALTER TABLE pull_task_group_execution MODIFY COLUMN normalized_link VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''归一化群链接chat.whatsapp.com/<邀请码>;新群模式建群成功后回填''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'invite_code' AND is_nullable = 'NO') = 1,
    'ALTER TABLE pull_task_group_execution MODIFY COLUMN invite_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''群邀请码;大小写敏感;新群模式建群成功后回填''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'source_link_line_no' AND is_nullable = 'NO') = 1,
    'ALTER TABLE pull_task_group_execution MODIFY COLUMN source_link_line_no INT NULL COMMENT ''粘贴内容中的原始行号;新群模式无粘贴来源为空''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 建群阶段字段 ---------------------------------------------------------------

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'create_step') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN create_step TINYINT DEFAULT NULL COMMENT ''建群阶段内部步骤游标:1=选角色 2=调建群 3=落JID与站台回执 4=群资料 5=生成邀请链接 6=拉人前群设置 7=登记自建群;非新群模式为NULL'' AFTER stage',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'create_operation_id') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN create_operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT ''建群幂等键;同一执行行的同一次逻辑建群恒定复用,禁止重试时重新生成'' AFTER create_step',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'create_attempt_count') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN create_attempt_count INT NOT NULL DEFAULT 0 COMMENT ''建群尝试次数;只累计确定未创建类失败,结果不明不累计也不自动重建(ADR-0013)'' AFTER create_operation_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'group_subject') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN group_subject VARCHAR(255) DEFAULT NULL COMMENT ''本群最终名称;新群模式建群时确定'' AFTER create_attempt_count',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) 订正 stage 列注释 ----------------------------------------------------------

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution'
       AND column_name = 'stage') = 1,
    'ALTER TABLE pull_task_group_execution MODIFY COLUMN stage TINYINT NOT NULL DEFAULT 1 COMMENT ''业务阶段:1=链接校验 2=管理入群 3=管理员提权 4=管理拉手联系人 5=管理邀请拉手 6=拉人执行 7=料子提权 8=收口 9=建群(新群模式起始阶段,完成后置2跳过1)''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
