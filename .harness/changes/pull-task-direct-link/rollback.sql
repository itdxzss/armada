-- 本地记录，未执行。应用回滚前先关闭新模式创建并停止/收敛全部在途任务。
-- 若仍有 DIRECT_LINK 数据，保留支持该枚举及阶段 10 的后端；禁止直接回退旧后端。
-- 迁移均为兼容性放宽及新增可空列，正常应用回滚无需删除列或还原 NOT NULL。
-- 以下只读核查用于评估回滚条件，不删除任务/执行历史，不改 Flyway 历史。
SELECT creation_mode, status, COUNT(*) AS task_count
FROM pull_task
WHERE creation_mode = 'DIRECT_LINK'
GROUP BY creation_mode, status;
SELECT COUNT(*) AS manager_nullable_rows
FROM pull_task_standard_setting
WHERE manager_group_id IS NULL OR manager_group_name IS NULL;
-- 若确需恢复原 schema，必须确认上面计数均为 0 并另建有明确环境授权的前向 Flyway 迁移。
