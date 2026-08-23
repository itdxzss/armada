-- 添加 phone 唯一键，防止同群同号码产生双行
-- 执行前提：已完成双行数据归并

-- 检查当前是否还有双行（应该为0）
SELECT
    COUNT(*) as remaining_duplicates,
    '如果不是0，不能添加唯一键' as warning
FROM (
    SELECT tenant_id, group_id, phone
    FROM wa_group_participant
    WHERE tenant_id = 1 AND phone IS NOT NULL
    GROUP BY tenant_id, group_id, phone
    HAVING COUNT(*) > 1
) t;

-- 添加唯一键
ALTER TABLE wa_group_participant
ADD UNIQUE KEY uq_wa_group_participant_phone (tenant_id, group_id, phone);

-- 验证索引已创建
SHOW INDEX FROM wa_group_participant WHERE Key_name = 'uq_wa_group_participant_phone';
