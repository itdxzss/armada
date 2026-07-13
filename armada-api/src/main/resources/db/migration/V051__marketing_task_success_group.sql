CREATE TABLE IF NOT EXISTS marketing_task_success_group (
    id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id                BIGINT       NOT NULL                COMMENT '租户ID',
    marketing_task_id        BIGINT       NOT NULL                COMMENT '普通群组营销任务ID(→marketing_task.id)',
    group_jid                VARCHAR(128) NOT NULL                COMMENT '首次成功触达的WhatsApp群JID',
    first_success_attempt_id BIGINT       NOT NULL                COMMENT '首次成功发送尝试ID(→marketing_task_send_attempt.id)',
    first_success_at         BIGINT       NOT NULL                COMMENT '首次成功时间(epoch毫秒)',
    created_at               BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_marketing_task_success_group (tenant_id, marketing_task_id, group_jid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群组营销任务累计成功群组事实';

INSERT IGNORE INTO marketing_task_success_group
    (marketing_task_id, group_jid, first_success_attempt_id, first_success_at, created_at, tenant_id)
SELECT ranked.marketing_task_id,
       ranked.group_jid,
       ranked.attempt_id,
       ranked.first_success_at,
       ranked.first_success_at,
       ranked.tenant_id
FROM (
    SELECT a.tenant_id,
           a.marketing_task_id,
           a.id AS attempt_id,
           TRIM(a.group_jid) AS group_jid,
           COALESCE(a.result_at, a.attempted_at, a.created_at) AS first_success_at,
           ROW_NUMBER() OVER (
               PARTITION BY a.tenant_id, a.marketing_task_id, TRIM(a.group_jid)
               ORDER BY COALESCE(a.result_at, a.attempted_at, a.created_at) ASC, a.id ASC
           ) AS success_rank
    FROM marketing_task_send_attempt a
    WHERE a.status = 1
      AND a.group_jid IS NOT NULL
      AND TRIM(a.group_jid) <> ''
      AND NOT EXISTS (
          SELECT 1
          FROM group_creation_marketing_item item
          WHERE item.tenant_id = a.tenant_id
            AND item.marketing_attempt_id = a.id
      )
) ranked
WHERE ranked.success_rank = 1;

UPDATE marketing_task task
LEFT JOIN (
    SELECT tenant_id,
           marketing_task_id,
           COUNT(*) AS success_group_count
    FROM marketing_task_success_group
    GROUP BY tenant_id, marketing_task_id
) success ON success.tenant_id = task.tenant_id
         AND success.marketing_task_id = task.id
SET task.target_group_count = COALESCE(success.success_group_count, 0);

ALTER TABLE marketing_task
    MODIFY COLUMN target_group_count INT NOT NULL DEFAULT 0
    COMMENT '任务累计成功触达去重群数';
